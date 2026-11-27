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
    const val UPGRADE_LEVEL = 5

    /** Background services hidden from KDE's autostart (no-ops or error spam inside proot). */
    private val DISABLED_AUTOSTART = listOf(
        "org.kde.discover.notifier", "org.kde.kalendarac", "org.kde.plasma-welcome", "kaccess",
        "xembedsniproxy", "gmenudbusmenuproxy", "polkit-kde-authentication-agent-1", "geoclue-demo-agent",
        "baloo_file", "org_kde_powerdevil", "powerdevil", "pipewire", "pipewire-pulse", "wireplumber",
        "at-spi-dbus-bus", "im-launch", "xdg-user-dirs", "xdg-user-dirs-kde", "spice-vdagent",
        "org.kde.kunifiedpush-distributor", "kde-systemd-start-condition", "org.kde.kdeconnect.daemon",
        "user-dirs-update-gtk", "org.freedesktop.problems.applet", "kup-daemon", "org.kde.korgac",
    )

    /**
     * Finishes package installs that were cut off (Android killed Linux, the phone turned off…).
     * Until this runs, dpkg refuses every install, Discover's included.
     */
    val REPAIR = """
        |if [ -n "${'$'}(dpkg --audit 2>/dev/null)" ] || ! apt-get check >/dev/null 2>&1; then
        |  echo '>>> Repairing packages from an install that was cut off'
        |  dpkg --configure -a --force-confdef --force-confold
        |  # --no-remove: never let apt "fix" things by removing KDE or its apps.
        |  apt-get -y -f --no-remove install -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold ||
        |    echo '!!! Some packages could not be repaired without removing others; nothing was removed'
        |fi
        |""".trimMargin()

    /** Cheap check (no proot) for what [REPAIR] fixes. */
    fun dpkgNeedsRepair(rootfs: File): Boolean {
        if (File(rootfs, "var/lib/dpkg/updates").list()?.isNotEmpty() == true) return true
        val status = runCatching { File(rootfs, "var/lib/dpkg/status").readText() }.getOrNull() ?: return false
        return Regex("^Status: \\S+ \\S+ (half-installed|unpacked|half-configured|triggers-awaited|triggers-pending)$", RegexOption.MULTILINE)
            .containsMatchIn(status)
    }

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
        if (fromLevel < 4) {
            // Sound (PulseAudio + the KDE volume control) and what Discover needs to work:
            // polkit (permissions), PackageKit, and AppStream data (the app catalogue).
            steps += """
                |echo '>>> Adding sound and app store support'
                |apt-get update || true
                |for p in pulseaudio pulseaudio-utils plasma-pa polkitd packagekit appstream plasma-discover; do
                |  dpkg -s "${'$'}p" >/dev/null 2>&1 && continue
                |  echo ">>> Installing ${'$'}p"
                |  apt-get install -y --no-remove --no-install-recommends "${'$'}p" || echo "(${'$'}p is not available; skipping)"
                |done
                |echo '>>> Downloading the app catalogue for Discover'
                |apt-get update || true
                |appstreamcli refresh-cache --force >/dev/null 2>&1 || true
                |""".trimMargin()
        }
        if (fromLevel < 5) {
            // Put back KDE's apps if an earlier repair or install removed any.
            steps += """
                |echo '>>> Checking that KDE and its apps are all installed'
                |apt-get update || true
                |for p in kde-plasma-desktop plasma-workspace plasma-desktop kwin-x11 dolphin konsole systemsettings \
                |         plasma-discover firefox-esr kate ark gwenview okular plasma-pa kglobalacceld; do
                |  dpkg -s "${'$'}p" 2>/dev/null | grep -q '^Status: install ok installed' && continue
                |  echo ">>> Installing ${'$'}p again"
                |  apt-get install -y --no-remove --no-install-recommends "${'$'}p" || echo "(${'$'}p is not available; skipping)"
                |done
                |""".trimMargin()
        }
        if (steps.isEmpty()) return null
        return "export DEBIAN_FRONTEND=noninteractive\n" + REPAIR + "\n" + steps.joinToString("\n") + "\nexit 0\n"
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
        writeSystemConfig(rootfs)
        writeSound(rootfs, home)
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
            |# Sound: PulseAudio plays into a virtual sink that KDE Home streams to Android.
            |if command -v pulseaudio >/dev/null; then
            |  pulseaudio --kill 2>/dev/null
            |  pulseaudio --daemonize=no --exit-idle-time=-1 --disallow-exit -n -F /etc/kdehome/pulse.pa \
            |    --disable-shm=true --realtime=false --high-priority=false --log-target=stderr --log-level=error &
            |fi
            |# KDE's list of installed apps (ksycoca): menu, favourites, Android shortcuts. Rebuilt so
            |# apps installed or removed while KDE wasn't running show up, and a broken one is replaced.
            |if [ -f /etc/xdg/menus/plasma-applications.menu ]; then export XDG_MENU_PREFIX=plasma-
            |elif [ -f /etc/xdg/menus/kf5-applications.menu ]; then export XDG_MENU_PREFIX=kf5-; fi
            |export XDG_DATA_DIRS=/usr/local/share:/usr/share XDG_CONFIG_DIRS=/etc/xdg
            |if [ ! -f ~/.cache/kdehome-sycoca-v1 ]; then rm -f ~/.cache/ksycoca*; mkdir -p ~/.cache; touch ~/.cache/kdehome-sycoca-v1; fi
            |for k in kbuildsycoca6 kbuildsycoca5; do
            |  command -v "${'$'}k" >/dev/null && { "${'$'}k" >/dev/null 2>&1; break; }
            |done
            |exec startplasma-x11
            |""".trimMargin() + "\n"
        )
        Os.chmod(f.absolutePath, 493)
    }

    /**
     * The system D-Bus and polkit, without the checks proot can't satisfy. Every Linux program here
     * runs as the same Android user, so D-Bus sees one user ID for all of them, and there is no
     * login session for polkit to check. KDE Home is a single-user phone, so allow everything.
     */
    private fun writeSystemConfig(rootfs: File) {
        // KDE Home's own system bus config. Debian's switches to the "messagebus" user, which
        // fails under proot, and then nothing (Discover, devices, power) has a system bus.
        val allowAll = """
            |    <allow user="*"/>
            |    <allow own="*"/>
            |    <allow send_destination="*" eavesdrop="true"/>
            |    <allow receive_sender="*"/>
            |""".trimMargin()
        writeIfChanged(
            File(rootfs, "etc/kdehome/system-bus.conf"),
            """
            |<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
            | "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            |<!-- Written by KDE Home: the system bus for a single-user phone under proot. -->
            |<busconfig>
            |  <type>system</type>
            |  <listen>unix:path=/run/dbus/system_bus_socket</listen>
            |  <auth>EXTERNAL</auth>
            |  <auth>ANONYMOUS</auth>
            |  <allow_anonymous/>
            |  <standard_system_servicedirs/>
            |  <policy context="default">
            |$allowAll
            |  </policy>
            |  <includedir>/usr/share/dbus-1/system.d</includedir>
            |  <includedir>/etc/dbus-1/system.d</includedir>
            |  <policy context="mandatory">
            |$allowAll
            |  </policy>
            |</busconfig>
            |""".trimMargin(),
        )
        writeIfChanged(
            File(rootfs, "etc/dbus-1/system-local.conf"),
            """
            |<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
            | "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            |<!-- Written by KDE Home: everything on this bus is the phone's one user. -->
            |<busconfig>
            |  <auth>EXTERNAL</auth>
            |  <auth>ANONYMOUS</auth>
            |  <allow_anonymous/>
            |  <policy context="mandatory">
            |    <allow user="*"/>
            |    <allow own="*"/>
            |    <allow send_destination="*"/>
            |    <allow receive_sender="*"/>
            |  </policy>
            |</busconfig>
            |""".trimMargin(),
        )
        writeIfChanged(
            File(rootfs, "etc/polkit-1/rules.d/49-kdehome.rules"),
            "// Written by KDE Home: no password prompts (there is no login session under proot).\n" +
                "polkit.addRule(function(action, subject) { return polkit.Result.YES; });\n",
        )
    }

    private fun writeSound(rootfs: File, home: File) {
        writeIfChanged(
            File(rootfs, "etc/kdehome/pulse.pa"),
            """
            |# Written by KDE Home. Everything Linux plays goes to the "Android" sink; its monitor is
            |# streamed as raw 48 kHz stereo to 127.0.0.1:$AUDIO_PORT, where the app plays it.
            |.fail
            |load-module module-null-sink sink_name=android rate=48000 sink_properties=device.description=Android
            |set-default-sink android
            |load-module module-native-protocol-unix auth-anonymous=1
            |load-module module-simple-protocol-tcp source=android.monitor record=true playback=false port=$AUDIO_PORT listen=127.0.0.1 rate=48000 format=s16le channels=2
            |.nofail
            |# Stop streaming (and save battery) when nothing has played for a moment.
            |load-module module-suspend-on-idle timeout=2
            |load-module module-stream-restore
            |load-module module-default-device-restore
            |""".trimMargin(),
        )
        writeIfChanged(
            File(home, ".config/pulse/client.conf"),
            "# Written by KDE Home: use the server it starts.\nautospawn = no\ndefault-server = unix:/tmp/runtime-user/pulse/native\n",
        )
    }

    private fun writeIfChanged(f: File, text: String) {
        if (f.exists() && runCatching { f.readText() }.getOrNull() == text) return
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    const val AUDIO_PORT = 4712

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
