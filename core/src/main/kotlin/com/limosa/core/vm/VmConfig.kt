package com.limosa.core.vm

import com.limosa.core.json.bool
import com.limosa.core.json.long
import com.limosa.core.json.str

/** A saved virtual machine definition. Disks and ISOs refer to [com.limosa.core.storage.ChunkStore] disks. */
data class VmConfig(
    val name: String,
    val arch: Arch = Arch.X86_64,
    val memoryMb: Int = 1024,
    val cpus: Int = 2,
    /** Main hard disk (a store disk name), or null. */
    val disk: String? = null,
    /** Installer / live CD (a read-only store disk), or null. */
    val cdrom: String? = null,
    val bootFromCdrom: Boolean = false,
    /** Pause automatically when the phone is inactive. */
    val autoPause: Boolean = true,
    val network: Boolean = true,
    /** Extra raw QEMU arguments, space separated (advanced). */
    val extraArgs: String = "",
) {
    enum class Arch(val binary: String, val machine: String) {
        X86_64("qemu-system-x86_64", "q35"),
        I386("qemu-system-i386", "pc"),
        AARCH64("qemu-system-aarch64", "virt"),
        ;

        companion object {
            fun parse(s: String?): Arch = entries.firstOrNull { it.name.equals(s, true) || it.binary == s } ?: X86_64
        }
    }

    fun toJson(): Map<String, Any?> = mapOf(
        "name" to name,
        "arch" to arch.name.lowercase(),
        "memoryMb" to memoryMb,
        "cpus" to cpus,
        "disk" to disk,
        "cdrom" to cdrom,
        "bootFromCdrom" to bootFromCdrom,
        "autoPause" to autoPause,
        "network" to network,
        "extraArgs" to extraArgs,
    )

    companion object {
        fun fromJson(m: Map<String, Any?>): VmConfig = VmConfig(
            name = m.str("name") ?: throw IllegalArgumentException("name is required"),
            arch = Arch.parse(m.str("arch")),
            memoryMb = (m.long("memoryMb") ?: 1024).toInt().coerceIn(64, 65536),
            cpus = (m.long("cpus") ?: 2).toInt().coerceIn(1, 32),
            disk = m.str("disk")?.takeIf { it.isNotBlank() },
            cdrom = m.str("cdrom")?.takeIf { it.isNotBlank() },
            bootFromCdrom = m.bool("bootFromCdrom") ?: false,
            autoPause = m.bool("autoPause") ?: true,
            network = m.bool("network") ?: true,
            extraArgs = m.str("extraArgs") ?: "",
        )
    }
}
