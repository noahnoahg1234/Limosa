package com.limosa.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleSplitsTest {
    private val arm = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    // APKMirror .apkm layout.
    private val apkm = listOf(
        "base.apk", "split_config.arm64_v8a.apk", "split_config.armeabi_v7a.apk", "split_config.x86_64.apk",
        "split_config.xxhdpi.apk", "split_config.xhdpi.apk", "split_config.mdpi.apk", "split_config.en.apk",
        "split_config.de.apk", "info.json", "icon.png",
    )

    @Test fun apkmPicksAbiDensityAndKeepsLanguages() {
        assertEquals(
            listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk", "split_config.en.apk", "split_config.de.apk"),
            BundleSplits.choose(apkm, arm, 440).apks,
        )
    }

    @Test fun densityFallsBackToTheLargest() {
        assertEquals(listOf("split_config.mdpi.apk"), BundleSplits.choose(apkm, arm, 160).apks.filter { "dpi" in it })
        assertEquals(listOf("split_config.xxhdpi.apk"), BundleSplits.choose(apkm, arm, 640).apks.filter { "dpi" in it })
    }

    @Test fun thirtyTwoBitPhoneGetsV7a() {
        assertEquals(listOf("split_config.armeabi_v7a.apk"), BundleSplits.choose(apkm, listOf("armeabi-v7a"), 320).apks.filter { "arm" in it })
    }

    @Test fun wrongAbiIsAnError() {
        val c = BundleSplits.choose(listOf("base.apk", "split_config.x86_64.apk"), arm, 320)
        assertTrue(c.error!!.startsWith("This game is built for another processor type"))
    }

    @Test fun xapkWithOneApk() {
        val xapk = listOf("com.game.vr.apk", "Android/obb/com.game.vr/main.1.com.game.vr.obb", "manifest.json")
        assertEquals(listOf("com.game.vr.apk"), BundleSplits.choose(xapk, arm, 420).apks)
    }

    @Test fun bundletoolApksSkipsStandalones() {
        val apks = listOf(
            "toc.pb", "splits/base-master.apk", "splits/base-arm64_v8a.apk", "splits/base-armeabi_v7a.apk",
            "splits/base-xxhdpi.apk", "splits/base-hdpi.apk", "splits/base-en.apk", "splits/feature-master.apk",
            "standalones/standalone-arm64_v8a_hdpi.apk",
        )
        assertEquals(
            listOf("splits/base-master.apk", "splits/base-arm64_v8a.apk", "splits/base-xxhdpi.apk", "splits/base-en.apk", "splits/feature-master.apk"),
            BundleSplits.choose(apks, arm, 480).apks,
        )
    }

    @Test fun brokenBundles() {
        assertEquals("This bundle has no base APK.", BundleSplits.choose(listOf("split_config.en.apk"), arm, 320).error)
        assertEquals("There is no APK inside this file.", BundleSplits.choose(listOf("readme.txt"), arm, 320).error)
        assertEquals("arm64_v8a", BundleSplits.qualifier("split_feature_x.config.arm64_v8a.apk"))
    }
}
