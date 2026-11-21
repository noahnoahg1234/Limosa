package com.limosa.core.vm

import com.limosa.core.json.Json
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks to QEMU's QMP control socket (`-qmp tcp:127.0.0.1:<port>,server=on,wait=off`).
 * Used to pause (`stop`) and resume (`cont`) the guest without killing it.
 */
class QmpClient private constructor(private val socket: Socket) : Closeable {

    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

    @Synchronized
    fun execute(command: String, arguments: Map<String, Any?>? = null): Any? {
        val msg = if (arguments == null) mapOf("execute" to command) else mapOf("execute" to command, "arguments" to arguments)
        writer.write(Json.stringify(msg))
        writer.write("\n")
        writer.flush()
        while (true) {
            val line = reader.readLine() ?: throw IOException("QMP connection closed")
            if (line.isBlank()) continue
            val obj = Json.parseObject(line)
            if (obj.containsKey("event")) continue // asynchronous events are ignored
            if (obj.containsKey("error")) {
                @Suppress("UNCHECKED_CAST")
                val err = obj["error"] as? Map<String, Any?>
                throw QmpException(err?.get("desc")?.toString() ?: line)
            }
            if (obj.containsKey("return")) return obj["return"]
        }
    }

    fun stop() { execute("stop") }
    fun cont() { execute("cont") }
    fun quit() { execute("quit") }
    fun powerdown() { execute("system_powerdown") }

    /** QEMU run state, e.g. "running", "paused", "shutdown". */
    @Suppress("UNCHECKED_CAST")
    fun status(): String = ((execute("query-status") as? Map<String, Any?>)?.get("status") ?: "unknown").toString()

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun connect(port: Int, timeoutMs: Int = 3000): QmpClient {
            val s = Socket()
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMs)
            s.soTimeout = 15_000
            val c = QmpClient(s)
            try {
                val greeting = c.reader.readLine() ?: throw IOException("No QMP greeting")
                if (!greeting.contains("QMP")) throw IOException("Unexpected QMP greeting: $greeting")
                c.execute("qmp_capabilities")
            } catch (e: Exception) {
                c.close()
                throw e
            }
            return c
        }
    }
}

class QmpException(message: String) : IOException(message)
