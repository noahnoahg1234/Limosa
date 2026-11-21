package com.limosa.core.http

import com.limosa.core.json.Json
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A tiny HTTP/1.1 server (one request per connection) bound to 127.0.0.1. */
class HttpServer(port: Int, private val handler: (Request) -> Response, private val log: (String) -> Unit = {}) : Closeable {

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
    ) {
        val contentLength: Long? get() = headers["content-length"]?.toLongOrNull()
        fun header(name: String) = headers[name.lowercase()]
        fun bodyText(limit: Int = 1 shl 20): String {
            val n = (contentLength ?: 0).toInt()
            if (n > limit) throw IllegalArgumentException("Body too large")
            return String(body.readNBytesCompat(n), Charsets.UTF_8)
        }
        fun bodyJson(): Map<String, Any?> = bodyText().let { if (it.isBlank()) emptyMap() else Json.parseObject(it) }
        fun cookie(name: String): String? = header("cookie")?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }

    class Response(
        val status: Int,
        val contentType: String = "application/json",
        val headers: Map<String, String> = emptyMap(),
        val length: Long? = null,
        val writer: (OutputStream) -> Unit,
    ) {
        companion object {
            fun bytes(status: Int, type: String, data: ByteArray, headers: Map<String, String> = emptyMap()) =
                Response(status, type, headers, data.size.toLong()) { it.write(data) }

            fun json(value: Any?, status: Int = 200) =
                bytes(status, "application/json; charset=utf-8", Json.stringify(value).toByteArray())

            fun error(status: Int, message: String) = json(mapOf("error" to message), status)
        }
    }

    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
    }
    val port: Int get() = server.localPort
    private val pool: ExecutorService = Executors.newFixedThreadPool(8) { r -> Thread(r, "http").apply { isDaemon = true } }
    @Volatile private var running = true

    fun start(): HttpServer {
        Thread({
            while (running) {
                val s = try { server.accept() } catch (e: IOException) { break }
                pool.execute { serve(s) }
            }
        }, "http-accept").apply { isDaemon = true }.start()
        return this
    }

    private fun serve(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 60_000
            val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            val out = sock.getOutputStream().buffered(64 * 1024)
            val response = try {
                val req = parse(input) ?: return
                try {
                    handler(req)
                } catch (e: NoSuchElementException) {
                    Response.error(404, e.message ?: "Not found")
                } catch (e: IllegalArgumentException) {
                    Response.error(400, e.message ?: "Bad request")
                } catch (e: IllegalStateException) {
                    Response.error(409, e.message ?: "Conflict")
                } catch (e: Exception) {
                    log("http ${req.method} ${req.path}: $e")
                    Response.error(500, e.message ?: e.toString())
                }
            } catch (e: Exception) {
                Response.error(400, "Malformed request")
            }
            try {
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
                sb.append("Content-Type: ").append(response.contentType).append("\r\n")
                response.length?.let { sb.append("Content-Length: ").append(it).append("\r\n") }
                sb.append("Connection: close\r\n")
                sb.append("Cache-Control: no-store\r\n")
                for ((k, v) in response.headers) sb.append(k).append(": ").append(v).append("\r\n")
                sb.append("\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                response.writer(out)
                out.flush()
            } catch (e: IOException) {
                // client closed early
            }
        }
    }

    private fun parse(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw IOException("Bad request line")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 100) throw IOException("Too many headers")
        }
        val target = parts[1]
        val path = decode(target.substringBefore('?'))
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        return Request(parts[0].uppercase(), path, query, headers, LimitedInputStream(input, len))
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16 * 1024) throw IOException("Line too long")
        }
    }

    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    override fun close() {
        running = false
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val c = super.read()
            if (c >= 0) remaining--
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
        override fun close() {}
    }

    companion object {
        fun reason(code: Int) = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"
            302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            416 -> "Range Not Satisfiable"; else -> if (code >= 500) "Server Error" else "OK"
        }
    }
}

/** InputStream.readNBytes is Java 11 / Android API 33; keep a portable version. */
fun InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r < 0) break
        off += r
    }
    return if (off == n) buf else buf.copyOf(off)
}
