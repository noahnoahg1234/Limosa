package com.limosa.kdehome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.system.Os
import java.io.File

/**
 * Turns the phone's Android apps into KDE shortcuts:
 * - `~/.local/share/applications/android-*.desktop` → an "Android" section in the KDE app menu,
 * - `~/AndroidApps/` → the "Android Apps" folder on the KDE desktop,
 * - selected apps (phone, messages, camera, browser, settings, Play Store) straight on the desktop.
 * Each shortcut runs `android-launch <package/activity>` (see [AndroidBridge]).
 */
class AndroidShortcuts(private val context: Context, private val paths: LinuxPaths) {

    data class App(val label: String, val component: ComponentName)

    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    private val home get() = paths.userHome
    private val appsDir get() = File(home, ".local/share/applications")
    private val iconsDir get() = File(home, ".local/share/android-icons")
    private val folderDir get() = File(home, "AndroidApps")
    private val desktopDir get() = File(home, "Desktop")

    fun launchableApps(): List<App> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { App(it.loadLabel(pm).toString().trim(), ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    fun icon(app: App): Drawable =
        runCatching { pm.getActivityIcon(app.component) }.getOrElse { pm.defaultActivityIcon }

    fun launchIntent(app: App): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** Rewrites all Android shortcuts. Safe to call often; runs on a background thread. */
    @Synchronized
    fun sync() {
        if (!home.isDirectory) return
        for (d in listOf(appsDir, iconsDir, folderDir, desktopDir)) d.mkdirs()
        writeMenu()
        val apps = launchableApps()
        val keep = HashSet<String>()
        for (app in apps) {
            val name = fileName(app)
            keep += name
            val iconFile = File(iconsDir, name.removeSuffix(".desktop") + ".png")
            if (!iconFile.exists()) writeIcon(app, iconFile)
            val text = desktopEntry(app, iconFile)
            writeIfChanged(File(appsDir, name), text, executable = false)
            writeIfChanged(File(folderDir, name), text, executable = true)
        }
        // Remove shortcuts of uninstalled apps.
        for (dir in listOf(appsDir, folderDir, desktopDir)) {
            dir.listFiles()?.filter { it.name.startsWith("android-") && it.name.endsWith(".desktop") && it.name !in keep }
                ?.forEach { it.delete() }
        }
        iconsDir.listFiles()?.filter { it.name.removeSuffix(".png") + ".desktop" !in keep }?.forEach { it.delete() }
        if (!prefs.getBoolean("defaultsPinned", false)) {
            pinDefaults(apps)
            prefs.edit().putBoolean("defaultsPinned", true).apply()
        }
    }

    /** Puts an app's shortcut on the KDE desktop. */
    fun pinToDesktop(app: App) {
        if (!home.isDirectory) return
        desktopDir.mkdirs()
        iconsDir.mkdirs()
        val name = fileName(app)
        val iconFile = File(iconsDir, name.removeSuffix(".desktop") + ".png")
        if (!iconFile.exists()) writeIcon(app, iconFile)
        writeIfChanged(File(desktopDir, name), desktopEntry(app, iconFile), executable = true)
    }

    private fun pinDefaults(apps: List<App>) {
        val wanted = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.debian.org")),
        ).mapNotNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .filter { it != "android" } + listOf("com.android.vending", "com.android.settings")
        for (pkg in wanted.distinct()) apps.firstOrNull { it.component.packageName == pkg }?.let(::pinToDesktop)
        // Settings may not have a launcher entry under that package name on every phone.
        if (apps.none { it.component.packageName == "com.android.settings" }) {
            pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.let { ai ->
                apps.firstOrNull { it.component.packageName == ai.packageName }?.let(::pinToDesktop)
            }
        }
    }

    private fun fileName(app: App): String {
        val id = "${app.component.packageName}--${app.component.className}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "android-$id.desktop"
    }

    private fun desktopEntry(app: App, icon: File): String {
        val flat = app.component.flattenToString()
        // Desktop-entry Exec needs quoting for some characters; package names never have them.
        val target = if (flat.matches(Regex("[A-Za-z0-9._/]+"))) flat else app.component.packageName
        val label = app.label.replace(Regex("[\\r\\n]"), " ").ifBlank { app.component.packageName }
        return """
            |[Desktop Entry]
            |Type=Application
            |Version=1.0
            |Name=$label
            |GenericName=Android app
            |Comment=Opens $label on Android (${app.component.packageName})
            |Exec=/usr/local/bin/android-launch $target
            |Icon=/home/user/${icon.relativeTo(home).path}
            |Terminal=false
            |StartupNotify=false
            |Categories=X-Android;
            |X-KDE-Home-Component=$flat
            |""".trimMargin()
    }

    private fun writeMenu() {
        // An "Android" submenu in the KDE launcher. Plasma 5 and 6 read different merge folders.
        val menu = """
            |<!DOCTYPE Menu PUBLIC "-//freedesktop//DTD Menu 1.0//EN" "http://www.freedesktop.org/standards/menu-spec/menu-1.0.dtd">
            |<Menu>
            |  <Name>Applications</Name>
            |  <Menu>
            |    <Name>Android</Name>
            |    <Directory>android.directory</Directory>
            |    <Include><Category>X-Android</Category></Include>
            |  </Menu>
            |</Menu>
            |""".trimMargin()
        for (dir in listOf("applications-merged", "kf5-applications-merged", "plasma-applications-merged")) {
            writeIfChanged(File(home, ".config/menus/$dir/android.menu"), menu, executable = false)
        }
        writeIfChanged(
            File(home, ".local/share/desktop-directories/android.directory"),
            "[Desktop Entry]\nType=Directory\nName=Android\nComment=Apps on this phone\nIcon=phone\n",
            executable = false,
        )
    }

    private fun writeIcon(app: App, file: File) {
        runCatching {
            val d = icon(app)
            val bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            d.draw(Canvas(bmp))
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
    }

    private fun writeIfChanged(file: File, text: String, executable: Boolean) {
        if (!(file.exists() && runCatching { file.readText() }.getOrNull() == text)) {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        // KDE only runs desktop-folder shortcuts that are executable ("trusted").
        if (executable) runCatching { Os.chmod(file.absolutePath, 493) }
    }

    companion object {
        private const val ICON_SIZE = 128
    }
}
