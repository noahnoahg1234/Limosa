package com.limosa.kdehome

import android.content.Context
import android.os.Process as AndroidProcess
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root mode: Debian runs from one ext4 image, Internal storage/Linux/debian.img, with chroot and
 * real root instead of proot. It needs a rooted phone (Magisk's `su`).
 *
 * The image is mounted where proot mode keeps Debian (files/linux/rootfs, with this app's SELinux
 * label), so the rest of KDE Home writes its settings and shortcuts there exactly as before. The
 * desktop user gets this app's Android user ID, so those files belong to both; system files belong
 * to real root, and sudo is the real sudo. Programs started through su are outside the app's
 * process group, so Android's phantom-process limit doesn't kill them.
 */
object RootMode {
    private const val PREF = "rootMode"

    fun enabled(context: Context) = context.getSharedPreferences("linux", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("linux", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
    }

    /** Asks su for root (Magisk shows its prompt the first time). */
    fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroy()
            return false
        }
        p.inputStream.bufferedReader().readText().trim().lines().lastOrNull()?.trim() == "0"
    }.getOrDefault(false)

    /** The image as Android's Files app sees it. */
    fun image(paths: LinuxPaths) = File(paths.linuxFolder, "debian.img")

    /** The same file under /data/media, which root mounts without going through the FUSE layer. */
    private fun imageBacking(paths: LinuxPaths): String {
        val user = AndroidProcess.myUid() / 100000
        return "/data/media/$user/Linux/debian.img"
    }

    fun protCopy(paths: LinuxPaths) = File(paths.base, "rootfs-proot")

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Mounts the image and the system folders inside it, if they aren't already. */
    fun mountScript(paths: LinuxPaths, binds: Boolean = true): String = mountScript(
        paths.rootfs.absolutePath, paths.base.absolutePath, imageBacking(paths),
        paths.androidStorage.absolutePath, paths.linuxFolder.absolutePath, binds,
    )

    /** The same with plain paths (also used by the tests). */
    fun mountScript(rootfs: String, base: String, image: String, storagePath: String, linuxPath: String, binds: Boolean): String {
        val mnt = q(rootfs)
        val storage = q(storagePath)
        val linux = q(linuxPath)
        return """
            |# The real path: /data/user/0 is a link to /data/data, and /proc/mounts shows the real one.
            |mkdir -p $mnt
            |MNT=${'$'}(readlink -f $mnt 2>/dev/null || echo $mnt)
            |mounted() { grep -q " ${'$'}1 " /proc/mounts; }
            |if ! mounted "${'$'}MNT"; then
            |  # Label every file like this app's own data, so KDE Home can still write its settings there.
            |  set -- ${'$'}(ls -Zd ${q(base)} 2>/dev/null); CTX=${'$'}1
            |  mount -t ext4 -o loop,noatime,context="${'$'}CTX" ${q(image)} "${'$'}MNT" 2>/dev/null ||
            |    mount -t ext4 -o loop,noatime ${q(image)} "${'$'}MNT" ||
            |    { echo "!!! Could not mount $image"; exit 90; }
            |fi
            |""".trimMargin() + if (!binds) "\n" else """
            |
            |for d in dev dev/pts proc sys; do
            |  mkdir -p "${'$'}MNT/${'$'}d"
            |  mounted "${'$'}MNT/${'$'}d" || mount -o bind "/${'$'}d" "${'$'}MNT/${'$'}d"
            |done
            |mkdir -p "${'$'}MNT/dev/shm"
            |mounted "${'$'}MNT/dev/shm" || mount -t tmpfs -o mode=1777 tmpfs "${'$'}MNT/dev/shm"
            |if [ -d $storage ]; then
            |  mkdir -p "${'$'}MNT/sdcard"
            |  mounted "${'$'}MNT/sdcard" || mount -o bind $storage "${'$'}MNT/sdcard"
            |  if [ -d "${'$'}MNT/home/user" ]; then
            |    mkdir -p $linux "${'$'}MNT/home/user/Linux"
            |    mounted "${'$'}MNT/home/user/Linux" || mount -o bind $linux "${'$'}MNT/home/user/Linux"
            |  fi
            |fi
            |# Android's DNS isn't in /etc/resolv.conf.
            |[ -s "${'$'}MNT/etc/resolv.conf" ] || printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "${'$'}MNT/etc/resolv.conf"
            |""".trimMargin() + "\n"
    }

    /** Sends [signal] to every process running inside the chroot. */
    fun signalScript(paths: LinuxPaths, signal: String): String = signalScript(paths.rootfs.absolutePath, signal)

    fun signalScript(rootfs: String, signal: String): String = """
        |MNT=${'$'}(readlink -f ${q(rootfs)} 2>/dev/null || echo ${q(rootfs)})
        |for p in /proc/[0-9]*; do
        |  [ "${'$'}(readlink "${'$'}p/root" 2>/dev/null)" = "${'$'}MNT" ] && kill -$signal "${'$'}{p#/proc/}" 2>/dev/null
        |done
        |""".trimMargin() + "\n"

    /** Stops everything inside the chroot and unmounts it. */
    fun unmountScript(paths: LinuxPaths): String = unmountScript(paths.rootfs.absolutePath)

    fun unmountScript(rootfs: String): String = signalScript(rootfs, "KILL") + """
        |sleep 1
        |for d in home/user/Linux sdcard dev/shm dev/pts dev proc sys; do umount -l "${'$'}MNT/${'$'}d" 2>/dev/null; done
        |umount -l "${'$'}MNT" 2>/dev/null
        |true
        |""".trimMargin() + "\n"

    /** Runs a shell script as root (outside the chroot). */
    fun su(paths: LinuxPaths, script: String): Process {
        val dir = File(paths.base, "jobs").apply { mkdirs() }
        val f = File(dir, "root-${System.nanoTime()}.sh")
        f.writeText(script + "\nrm -f ${q(f.absolutePath)}\n")
        return ProcessBuilder("su", "-c", "sh ${f.absolutePath}").redirectErrorStream(true).start()
    }

    /** Runs a root script to the end, passing each line of output to [log]. Returns the exit code. */
    fun run(paths: LinuxPaths, script: String, log: (String) -> Unit = {}): Int {
        val p = su(paths, script)
        p.inputStream.bufferedReader().forEachLine(log)
        return p.waitFor()
    }

    /** A process running [script] with bash inside the chroot, as root or as the desktop user. */
    fun start(paths: LinuxPaths, script: String, asUser: Boolean): Process {
        val dir = File(paths.base, "jobs").apply { mkdirs() }
        val job = File(dir, "job-${System.nanoTime()}.sh").apply { writeText("rm -f \"\$0\"\n$script\n") }
        val home = if (asUser) "/home/user" else "/root"
        val user = if (asUser) "user" else "root"
        val env = "/usr/bin/env -i HOME=$home USER=$user LOGNAME=$user SHELL=/bin/bash " +
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color LANG=C.UTF-8 TMPDIR=/tmp"
        val run = if (asUser) "chroot \"\$MNT\" /usr/sbin/runuser -u user -- $env /bin/bash \"\$J\""
            else "chroot \"\$MNT\" $env /bin/bash \"\$J\""
        val script2 = mountScript(paths) + """
            |J=/tmp/kdehome-job-${'$'}${'$'}.sh
            |cp ${q(job.absolutePath)} "${'$'}MNT${'$'}J" && chmod 755 "${'$'}MNT${'$'}J"
            |rm -f ${q(job.absolutePath)}
            |cd "${'$'}MNT$home" 2>/dev/null || cd "${'$'}MNT"
            |exec $run
            |""".trimMargin()
        return su(paths, script2)
    }

    /**
     * Moves the proot install into a new image: creates Internal storage/Linux/debian.img, copies
     * Debian into it, gives system files to root and the home folder to the desktop user, and
     * turns on the real sudo. The proot copy is kept (files/linux/rootfs-proot) until deleted.
     */
    fun migrate(paths: LinuxPaths, proot: Proot, log: (String) -> Unit, progress: (String) -> Unit) {
        if (!hasRoot()) throw java.io.IOException("No root: su is missing or denied. Allow KDE Home in Magisk.")
        if (!paths.hasStorageAccess()) throw java.io.IOException("Allow storage access first (the image goes in Internal storage/Linux)")
        val uid = AndroidProcess.myUid()
        val src = protCopy(paths)

        progress("Measuring Debian…")
        var used = 0L
        run(paths, "du -sk ${q(paths.rootfs.absolutePath)} 2>/dev/null") { line ->
            line.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.let { used = it * 1024 }
        }
        if (used <= 0) throw java.io.IOException("Could not measure the Debian folder")
        // Room to grow: what's there now, plus 8 GB. The file is sparse, so free space is only used as it fills.
        val size = used + 8L * 1024 * 1024 * 1024
        val img = image(paths)
        if (img.exists()) throw java.io.IOException("${img.absolutePath} already exists; delete it first")
        paths.linuxFolder.mkdirs()

        progress("Creating ${img.name} (${size shr 30} GB)…")
        java.io.RandomAccessFile(img, "rw").use { it.setLength(size) }
        // Debian's own mke2fs, run with proot (no root needed for a file).
        val mk = proot.process("mke2fs -q -F -t ext4 -m 0 -L kdehome /sdcard/Linux/${img.name}", asUser = false).start()
        mk.inputStream.bufferedReader().forEachLine(log)
        if (mk.waitFor() != 0) {
            img.delete()
            throw java.io.IOException("mke2fs failed")
        }

        progress("Copying Debian into the image (this takes a while)…")
        if (!paths.rootfs.renameTo(src)) throw java.io.IOException("Could not move the proot copy aside")
        paths.rootfs.mkdirs()
        val list = File(paths.base, "setuid.list").absolutePath
        val script = mountScript(paths, binds = false) + """
            |echo '>>> Copying'
            |cp -a ${q(src.absolutePath)}/. "${'$'}MNT/" || { echo '!!! Copy failed'; exit 1; }
            |echo '>>> Giving system files to root'
            |cd "${'$'}MNT" || exit 1
            |find . -xdev -type f \( -perm -4000 -o -perm -2000 \) -exec stat -c '%a %n' {} \; > ${q(list)}
            |chown -R 0:0 "${'$'}MNT"
            |while read -r m p; do chmod "${'$'}m" "${'$'}MNT/${'$'}p"; done < ${q(list)}
            |rm -f ${q(list)}
            |chmod 1777 "${'$'}MNT/tmp"
            |""".trimMargin() + "\n" + mountScript(paths) + """
            |echo '>>> Setting up the desktop user and the real sudo'
            |chroot "${'$'}MNT" /bin/bash -c '
            |  groupadd -g 3003 aid_inet 2>/dev/null; groupadd -g 3004 aid_net_raw 2>/dev/null
            |  getent group sudo >/dev/null || groupadd sudo
            |  groupmod -o -g $uid user
            |  usermod -o -u $uid -g $uid user
            |  usermod -aG aid_inet,aid_net_raw,sudo user
            |  usermod -aG aid_inet _apt 2>/dev/null
            |  rm -f /usr/local/bin/sudo /usr/local/bin/su /usr/local/bin/pkexec
            |  true'
            |chown -R $uid:$uid "${'$'}MNT/home/user"
            |# Files KDE Home itself keeps up to date.
            |for p in etc/kdehome usr/local/bin; do [ -e "${'$'}MNT/${'$'}p" ] && chown -R $uid "${'$'}MNT/${'$'}p"; done
            |for p in etc/dbus-1/system-local.conf etc/polkit-1/rules.d/49-kdehome.rules; do [ -e "${'$'}MNT/${'$'}p" ] && chown $uid "${'$'}MNT/${'$'}p"; done
            |echo '>>> Done'
            |""".trimMargin()
        val code = run(paths, script) { log(it); if (it.startsWith(">>> ")) progress(it.removePrefix(">>> ")) }
        // The app must see the mounted image (Magisk's mount namespace must be "inherit").
        val visible = File(paths.rootfs, "usr/bin/apt-get").exists()
        if (code != 0 || !visible) {
            run(paths, unmountScript(paths))
            paths.rootfs.delete()
            src.renameTo(paths.rootfs)
            img.delete()
            throw java.io.IOException(
                if (code != 0) "Moving Debian into the image failed (exit $code); proot mode is unchanged"
                else "KDE Home can't see the mounted image. In Magisk › Settings, set Mount Namespace Mode to " +
                    "\"Inherit\", then try again. Proot mode is unchanged."
            )
        }
    }

    /** Back to proot: unmounts the image and puts the old proot copy back (changes since are not in it). */
    fun backToProot(paths: LinuxPaths) {
        run(paths, unmountScript(paths))
        val src = protCopy(paths)
        if (!src.isDirectory) throw java.io.IOException("The proot copy was deleted; reinstall to use proot again")
        paths.rootfs.delete()
        if (!src.renameTo(paths.rootfs)) throw java.io.IOException("Could not put the proot copy back")
    }
}
