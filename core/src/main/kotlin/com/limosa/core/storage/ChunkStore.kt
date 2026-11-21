package com.limosa.core.storage

import com.limosa.core.json.Json
import com.limosa.core.json.bool
import com.limosa.core.json.long
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * "Fake storage" for VM disks.
 *
 * A guest sees a disk of its full virtual size (say 64 GB), but the phone only stores what
 * holds data:
 *  - **Thin provisioning**: chunks the guest never wrote take no space.
 *  - **Zero elimination**: chunks that are all zeroes are dropped, and so are TRIM/discard
 *    and WRITE_ZEROES from the guest, which frees real space.
 *  - **Deduplication**: identical chunks are stored once across *all* disks, so two VMs
 *    with the same OS share most of their blocks, and [cloneDisk] costs almost nothing.
 *  - **Compression**: each chunk is deflated when that makes it noticeably smaller.
 *
 * Layout under [root]:
 * ```
 * chunks/ab/<32 hex>   content-addressed chunk (1 header byte: 0 = raw, 1 = deflate)
 * disks/<name>.json    disk metadata
 * disks/<name>.idx     sparse index, 16 bytes per chunk (all zero = unallocated)
 * ```
 *
 * The store is shared by the NBD server (QEMU's disks) and the localhost HTTP site.
 * All public operations are thread-safe (they serialize on the store).
 */
class ChunkStore(val root: File, val chunkSize: Int = DEFAULT_CHUNK_SIZE) : Closeable {

    init {
        require(chunkSize >= 4096 && Integer.bitCount(chunkSize) == 1) { "chunkSize must be a power of two >= 4096" }
    }

    private val chunksDir = File(root, "chunks")
    private val disksDir = File(root, "disks")
    private val refs = HashMap<ChunkKey, ChunkInfo>()
    private val disks = LinkedHashMap<String, VirtualDisk>()
    private val pendingDeletes = HashSet<ChunkKey>()
    private val cache = object : LinkedHashMap<ChunkKey, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChunkKey, ByteArray>?) =
            size > maxOf(16, CACHE_BYTES / chunkSize)
    }
    private val zeroChunk = ByteArray(chunkSize)
    private var closed = false

    init {
        chunksDir.mkdirs()
        disksDir.mkdirs()
        load()
    }

    // ------------------------------------------------------------------ disks

    @Synchronized
    fun listDisks(): List<VirtualDisk> = disks.values.toList()

    @Synchronized
    fun disk(name: String): VirtualDisk? = disks[name]

    @Synchronized
    fun createDisk(name: String, virtualSize: Long, readOnly: Boolean = false): VirtualDisk {
        checkOpen()
        requireValidName(name)
        require(virtualSize > 0) { "Size must be positive" }
        require(virtualSize <= MAX_VIRTUAL_SIZE) { "Size too large (max ${MAX_VIRTUAL_SIZE shr 40} TiB)" }
        require(name !in disks) { "Disk '$name' already exists" }
        val size = roundUp(virtualSize, SECTOR)
        val d = VirtualDisk(name, size, readOnly, System.currentTimeMillis())
        d.openIndex(create = true)
        d.saveMeta()
        disks[name] = d
        return d
    }

    @Synchronized
    fun deleteDisk(name: String) {
        checkOpen()
        val d = disks.remove(name) ?: throw NoSuchElementException("No disk '$name'")
        for (key in d.map.values) release(key)
        d.map.clear()
        d.closeIndex()
        File(disksDir, "$name.idx").delete()
        File(disksDir, "$name.json").delete()
        flush()
    }

    /** Instant copy: the new disk shares every chunk with [source] until either is written. */
    @Synchronized
    fun cloneDisk(source: String, target: String, readOnly: Boolean = false): VirtualDisk {
        val src = disks[source] ?: throw NoSuchElementException("No disk '$source'")
        val d = createDisk(target, src.size, readOnly)
        for ((idx, key) in src.map) {
            retain(key)
            d.map[idx] = key
            d.writeIndexEntry(idx, key)
        }
        d.syncIndex()
        return d
    }

    /**
     * Streams [input] into a new disk (e.g. an uploaded ISO). If [knownSize] is null the
     * disk's size is the number of bytes read, rounded up to a 512-byte sector.
     */
    fun importDisk(name: String, input: InputStream, knownSize: Long?, readOnly: Boolean): VirtualDisk {
        val d = createDisk(name, knownSize?.takeIf { it > 0 } ?: MAX_VIRTUAL_SIZE, readOnly = false)
        try {
            val buf = ByteArray(chunkSize)
            var offset = 0L
            while (true) {
                val n = readFully(input, buf)
                if (n <= 0) break
                if (n < chunkSize) java.util.Arrays.fill(buf, n, chunkSize, 0)
                // Lock per chunk, not for the whole upload, so running VMs keep their disk I/O.
                synchronized(this) {
                    checkOpen()
                    if (offset + chunkSize > d.size) d.size = roundUp(offset + chunkSize, SECTOR)
                    d.writeWholeChunk(offset / chunkSize, buf)
                }
                offset += n
                if (n < chunkSize) break
            }
            if (offset == 0L) throw IOException("Empty upload")
            synchronized(this) {
                d.size = roundUp(knownSize?.takeIf { it > 0 } ?: offset, SECTOR)
                d.readOnly = readOnly
                d.saveMeta()
                flush()
            }
            return d
        } catch (e: Throwable) {
            runCatching { deleteDisk(name) }
            throw e
        }
    }

    // ------------------------------------------------------------------ stats

    data class Stats(
        val disks: Int,
        val virtualBytes: Long,
        /** Bytes the guests would use on a plain raw image of only their written data. */
        val logicalBytes: Long,
        /** Bytes actually on the phone's flash for chunk data. */
        val physicalBytes: Long,
        val uniqueChunks: Int,
    ) {
        /** Bytes that a set of plain, fully allocated raw images would have needed in addition. */
        val savedBytes: Long get() = virtualBytes - physicalBytes
    }

    @Synchronized
    fun stats(): Stats = Stats(
        disks = disks.size,
        virtualBytes = disks.values.sumOf { it.size },
        logicalBytes = disks.values.sumOf { it.map.size.toLong() * chunkSize },
        physicalBytes = refs.values.sumOf { it.storedSize.toLong() },
        uniqueChunks = refs.size,
    )

    /** Makes all index writes durable, then deletes chunks that nothing references. */
    @Synchronized
    fun flush() {
        if (closed) return
        for (d in disks.values) d.syncIndex()
        for (key in pendingDeletes) {
            if (refs[key] == null) chunkFile(key).delete()
        }
        pendingDeletes.clear()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        flush()
        for (d in disks.values) d.closeIndex()
        closed = true
    }

    // ------------------------------------------------------------------ internals

    private fun checkOpen() {
        if (closed) throw IOException("Store is closed")
    }

    private fun load() {
        disksDir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { metaFile ->
            try {
                val m = Json.parseObject(metaFile.readText())
                val name = metaFile.name.removeSuffix(".json")
                val d = VirtualDisk(
                    name,
                    m.long("size") ?: return@forEach,
                    m.bool("readOnly") ?: false,
                    m.long("created") ?: metaFile.lastModified(),
                )
                d.openIndex(create = false)
                d.loadIndex()
                disks[name] = d
            } catch (e: Exception) {
                System.err.println("Limosa: skipping broken disk ${metaFile.name}: $e")
            }
        }
        // Count references and drop index entries that point at missing chunk files.
        for (d in disks.values) {
            val missing = ArrayList<Long>()
            for ((idx, key) in d.map) {
                val info = refs[key]
                if (info != null) {
                    info.refs++
                } else {
                    val f = chunkFile(key)
                    if (f.exists()) refs[key] = ChunkInfo(1, f.length().toInt()) else missing.add(idx)
                }
            }
            for (idx in missing) {
                d.map.remove(idx)
                d.writeIndexEntry(idx, null)
            }
        }
        // Garbage-collect chunk files no index references (e.g. after a crash).
        chunksDir.listFiles()?.forEach { sub ->
            sub.listFiles()?.forEach { f ->
                val key = ChunkKey.fromHex(f.name)
                if (key == null || key !in refs) f.delete()
            }
        }
    }

    private fun chunkFile(key: ChunkKey): File {
        val hex = key.hex()
        return File(File(chunksDir, hex.substring(0, 2)), hex)
    }

    private fun retain(key: ChunkKey) {
        refs.getValue(key).refs++
    }

    private fun release(key: ChunkKey) {
        val info = refs[key] ?: return
        if (--info.refs <= 0) {
            refs.remove(key)
            cache.remove(key)
            pendingDeletes.add(key) // deleted on flush(), after the index no longer points to it
        }
    }

    /** Stores [data] (exactly one chunk) and returns its key, or null if it is all zero. */
    private fun store(data: ByteArray): ChunkKey? {
        if (isZero(data)) return null
        val key = ChunkKey.of(data)
        val existing = refs[key]
        if (existing != null) {
            existing.refs++
            return key
        }
        val f = chunkFile(key)
        f.parentFile.mkdirs()
        val payload = encode(data)
        val tmp = File(f.parentFile, f.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            raf.write(payload)
            raf.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("Could not write chunk ${f.name}")
        }
        refs[key] = ChunkInfo(1, payload.size)
        pendingDeletes.remove(key)
        cache[key] = data.copyOf()
        return key
    }

    private fun loadChunk(key: ChunkKey): ByteArray {
        cache[key]?.let { return it }
        val raw = chunkFile(key).readBytes()
        val data = when (raw[0].toInt()) {
            0 -> raw.copyOfRange(1, raw.size)
            1 -> {
                val inf = Inflater()
                try {
                    inf.setInput(raw, 1, raw.size - 1)
                    val out = ByteArray(chunkSize)
                    var n = 0
                    while (n < chunkSize && !inf.finished()) {
                        val r = inf.inflate(out, n, chunkSize - n)
                        if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                        n += r
                    }
                    if (n != chunkSize) throw IOException("Corrupt chunk ${key.hex()} (inflated $n bytes)")
                    out
                } finally {
                    inf.end()
                }
            }
            else -> throw IOException("Corrupt chunk ${key.hex()}")
        }
        if (data.size != chunkSize) throw IOException("Corrupt chunk ${key.hex()} (size ${data.size})")
        cache[key] = data
        return data
    }

    private fun encode(data: ByteArray): ByteArray {
        val def = Deflater(Deflater.BEST_SPEED)
        try {
            def.setInput(data)
            def.finish()
            val out = ByteArrayOutputStream(chunkSize / 2)
            out.write(1)
            val buf = ByteArray(16 * 1024)
            while (!def.finished()) {
                val n = def.deflate(buf)
                out.write(buf, 0, n)
                // Stop early if compression is not paying off.
                if (out.size() > chunkSize - chunkSize / 8) break
            }
            if (def.finished() && out.size() <= chunkSize - chunkSize / 8) return out.toByteArray()
        } finally {
            def.end()
        }
        val raw = ByteArray(chunkSize + 1)
        System.arraycopy(data, 0, raw, 1, chunkSize)
        return raw
    }

    private fun isZero(b: ByteArray): Boolean = java.util.Arrays.equals(b, zeroChunk)

    private class ChunkInfo(var refs: Int, val storedSize: Int)

    /**
     * A virtual block device backed by the store.
     * Reads of unallocated ranges return zeroes.
     */
    inner class VirtualDisk internal constructor(
        val name: String,
        size: Long,
        readOnly: Boolean,
        val created: Long,
    ) {
        @Volatile var size: Long = size
            internal set
        @Volatile var readOnly: Boolean = readOnly
            internal set

        internal val map = HashMap<Long, ChunkKey>()
        private var index: FileChannel? = null
        private var indexDirty = false

        val allocatedBytes: Long get() = synchronized(this@ChunkStore) { map.size.toLong() * chunkSize }

        fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int) = synchronized(this@ChunkStore) {
            checkOpen()
            checkRange(offset, len.toLong())
            var pos = offset
            var o = dstOff
            var remaining = len
            while (remaining > 0) {
                val idx = pos / chunkSize
                val inChunk = (pos % chunkSize).toInt()
                val n = minOf(remaining, chunkSize - inChunk)
                val key = map[idx]
                if (key == null) {
                    java.util.Arrays.fill(dst, o, o + n, 0)
                } else {
                    System.arraycopy(loadChunk(key), inChunk, dst, o, n)
                }
                pos += n; o += n; remaining -= n
            }
        }

        fun write(offset: Long, src: ByteArray, srcOff: Int, len: Int) = synchronized(this@ChunkStore) {
            checkWritable()
            checkRange(offset, len.toLong())
            var pos = offset
            var o = srcOff
            var remaining = len
            while (remaining > 0) {
                val idx = pos / chunkSize
                val inChunk = (pos % chunkSize).toInt()
                val n = minOf(remaining, chunkSize - inChunk)
                val chunk = if (n == chunkSize) {
                    src.copyOfRange(o, o + n)
                } else {
                    val base = map[idx]?.let { loadChunk(it).copyOf() } ?: ByteArray(chunkSize)
                    System.arraycopy(src, o, base, inChunk, n)
                    base
                }
                writeWholeChunk(idx, chunk)
                pos += n; o += n; remaining -= n
            }
        }

        /** Zeroes a range. Whole chunks are dropped, so this frees real space. */
        fun zero(offset: Long, len: Long) = synchronized(this@ChunkStore) {
            checkWritable()
            checkRange(offset, len)
            var pos = offset
            var remaining = len
            while (remaining > 0) {
                val idx = pos / chunkSize
                val inChunk = (pos % chunkSize).toInt()
                val n = minOf(remaining, (chunkSize - inChunk).toLong()).toInt()
                val key = map[idx]
                if (key != null) {
                    if (n == chunkSize) {
                        setEntry(idx, null)
                    } else {
                        val base = loadChunk(key).copyOf()
                        java.util.Arrays.fill(base, inChunk, inChunk + n, 0)
                        writeWholeChunk(idx, base)
                    }
                }
                pos += n; remaining -= n
            }
        }

        /** Guest TRIM/discard: drop whole chunks inside the range; partial edges are left alone. */
        fun trim(offset: Long, len: Long) = synchronized(this@ChunkStore) {
            checkWritable()
            checkRange(offset, len)
            val first = (offset + chunkSize - 1) / chunkSize
            val endExclusive = (offset + len) / chunkSize
            if (endExclusive - first > map.size) {
                map.keys.filter { it in first until endExclusive }.forEach { setEntry(it, null) }
            } else {
                for (idx in first until endExclusive) if (map.containsKey(idx)) setEntry(idx, null)
            }
        }

        fun flush() = this@ChunkStore.flush()

        /** True if any byte in the chunk containing [offset] may be non-zero. */
        fun isAllocated(offset: Long): Boolean = synchronized(this@ChunkStore) { map.containsKey(offset / chunkSize) }

        internal fun writeWholeChunk(idx: Long, chunk: ByteArray) {
            val key = store(chunk)
            // store() already took a reference on key; setEntry must not take another.
            val old = map[idx]
            if (old == key) {
                if (key != null) release(key)
                return
            }
            if (key == null) map.remove(idx) else map[idx] = key
            writeIndexEntry(idx, key)
            if (old != null) release(old)
        }

        private fun setEntry(idx: Long, key: ChunkKey?) {
            val old = map[idx]
            if (old == key) return
            if (key != null) retain(key)
            if (key == null) map.remove(idx) else map[idx] = key
            writeIndexEntry(idx, key)
            if (old != null) release(old)
        }

        private fun checkRange(offset: Long, len: Long) {
            if (offset < 0 || len < 0 || offset + len > size) {
                throw IndexOutOfBoundsException("Range $offset+$len outside disk '$name' of size $size")
            }
        }

        private fun checkWritable() {
            checkOpen()
            if (readOnly) throw ReadOnlyException(name)
        }

        internal fun openIndex(create: Boolean) {
            val f = File(disksDir, "$name.idx")
            if (!create && !f.exists()) throw IOException("Missing index for $name")
            index = RandomAccessFile(f, "rw").channel
        }

        internal fun loadIndex() {
            val ch = index ?: return
            val buf = ByteBuffer.allocate(64 * 1024)
            var pos = 0L
            val total = ch.size()
            while (pos < total) {
                buf.clear()
                val n = ch.read(buf, pos)
                if (n <= 0) break
                buf.flip()
                var entry = pos / ENTRY
                while (buf.remaining() >= ENTRY) {
                    val hi = buf.long
                    val lo = buf.long
                    if (hi != 0L || lo != 0L) map[entry] = ChunkKey(hi, lo)
                    entry++
                }
                pos += n - (n % ENTRY)
            }
        }

        internal fun writeIndexEntry(idx: Long, key: ChunkKey?) {
            val ch = index ?: return
            val b = ByteBuffer.allocate(ENTRY)
            b.putLong(key?.hi ?: 0L).putLong(key?.lo ?: 0L).flip()
            var p = idx * ENTRY
            while (b.hasRemaining()) p += ch.write(b, p)
            indexDirty = true
        }

        internal fun syncIndex() {
            if (indexDirty) {
                index?.force(false)
                indexDirty = false
            }
        }

        internal fun closeIndex() {
            runCatching { syncIndex() }
            runCatching { index?.close() }
            index = null
        }

        internal fun saveMeta() {
            val f = File(disksDir, "$name.json")
            val tmp = File(disksDir, "$name.json.tmp")
            tmp.writeText(Json.stringify(mapOf("size" to size, "readOnly" to readOnly, "created" to created)))
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) throw IOException("Could not save metadata for $name")
            }
        }

        fun toJson(): Map<String, Any?> = synchronized(this@ChunkStore) {
            mapOf(
                "name" to name,
                "size" to size,
                "allocated" to map.size.toLong() * chunkSize,
                "readOnly" to readOnly,
                "created" to created,
            )
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val SECTOR = 512L
        const val MAX_VIRTUAL_SIZE = 4L shl 40 // 4 TiB
        private const val ENTRY = 16
        private const val CACHE_BYTES = 32 * 1024 * 1024
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        fun isValidName(name: String) = NAME.matches(name)

        fun requireValidName(name: String) =
            require(isValidName(name)) { "Invalid name '$name' (use letters, digits, '.', '_', '-'; max 64)" }

        private fun roundUp(v: Long, to: Long) = (v + to - 1) / to * to

        private fun readFully(input: InputStream, buf: ByteArray): Int {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }
    }
}

class ReadOnlyException(name: String) : IOException("Disk '$name' is read-only")

/** 128-bit truncated SHA-256 of a chunk. */
data class ChunkKey(val hi: Long, val lo: Long) {
    fun hex(): String = "%016x%016x".format(hi, lo)

    companion object {
        fun of(data: ByteArray): ChunkKey {
            val d = MessageDigest.getInstance("SHA-256").digest(data)
            val b = ByteBuffer.wrap(d)
            return ChunkKey(b.long, b.long)
        }

        fun fromHex(s: String): ChunkKey? {
            if (s.length != 32 || s.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return ChunkKey(java.lang.Long.parseUnsignedLong(s.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(s.substring(16), 16))
        }
    }
}
