package com.limosa.core.http

import com.limosa.core.LimosaEngine
import com.limosa.core.http.HttpServer.Request
import com.limosa.core.http.HttpServer.Response
import com.limosa.core.json.bool
import com.limosa.core.json.long
import com.limosa.core.json.str
import com.limosa.core.storage.ChunkStore
import com.limosa.core.vm.VmConfig
import com.limosa.core.vm.VmManager
import java.security.MessageDigest

/**
 * The localhost site: static dashboard files plus a JSON API, and raw disk access at
 * `/disk/<name>` (HTTP Range reads), which is the "fake storage" seen from the web side.
 */
class DashboardApi(private val engine: LimosaEngine, private val staticFiles: (String) -> ByteArray?) {

    fun handle(req: Request): Response {
        val p = req.path
        // Visiting /?t=<token> stores the token in a cookie for later requests.
        val queryToken = req.query["t"]
        val authed = tokenOk(queryToken) || tokenOk(req.cookie(COOKIE)) || tokenOk(req.header("x-limosa-token"))

        if (!p.startsWith("/api/") && !p.startsWith("/disk/")) {
            if (!authed) return Response.bytes(401, "text/html; charset=utf-8", UNAUTHORIZED.toByteArray())
            val headers = if (tokenOk(queryToken)) mapOf("Set-Cookie" to "$COOKIE=$queryToken; Path=/; HttpOnly; SameSite=Strict") else emptyMap()
            return static(p, headers)
        }
        if (!authed) return Response.error(401, "Missing or wrong token")

        val seg = p.trim('/').split('/')
        return when {
            p == "/api/status" -> Response.json(status())

            // ---- disks
            p == "/api/disks" && req.method == "GET" -> Response.json(engine.store.listDisks().map { it.toJson() })
            p == "/api/disks" && req.method == "POST" -> {
                val b = req.bodyJson()
                val name = b.str("name") ?: throw IllegalArgumentException("name is required")
                val size = b.long("size") ?: ((b["sizeGb"] as? Number)?.toDouble()?.let { (it * GiB).toLong() })
                    ?: throw IllegalArgumentException("size or sizeGb is required")
                Response.json(engine.store.createDisk(name, size, b.bool("readOnly") ?: false).toJson(), 201)
            }
            seg.size == 3 && seg[0] == "api" && seg[1] == "disks" && req.method == "PUT" -> {
                // Raw upload, e.g. an installer ISO: PUT /api/disks/<name>?readOnly=true
                val name = seg[2]
                val ro = req.query["readOnly"]?.toBooleanStrictOrNull() ?: true
                Response.json(engine.store.importDisk(name, req.body, req.contentLength, ro).toJson(), 201)
            }
            seg.size == 3 && seg[0] == "api" && seg[1] == "disks" && req.method == "DELETE" -> {
                val inUse = engine.vms.runningVms().filter { it.config.disk == seg[2] || it.config.cdrom == seg[2] }
                check(inUse.isEmpty()) { "Disk is used by running VM ${inUse.first().config.name}" }
                engine.store.deleteDisk(seg[2])
                Response.json(mapOf("ok" to true))
            }
            seg.size == 4 && seg[0] == "api" && seg[1] == "disks" && seg[3] == "clone" && req.method == "POST" -> {
                val target = req.bodyJson().str("target") ?: throw IllegalArgumentException("target is required")
                Response.json(engine.store.cloneDisk(seg[2], target).toJson(), 201)
            }
            seg.size == 2 && seg[0] == "disk" && (req.method == "GET" || req.method == "HEAD") -> rawDisk(seg[1], req)

            // ---- VMs
            p == "/api/vms" && req.method == "GET" -> Response.json(engine.vms.list().map { vmJson(it) })
            p == "/api/vms" && req.method == "POST" -> {
                val c = VmConfig.fromJson(req.bodyJson())
                engine.vms.save(c)
                Response.json(vmJson(c), 201)
            }
            seg.size == 3 && seg[0] == "api" && seg[1] == "vms" && req.method == "DELETE" -> {
                engine.vms.delete(seg[2])
                Response.json(mapOf("ok" to true))
            }
            seg.size == 4 && seg[0] == "api" && seg[1] == "vms" && req.method == "GET" && seg[3] == "log" ->
                Response.json(engine.vms.log(seg[2]))
            seg.size == 4 && seg[0] == "api" && seg[1] == "vms" && req.method == "GET" && seg[3] == "command" -> {
                val c = engine.vms.get(seg[2]) ?: throw NoSuchElementException("No VM '${seg[2]}'")
                Response.json(engine.vms.buildCommand(c, 0))
            }
            seg.size == 4 && seg[0] == "api" && seg[1] == "vms" && req.method == "POST" -> {
                val name = seg[2]
                when (seg[3]) {
                    "start" -> engine.vms.start(name)
                    "stop" -> engine.vms.stop(name)
                    "kill" -> engine.vms.kill(name)
                    "pause" -> engine.vms.pause(name, VmManager.PauseReason.USER)
                    "resume" -> engine.vms.resume(name, VmManager.PauseReason.USER)
                    else -> throw NoSuchElementException("Unknown action ${seg[3]}")
                }
                Response.json(vmJson(engine.vms.get(name)!!))
            }

            // ---- idle / power settings
            p == "/api/settings" && req.method == "GET" -> Response.json(LimosaEngine.settingsToJson(engine.idle.settings))
            p == "/api/settings" && req.method == "POST" -> {
                engine.updateIdleSettings(LimosaEngine.settingsFromJson(req.bodyJson(), engine.idle.settings))
                Response.json(LimosaEngine.settingsToJson(engine.idle.settings))
            }
            else -> Response.error(404, "No route for ${req.method} $p")
        }
    }

    private fun status(): Map<String, Any?> {
        val s = engine.store.stats()
        val sig = engine.idle.signals
        return mapOf(
            "storage" to mapOf(
                "disks" to s.disks,
                "virtualBytes" to s.virtualBytes,
                "logicalBytes" to s.logicalBytes,
                "physicalBytes" to s.physicalBytes,
                "savedBytes" to s.savedBytes,
                "uniqueChunks" to s.uniqueChunks,
                "chunkSize" to engine.store.chunkSize,
                "freeBytes" to engine.store.root.usableSpace,
            ),
            "idle" to mapOf(
                "inactive" to engine.idle.idle,
                "reason" to engine.idle.idleReason,
                "screenOn" to sig.screenOn,
                "locked" to sig.locked,
                "deviceIdle" to sig.deviceIdle,
                "powerSave" to sig.powerSave,
                "batteryPercent" to sig.batteryPercent,
                "charging" to sig.charging,
            ),
            "nbdPort" to engine.nbd.port,
            "nbdConnections" to engine.nbd.connections,
            "running" to engine.vms.runningVms().size,
        )
    }

    private fun vmJson(c: VmConfig): Map<String, Any?> {
        val r = engine.vms.running(c.name)
        return c.toJson() + mapOf(
            "state" to (r?.state ?: VmManager.State.STOPPED).name.lowercase(),
            "pauseReason" to r?.pauseReason?.name?.lowercase(),
            "vncWebSocketPort" to r?.vncWebSocketPort,
            "startedAt" to r?.startedAt,
        )
    }

    private fun rawDisk(name: String, req: Request): Response {
        val d = engine.store.disk(name) ?: throw NoSuchElementException("No disk '$name'")
        var start = 0L
        var end = d.size - 1
        var status = 200
        val range = req.header("range")
        if (range != null) {
            val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range.trim())
                ?: return Response.error(416, "Unsupported range")
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            when {
                a.isEmpty() && b.isNotEmpty() -> start = maxOf(0, d.size - b.toLong())
                a.isNotEmpty() -> {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(end, b.toLong())
                }
                else -> return Response.error(416, "Unsupported range")
            }
            if (start > end || start >= d.size) {
                return Response(416, "text/plain", mapOf("Content-Range" to "bytes */${d.size}"), 0) {}
            }
            status = 206
        }
        val len = end - start + 1
        val headers = buildMap {
            put("Accept-Ranges", "bytes")
            put("Content-Disposition", "attachment; filename=\"$name.img\"")
            if (status == 206) put("Content-Range", "bytes $start-$end/${d.size}")
        }
        val head = req.method == "HEAD"
        return Response(status, "application/octet-stream", headers, len) { out ->
            if (head) return@Response
            val buf = ByteArray(engine.store.chunkSize)
            var pos = start
            while (pos <= end) {
                val n = minOf(buf.size.toLong(), end - pos + 1).toInt()
                d.read(pos, buf, 0, n)
                out.write(buf, 0, n)
                pos += n
            }
        }
    }

    private fun static(path: String, headers: Map<String, String>): Response {
        val clean = if (path == "/" || path.isEmpty()) "index.html" else path.trimStart('/')
        if (clean.contains("..")) return Response.error(400, "Bad path")
        val data = staticFiles(clean) ?: return Response.error(404, "Not found: $clean")
        return Response.bytes(200, mime(clean), data, headers)
    }

    private fun tokenOk(t: String?): Boolean =
        t != null && MessageDigest.isEqual(t.toByteArray(), engine.token.toByteArray())

    companion object {
        const val COOKIE = "limosa"
        private const val GiB = 1024.0 * 1024 * 1024
        private const val UNAUTHORIZED =
            "<!doctype html><meta charset=utf-8><title>Limosa</title><p>Open the dashboard from the Limosa app.</p>"

        fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}
