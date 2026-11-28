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
class VncClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
    /** For Android's performance hints (API 31+): keeps the CPU fast enough for 60 frames a second. */
    private val context: android.content.Context? = null,
) {

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
    /**
     * The framebuffer exactly as Android stores an ARGB_8888 bitmap in memory (R, G, B, X bytes).
     * The server sends pixels in this order, so rectangles are read straight into it with no
     * per-pixel work, and each frame is one memory copy into the bitmap.
     */
    private var fb = ByteArray(0)
    private var fbBuffer: java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(fb)
    private var hint: Any? = null   // android.os.PerformanceHintManager.Session

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
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { (hint as? android.os.PerformanceHintManager.Session)?.close() }
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
            // SetPixelFormat: 32 bpp, depth 24, little endian, true colour, red in the lowest byte:
            // R, G, B, X in memory, which is Android's own bitmap layout.
            o.writeByte(0); o.write(ByteArray(3))
            o.writeByte(32); o.writeByte(24); o.writeByte(0); o.writeByte(1)
            o.writeShort(255); o.writeShort(255); o.writeShort(255)
            o.writeByte(0); o.writeByte(8); o.writeByte(16)
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

        startHint()
        while (!closed) {
            when (val type = input.readUnsignedByte()) {
                0 -> {
                    val t0 = System.nanoTime()
                    framebufferUpdate(input)
                    synchronized(lock) {
                        val b = bitmap
                        if (b != null && b.width == width && b.height == height) {
                            fbBuffer.rewind()
                            b.copyPixelsFromBuffer(fbBuffer)
                        }
                    }
                    reportHint(System.nanoTime() - t0)
                    listener.onFramebufferUpdated()
                    // Up to 60 frames a second (one per 16 ms).
                    val wait = lastRequest + MIN_FRAME_MS - System.currentTimeMillis()
                    if (wait > 0) Thread.sleep(wait)
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

    private var lastRequest = 0L

    private fun requestUpdate(o: DataOutputStream, incremental: Boolean) {
        lastRequest = System.currentTimeMillis()
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
        if (w == 0 || h == 0) return
        val stride = width * 4
        if (x + w > width || y + h > height) {
            input.readFully(ByteArray(w * h * 4))   // out of bounds: skip it
            return
        }
        // Each row goes straight into the framebuffer.
        var off = y * stride + x * 4
        val rowBytes = w * 4
        for (row in 0 until h) {
            input.readFully(fb, off, rowBytes)
            off += stride
        }
    }

    private fun copyRect(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        if (sx + w > width || sy + h > height || x + w > width || y + h > height) return
        val stride = width * 4
        val rowBytes = w * 4
        // Moving down: copy the bottom row first so overlapping rows aren't overwritten too early.
        val rows = if (y > sy) (h - 1 downTo 0) else (0 until h)
        for (r in rows) System.arraycopy(fb, (sy + r) * stride + sx * 4, fb, (y + r) * stride + x * 4, rowBytes)
    }

    private fun resize(w: Int, h: Int) {
        synchronized(lock) {
            width = w
            height = h
            fb = ByteArray(w.coerceAtLeast(1) * h.coerceAtLeast(1) * 4)
            fbBuffer = java.nio.ByteBuffer.wrap(fb)
            bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply {
                // The 4th byte is padding from the server, not transparency.
                setHasAlpha(false)
            }
        }
    }

    /** Tells Android this thread has 16 ms per frame, so it keeps the CPU fast enough. */
    private fun startHint() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        hint = runCatching {
            context?.getSystemService(android.os.PerformanceHintManager::class.java)
                ?.createHintSession(intArrayOf(android.os.Process.myTid()), FRAME_NS)
        }.getOrNull()
    }

    private fun reportHint(ns: Long) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        runCatching { (hint as? android.os.PerformanceHintManager.Session)?.reportActualWorkDuration(ns.coerceAtLeast(1)) }
    }

    private companion object {
        const val MIN_FRAME_MS = 16L
        const val FRAME_NS = 16_666_667L
    }

    private fun reason(input: DataInputStream): String {
        val len = input.readInt()
        if (len !in 0..4096) return "connection refused"
        return ByteArray(len).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }
}
