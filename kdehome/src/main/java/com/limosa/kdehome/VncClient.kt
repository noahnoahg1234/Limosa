package com.limosa.kdehome

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * Minimal RFB (VNC) client for the local KDE display: no authentication (the server only
 * listens on 127.0.0.1), 32-bit true colour, Raw + CopyRect + DesktopSize encodings.
 */
class VncClient(private val host: String, private val port: Int, private val listener: Listener) {

    interface Listener {
        fun onConnected(width: Int, height: Int)
        fun onFramebufferUpdated()
        fun onServerClipboard(text: String)
        fun onDisconnected(error: String?)
    }

    /** Guards [bitmap]: hold it while drawing. */
    val lock = Any()
    @Volatile var bitmap: Bitmap? = null
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()
    private var raw = ByteArray(0)
    private var pixels = IntArray(0)

    fun start() {
        thread(name = "vnc-reader", isDaemon = true) {
            var error: String? = null
            try {
                session()
            } catch (e: Exception) {
                if (!closed) error = e.message ?: e.javaClass.simpleName
            } finally {
                close()
                listener.onDisconnected(error)
            }
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        writer.shutdown()
    }

    // ---- Input -----------------------------------------------------------------------------

    /** [mask]: bit 0 left, 1 middle, 2 right, 3 wheel up, 4 wheel down. */
    fun pointer(mask: Int, x: Int, y: Int) = send {
        it.writeByte(5)
        it.writeByte(mask)
        it.writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0)))
        it.writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
    }

    fun key(keysym: Int, down: Boolean) = send {
        it.writeByte(4)
        it.writeByte(if (down) 1 else 0)
        it.writeShort(0)
        it.writeInt(keysym)
    }

    fun clipboard(text: String) = send {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        it.writeByte(6)
        it.write(ByteArray(3))
        it.writeInt(bytes.size)
        it.write(bytes)
    }

    private fun send(block: (DataOutputStream) -> Unit) {
        val o = out ?: return
        if (closed) return
        try {
            writer.execute {
                try {
                    synchronized(o) { block(o); o.flush() }
                } catch (_: IOException) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    // ---- Protocol --------------------------------------------------------------------------

    private fun session() {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 3000)
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 13))

        val banner = ByteArray(12).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        if (!banner.startsWith("RFB ")) throw IOException("not a VNC server")
        val major = banner.substring(4, 7).toIntOrNull() ?: 3
        val minor = banner.substring(8, 11).toIntOrNull() ?: 3
        val version = when {
            major > 3 || minor >= 8 -> 8
            minor == 7 -> 7
            else -> 3
        }
        o.write("RFB 003.00$version\n".toByteArray(Charsets.US_ASCII))
        o.flush()
        if (version == 3) {
            when (input.readInt()) {
                0 -> throw IOException(reason(input))
                1 -> Unit
                else -> throw IOException("the VNC server wants a password")
            }
        } else {
            val n = input.readUnsignedByte()
            if (n == 0) throw IOException(reason(input))
            val types = ByteArray(n).also { input.readFully(it) }
            if (types.none { it.toInt() == 1 }) throw IOException("the VNC server wants a password")
            o.writeByte(1)
            o.flush()
            if (version == 8 && input.readInt() != 0) throw IOException("VNC login failed: ${reason(input)}")
        }
        o.writeByte(1) // shared session
        o.flush()

        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        input.readFully(ByteArray(16))  // server pixel format; we set our own
        val nameLen = input.readInt()
        if (nameLen in 1..65536) input.readFully(ByteArray(nameLen))
        resize(w, h)

        synchronized(o) {
            // SetPixelFormat: 32 bpp, depth 24, little endian, true colour, BGRX in memory.
            o.writeByte(0); o.write(ByteArray(3))
            o.writeByte(32); o.writeByte(24); o.writeByte(0); o.writeByte(1)
            o.writeShort(255); o.writeShort(255); o.writeShort(255)
            o.writeByte(16); o.writeByte(8); o.writeByte(0)
            o.write(ByteArray(3))
            // SetEncodings: CopyRect, Raw, DesktopSize.
            val encodings = intArrayOf(1, 0, -223)
            o.writeByte(2); o.writeByte(0); o.writeShort(encodings.size)
            for (e in encodings) o.writeInt(e)
            o.flush()
        }
        out = o
        requestUpdate(o, incremental = false)
        listener.onConnected(w, h)

        while (!closed) {
            when (val type = input.readUnsignedByte()) {
                0 -> {
                    framebufferUpdate(input)
                    listener.onFramebufferUpdated()
                    requestUpdate(o, incremental = true)
                }
                1 -> { // SetColourMapEntries (not used in true colour)
                    input.readUnsignedByte(); input.readUnsignedShort()
                    val n = input.readUnsignedShort()
                    input.readFully(ByteArray(n * 6))
                }
                2 -> Unit // Bell
                3 -> {
                    input.readFully(ByteArray(3))
                    val len = input.readInt()
                    if (len < 0 || len > 8 shl 20) throw IOException("bad clipboard length")
                    val text = ByteArray(len).also { input.readFully(it) }.toString(Charsets.ISO_8859_1)
                    listener.onServerClipboard(text)
                }
                else -> throw IOException("unknown VNC message $type")
            }
        }
    }

    private fun requestUpdate(o: DataOutputStream, incremental: Boolean) {
        synchronized(o) {
            o.writeByte(3)
            o.writeByte(if (incremental) 1 else 0)
            o.writeShort(0); o.writeShort(0)
            o.writeShort(width); o.writeShort(height)
            o.flush()
        }
    }

    private fun framebufferUpdate(input: DataInputStream) {
        input.readUnsignedByte()
        val rects = input.readUnsignedShort()
        repeat(rects) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val enc = input.readInt()) {
                0 -> rawRect(input, x, y, w, h)
                1 -> copyRect(input.readUnsignedShort(), input.readUnsignedShort(), x, y, w, h)
                -223 -> resize(w, h)
                else -> throw IOException("unsupported VNC encoding $enc")
            }
        }
    }

    private fun rawRect(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val count = w * h
        if (count == 0) return
        val bytes = count * 4
        if (raw.size < bytes) raw = ByteArray(bytes)
        if (pixels.size < count) pixels = IntArray(count)
        input.readFully(raw, 0, bytes)
        val r = raw
        val p = pixels
        var j = 0
        for (i in 0 until count) {
            p[i] = -0x1000000 or
                ((r[j + 2].toInt() and 0xFF) shl 16) or
                ((r[j + 1].toInt() and 0xFF) shl 8) or
                (r[j].toInt() and 0xFF)
            j += 4
        }
        synchronized(lock) {
            val b = bitmap ?: return
            if (x + w <= b.width && y + h <= b.height) b.setPixels(p, 0, w, x, y, w, h)
        }
    }

    private fun copyRect(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        synchronized(lock) {
            val b = bitmap ?: return
            if (sx + w > b.width || sy + h > b.height || x + w > b.width || y + h > b.height) return
            val tmp = IntArray(w * h)
            b.getPixels(tmp, 0, w, sx, sy, w, h)
            b.setPixels(tmp, 0, w, x, y, w, h)
        }
    }

    private fun resize(w: Int, h: Int) {
        synchronized(lock) {
            width = w
            height = h
            bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }
    }

    private fun reason(input: DataInputStream): String {
        val len = input.readInt()
        if (len !in 0..4096) return "connection refused"
        return ByteArray(len).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }
}
