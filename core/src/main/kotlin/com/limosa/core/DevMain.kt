package com.limosa.core

import com.limosa.core.vm.QemuEnvironment
import com.limosa.core.vm.VmConfig
import java.io.File

/**
 * Runs Limosa on a desktop (Linux/macOS) for development:
 * `./gradlew :core:run --args="<dataDir> <webDir>"`.
 * Uses `qemu-system-*` from PATH (or LIMOSA_QEMU_DIR) and serves the dashboard from webDir.
 */
fun main(args: Array<String>) {
    val data = File(args.getOrElse(0) { "build/dev-data" })
    val web = File(args.getOrElse(1) { "../app/src/main/assets/web" })
    val qemuDir = System.getenv("LIMOSA_QEMU_DIR")
    val qemu = object : QemuEnvironment {
        override fun binary(arch: VmConfig.Arch): File? {
            val dirs = listOfNotNull(qemuDir) + (System.getenv("PATH") ?: "").split(File.pathSeparator)
            return dirs.map { File(it, arch.binary) }.firstOrNull { it.canExecute() }
        }
        override fun dataDir(): File? = null
        override fun kvmAvailable(arch: VmConfig.Arch) =
            File("/dev/kvm").let { it.canRead() && it.canWrite() } &&
                System.getProperty("os.arch").let { a -> (a == "amd64" && arch == VmConfig.Arch.X86_64) || (a == "aarch64" && arch == VmConfig.Arch.AARCH64) }
    }
    val port = System.getenv("LIMOSA_HTTP_PORT")?.toIntOrNull() ?: LimosaEngine.DEFAULT_HTTP_PORT
    val engine = LimosaEngine(data, qemu, { path ->
        File(web, path).takeIf { it.isFile && it.canonicalPath.startsWith(web.canonicalPath) }?.readBytes()
    }, httpPort = port, log = { println(it) })
    Runtime.getRuntime().addShutdownHook(Thread { engine.close() })
    println("Limosa dashboard: ${engine.dashboardUrl}")
    println("NBD storage server: nbd://127.0.0.1:${engine.nbd.port}/<disk>")
    Thread.currentThread().join()
}
