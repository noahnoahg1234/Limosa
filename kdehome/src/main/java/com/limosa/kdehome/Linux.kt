package com.limosa.kdehome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Where everything lives.
 *
 * - The Debian root filesystem is in the app's internal storage (`/data/data/<app>/files/linux/rootfs`).
 *   It cannot live on shared storage: `/storage/emulated/0` is a FUSE file system without
 *   symlinks, Unix permissions or executable files, and Debian needs all three.
 * - `/storage/emulated/0/Linux` ("Internal storage › Linux" in the Files app) is the Linux folder you
 *   can see from Android. It holds the installer download and logs, and inside Linux it is
 *   `/home/user/Linux` (and the whole of internal storage is `/sdcard`).
 */
class LinuxPaths(val context: Context) {
    val base = File(context.filesDir, "linux")
    val rootfs = File(base, "rootfs")
    val prootTmp = File(base, "tmp")
    val fakeProc = File(base, "fake-proc")
    val installedMarker = File(base, "installed")
    val extractedMarker = File(base, "extracted")
    val sessionLog = File(base, "session.log")
    val userHome = File(rootfs, "home/user")

    val libDir = File(context.applicationInfo.nativeLibraryDir)
    val proot = File(libDir, "libproot.so")
    val loader = File(libDir, "libproot-loader.so")
    val loader32 = File(libDir, "libproot-loader32.so")

    @Suppress("DEPRECATION")
    val androidStorage: File get() = Environment.getExternalStorageDirectory()

    /** Internal storage/Linux. */
    val linuxFolder: File get() = File(androidStorage, "Linux")

    val isInstalled get() = installedMarker.exists()
    val hasProot get() = proot.exists() && loader.exists()

    fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Internal storage/Linux/installer when we may write there, else the app cache. */
    fun installerDir(): File {
        val dir = if (hasStorageAccess()) File(linuxFolder, "installer") else File(context.cacheDir, "installer")
        dir.mkdirs()
        return dir
    }

    /** Copies a log into Internal storage/Linux/logs so it can be read or shared from Android. */
    fun publishLog(log: File) {
        if (!hasStorageAccess() || !log.exists()) return
        runCatching {
            val dir = File(linuxFolder, "logs").apply { mkdirs() }
            log.copyTo(File(dir, log.name), overwrite = true)
        }
    }

    /** proot-distro architecture name of this phone. */
    fun rootfsArch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x86_64"
        "armeabi-v7a" -> "arm"
        "x86" -> "i686"
        else -> "aarch64"
    }

    /** Deletes a tree without following symlinks (rootfs links point at absolute host paths). */
    fun deleteTree(dir: File) {
        val root = dir.toPath()
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                runCatching { Files.deleteIfExists(file) }
                return FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                // We extracted these files ourselves, but tarballs may contain read-only directories.
                runCatching { android.system.Os.chmod(d.toString(), 448 /* 0700 */) }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, exc: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(d)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

/** Builds proot command lines that enter the Debian root filesystem. */
class Proot(private val paths: LinuxPaths) {

    /**
     * A process that runs [script] with bash inside Debian, as fake root or as `user` (uid 1000).
     * KDE apps refuse to run as root, so the desktop runs as `user`.
     */
    /**
     * [noSeccomp]: turn off proot's seccomp acceleration. It is faster, but on some phones it makes
     * proot abort (exit code 134) under a heavy load like a KDE session.
     */
    fun process(script: String, asUser: Boolean, noSeccomp: Boolean = false): ProcessBuilder {
        paths.prootTmp.mkdirs()
        val home = if (asUser) "/home/user" else "/root"
        val user = if (asUser) "user" else "root"
        val cmd = mutableListOf(
            paths.proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",   // Android forbids hard links; dpkg and apt need them
            "--sysvipc",        // shared memory for X11 / Qt
            "-L",               // correct lstat sizes of symlinks
            if (asUser) "--change-id=1000:1000" else "--root-id",
            "--rootfs=${paths.rootfs.absolutePath}",
            "--cwd=$home",
        )
        for (b in binds()) cmd += "--bind=$b"
        cmd += listOf(
            "/usr/bin/env", "-i",
            "HOME=$home", "USER=$user", "LOGNAME=$user", "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "/bin/bash", "-c", script,
        )
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_LOADER"] = paths.loader.absolutePath
        if (paths.loader32.exists()) env["PROOT_LOADER_32"] = paths.loader32.absolutePath
        env["PROOT_TMP_DIR"] = paths.prootTmp.absolutePath
        env["TMPDIR"] = paths.prootTmp.absolutePath
        env["LD_LIBRARY_PATH"] = paths.libDir.absolutePath   // libtalloc
        env.remove("LD_PRELOAD")
        if (noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        return pb
    }

    private fun binds(): List<String> {
        val b = mutableListOf(
            "/dev",
            "/proc",
            "/sys",
            "/dev/urandom:/dev/random",
            "${File(paths.rootfs, "tmp").absolutePath}:/dev/shm",
        )
        if (paths.hasStorageAccess()) {
            paths.linuxFolder.mkdirs()
            b += "${paths.androidStorage.absolutePath}:/sdcard"
            // Only once the user exists: an early bind would make useradd skip creating the home folder.
            if (File(paths.userHome, "Linux").isDirectory) b += "${paths.linuxFolder.absolutePath}:/home/user/Linux"
        }
        b += fakeProcBinds()
        return b
    }

    /** Android hides some /proc files from apps; Linux programs (ps, top, KDE's system monitor) want them. */
    private fun fakeProcBinds(): List<String> {
        val out = mutableListOf<String>()
        val cpus = Runtime.getRuntime().availableProcessors()
        val fakes = mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "uptime" to "7325.38 28490.12\n",
            "version" to "Linux version ${System.getProperty("os.version") ?: "6.1.0"} (limosa@kdehome) #1 SMP PREEMPT\n",
            "stat" to buildString {
                append("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")
                for (i in 0 until cpus) append("cpu$i 245 0 360 11660 33 43 32 11 0 0\n")
                append("intr 63361 0 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\n")
                append("procs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")
            },
            "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n" +
                "nr_zone_inactive_file 22858\nnr_zone_active_file 51328\nnr_mlock 0\npgpgin 198\npgpgout 0\n",
            "sys/kernel/cap_last_cap" to "40\n",
            "sys/fs/inotify/max_user_watches" to "65536\n",
        )
        for ((name, content) in fakes) {
            if (readable(File("/proc/$name"))) continue
            val f = File(paths.fakeProc, name)
            runCatching {
                f.parentFile?.mkdirs()
                if (!f.exists() || f.readText() != content) f.writeText(content)
                out += "${f.absolutePath}:/proc/$name"
            }
        }
        return out
    }

    private fun readable(f: File): Boolean = runCatching { FileInputStream(f).use { it.read() }; true }.getOrDefault(false)
}
