package com.limosa.vm

import android.content.Context
import android.os.Build
import com.limosa.core.vm.QemuEnvironment
import com.limosa.core.vm.VmConfig
import java.io.File

/**
 * Finds QEMU inside the APK. Android 10+ only lets apps execute files from their native
 * library directory, so each emulator is packaged as `jniLibs/<abi>/libqemu-system-<arch>.so`
 * (with its shared libraries next to it), and firmware lives in `assets/qemu/`.
 */
class AndroidQemu(private val context: Context) : QemuEnvironment {

    private val libDir = File(context.applicationInfo.nativeLibraryDir)
    private val firmwareDir = File(context.filesDir, "qemu-data")

    override fun binary(arch: VmConfig.Arch): File? =
        File(libDir, "lib${arch.binary}.so").takeIf { it.exists() }

    fun installed(): List<VmConfig.Arch> = VmConfig.Arch.entries.filter { binary(it) != null }

    override fun dataDir(): File? = firmwareDir.takeIf { it.isDirectory }

    override fun environment(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to libDir.absolutePath,
        "TMPDIR" to context.cacheDir.absolutePath,
        "HOME" to context.filesDir.absolutePath,
    )

    override fun kvmAvailable(arch: VmConfig.Arch): Boolean {
        val kvm = File("/dev/kvm")
        if (!kvm.exists() || !kvm.canRead() || !kvm.canWrite()) return false
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        return (arch == VmConfig.Arch.AARCH64 && abi == "arm64-v8a") ||
            (arch == VmConfig.Arch.X86_64 && abi == "x86_64")
    }

    /** Copies `assets/qemu` (BIOS, VGA ROMs, keymaps…) to internal storage once per app version. */
    fun extractFirmware() {
        val stamp = File(firmwareDir, ".version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return
        firmwareDir.deleteRecursively()
        copyAssets("qemu", firmwareDir)
        if (firmwareDir.isDirectory) stamp.writeText(version)
    }

    private fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path) ?: return
        if (children.isEmpty()) {
            // A file (or an empty directory, which assets cannot tell apart).
            runCatching {
                context.assets.open(path).use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            return
        }
        target.mkdirs()
        for (c in children) copyAssets("$path/$c", File(target, c))
    }
}
