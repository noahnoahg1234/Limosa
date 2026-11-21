package com.limosa.core

import com.limosa.core.http.DashboardApi
import com.limosa.core.http.HttpServer
import com.limosa.core.idle.IdleController
import com.limosa.core.json.Json
import com.limosa.core.json.bool
import com.limosa.core.json.long
import com.limosa.core.nbd.NbdServer
import com.limosa.core.storage.ChunkStore
import com.limosa.core.vm.QemuEnvironment
import com.limosa.core.vm.VmManager
import java.io.Closeable
import java.io.File
import java.security.SecureRandom

/**
 * Everything Limosa runs, wired together:
 *  - [store]: the deduplicating, thin-provisioned fake storage
 *  - [nbd]: serves the store's disks to QEMU on 127.0.0.1
 *  - [vms]: QEMU processes
 *  - [idle]: pauses VMs while the OS is inactive
 *  - [http]: the localhost dashboard site and its API
 */
class LimosaEngine(
    val dataDir: File,
    qemu: QemuEnvironment,
    staticFiles: (String) -> ByteArray?,
    httpPort: Int = DEFAULT_HTTP_PORT,
    nbdPort: Int = NbdServer.DEFAULT_PORT,
    signaller: ((Long, String) -> Unit)? = null,
    val log: (String) -> Unit = { System.err.println(it) },
) : Closeable {

    val store = ChunkStore(File(dataDir, "storage"))
    val nbd = NbdServer(store, nbdPort, log).start()
    val vms = if (signaller != null) {
        VmManager(File(dataDir, "vms"), store, qemu, nbd.port, signaller, log)
    } else {
        VmManager(File(dataDir, "vms"), store, qemu, nbd.port, log = log)
    }
    private val settingsFile = File(dataDir, "idle-settings.json")

    val idle = IdleController(
        settings = loadSettings(),
        onIdle = { reason ->
            val n = vms.pauseAllForIdle()
            log("OS inactive ($reason): paused $n VM(s)")
        },
        onActive = {
            val n = vms.resumeAllFromIdle()
            log("OS active again: resumed $n VM(s)")
        },
    ).start()

    /** Secret the dashboard must present, so other apps on the phone cannot drive it. */
    val token: String = File(dataDir, "token").let { f ->
        if (f.exists() && f.readText().length >= 32) f.readText().trim()
        else ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { f.parentFile?.mkdirs(); f.writeText(it) }
    }

    val http = HttpServer(httpPort, DashboardApi(this, staticFiles)::handle, log).start()

    val dashboardUrl: String get() = "http://127.0.0.1:${http.port}/?t=$token"

    // Periodically persist the store so a killed app loses at most a few seconds of writes.
    private val flusher = Thread({
        while (!Thread.currentThread().isInterrupted) {
            try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
            runCatching { store.flush() }
        }
    }, "store-flush").apply { isDaemon = true; start() }

    fun updateIdleSettings(s: IdleController.Settings) {
        idle.settings = s
        settingsFile.writeText(Json.stringify(settingsToJson(s)))
        idle.evaluate()
    }

    private fun loadSettings(): IdleController.Settings = runCatching {
        settingsFromJson(Json.parseObject(settingsFile.readText()))
    }.getOrDefault(IdleController.Settings())

    override fun close() {
        flusher.interrupt()
        idle.close()
        http.close()
        vms.close()
        nbd.close()
        store.close()
    }

    companion object {
        const val DEFAULT_HTTP_PORT = 8765

        fun settingsToJson(s: IdleController.Settings) = mapOf(
            "enabled" to s.enabled,
            "screenOffDelaySec" to s.screenOffDelaySec,
            "pauseWhenLocked" to s.pauseWhenLocked,
            "pauseOnDeviceIdle" to s.pauseOnDeviceIdle,
            "pauseOnPowerSave" to s.pauseOnPowerSave,
            "lowBatteryPercent" to s.lowBatteryPercent,
        )

        fun settingsFromJson(m: Map<String, Any?>, base: IdleController.Settings = IdleController.Settings()) =
            IdleController.Settings(
                enabled = m.bool("enabled") ?: base.enabled,
                screenOffDelaySec = (m.long("screenOffDelaySec") ?: base.screenOffDelaySec.toLong()).toInt().coerceIn(-1, 86400),
                pauseWhenLocked = m.bool("pauseWhenLocked") ?: base.pauseWhenLocked,
                pauseOnDeviceIdle = m.bool("pauseOnDeviceIdle") ?: base.pauseOnDeviceIdle,
                pauseOnPowerSave = m.bool("pauseOnPowerSave") ?: base.pauseOnPowerSave,
                lowBatteryPercent = (m.long("lowBatteryPercent") ?: base.lowBatteryPercent.toLong()).toInt().coerceIn(0, 100),
            )
    }
}
