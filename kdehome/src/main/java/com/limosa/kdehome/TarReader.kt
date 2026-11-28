package com.limosa.kdehome

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * A small streaming tar reader: ustar, GNU long names (`L`/`K`) and pax headers (`x`),
 * which is what Debian root filesystem tarballs use.
 */
class TarReader(private val input: InputStream) {

    class Entry(val name: String, val type: Char, val size: Long, val mode: Int, val linkName: String) {
        val isDirectory get() = type == '5'
        val isSymlink get() = type == '2'
        val isHardLink get() = type == '1'
        val isFile get() = type == '0' || type == '\u0000' || type == '7'
    }

    private val header = ByteArray(512)
    private val skipBuf = ByteArray(64 * 1024)
    private var remaining = 0L      // unread data bytes of the current entry
    private var padding = 0L        // block padding after them

    /** The next entry, or null at the end of the archive. Unread data of the previous entry is skipped. */
    fun next(): Entry? {
        finishEntry()
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        while (true) {
            if (!readBlock(header)) return null
            if (header.all { it == 0.toByte() }) return null
            val type = (header[156].toInt() and 0xFF).toChar()
            val size = number(header, 124, 12)
            when (type) {
                'L' -> { longName = readString(size); continue }
                'K' -> { longLink = readString(size); continue }
                'x' -> { pax = parsePax(readBytes(size)); continue }
                'g' -> { readBytes(size); continue }
            }
            val posix = header[257] == 'u'.code.toByte() && header[262] == 0.toByte()
            val shortName = cstring(header, 0, 100)
            val prefix = if (posix) cstring(header, 345, 155) else ""
            val name = longName ?: pax["path"] ?: if (prefix.isNotEmpty()) "$prefix/$shortName" else shortName
            val link = longLink ?: pax["linkpath"] ?: cstring(header, 157, 100)
            val realSize = pax["size"]?.toLongOrNull() ?: size
            val mode = number(header, 100, 8).toInt() and 4095
            remaining = realSize
            padding = (512 - realSize % 512) % 512
            return Entry(name, type, realSize, mode, link)
        }
    }

    /** Writes the current entry's data to [out]. */
    fun copyTo(out: OutputStream, onBytes: (Int) -> Unit = {}) {
        while (remaining > 0) {
            val n = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("tar archive is truncated")
            out.write(skipBuf, 0, n)
            remaining -= n
            onBytes(n)
        }
    }

    private fun finishEntry() {
        skip(remaining + padding)
        remaining = 0
        padding = 0
    }

    private fun skip(count: Long) {
        var left = count
        while (left > 0) {
            val n = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("tar archive is truncated")
            left -= n
        }
    }

    private fun readBlock(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("tar archive is truncated")
            }
            off += n
        }
        return true
    }

    private fun readBytes(size: Long): ByteArray {
        if (size > 16L * 1024 * 1024) throw java.io.IOException("tar header too large")
        val data = ByteArray(size.toInt())
        var off = 0
        while (off < data.size) {
            val n = input.read(data, off, data.size - off)
            if (n < 0) throw EOFException("tar archive is truncated")
            off += n
        }
        skip((512 - size % 512) % 512)
        return data
    }

    private fun readString(size: Long): String = String(readBytes(size), Charsets.UTF_8).trimEnd('\u0000')

    private fun parsePax(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var pos = 0
        while (pos < data.size) {
            var sp = pos
            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
            val len = String(data, pos, sp - pos, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || pos + len > data.size) break
            val record = String(data, sp + 1, pos + len - sp - 2, Charsets.UTF_8) // drop trailing \n
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            pos += len
        }
        return out
    }

    private fun cstring(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun number(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {
            // GNU base-256 encoding for large values.
            var v = (b[off].toLong() and 0x7F)
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c in '0'.code..'7'.code) v = v * 8 + (c - '0'.code)
            else if (c == 0 || (c == ' '.code && v > 0)) break
        }
        return v
    }
}
