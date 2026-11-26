package com.limosa.xr

/**
 * Chooses which APKs of a split-APK bundle (.apkm, .xapk, .apks) to install on this device,
 * the way an app store would: the base and feature APKs, every language, the ABI split for
 * the device's preferred processor type and the screen-density split closest to the screen's.
 *
 * Pure Kotlin so it can be tested without Android.
 */
object BundleSplits {
    private val DENSITIES = mapOf(
        "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
    )
    private val ABIS = setOf("armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64")

    class Choice(val apks: List<String>, val error: String?)

    /**
     * The split qualifier, or null for base and feature APKs:
     * "split_config.arm64_v8a.apk" (APKMirror, APKPure, SAI) and "splits/base-arm64_v8a.apk"
     * (bundletool, where "-master" is the module's main APK) both give "arm64_v8a".
     */
    fun qualifier(entryName: String): String? {
        val file = entryName.substringAfterLast('/').removeSuffix(".apk")
        val i = file.lastIndexOf("config.")
        if (i >= 0) return file.substring(i + 7)
        if (entryName.startsWith("splits/") && file.contains('-')) {
            val q = file.substringAfterLast('-')
            return if (q == "master") null else q
        }
        return null
    }

    /**
     * @param entries APK entry names inside the bundle
     * @param deviceAbis Build.SUPPORTED_ABIS, most preferred first (e.g. "arm64-v8a")
     * @param densityDpi the screen density in dpi
     */
    fun choose(entries: List<String>, deviceAbis: List<String>, densityDpi: Int): Choice {
        var apks = entries.filter { it.endsWith(".apk") && !it.endsWith("/") }
        // bundletool .apks: install the splits, not the all-in-one "standalone" APKs next to them.
        if (apks.any { it.startsWith("splits/") }) apks = apks.filter { it.startsWith("splits/") }
        if (apks.isEmpty()) return Choice(emptyList(), "There is no APK inside this file.")
        val hasBase = apks.any { qualifier(it) == null }
        if (!hasBase) return Choice(emptyList(), "This bundle has no base APK.")

        // ABI: the first device ABI that the bundle has a split for.
        val abiSplits = apks.filter { qualifier(it) in ABIS }
        var abi: String? = null
        if (abiSplits.isNotEmpty()) {
            abi = deviceAbis.map { it.replace('-', '_') }.firstOrNull { a -> abiSplits.any { qualifier(it) == a } }
                ?: return Choice(emptyList(), "This game is built for another processor type (" +
                    abiSplits.mapNotNull { qualifier(it)?.replace('_', '-') }.distinct().joinToString() + ").")
        }

        // Density: the smallest one at or above the screen's, else the largest available.
        val densitySplits = apks.mapNotNull { a -> qualifier(a)?.let { q -> DENSITIES[q]?.let { q to it } } }.distinct()
        val density = densitySplits.filter { it.second >= densityDpi }.minByOrNull { it.second }?.first
            ?: densitySplits.maxByOrNull { it.second }?.first

        val chosen = apks.filter { a ->
            val q = qualifier(a)
            when {
                q == null -> true
                q in ABIS -> q == abi
                q in DENSITIES -> q == density
                else -> true  // languages, nodpi and anything else
            }
        }
        return Choice(chosen, null)
    }
}
