package com.limosa.xr

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Finds installed native VR games, tells whether the Limosa XR runtime can run them, launches
 * them and installs new APKs.
 */
class NativeApps(private val context: Context) {
    private val pm = context.packageManager

    /**
     * Installed apps as JSON for the home page: the ones that look like VR games, or with
     * [all] every app the user installed, each with what the scan found. Slow: call off the UI thread.
     */
    fun list(all: Boolean = false): JSONArray {
        val vrActivities = HashSet<String>()
        for (cat in VR_CATEGORIES) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            pm.queryIntentActivities(i, 0).forEach { vrActivities.add(it.activityInfo.packageName) }
        }
        val out = ArrayList<JSONObject>()
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in apps) {
            if (app.packageName == context.packageName) continue
            val system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0 && app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (system) continue
            val signals = ArrayList<String>()
            app.metaData?.let { meta ->
                for (k in VR_META_KEYS) if (meta.containsKey(k)) signals.add(k)
            }
            if (app.packageName in vrActivities) signals.add("VR launch category")
            val scan = scanApk(app)
            signals.addAll(scan.libs)
            val looksVr = signals.isNotEmpty()
            if (!looksVr && !all) continue
            val launchable = launchIntent(app.packageName) != null
            if (!looksVr && !launchable) continue
            out.add(JSONObject().apply {
                put("pkg", app.packageName)
                put("name", pm.getApplicationLabel(app).toString())
                put("loader", scan.loader.name.lowercase())
                put("abis", JSONArray(scan.abis.toList()))
                put("signals", JSONArray(signals))
                put("verdict", verdict(scan, looksVr))
                put("vr", looksVr)
                put("launchable", launchable)
                put("scanError", scan.error ?: JSONObject.NULL)
            })
        }
        out.sortWith(compareBy({ verdictRank(it.getString("verdict")) }, { it.getString("name").lowercase() }))
        return JSONArray(out)
    }

    enum class Loader { NONE, KHRONOS, OTHER }

    private class Scan(val loader: Loader, val libs: List<String>, val abis: Set<String>, val error: String?)

    private fun verdict(s: Scan, looksVr: Boolean): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        return when {
            s.abis.isNotEmpty() && abi !in s.abis -> "wrong-abi"
            s.loader == Loader.KHRONOS -> "compatible"
            s.loader == Loader.OTHER -> "headset-loader"
            "libOVRPlugin.so" in s.libs -> "ovrplugin"
            "libvrapi.so" in s.libs -> "vrapi"
            !looksVr -> "flat"
            else -> "unknown"
        }
    }

    private fun verdictRank(v: String) = when (v) {
        "compatible" -> 0
        "unknown" -> 1
        "flat" -> 3
        else -> 2
    }

    /** Looks inside the APK (and its splits) for the OpenXR loader and other VR libraries. */
    private fun scanApk(app: ApplicationInfo): Scan {
        val files = listOfNotNull(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())
        var loader = Loader.NONE
        val libs = LinkedHashSet<String>()
        val abis = HashSet<String>()
        val primary = Build.SUPPORTED_ABIS.firstOrNull()
        var error: String? = null
        for (path in files) {
            try {
                ZipFile(path).use { zip ->
                    val entries = zip.entries()
                    var loaderEntry: java.util.zip.ZipEntry? = null
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val n = e.name
                        if (!n.startsWith("lib/") || !n.endsWith(".so")) continue
                        val abi = n.substring(4).substringBefore('/')
                        abis.add(abi)
                        val file = n.substringAfterLast('/')
                        if (file in VR_LIBS) libs.add(file)
                        if (file == "libopenxr_loader.so" && (loaderEntry == null || abi == primary)) loaderEntry = e
                    }
                    loaderEntry?.let { e ->
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        loader = if (contains(bytes, BROKER_MARKER)) Loader.KHRONOS else Loader.OTHER
                    }
                }
            } catch (t: Throwable) {
                error = t.toString()
            }
        }
        // Libraries may also have been extracted on install.
        val libDir = File(app.nativeLibraryDir ?: "")
        libDir.list()?.forEach { if (it in VR_LIBS) libs.add(it) }
        if (loader == Loader.NONE) {
            val f = File(libDir, "libopenxr_loader.so")
            if (f.exists()) loader = runCatching { if (contains(f.readBytes(), BROKER_MARKER)) Loader.KHRONOS else Loader.OTHER }.getOrDefault(Loader.OTHER)
        }
        return Scan(loader, libs.toList(), abis, error)
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    fun launchIntent(pkg: String): Intent? {
        for (cat in VR_CATEGORIES) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(pkg)
            val r = pm.queryIntentActivities(i, 0).firstOrNull() ?: continue
            return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(r.activityInfo.packageName, r.activityInfo.name)
        }
        return pm.getLaunchIntentForPackage(pkg)
    }

    /** Starts the game. Returns an error message, or null. */
    fun launch(activity: Activity, pkg: String): String? {
        val i = launchIntent(pkg) ?: return "This app has no screen that can be opened."
        return try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (t: Throwable) {
            t.message ?: t.toString()
        }
    }

    /** True when the Limosa XR broker is the one games will find. */
    fun brokerOwner(): String? = runCatching {
        pm.resolveContentProvider("org.khronos.openxr.runtime_broker", 0)?.packageName
    }.getOrNull()

    /**
     * Installs an .apk, or a split-APK bundle (.apkm, .xapk, .apks), picked with the file picker.
     * Android then asks the user to confirm.
     */
    fun install(activity: Activity, uri: Uri): BundleInstaller.Result {
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return BundleInstaller.Result("Allow Limosa XR to install apps, then pick the file again.")
        }
        return try {
            BundleInstaller(context).install(uri)
        } catch (t: Throwable) {
            BundleInstaller.Result(t.message ?: t.toString())
        }
    }

    companion object {
        val VR_CATEGORIES = listOf("org.khronos.openxr.intent.category.IMMERSIVE_HMD", "com.oculus.intent.category.VR")
        private val VR_META_KEYS = listOf(
            "com.oculus.supportedDevices", "com.oculus.vr.focusaware", "com.oculus.intent.category.VR",
            "com.oculus.handtracking.version", "com.samsung.android.vr.application.mode", "pvr.app.type", "pico.vr.mode",
        )
        /** Libraries that only VR apps carry. */
        private val VR_LIBS = setOf(
            "libopenxr_loader.so", "libOVRPlugin.so", "libvrapi.so", "libUnityOpenXR.so", "libopenxr_forwardloader.so",
            "libOculusXRPlugin.so", "libovrplatformloader.so", "libpxr_api.so", "libPxrPlatform.so", "libgodot_openxr.so",
        )
        private val BROKER_MARKER = "org.khronos.openxr.runtime_broker".toByteArray()
        /** Set by MainActivity to refresh the home page when an install finishes. */
        @Volatile var onInstalled: ((String) -> Unit)? = null
    }
}

/** Receives the result of an APK install and shows Android's confirmation screen when needed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Installed", Toast.LENGTH_SHORT).show()
                NativeApps.onInstalled?.invoke("ok")
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                Toast.makeText(context, "Install failed: $msg", Toast.LENGTH_LONG).show()
                NativeApps.onInstalled?.invoke(msg)
            }
        }
    }
}
