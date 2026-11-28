package com.limosa.kdehome

import android.system.Os
import org.json.JSONArray
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.zip.GZIPInputStream

/**
 * Installs Debian and KDE Plasma:
 * 1. downloads a Debian root filesystem (the one proot-distro publishes) to Internal storage/Linux/installer,
 * 2. unpacks it into the app's internal storage,
 * 3. runs apt inside proot to install KDE Plasma, TigerVNC and friends,
 * 4. creates the desktop user and the Android bridge.
 */
class RootfsInstaller(
    private val paths: LinuxPaths,
    private val proot: Proot,
    private val log: (String) -> Unit,
    private val progress: (String, Int) -> Unit,
) {
    class Options(
        /** Blank: pick the newest Debian rootfs from proot-distro's GitHub releases. */
        val rootfsUrl: String = "",
        val extraPackages: String = DEFAULT_EXTRAS,
        val withRecommends: Boolean = false,
        /** A backup made by KDE Home (Internal storage/Linux/debian-backup.tar.gz): restore it instead of installing. */
        val restoreFrom: String = "",
    )

    @Volatile var cancelled = false

    fun install(opts: Options) {
        if (!paths.hasProot) {
            throw IOException("This APK was built without proot, so Linux can't run. Build it with scripts/fetch_proot_termux.py (CI does this).")
        }
        paths.base.mkdirs()
        if (opts.restoreFrom.isNotEmpty()) {
            val backup = File(opts.restoreFrom)
            if (!backup.isFile) throw IOException("No backup at ${backup.absolutePath}")
            log("Restoring Debian from ${backup.absolutePath}")
            extract(backup)
            paths.extractedMarker.writeText("restored from ${backup.absolutePath}")
        } else if (!paths.extractedMarker.exists() || !File(paths.rootfs, "usr/bin/apt-get").exists()) {
            val url = opts.rootfsUrl.trim().ifEmpty { findRootfsUrl() }
            val archive = download(url)
            extract(archive)
            paths.extractedMarker.writeText(url)
        } else {
            log("Debian is already unpacked, continuing with the packages.")
        }
        configureRootfs()
        // A restored backup already has KDE: no need to download it again.
        if (opts.restoreFrom.isEmpty() || !File(paths.rootfs, "usr/bin/startplasma-x11").exists()) runApt(opts)
        configureUser()
        File(paths.base, "last-system-update").writeText(System.currentTimeMillis().toString())
        paths.installedMarker.writeText(System.currentTimeMillis().toString())
        progress("Debian and KDE Plasma are installed", 100)
    }

    // ---- 1. Find and download the root filesystem -------------------------------------------

    private fun findRootfsUrl(): String {
        val arch = paths.rootfsArch()
        progress("Looking for the newest Debian for $arch…", -1)
        val json = httpText("https://api.github.com/repos/termux/proot-distro/releases?per_page=30")
        val name = Regex("^debian-(?:([a-z]+)-)?${Regex.escape(arch)}-pd-v[0-9.]+\\.tar\\.xz$")
        var fallback: String? = null
        val releases = JSONArray(json)
        for (i in 0 until releases.length()) {
            val assets = releases.getJSONObject(i).optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val m = name.find(asset.optString("name")) ?: continue
                val url = asset.optString("browser_download_url")
                if (url.isEmpty()) continue
                // Prefer Debian 12 "bookworm": its KDE Plasma 5.27 is known to work well under proot.
                if (m.groupValues[1] == PREFERRED_CODENAME) {
                    log("Using ${asset.optString("name")}")
                    return url
                }
                if (fallback == null) fallback = url
            }
        }
        fallback?.let { log("Using ${it.substringAfterLast('/')}"); return it }
        throw IOException("No Debian root filesystem for $arch was found in proot-distro's releases. Enter a rootfs URL instead.")
    }

    private fun httpText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "kdehome-installer")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} from $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String): File {
        val fileName = url.substringAfterLast('/').substringBefore('?').ifEmpty { "debian-rootfs.tar.xz" }
        val dest = File(paths.installerDir(), fileName)
        if (dest.exists() && dest.length() > 0) {
            log("Reusing downloaded ${dest.absolutePath}")
            return dest
        }
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            try {
                downloadOnce(url, part)
                break
            } catch (e: IOException) {
                if (cancelled || ++attempt >= 5) throw e
                log("Download interrupted (${e.message}), retrying…")
                Thread.sleep(2000L * attempt)
            }
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return dest
    }

    private fun downloadOnce(url: String, part: File) {
        val have = if (part.exists()) part.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "kdehome-installer")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        try {
            val append = when (c.responseCode) {
                206 -> true
                200 -> false
                416 -> return   // already complete
                else -> throw IOException("HTTP ${c.responseCode} downloading $url")
            }
            val total = (if (append) have else 0L) + c.contentLengthLong.coerceAtLeast(0)
            var done = if (append) have else 0L
            var lastPct = -1
            c.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            progress("Downloading Debian: ${done shr 20} / ${total shr 20} MB", pct)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("connection closed early")
        } finally {
            c.disconnect()
        }
    }

    // ---- 2. Unpack ------------------------------------------------------------------------

    private fun extract(archive: File) {
        progress("Unpacking Debian into internal storage…", -1)
        paths.deleteTree(paths.rootfs)
        paths.rootfs.mkdirs()
        val total = archive.length()
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 20))
        val stream: InputStream = when {
            archive.name.endsWith(".xz") -> XZInputStream(counting)
            archive.name.endsWith(".gz") || archive.name.endsWith(".tgz") -> GZIPInputStream(counting, 1 shl 16)
            else -> counting
        }
        val root = paths.rootfs.absolutePath
        val dirModes = ArrayList<Pair<String, Int>>()
        var strip: String? = null
        var first = true
        var count = 0
        var lastPct = -1
        stream.use { s ->
            val tar = TarReader(s)
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e = tar.next() ?: break
                var name = clean(e.name) ?: continue
                if (first) {
                    first = false
                    // proot-distro tarballs keep everything in one top-level folder.
                    val top = name.substringBefore('/')
                    if (top !in ROOTFS_TOP_LEVEL) strip = top
                }
                strip?.let { if (name == it) name = "" else if (name.startsWith("$it/")) name = name.substring(it.length + 1) }
                if (name.isEmpty()) continue
                val path = "$root/$name"
                File(path).parentFile?.mkdirs()
                when {
                    e.isDirectory -> {
                        File(path).mkdirs()
                        dirModes += path to e.mode
                    }
                    e.isSymlink -> {
                        File(path).let { if (it.exists() || Files.isSymbolicLink(it.toPath())) Files.deleteIfExists(it.toPath()) }
                        Os.symlink(e.linkName, path)
                    }
                    e.isHardLink -> {
                        var target = clean(e.linkName) ?: continue
                        strip?.let { if (target.startsWith("$it/")) target = target.substring(it.length + 1) }
                        // Android does not allow hard links in app storage: copy instead.
                        Files.copy(File("$root/$target").toPath(), File(path).toPath(), StandardCopyOption.REPLACE_EXISTING)
                        Os.chmod(path, e.mode or 384)
                    }
                    e.isFile -> {
                        val f = File(path)
                        if (Files.isSymbolicLink(f.toPath())) f.delete()
                        FileOutputStream(f).use { tar.copyTo(it) }
                        Os.chmod(path, e.mode or 384 /* u+rw */)
                    }
                    else -> Unit  // device nodes, FIFOs: proot binds the host's /dev
                }
                if (++count % 500 == 0) {
                    val pct = if (total > 0) (counting.count * 100 / total).toInt() else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        progress("Unpacking Debian: $count files", pct)
                    }
                }
            }
        }
        // Directories last: a read-only directory would block writing its files.
        for ((path, mode) in dirModes) runCatching { Os.chmod(path, mode or 448 /* u+rwx */) }
        log("Unpacked $count files into ${paths.rootfs.absolutePath}")
    }

    private fun clean(name: String): String? {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        @Volatile var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }

    // ---- 3. Configure and install KDE ------------------------------------------------------

    private fun configureRootfs() {
        val r = paths.rootfs
        fun write(rel: String, text: String, mode: Int = 420 /* 0644 */) {
            val f = File(r, rel)
            f.parentFile?.mkdirs()
            Files.deleteIfExists(f.toPath())   // may be a dangling symlink, e.g. /etc/resolv.conf
            f.writeText(text)
            Os.chmod(f.absolutePath, mode)
        }
        write("etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write("etc/hosts", "127.0.0.1 localhost kdehome\n::1 localhost ip6-localhost ip6-loopback\n")
        write("etc/hostname", "kdehome\n")
        // apt drops privileges to _apt, which proot's fake root can't do.
        write("etc/apt/apt.conf.d/01-kdehome", "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDpkg::Use-Pty \"0\";\n")
        // There is no init system: don't let packages try to start services.
        write("usr/sbin/policy-rc.d", "#!/bin/sh\nexit 101\n", 493 /* 0755 */)
        File(r, "tmp").mkdirs()
        for (d in listOf("dev", "proc", "sys", "run", "sdcard")) File(r, d).mkdirs()   // not in backups
        Os.chmod(File(r, "tmp").absolutePath, 1023 /* 01777 */)
        writeBridge(r)
    }

    private fun writeBridge(r: File) {
        val tokenFile = File(r, "etc/kdehome/bridge-token")
        if (!tokenFile.exists()) {
            tokenFile.parentFile?.mkdirs()
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            tokenFile.writeText(bytes.joinToString("") { "%02x".format(it) })
        }
        val bin = File(r, "usr/local/bin").apply { mkdirs() }
        fun script(name: String, text: String) {
            val f = File(bin, name)
            f.writeText(text)
            Os.chmod(f.absolutePath, 493)
        }
        script("android-bridge", """
            |#!/bin/bash
            |# Talks to the KDE Home Android app. Usage: android-bridge <launch|open|home> [argument]
            |port=${AndroidBridge.PORT}
            |token=${'$'}(cat /etc/kdehome/bridge-token 2>/dev/null)
            |exec 3<>/dev/tcp/127.0.0.1/${'$'}port 2>/dev/null || { echo "KDE Home (the Android app) is not running" >&2; exit 1; }
            |printf '%s %s %s\n' "${'$'}token" "${'$'}1" "${'$'}2" >&3
            |read -r -t 10 reply <&3
            |echo "${'$'}reply"
            |[ "${'$'}{reply%% *}" = OK ]
            |""".trimMargin())
        script("android-launch", "#!/bin/bash\n# Opens an Android app: android-launch <package> or <package/activity>\nexec android-bridge launch \"$1\"\n")
        script("android-open", "#!/bin/bash\n# Opens a URL or file on Android: android-open <url>\nexec android-bridge open \"$1\"\n")
        script("android-home", "#!/bin/bash\nexec android-bridge home\n")
    }

    private fun runApt(opts: Options) {
        val extras = opts.extraPackages.split(Regex("[\\s,]+")).filter { it.matches(Regex("[a-z0-9][a-z0-9+.-]*")) }
        val rec = if (opts.withRecommends) "" else "--no-install-recommends"
        val script = """
            |set -e
            |export DEBIAN_FRONTEND=noninteractive
            |APT="apt-get -y $rec -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
            |echo '>>> Updating package lists'
            |apt-get update
            |echo '>>> Installing KDE Plasma and the VNC display server (about 1-2 GB, this takes a while)'
            |if ! ${'$'}APT install $CORE_PACKAGES; then
            |  echo '>>> Retrying after repairing dpkg'
            |  dpkg --configure -a || true
            |  apt-get -y -f install || true
            |  ${'$'}APT install $CORE_PACKAGES
            |fi
            |for p in ${extras.joinToString(" ")}; do
            |  echo ">>> Optional package: ${'$'}p"
            |  ${'$'}APT install "${'$'}p" || echo "!!! ${'$'}p could not be installed, skipping"
            |done
            |dpkg --configure -a || true
            |if ! id user >/dev/null 2>&1; then
            |  useradd -m -o -u 1000 -s /bin/bash -c 'KDE Home user' user
            |fi
            |${SessionTuning.MAKE_ADMIN}
            |mkdir -p /home/user/Linux /home/user/Desktop /home/user/AndroidApps
            |echo '>>> Updating everything to the newest versions'
            |apt-get -y --with-new-pkgs -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade || true
            |apt-get clean
            |echo '>>> Packages installed'
            |""".trimMargin()
        progress("Installing KDE Plasma (this can take 20-60 minutes)…", -1)
        val p = proot.process(script, asUser = false).start()
        val logFile = File(paths.base, "install.log")
        logFile.appendText("\n==== apt ${java.util.Date()} ====\n")
        var lines = 0
        p.inputStream.bufferedReader().useLines { seq ->
            for (line in seq) {
                if (cancelled) { p.destroy(); throw IOException("cancelled") }
                log(line)
                logFile.appendText(line + "\n")
                lines++
                when {
                    line.startsWith(">>> ") -> progress(line.removePrefix(">>> "), -1)
                    line.startsWith("Get:") -> progress("Downloading packages… ($lines)", -1)
                    line.startsWith("Unpacking ") -> progress("Unpacking ${line.removePrefix("Unpacking ").substringBefore(' ')}", -1)
                    line.startsWith("Setting up ") -> progress("Setting up ${line.removePrefix("Setting up ").substringBefore(' ')}", -1)
                }
            }
        }
        val code = p.waitFor()
        paths.publishLog(logFile)
        if (code != 0) throw IOException("Package installation failed (exit $code). See the log; you can tap Install again to retry.")
    }

    // ---- 4. Desktop user --------------------------------------------------------------------

    private fun configureUser() {
        val home = paths.userHome
        val cfg = File(home, ".config").apply { mkdirs() }
        fun write(f: File, text: String) { f.parentFile?.mkdirs(); f.writeText(text) }
        // No screen locker (Android has its own), no file indexer, no compositor (no GPU in proot).
        write(File(cfg, "kscreenlockerrc"), "[Daemon]\nAutolock=false\nLockOnResume=false\n")
        write(File(cfg, "baloofilerc"), "[Basic Settings]\nIndexing-Enabled=false\n")
        write(File(cfg, "kwinrc"), "[Compositing]\nEnabled=false\n")
        write(File(cfg, "startkderc"), "[General]\nsystemdBoot=false\n")
        write(File(cfg, "powermanagementprofilesrc"), "[AC][DPMSControl]\nidleTime=0\n")
        // Android storage on the KDE desktop.
        val desktop = File(home, "Desktop").apply { mkdirs() }
        fun link(name: String, target: String) {
            val l = File(desktop, name)
            if (!Files.isSymbolicLink(l.toPath()) && !l.exists()) runCatching { Os.symlink(target, l.absolutePath) }
        }
        link("Linux folder", "/home/user/Linux")
        link("Android storage", "/sdcard")
        link("Android Apps", "/home/user/AndroidApps")
    }

    companion object {
        const val PREFERRED_CODENAME = "bookworm"

        /** Where Back up Debian puts the whole system. */
        fun backupFile(paths: LinuxPaths) = File(paths.linuxFolder, "debian-backup.tar.gz")

        /** Must exist in every Debian release that ships KDE Plasma. */
        const val CORE_PACKAGES = "kde-plasma-desktop plasma-workspace kwin-x11 konsole dolphin " +
            "breeze breeze-icon-theme tigervnc-standalone-server dbus-x11 x11-xserver-utils xfonts-base " +
            "libgl1-mesa-dri xdg-utils"

        /** Installed one by one, so a missing name doesn't stop the install. */
        const val DEFAULT_EXTRAS = "systemsettings kate ark gwenview okular kde-spectacle plasma-systemmonitor " +
            "kinfocenter khelpcenter plasma-widgets-addons fonts-noto-core fonts-noto-color-emoji " +
            "tigervnc-tools kglobalacceld locales sudo fakeroot nano firefox-esr plasma-discover packagekit polkitd appstream dbus-daemon " +
            "pulseaudio pulseaudio-utils plasma-pa"

        private val ROOTFS_TOP_LEVEL = setOf(
            "bin", "boot", "dev", "etc", "home", "lib", "lib32", "lib64", "libx32", "media", "mnt",
            "opt", "proc", "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var",
        )
    }
}
