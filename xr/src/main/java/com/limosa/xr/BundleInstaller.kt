package com.limosa.xr

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Installs a picked file with Android's PackageInstaller. Accepts a plain .apk and split-APK
 * bundles: .apkm (APKMirror), .xapk (APKPure, may carry OBB game data) and .apks (SAI, bundletool).
 */
class BundleInstaller(private val context: Context) {

    class Result(val error: String?, val note: String? = null)

    private class Source(val zip: ZipFile, private val pfd: ParcelFileDescriptor?, private val temp: File?) : Closeable {
        override fun close() {
            runCatching { zip.close() }
            runCatching { pfd?.close() }
            temp?.delete()
        }
    }

    fun install(uri: Uri): Result {
        val src = try {
            open(uri)
        } catch (e: ZipException) {
            return Result("This file could not be read as an APK or APK bundle. If it is an .apkm from APKMirror's older, " +
                "encrypted format, download it again (newer .apkm files are not encrypted). (${e.message})")
        }
        src.use { s ->
            val zip = s.zip
            // A plain APK has its manifest at the root.
            if (zip.getEntry("AndroidManifest.xml") != null) {
                return commit(listOf("base.apk" to { context.contentResolver.openInputStream(uri)!! to -1L }), null)
            }
            val names = zip.entries().toList().map { it.name }
            val choice = BundleSplits.choose(names, Build.SUPPORTED_ABIS.toList(), context.resources.displayMetrics.densityDpi)
            if (choice.error != null) return Result(choice.error)
            val pkg = packageName(zip)
            val used = HashSet<String>()
            val parts = choice.apks.map { name ->
                var file = name.substringAfterLast('/')
                while (!used.add(file)) file = "x$file"
                val entry = zip.getEntry(name)
                file to { zip.getInputStream(entry) to entry.size }
            }
            val obbNote = copyObbs(zip, names, pkg)
            val r = commit(parts, obbNote)
            return r
        }
    }

    /** Opens the file as a zip, directly if the provider gives a seekable file, else via a cache copy. */
    private fun open(uri: Uri): Source {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            try {
                return Source(ZipFile("/proc/self/fd/${pfd.fd}"), pfd, null)
            } catch (e: Exception) {
                pfd.close()
            }
        }
        val tmp = File(context.cacheDir, "install.bundle")
        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        try {
            return Source(ZipFile(tmp), null, tmp)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun packageName(zip: ZipFile): String? {
        for ((file, key) in listOf("info.json" to "pname", "manifest.json" to "package_name", "info.json" to "package_name")) {
            val e = zip.getEntry(file) ?: continue
            val json = runCatching { JSONObject(zip.getInputStream(e).bufferedReader().readText()) }.getOrNull() ?: continue
            json.optString(key).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /**
     * XAPK files can carry the game's OBB data. Installer apps may write other apps' Android/obb
     * folders on Android 11+; if that is not allowed here, say where to copy them instead.
     */
    private fun copyObbs(zip: ZipFile, names: List<String>, pkg: String?): String? {
        val obbs = names.filter { it.endsWith(".obb") }
        if (obbs.isEmpty()) return null
        val root = File(Environment.getExternalStorageDirectory(), "Android/obb")
        var copied = 0
        var failed: String? = null
        for (name in obbs) {
            val rel = when {
                name.startsWith("Android/obb/") -> name.removePrefix("Android/obb/")
                pkg != null -> "$pkg/${name.substringAfterLast('/')}"
                else -> null
            } ?: continue
            if (rel.contains("..")) continue
            val dest = File(root, rel)
            try {
                dest.parentFile?.mkdirs()
                zip.getInputStream(zip.getEntry(name)).use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 20) } }
                copied++
            } catch (t: Throwable) {
                failed = t.message ?: t.toString()
                break
            }
        }
        return if (failed != null) {
            "The game's data files (OBB) could not be copied automatically ($failed). Extract the Android/obb folder " +
                "from the file and copy it to Android/obb on your phone."
        } else {
            "Copied $copied game data file(s) to Android/obb."
        }
    }

    private fun commit(parts: List<Pair<String, () -> Pair<java.io.InputStream, Long>>>, note: String?): Result {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                for ((name, open) in parts) {
                    val (input, size) = open()
                    input.use { inp ->
                        session.openWrite(name, 0, size).use { out ->
                            inp.copyTo(out, 1 shl 20)
                            session.fsync(out)
                        }
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw t
        }
        return Result(null, note)
    }
}
