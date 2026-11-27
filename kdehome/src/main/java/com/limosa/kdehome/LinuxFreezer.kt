package com.limosa.kdehome

import android.os.Process
import java.io.File

/**
 * Resource saving: pauses every Linux program while KDE isn't on screen, so they use no CPU or
 * battery, and Android's limit on busy background processes doesn't kill them.
 *
 * It stops the programs inside proot (as `kill -STOP` would), not proot itself, so proot passes
 * the stop and continue along like it does for job control in a Linux shell.
 */
object LinuxFreezer {
    private const val SIGCONT = 18
    private const val SIGSTOP = 19

    @Volatile var frozen = false
        private set

    @Synchronized
    fun freeze() {
        if (frozen) return
        val pids = linuxPids()
        if (pids.isEmpty()) return
        for (pid in pids) Process.sendSignal(pid, SIGSTOP)
        frozen = true
    }

    @Synchronized
    fun thaw() {
        if (!frozen) return
        frozen = false
        // Twice, a moment apart: proot may still be passing the stop along when the first one lands.
        repeat(2) {
            for (pid in linuxPids()) Process.sendSignal(pid, SIGCONT)
            Thread.sleep(150)
        }
    }

    /** Our other processes (same Android user ID), except proot itself. */
    private fun linuxPids(): List<Int> {
        val me = Process.myPid()
        val uid = Process.myUid()
        val out = ArrayList<Int>()
        val entries = File("/proc").list() ?: return out
        for (name in entries) {
            val pid = name.toIntOrNull() ?: continue
            if (pid == me) continue
            val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull() ?: continue
            val uidLine = status.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: continue
            if (uidLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() != uid) continue
            val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrDefault("")
            if (cmdline.contains("libproot")) continue
            out += pid
        }
        return out
    }
}
