package com.takahashirinta.ncrust.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发布通道（v3.8.3）：3.7.x 与 3.8.x 是两条线，不能互相推送。
 *
 * 起因：LTS 包是 minSdk 24，主线是 minSdk 29。检查更新如果只看「版本号更大」，
 * Android 7 的 LTS 用户会被推 3.8.x，点下去装不上；反过来主线用户会被 `lts-3.7`
 * 那条滚动 Release 干扰。规则与内核的 stable/LTS 分线一致：只在自己那条线里更新。
 */
class ReleaseChannelTest {

    private fun v(major: Int, minor: Int, build: Int = 0) = AppVersion(major, minor, build)

    @Test
    fun `LTS 用户不会被推到主线版本`() {
        assertFalse(UpdateChecker.sameChannel("v3.8.3-gpl", v(3, 8, 3), v(3, 7, 13)))
    }

    @Test
    fun `LTS 用户能在 3_7 线内更新`() {
        assertTrue(UpdateChecker.sameChannel("v3.7.14-gpl", v(3, 7, 14), v(3, 7, 13)))
    }

    @Test
    fun `LTS 用户能看到 lts 滚动条目`() {
        assertTrue(UpdateChecker.sameChannel("lts-3.7", v(3, 7, 14), v(3, 7, 13)))
    }

    @Test
    fun `主线用户忽略 lts 滚动条目`() {
        assertFalse(UpdateChecker.sameChannel("lts-3.7", v(3, 7, 14), v(3, 8, 3)))
    }

    @Test
    fun `主线用户能跨 minor 升级`() {
        assertTrue(UpdateChecker.sameChannel("v3.9.0-gpl", v(3, 9, 0), v(3, 8, 3)))
        assertTrue(UpdateChecker.sameChannel("v3.8.4-gpl", v(3, 8, 4), v(3, 8, 3)))
    }
    @Test
    fun `rolling LTS skips an accidentally attached mainline asset`() {
        val json = """[{"tag_name":"lts-3.7","assets":[
            {"name":"Arris-v4.0.4-gpl.apk","browser_download_url":"https://example.invalid/main"},
            {"name":"Arris-v3.7.27-gpl.apk","browser_download_url":"https://example.invalid/lts"}
        ]}]"""
        assertEquals(v(3, 7, 27), UpdateChecker.find(json, v(3, 7, 26))?.version)
        assertNull(UpdateChecker.find(json, v(4, 0, 3)))
    }

    @Test
    fun `malformed release pages must fall back instead of reporting current`() {
        val current = v(4, 0, 4)
        assertNull(evaluateReleases(listOf("[1]"), current))
        assertNull(evaluateReleases(listOf("[]", """[{"tag_name":"v4.0.4-gpl","assets":{}}]"""), current))
        assertEquals(CheckOutcome.UpToDate, evaluateReleases(listOf("[]"), current))
    }
}
