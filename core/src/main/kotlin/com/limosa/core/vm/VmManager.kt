package com.limosa.core.vm

import com.limosa.core.json.Json
import com.limosa.core.storage.ChunkStore
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Where to find QEMU on this device. On Android, QEMU is shipped as `libqemu-system-*.so`
 * in the APK's native library dir, because that is the only app location from which
 * Android 10+ lets an app execute a binary.
 */
interface QemuEnvironment {
    fun binary(arch: VmConfig.Arch): File?
    /** Directory with BIOS/firmware/keymaps (passed as `-L`). */
    fun dataDir(): File?
    fun environment(): Map<String, String> = emptyMap()
    /** True if hardware virtualisation (KVM) is usable for [arch] on this device. */
    fun kvmAvailable(arch: VmConfig.Arch): Boolean = false
}

/**
 * Starts, stops, pauses and resumes QEMU processes. Disks are attached over NBD from the
 * local fake-storage server, so they take only the space their data really needs.
 */
class VmManager(
    private val configDir: File,
    private val store: ChunkStore,
    private val qemu: QemuEnvironment,
    private val nbdPort: Int,
    private val signaller: (pid: Long, signal: String) -> Unit = ::defaultSignal,
    private val log: (String) -> Unit = {},
) : Closeable {

    enum class State { STARTING, RUNNING, PAUSED, STOPPED }

    /** Who paused a VM. Auto-resume only undoes [IDLE] pauses, never a user's. */
    enum class PauseReason { USER, IDLE }

    inner class RunningVm internal constructor(
        val config: VmConfig,
        val slot: Int,
        internal val process: Process,
    ) {
        @Volatile var state: State = State.STARTING
            internal set
        @Volatile var pauseReason: PauseReason? = null
            internal set
        @Volatile var deepSleep = false
            internal set
        val startedAt = System.currentTimeMillis()
        val qmpPort get() = QMP_BASE_PORT + slot
        val vncWebSocketPort get() = VNC_WS_BASE_PORT + slot
        internal val logLines = ArrayDeque<String>()
        @Volatile internal var qmp: QmpClient? = null

        fun log(): List<String> = synchronized(logLines) { logLines.toList() }

        internal fun qmp(): QmpClient {
            qmp?.let { return it }
            var last: Exception? = null
            repeat(20) {
                try {
                    return QmpClient.connect(qmpPort).also { qmp = it }
                } catch (e: Exception) {
                    last = e
                    if (!process.isAlive) throw IOException("QEMU exited")
                    Thread.sleep(250)
                }
            }
            throw IOException("Cannot reach QMP on port $qmpPort: $last")
        }
    }

    private val configs = LinkedHashMap<String, VmConfig>()
    private val running = LinkedHashMap<String, RunningVm>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        configDir.mkdirs()
        configDir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach {
            runCatching { VmConfig.fromJson(Json.parseObject(it.readText())) }
                .onSuccess { c -> configs[c.name] = c }
                .onFailure { e -> log("Skipping VM config ${it.name}: $e") }
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    private fun changed() = listeners.forEach { runCatching { it() } }

    // ------------------------------------------------------------------ configs

    @Synchronized fun list(): List<VmConfig> = configs.values.toList()
    @Synchronized fun get(name: String): VmConfig? = configs[name]
    @Synchronized fun running(name: String): RunningVm? = running[name]
    @Synchronized fun runningVms(): List<RunningVm> = running.values.toList()

    @Synchronized
    fun save(config: VmConfig) {
        ChunkStore.requireValidName(config.name)
        config.disk?.let { require(store.disk(it) != null) { "Disk '$it' does not exist" } }
        config.cdrom?.let { require(store.disk(it) != null) { "CD image '$it' does not exist" } }
        require(config.name !in running) { "Stop the VM before editing it" }
        File(configDir, "${config.name}.json").writeText(Json.stringify(config.toJson()))
        configs[config.name] = config
        changed()
    }

    @Synchronized
    fun delete(name: String) {
        require(name !in running) { "Stop the VM before deleting it" }
        configs.remove(name) ?: throw NoSuchElementException("No VM '$name'")
        File(configDir, "$name.json").delete()
        changed()
    }

    // ------------------------------------------------------------------ lifecycle

    /** The QEMU command line for [config] in [slot]. Public so it can be shown/tested. */
    fun buildCommand(config: VmConfig, slot: Int): List<String> {
        val bin = qemu.binary(config.arch) ?: throw IOException(
            "QEMU for ${config.arch.name.lowercase()} is not installed (expected ${config.arch.binary})"
        )
        val cmd = ArrayList<String>()
        cmd += bin.absolutePath
        cmd += listOf("-name", config.name)
        qemu.dataDir()?.let { cmd += listOf("-L", it.absolutePath) }
        val kvm = qemu.kvmAvailable(config.arch)
        when (config.arch) {
            VmConfig.Arch.AARCH64 -> {
                cmd += listOf("-machine", "virt", "-cpu", if (kvm) "host" else "max")
                qemu.dataDir()?.let { dir ->
                    File(dir, "edk2-aarch64-code.fd").takeIf { it.exists() }?.let { cmd += listOf("-bios", it.absolutePath) }
                }
                cmd += listOf("-device", "virtio-gpu-pci", "-device", "qemu-xhci", "-device", "usb-kbd", "-device", "usb-tablet")
            }
            else -> {
                cmd += listOf("-machine", config.arch.machine, "-vga", "std", "-device", "qemu-xhci", "-device", "usb-tablet")
            }
        }
        cmd += listOf("-accel", if (kvm) "kvm" else "tcg,thread=multi")
        cmd += listOf("-m", config.memoryMb.toString(), "-smp", config.cpus.toString())

        val diskBus = if (config.arch == VmConfig.Arch.AARCH64) "virtio" else "ide"
        config.disk?.let {
            cmd += listOf("-drive", "file=${nbdUrl(it)},format=raw,if=$diskBus,cache=writeback,discard=unmap,detect-zeroes=unmap")
        }
        config.cdrom?.let {
            if (config.arch == VmConfig.Arch.AARCH64) {
                cmd += listOf("-drive", "file=${nbdUrl(it)},format=raw,if=none,id=cd0,readonly=on",
                    "-device", "usb-storage,drive=cd0,removable=on")
            } else {
                cmd += listOf("-drive", "file=${nbdUrl(it)},format=raw,media=cdrom,readonly=on")
            }
        }
        if (config.arch != VmConfig.Arch.AARCH64) {
            cmd += listOf("-boot", if (config.bootFromCdrom || config.disk == null) "order=dc,menu=off" else "order=cd,menu=off")
        }
        if (config.network) {
            val nic = if (config.arch == VmConfig.Arch.AARCH64) "virtio-net-pci" else "e1000"
            cmd += listOf("-netdev", "user,id=net0", "-device", "$nic,netdev=net0")
        } else {
            cmd += listOf("-nic", "none")
        }
        cmd += listOf("-display", "none")
        cmd += listOf("-vnc", "127.0.0.1:${VNC_DISPLAY_BASE + slot},websocket=127.0.0.1:${VNC_WS_BASE_PORT + slot}")
        cmd += listOf("-qmp", "tcp:127.0.0.1:${QMP_BASE_PORT + slot},server=on,wait=off")
        cmd += listOf("-monitor", "none", "-serial", "none", "-parallel", "none")
        if (config.extraArgs.isNotBlank()) cmd += config.extraArgs.trim().split(Regex("\\s+"))
        return cmd
    }

    private fun nbdUrl(disk: String) = "nbd://127.0.0.1:$nbdPort/$disk"

    fun start(name: String): RunningVm {
        val vm: RunningVm
        synchronized(this) {
            running[name]?.let { return it }
            val config = configs[name] ?: throw NoSuchElementException("No VM '$name'")
            val used = running.values.map { it.slot }.toSet()
            val slot = (0 until MAX_VMS).firstOrNull { it !in used } ?: throw IllegalStateException("Too many VMs running")
            val cmd = buildCommand(config, slot)
            log("Starting ${config.name}: ${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(qemu.environment())
            pb.directory(configDir)
            val p = pb.start()
            vm = RunningVm(config, slot, p)
            running[name] = vm
        }
        Thread({ pumpOutput(vm) }, "qemu-out-$name").apply { isDaemon = true }.start()
        Thread({ waitForExit(vm) }, "qemu-wait-$name").apply { isDaemon = true }.start()
        Thread({
            try {
                vm.qmp()
                if (vm.state == State.STARTING) vm.state = State.RUNNING
            } catch (e: Exception) {
                appendLog(vm, "Limosa: could not connect to QEMU control socket: ${e.message}")
            }
            changed()
        }, "qemu-qmp-$name").apply { isDaemon = true }.start()
        changed()
        return vm
    }

    /** Asks the guest OS to shut down (ACPI power button); kills QEMU if it ignores it. */
    fun stop(name: String, graceMs: Long = 30_000) {
        val vm = running(name) ?: return
        wake(vm)
        try {
            vm.qmp().powerdown()
        } catch (e: Exception) {
            kill(name)
            return
        }
        Thread({
            if (!vm.process.waitFor(graceMs, TimeUnit.MILLISECONDS)) kill(name)
        }, "qemu-stop-$name").apply { isDaemon = true }.start()
    }

    fun kill(name: String) {
        val vm = running(name) ?: return
        wake(vm)
        runCatching { vm.qmp?.quit() }
        if (!vm.process.waitFor(2, TimeUnit.SECONDS)) vm.process.destroyForcibly()
    }

    fun pause(name: String, reason: PauseReason = PauseReason.USER) {
        val vm = running(name) ?: throw NoSuchElementException("VM '$name' is not running")
        synchronized(vm) {
            if (vm.state == State.PAUSED) {
                if (reason == PauseReason.USER) vm.pauseReason = PauseReason.USER // user takes ownership
                return
            }
            vm.qmp().stop()
            vm.state = State.PAUSED
            vm.pauseReason = reason
            // Idle pauses also freeze the whole QEMU process (timers, display, I/O threads),
            // so it uses no CPU at all until the phone wakes up again.
            if (reason == PauseReason.IDLE) {
                pid(vm.process)?.let { pid ->
                    runCatching { signaller(pid, "STOP") }.onSuccess { vm.deepSleep = true }
                }
            }
        }
        appendLog(vm, "Limosa: paused (${reason.name.lowercase()})")
        changed()
    }

    fun resume(name: String, reason: PauseReason = PauseReason.USER) {
        val vm = running(name) ?: throw NoSuchElementException("VM '$name' is not running")
        synchronized(vm) {
            if (vm.state != State.PAUSED) return
            if (reason == PauseReason.IDLE && vm.pauseReason != PauseReason.IDLE) return
            wake(vm)
            vm.qmp().cont()
            vm.state = State.RUNNING
            vm.pauseReason = null
        }
        appendLog(vm, "Limosa: resumed")
        changed()
    }

    /** Pauses every running VM that allows auto-pause. Returns how many were paused. */
    fun pauseAllForIdle(): Int = runningVms().count { vm ->
        vm.config.autoPause && vm.state == State.RUNNING &&
            runCatching { pause(vm.config.name, PauseReason.IDLE) }.onFailure { log("auto-pause ${vm.config.name}: $it") }.isSuccess
    }

    /** Resumes only VMs that [pauseAllForIdle] paused. */
    fun resumeAllFromIdle(): Int = runningVms().count { vm ->
        vm.state == State.PAUSED && vm.pauseReason == PauseReason.IDLE &&
            runCatching { resume(vm.config.name, PauseReason.IDLE) }.onFailure { log("auto-resume ${vm.config.name}: $it") }.isSuccess
    }

    private fun wake(vm: RunningVm) {
        if (vm.deepSleep) {
            pid(vm.process)?.let { runCatching { signaller(it, "CONT") } }
            vm.deepSleep = false
        }
    }

    private fun pumpOutput(vm: RunningVm) {
        runCatching {
            vm.process.inputStream.bufferedReader().forEachLine { appendLog(vm, it) }
        }
    }

    private fun appendLog(vm: RunningVm, line: String) {
        synchronized(vm.logLines) {
            vm.logLines.addLast(line)
            while (vm.logLines.size > 300) vm.logLines.removeFirst()
        }
    }

    private fun waitForExit(vm: RunningVm) {
        val code = runCatching { vm.process.waitFor() }.getOrDefault(-1)
        appendLog(vm, "Limosa: QEMU exited with code $code")
        vm.state = State.STOPPED
        runCatching { vm.qmp?.close() }
        synchronized(this) {
            if (running[vm.config.name] === vm) running.remove(vm.config.name)
            lastExit[vm.config.name] = vm
        }
        store.flush()
        changed()
    }

    private val lastExit = HashMap<String, RunningVm>()

    /** Log of the current or most recent run of a VM. */
    @Synchronized
    fun log(name: String): List<String> = (running[name] ?: lastExit[name])?.log() ?: emptyList()

    override fun close() {
        for (vm in runningVms()) {
            wake(vm)
            runCatching { vm.qmp?.quit() }
            if (!runCatching { vm.process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) vm.process.destroyForcibly()
        }
    }

    companion object {
        const val MAX_VMS = 8
        const val QMP_BASE_PORT = 44440
        const val VNC_DISPLAY_BASE = 10 // TCP 5910+
        const val VNC_WS_BASE_PORT = 45700

        fun pid(p: Process): Long? {
            // Process.pid() exists on Java 9+ and Android API 33+; older Android has a private field.
            runCatching { return Process::class.java.getMethod("pid").invoke(p) as Long }
            return runCatching {
                val f = p.javaClass.getDeclaredField("pid")
                f.isAccessible = true
                (f.get(p) as Number).toLong()
            }.getOrNull()
        }

        fun defaultSignal(pid: Long, signal: String) {
            val p = ProcessBuilder("kill", "-$signal", pid.toString()).redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) throw IOException("kill -$signal $pid failed")
        }
    }
}
