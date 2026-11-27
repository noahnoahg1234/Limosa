package com.limosa.kdehome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Lets Linux open Android apps. KDE shortcuts run `android-launch <package/activity>`, which sends
 * `<token> launch <package/activity>` to 127.0.0.1:[PORT]. The token is stored inside the Linux
 * root filesystem (app-private storage), so other Android apps can't use the bridge.
 */
class AndroidBridge(private val context: Context, private val tokenFile: File) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        val s = try {
            ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            Log.w(TAG, "bridge port $PORT busy: $e")
            return
        }
        server = s
        thread(name = "android-bridge", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(c) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) = socket.use { c ->
        c.soTimeout = 5000
        val line = c.getInputStream().bufferedReader().readLine()?.take(4096) ?: return
        val parts = line.trim().split(' ', limit = 3)
        val reply = if (parts.isEmpty() || !tokenOk(parts[0])) {
            "ERR bad token"
        } else {
            val cmd = parts.getOrElse(1) { "" }
            val arg = parts.getOrElse(2) { "" }.trim()
            runCatching { run(cmd, arg) }.fold({ "OK $it" }, { "ERR ${it.message}" })
        }
        c.getOutputStream().write((reply + "\n").toByteArray())
        c.getOutputStream().flush()
    }

    private fun tokenOk(given: String): Boolean {
        val expected = runCatching { tokenFile.readText().trim() }.getOrNull() ?: return false
        return expected.isNotEmpty() && MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    private fun run(cmd: String, arg: String): String = when (cmd) {
        "launch" -> {
            val intent = if (arg.contains('/')) {
                val cn = ComponentName.unflattenFromString(arg) ?: throw IllegalArgumentException("bad component $arg")
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
            } else {
                context.packageManager.getLaunchIntentForPackage(arg) ?: throw IllegalArgumentException("no app $arg")
            }
            start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            "launched $arg"
        }
        "open" -> {
            start(Intent(Intent.ACTION_VIEW, Uri.parse(arg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "opened $arg"
        }
        "home" -> {
            start(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "home"
        }
        else -> throw IllegalArgumentException("unknown command '$cmd' (use launch, open or home)")
    }

    private fun start(intent: Intent) {
        // Runs while the KDE home screen is in front, so Android allows starting the activity.
        main.post { runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "start failed: $it") } }
    }

    companion object {
        const val PORT = 7790
        private const val TAG = "KdeHomeBridge"
    }
}
