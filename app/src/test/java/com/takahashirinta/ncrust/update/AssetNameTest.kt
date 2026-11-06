package com.takahashirinta.ncrust.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 资产名解析（v3.8.5）：线上真名是 `Arris-v3.8.4-gpl.apk`，不带 `-release`。 */
class AssetNameTest {

    @Test
    fun `现有发布名能解析`() {
        assertEquals(AppVersion(3, 8, 4), ForkRelease.parseAsset("Arris-v3.8.4-gpl.apk")?.version)
        assertEquals("release", ForkRelease.parseAsset("Arris-v3.8.4-gpl.apk")?.variant)
        assertEquals(AppVersion(3, 7, 15), ForkRelease.parseAsset("Arris-v3.7.15-gpl.apk")?.version)
    }

    @Test
    fun `兼容已经发布的 LTS 资产名`() {
        assertEquals(
            ForkRelease.Asset(AppVersion(3, 7, 33), ForkRelease.VARIANT_RELEASE),
            ForkRelease.parseAsset("Arris-v3.7.33-lts-gpl.apk")
        )
    }

    @Test
    fun `早期带变体后缀的名字仍能解析`() {
        assertEquals("release", ForkRelease.parseAsset("Ncrust-v3.7.11-gpl-release.apk")?.variant)
        assertEquals("debug", ForkRelease.parseAsset("Ncrust-v3.4.12-gpl-debug.apk")?.variant)
    }

    @Test
    fun `带 formal 标记的历史包名能解析`() {
        assertNotNull(ForkRelease.parseAsset("Arris-v3.7.11-gpl-formal-code99-release.apk"))
    }

    @Test
    fun `校验文件与不相关文件不会被当成安装包`() {
        assertNull(ForkRelease.parseAsset("Arris-v3.8.4-gpl.apk.sha256"))
        assertNull(ForkRelease.parseAsset("latest.apk"))
        assertNull(ForkRelease.parseAsset("app-release.apk"))
    }
}
