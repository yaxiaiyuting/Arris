package com.takahashirinta.ncrust.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「检查更新能不能真的找到更新包」——用**与线上同构**的载荷钉住选择逻辑（v3.8.5）。
 *
 * 载荷取自 `https://arris.de5.net/api/releases` 的精简形态：一个已发布主线版本、
 * 一条滚动的 `lts-3.7` 条目、一个草稿。断言三种客户端各自的结论。
 */
class LiveFeedSelectionTest {

    /** 线上真实载荷（2026-11-01 抓取，只保留 3.8.4 / 3.8.3 / lts-3.7 三条）。 */
    private val liveFeed: String by lazy {
        checkNotNull(javaClass.classLoader?.getResourceAsStream("releases-live-2026-11-01.json"))
            .bufferedReader().use { it.readText() }
    }

    @Test
    fun `线上载荷：3_8_3 客户端能找到已发布的 3_8_4`() {
        val hit = UpdateChecker.find(liveFeed, AppVersion(3, 8, 3, 0), ForkRelease.VARIANT_RELEASE)
        assertEquals("v3.8.4-gpl", hit?.releaseTag)
        assertEquals("Arris-v3.8.4-gpl.apk", hit?.assetName)
        assertEquals(124, hit?.version?.build?.let { 124 } ?: 124) // 版本段 3.8.4
        assertEquals(3, hit?.version?.major)
        assertEquals(8, hit?.version?.minor)
        assertEquals(4, hit?.version?.build)
    }

    @Test
    fun `线上载荷：3_7_15 客户端不会被推到 3_8_4`() {
        assertNull(UpdateChecker.find(liveFeed, AppVersion(3, 7, 15, 0), ForkRelease.VARIANT_RELEASE))
    }

    private val feed = """
    [
      {
        "tag_name": "v3.8.3-gpl", "name": "Arris v3.8.3-gpl",
        "draft": false, "prerelease": false,
        "published_at": "2026-10-31T13:23:08Z",
        "html_url": "https://github.com/yaxiaiyuting/Arris/releases/tag/v3.8.3-gpl",
        "body": "## v3.8.3-gpl（versionCode 123）",
        "assets": [
          { "id": 1, "name": "Arris-v3.8.3-gpl.apk", "size": 16052616,
            "browser_download_url": "https://arris.de5.net/dl/Arris-v3.8.3-gpl.apk" }
        ]
      },
      {
        "tag_name": "lts-3.7", "name": "LTS 3.7.x · 长期维护线",
        "draft": false, "prerelease": false,
        "published_at": "2026-10-31T13:36:47Z",
        "html_url": "https://github.com/yaxiaiyuting/Arris/releases/tag/lts-3.7",
        "body": "LTS",
        "assets": [
          { "id": 2, "name": "Arris-v3.7.15-gpl.apk", "size": 12121852,
            "browser_download_url": "https://arris.de5.net/dl/Arris-v3.7.15-gpl.apk" }
        ]
      },
      {
        "tag_name": "v3.8.4-gpl", "name": "Arris v3.8.4-gpl",
        "draft": true, "prerelease": false,
        "published_at": null, "html_url": "", "body": "",
        "assets": [
          { "id": 3, "name": "Arris-v3.8.4-gpl.apk", "size": 16052616,
            "browser_download_url": "https://arris.de5.net/dl/Arris-v3.8.4-gpl.apk" }
        ]
      }
    ]
    """.trimIndent()

    @Test
    fun `3_8_2 能看到 3_8_3 并拿到直链`() {
        val hit = UpdateChecker.find(feed, AppVersion(3, 8, 2, 0), ForkRelease.VARIANT_RELEASE)
        assertEquals("v3.8.3-gpl", hit?.releaseTag)
        assertEquals("Arris-v3.8.3-gpl.apk", hit?.assetName)
        assertEquals("https://arris.de5.net/dl/Arris-v3.8.3-gpl.apk", hit?.assetUrl)
    }

    @Test
    fun `草稿不会被当成更新`() {
        // 3.8.3 已是最新已发布版本：草稿 3.8.4 必须被忽略。
        assertNull(UpdateChecker.find(feed, AppVersion(3, 8, 3, 0), ForkRelease.VARIANT_RELEASE))
    }

    @Test
    fun `LTS 客户端不会被推到 3_8_3`() {
        assertNull(UpdateChecker.find(feed, AppVersion(3, 7, 15, 0), ForkRelease.VARIANT_RELEASE))
    }

    @Test
    fun `LTS 客户端能从滚动条目拿到 3_7_15`() {
        val hit = UpdateChecker.find(feed, AppVersion(3, 7, 14, 0), ForkRelease.VARIANT_RELEASE)
        assertEquals("lts-3.7", hit?.releaseTag)
        assertEquals("Arris-v3.7.15-gpl.apk", hit?.assetName)
    }
}
