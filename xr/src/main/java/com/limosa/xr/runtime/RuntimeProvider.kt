package com.limosa.xr.runtime

import android.app.Service
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.IBinder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tells OpenXR games where the runtime is, and serves the runtime running inside them.
 *
 * - `org.khronos.openxr.runtime_broker`: the "installable runtime broker" that the standard
 *   Khronos OpenXR loader for Android asks for the active runtime:
 *   `content://org.khronos.openxr.runtime_broker/openxr/<major>/abi/<abi>/runtimes/active/0`
 *   returns our package, native library folder and runtime library name.
 * - `com.limosa.xr.runtime`: `/settings` (read by the runtime when a game starts) and `/log`
 *   (the runtime sends its log lines here so the home page can show them).
 */
class RuntimeProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val ctx = context ?: return null
        return when (uri.authority) {
            BROKER -> broker(ctx, uri)
            OWN -> if (uri.pathSegments.firstOrNull() == "settings") settings(ctx) else null
            else -> null
        }
    }

    private fun broker(ctx: Context, uri: Uri): Cursor {
        val seg = uri.pathSegments
        if (seg.lastOrNull() == "functions") {
            return MatrixCursor(arrayOf("_id", "function_name", "symbol_name"))
        }
        val cursor = MatrixCursor(arrayOf("_id", "package_name", "native_lib_dir", "so_filename", "has_functions"))
        val abi = seg.getOrNull(seg.indexOf("abi") + 1)
        val caller = runCatching { callingPackage }.getOrNull() ?: "?"
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("runtimeEnabled", true)
        val libDir = ctx.applicationInfo.nativeLibraryDir
        val lib = File(libDir, LIB)
        // One ABI is installed; a game of another ABI (e.g. a 32-bit one) cannot load us.
        val ourAbi = lib.exists() && abi == abiOfLibDir(libDir)
        when {
            !enabled -> appendLog(ctx, caller, "asked for the OpenXR runtime; Limosa XR runtime is turned off in settings")
            !ourAbi -> appendLog(ctx, caller, "asked for an OpenXR runtime for ABI $abi, which Limosa XR does not provide")
            else -> {
                cursor.addRow(arrayOf<Any>(0, ctx.packageName, libDir, LIB, 0))
                appendLog(ctx, caller, "found the Limosa XR OpenXR runtime ($abi)")
            }
        }
        return cursor
    }

    private fun abiOfLibDir(dir: String): String = when {
        dir.endsWith("/arm64") -> "arm64-v8a"
        dir.endsWith("/arm") -> "armeabi-v7a"
        dir.endsWith("/x86_64") -> "x86_64"
        dir.endsWith("/x86") -> "x86"
        else -> ""
    }

    private fun settings(ctx: Context): Cursor {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val c = MatrixCursor(arrayOf("stereo", "gyro", "eyeHeight", "fov", "ipd", "renderScale"))
        c.addRow(arrayOf<Any>(
            if (p.getBoolean("stereo", false)) 1 else 0,
            if (p.getBoolean("gyro", false)) 1 else 0,
            p.getFloat("eyeHeight", 1.6f),
            p.getFloat("fov", 70f),
            p.getFloat("ipd", 0.063f),
            p.getFloat("renderScale", 1f),
        ))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        if (uri.authority != OWN || uri.pathSegments.firstOrNull() != "log" || values == null) return null
        val pkg = runCatching { callingPackage }.getOrNull() ?: values.getAsString("package") ?: "?"
        appendLog(ctx, pkg, values.getAsString("text") ?: return null)
        return uri
    }

    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val BROKER = "org.khronos.openxr.runtime_broker"
        const val OWN = "com.limosa.xr.runtime"
        const val LIB = "liblimosaxr_runtime.so"
        private const val MAX_LOG = 256 * 1024

        fun logFile(ctx: Context) = File(ctx.filesDir, "runtime.log")

        @Synchronized
        fun appendLog(ctx: Context, pkg: String, text: String) {
            val f = logFile(ctx)
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val lines = text.lines().joinToString("") { "$time  $pkg  $it\n" }
            runCatching {
                if (f.length() > MAX_LOG) {
                    val keep = f.readText().takeLast(MAX_LOG / 2)
                    f.writeText(keep.substringAfter('\n'))
                }
                f.appendText(lines)
            }
        }
    }
}

/**
 * Exists so OpenXR games can see this app: the Khronos loader's manifest asks to see packages
 * with this service action, and the runtime loads its Java half from our package.
 */
class RuntimeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
