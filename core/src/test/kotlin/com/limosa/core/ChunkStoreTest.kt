package com.limosa.core

import com.limosa.core.storage.ChunkStore
import com.limosa.core.storage.ReadOnlyException
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkStoreTest {
    @TempDir lateinit var dir: File

    private val gib = 1L shl 30

    @Test
    fun `huge virtual disk takes no space until written`() {
        ChunkStore(dir).use { s ->
            val d = s.createDisk("big", 64 * gib)
            assertEquals(64 * gib, d.size)
            assertEquals(0, s.stats().physicalBytes)
            val buf = ByteArray(4096) { 1 }
            d.read(60 * gib, buf, 0, buf.size)
            assertTrue(buf.all { it == 0.toByte() })
        }
    }

    @Test
    fun `unaligned writes read back and survive reopen`() {
        val data = Random(1).nextBytes(200_000)
        ChunkStore(dir).use { s ->
            val d = s.createDisk("d", gib)
            d.write(12_345, data, 0, data.size)
            val back = ByteArray(data.size)
            d.read(12_345, back, 0, back.size)
            assertContentEquals(data, back)
        }
        ChunkStore(dir).use { s ->
            val d = s.disk("d")!!
            val back = ByteArray(data.size)
            d.read(12_345, back, 0, back.size)
            assertContentEquals(data, back)
            // neighbouring bytes are still zero
            val edge = ByteArray(10)
            d.read(12_335, edge, 0, 10)
            assertTrue(edge.all { it == 0.toByte() })
        }
    }

    @Test
    fun `identical data is stored once across disks`() {
        ChunkStore(dir).use { s ->
            val block = Random(2).nextBytes(s.chunkSize * 16)
            val a = s.createDisk("a", gib)
            val b = s.createDisk("b", gib)
            a.write(0, block, 0, block.size)
            val afterA = s.stats().physicalBytes
            b.write(s.chunkSize * 100L, block, 0, block.size)
            assertEquals(afterA, s.stats().physicalBytes, "second copy must be free")
            assertEquals(16, s.stats().uniqueChunks)
        }
    }

    @Test
    fun `compressible data is compressed`() {
        ChunkStore(dir).use { s ->
            val d = s.createDisk("t", gib)
            val text = ("Limosa fake storage ".repeat(20_000)).toByteArray().copyOf(s.chunkSize * 8)
            d.write(0, text, 0, text.size)
            assertTrue(s.stats().physicalBytes < text.size / 10, "physical=${s.stats().physicalBytes}")
        }
    }

    @Test
    fun `trim and zero writes free real space`() {
        ChunkStore(dir).use { s ->
            val d = s.createDisk("t", gib)
            val data = Random(3).nextBytes(s.chunkSize * 10)
            d.write(0, data, 0, data.size)
            assertTrue(s.stats().physicalBytes >= data.size)
            d.trim(0, s.chunkSize * 5L)
            d.zero(s.chunkSize * 5L, s.chunkSize * 5L)
            s.flush()
            assertEquals(0, s.stats().physicalBytes)
            assertEquals(0, File(dir, "chunks").walk().count { it.isFile })
            // writing zeroes over existing data is also dropped
            d.write(0, data, 0, data.size)
            d.write(0, ByteArray(data.size), 0, data.size)
            assertEquals(0, d.allocatedBytes)
        }
    }

    @Test
    fun `clone is instant and copy-on-write`() {
        ChunkStore(dir).use { s ->
            val d = s.createDisk("base", gib)
            val data = Random(4).nextBytes(s.chunkSize * 4)
            d.write(0, data, 0, data.size)
            val before = s.stats().physicalBytes
            val c = s.cloneDisk("base", "copy")
            assertEquals(before, s.stats().physicalBytes)
            c.write(0, ByteArray(10) { 7 }, 0, 10)
            val orig = ByteArray(10)
            d.read(0, orig, 0, 10)
            assertContentEquals(data.copyOf(10), orig)
            s.deleteDisk("base")
            val back = ByteArray(data.size)
            c.read(0, back, 0, back.size)
            assertContentEquals(data.copyOfRange(10, data.size), back.copyOfRange(10, data.size))
        }
    }

    @Test
    fun `import creates a read only image of the right size`() {
        ChunkStore(dir).use { s ->
            val iso = Random(5).nextBytes(300_000)
            val d = s.importDisk("cd", ByteArrayInputStream(iso), null, readOnly = true)
            assertEquals(300_032, d.size) // rounded up to a sector
            val back = ByteArray(iso.size)
            d.read(0, back, 0, back.size)
            assertContentEquals(iso, back)
            assertFailsWith<ReadOnlyException> { d.write(0, ByteArray(1), 0, 1) }
        }
        ChunkStore(dir).use { s -> assertTrue(s.disk("cd")!!.readOnly) }
    }

    @Test
    fun `out of range access is rejected`() {
        ChunkStore(dir).use { s ->
            val d = s.createDisk("small", 1024 * 1024)
            assertFailsWith<IndexOutOfBoundsException> { d.write(1024 * 1024 - 1, ByteArray(2), 0, 2) }
            assertFailsWith<IllegalArgumentException> { s.createDisk("../evil", 10) }
        }
    }

    @Test
    fun `orphan chunks are collected on open`() {
        ChunkStore(dir).use { s ->
            val d = s.createDisk("d", gib)
            d.write(0, Random(6).nextBytes(s.chunkSize), 0, s.chunkSize)
        }
        val stray = File(dir, "chunks/ff/ff" + "0".repeat(30)).apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) }
        ChunkStore(dir).use { s ->
            assertTrue(!stray.exists())
            assertEquals(1, s.stats().uniqueChunks)
        }
    }
}
