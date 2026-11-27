package com.limosa.kdehome

import android.system.Os
import java.io.File

/**
 * Keeps KDE light enough for a phone, and updates an existing Linux install in place, so new
 * versions of the app never need Debian to be reinstalled.
 *
 * Android 12+ kills apps whose child processes are too many or too busy (the "phantom process"
 * limit), and every Linux process under proot counts. So KDE's background services that do
 * nothing on a phone are switched off, and so are animations and session restore.
 */
object SessionTuning {

    /** Bump when [upgradeScript] gets a new step. Existing installs run the new steps once. */
    const val UPGRADE_LEVEL = 3

    /** Background services hidden from KDE's autostart (no-ops or error spam inside proot). */
    private val DISABLED_AUTOSTART = listOf(
        "org.kde.discover.notifier", "org.kde.kalendarac", "org.kde.plasma-welcome", "kaccess",
        "xembedsniproxy", "gmenudbusmenuproxy", "polkit-kde-authentication-agent-1", "geoclue-demo-agent",
        "baloo_file", "org_kde_powerdevil", "powerdevil", "pipewire", "pipewire-pulse", "wireplumber",
        "at-spi-dbus-bus", "im-launch", "xdg-user-dirs", "xdg-user-dirs-kde", "spice-vdagent",
        "org.kde.kunifiedpush-distributor", "kde-systemd-start-condition", "org.kde.kdeconnect.daemon",
        "user-dirs-update-gtk", "org.freedesktop.problems.applet", "kup-daemon", "org.kde.korgac",
    )

    /** Runs as fake root before the desktop starts, only for steps newer than the install. */
    fun upgradeScript(fromLevel: Int): String? {
        val steps = mutableListOf<String>()
        if (fromLevel < 2) {
            // Global shortcuts (the ❖ key opens the menu): Plasma 6 has kglobalacceld separately.
            steps += """
                |echo '>>> Installing the KDE global shortcut service'
                |apt-get install -y --no-install-recommends kglobalacceld 2>/dev/null ||
                |  { apt-get update && apt-get install -y --no-install-recommends kglobalacceld; } ||
                |  echo '(kglobalacceld is not in this Debian release; skipping)'
                |""".trimMargin()
        }
        if (fromLevel < 3) {
            // The main apps, one by one so a missing one doesn't stop the rest. Discover needs
            // PackageKit (it talks to it over the system D-Bus, which KDE Home now starts).
            steps += """
                |updated=
                |for p in dolphin firefox-esr plasma-discover packagekit dbus-daemon konsole systemsettings; do
                |  dpkg -s "${'$'}p" >/dev/null 2>&1 && continue
                |  echo ">>> Installing ${'$'}p"
                |  if ! apt-get install -y --no-install-recommends "${'$'}p"; then
                |    [ -z "${'$'}updated" ] && { apt-get update; updated=1; }
                |    apt-get install -y --no-install-recommends "${'$'}p" || echo "(${'$'}p is not available; skipping)"
                |  fi
                |done
                |""".trimMargin()
        }
        if (steps.isEmpty()) return null
        return "export DEBIAN_FRONTEND=noninteractive\n" + steps.joinToString("\n") + "\nexit 0\n"
    }

    /** Rewrites KDE settings that keep it fast. Runs before every start; keeps other settings. */
    fun apply(rootfs: File, home: File) {
        val cfg = File(home, ".config").apply { mkdirs() }
        // No animations: the biggest speed-up when everything is drawn in software.
        setIni(File(cfg, "kdeglobals"), "KDE", "AnimationDurationFactor", "0")
        setIni(File(cfg, "kdeglobals"), "KDE", "GraphicEffectsLevel", "0")
        setIni(File(cfg, "kwinrc"), "Compositing", "Enabled", "false")
        setIni(File(cfg, "kwinrc"), "Compositing", "OpenGLIsUnsafe", "true")
        // Don't reopen yesterday's windows (each one is more processes and slower start-up).
        setIni(File(cfg, "ksmserverrc"), "General", "loginMode", "emptySession")
        setIni(File(cfg, "ksplashrc"), "KSplash", "Engine", "none")
        setIni(File(cfg, "ksplashrc"), "KSplash", "Theme", "None")
        setIni(File(cfg, "kscreenlockerrc"), "Daemon", "Autolock", "false")
        setIni(File(cfg, "kscreenlockerrc"), "Daemon", "LockOnResume", "false")
        setIni(File(cfg, "baloofilerc"), "Basic Settings", "Indexing-Enabled", "false")
        setIni(File(cfg, "startkderc"), "General", "systemdBoot", "false")
        setIni(File(cfg, "kwalletrc"), "Wallet", "Enabled", "false")
        setIni(File(cfg, "plasma-welcomerc"), "General", "LastSeenVersion", "99.0")
        setIni(File(cfg, "dolphinrc"), "General", "RememberOpenedTabs", "false")

        val autostart = File(cfg, "autostart").apply { mkdirs() }
        val system = File(rootfs, "etc/xdg/autostart")
        for (name in DISABLED_AUTOSTART) {
            if (!File(system, "$name.desktop").exists()) continue
            val f = File(autostart, "$name.desktop")
            val text = "[Desktop Entry]\nHidden=true\n"
            if (!f.exists() || f.readText() != text) f.writeText(text)
        }

        pinApps(rootfs, home)
        writeSessionScript(rootfs)
    }

    /** Main KDE apps on the desktop, once each (if you delete one, it stays deleted). */
    private fun pinApps(rootfs: File, home: File) {
        val desktop = File(home, "Desktop").apply { mkdirs() }
        val record = File(home, ".config/kdehome-pinned")
        val done = if (record.exists()) record.readLines().toMutableSet() else mutableSetOf()
        var changed = false
        for (name in listOf("org.kde.dolphin", "firefox-esr", "org.kde.discover", "org.kde.konsole", "systemsettings")) {
            if (name in done) continue
            val src = File(rootfs, "usr/share/applications/$name.desktop")
            if (!src.exists()) continue
            val dst = File(desktop, "$name.desktop")
            if (!dst.exists()) {
                src.copyTo(dst)
                Os.chmod(dst.absolutePath, 493)   // KDE only runs desktop shortcuts that are executable
            }
            done += name
            changed = true
        }
        if (changed) record.writeText(done.joinToString("\n") + "\n")
    }

    /** Runs inside dbus-launch: starts what systemd would normally start, then Plasma. */
    private fun writeSessionScript(rootfs: File) {
        val f = File(rootfs, "usr/local/bin/kdehome-session")
        f.parentFile?.mkdirs()
        f.writeText(
            """
            |#!/bin/bash
            |# Started by KDE Home inside the D-Bus session.
            |for k in /usr/lib/*/libexec/kglobalacceld /usr/libexec/kglobalacceld /usr/lib/*/libexec/kf6/kglobalacceld; do
            |  if [ -x "${'$'}k" ]; then "${'$'}k" & break; fi
            |done
            |exec startplasma-x11
            |""".trimMargin() + "\n"
        )
        Os.chmod(f.absolutePath, 493)
    }

    /** Sets key=value in [group] of a KDE ini file, keeping everything else in it. */
    fun setIni(file: File, group: String, key: String, value: String) {
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        val header = "[$group]"
        var start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += header
            lines += "$key=$value"
        } else {
            var end = start + 1
            while (end < lines.size && !lines[end].trim().startsWith("[")) end++
            val idx = (start + 1 until end).firstOrNull { lines[it].substringBefore('=').trim() == key }
            if (idx != null) {
                if (lines[idx] == "$key=$value") return
                lines[idx] = "$key=$value"
            } else {
                // Insert after the group's last non-blank line.
                var at = end
                while (at > start + 1 && lines[at - 1].isBlank()) at--
                lines.add(at, "$key=$value")
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n") + "\n")
    }
}
