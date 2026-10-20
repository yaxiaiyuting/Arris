/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 移植自上游 Ncrust 3e462c4 的流程规则测试，资产名 / tag 改成 Arris 契约，
 * 并补上 release/debug 通道与 vX-gpl 版本解析。
 */

package com.takahashirinta.ncrust.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class UpdateFlowRulesTest {

    private fun info(
        version: String,
        assetId: String? = null,
        url: String = "https://example.invalid/$version.apk",
        variant: String = ForkRelease.VARIANT_RELEASE
    ) = UpdateInfo(
        version = AppVersion.parseOrNull(version)!!,
        assetName = "Ncrust-v$version-gpl-$variant.apk",
        assetUrl = url,
        assetSize = 100L,
        releaseTag = "v$version-gpl",
        releasePageUrl = "https://example.invalid/v$version-gpl",
        publishedAt = "2026-10-21T09:30:00Z",
        body = null,
        assetId = assetId
    )

    // ---------- 版本 / 资产名契约 ----------

    @Test
    fun `parses the fork version name and tag`() {
        assertEquals(AppVersion(3, 4, 12), ForkRelease.parseVersionName("3.4.12-gpl"))
        assertEquals(AppVersion(3, 4, 13), ForkRelease.parseTag("v3.4.13-gpl"))
        assertEquals(AppVersion(3, 4, 13, 1), ForkRelease.parseTag("v3.4.13.1-gpl"))
        assertEquals(AppVersion(3, 5), ForkRelease.parseTag("v3.5-gpl"))
        assertNull(ForkRelease.parseTag("3.4.12"))
        assertNull(ForkRelease.parseTag("v3.4.12-gpl-debug"))
    }

    @Test
    fun `parses fork release and debug assets`() {
        assertEquals(
            ForkRelease.Asset(AppVersion(3, 4, 13), ForkRelease.VARIANT_RELEASE),
            ForkRelease.parseAsset("Ncrust-v3.4.13-gpl-release.apk")
        )
        assertEquals(
            ForkRelease.Asset(AppVersion(3, 4, 13), ForkRelease.VARIANT_DEBUG),
            ForkRelease.parseAsset("Ncrust-v3.4.13-gpl-debug.apk")
        )
        assertNull("校验文件不是 APK", ForkRelease.parseAsset("Ncrust-v3.4.13-gpl-release.apk.sha256"))
        assertNull("上游命名不适用", ForkRelease.parseAsset("ncrust-android-universal-3.4.13.apk"))
        assertNull("非法版本段", ForkRelease.parseAsset("Ncrust-v3.4.x-gpl-release.apk"))
    }

    // ---------- 恢复落盘结果（升级后重启不得再提示「更新到当前版本」） ----------

    @Test
    fun `restore keeps only a strictly higher found version`() {
        val candidate = info("3.4.13")
        assertEquals(candidate, UpdateRecovery.restore(candidate, AppVersion(3, 4, 12)))
        assertNull("与当前相同必须丢弃", UpdateRecovery.restore(candidate, AppVersion(3, 4, 13)))
        assertNull("低于当前必须丢弃", UpdateRecovery.restore(candidate, AppVersion(3, 5, 0)))
        assertNull("没有落盘结果", UpdateRecovery.restore(null, AppVersion(3, 4, 12)))
        assertNull("当前版本解析失败时宁可不动", UpdateRecovery.restore(candidate, null))
    }

    // ---------- 稳定目标标识 / 页面按目标门控 ----------

    @Test
    fun `target key prefers the asset id and falls back to the url`() {
        assertEquals("42", info("3.4.13", assetId = "42").targetKey)
        assertEquals(
            "https://example.invalid/3.4.13.apk",
            info("3.4.13").targetKey
        )
    }

    @Test
    fun `matches target compares the stable identity`() {
        assertTrue(info("3.4.13", assetId = "42").matchesTarget(info("3.4.13", assetId = "42")))
        assertFalse(info("3.4.13", assetId = "42").matchesTarget(info("3.4.13", assetId = "43")))
    }

    @Test
    fun `for target hides ready downloading and failed states of other targets`() {
        val file = Files.createTempFile("arris-flow", ".apk").toFile().apply { writeText("apk") }
        val mine = info("3.4.13", assetId = "mine")
        try {
            val ready = DownloadState.Ready(file, mine)
            assertEquals(ready, ready.forTarget("mine"))
            assertEquals(DownloadState.Idle, ready.forTarget("other"))
            assertEquals(
                "文件不在了也不能认",
                DownloadState.Idle,
                DownloadState.Ready(file.apply { delete() }, mine).forTarget("mine")
            )

            val downloading = DownloadState.Downloading(10L, 100L, "mine")
            assertEquals(downloading, downloading.forTarget("mine"))
            assertEquals(DownloadState.Idle, downloading.forTarget("other"))

            assertEquals(DownloadState.Verifying("mine"), DownloadState.Verifying("mine").forTarget("mine"))
            assertEquals(DownloadState.Idle, DownloadState.Verifying("mine").forTarget("other"))

            val failed = DownloadState.Failed(FailureReason.NETWORK, "mine")
            assertEquals(failed, failed.forTarget("mine"))
            assertEquals(DownloadState.Idle, failed.forTarget("other"))

            assertEquals(DownloadState.Idle, DownloadState.Idle.forTarget("mine"))
        } finally {
            file.delete()
        }
    }

    // ---------- Content-Range 解析 ----------

    @Test
    fun `parses content range start end and total`() {
        assertEquals(ContentRange(400, 999, 1000), parseContentRange("bytes 400-999/1000"))
        assertEquals(ContentRange(400, 999, 1000), parseContentRange("  bytes 400-999/1000  "))
        assertEquals(ContentRange(0, 99, -1), parseContentRange("bytes 0-99/*"))
    }

    @Test
    fun `rejects malformed content range`() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange(""))
        assertNull(parseContentRange("bytes 400-999"))
        assertNull(parseContentRange("items 400-999/1000"))
        assertNull(parseContentRange("bytes abc-999/1000"))
    }

    // ---------- 续传元数据 ----------

    @Test
    fun `resume marker round trips through encode and decode`() {
        val marker = ResumeMarker("https://example.invalid/a.apk", "asset-9", 1234L, "W/\"abc\"")
        assertEquals(marker, ResumeMarker.decode(marker.encode()))
    }

    @Test
    fun `resume marker decode rejects incomplete or wrong version text`() {
        assertNull(ResumeMarker.decode(null))
        assertNull(ResumeMarker.decode(""))
        assertNull(ResumeMarker.decode("version=2\nurl=u\nidentity=i\nsize=1\netag="))
        assertNull(ResumeMarker.decode("version=1\nidentity=i\nsize=1\netag="))
        assertNull(ResumeMarker.decode("version=1\nurl=u\nsize=1\netag="))
    }

    @Test
    fun `resume marker matches only the same identity and consistent size`() {
        val marker = ResumeMarker("u", "id", 1000L, null)
        assertTrue(marker.matches("u", "id", 1000L))
        assertTrue("一方不知道总长时不作为否决条件", marker.matches("u", "id", 0L))
        assertFalse(marker.matches("u2", "id", 1000L))
        assertFalse(marker.matches("u", "id2", 1000L))
        assertFalse(marker.matches("u", "id", 999L))
    }

    // ---------- 分页 Link 解析 ----------

    @Test
    fun `next page url follows the rel next link`() {
        val header =
            "<https://api.github.com/repositories/1/releases?page=2>; rel=\"next\", " +
                "<https://api.github.com/repositories/1/releases?page=5>; rel=\"last\""
        assertEquals(
            "https://api.github.com/repositories/1/releases?page=2",
            nextPageUrl(header)
        )
    }

    @Test
    fun `next page url is null without a next link`() {
        assertNull(nextPageUrl(null))
        assertNull(nextPageUrl(""))
        assertNull(nextPageUrl("<https://api.github.com/x?page=5>; rel=\"last\""))
    }

    // ---------- 合并多页与「解析失败不是已最新」 ----------

    private fun release(tag: String, name: String, size: Long) =
        """{"tag_name":"$tag","html_url":"https://example.invalid/$tag","draft":false,""" +
            """"prerelease":false,"published_at":"2026-10-21T09:30:00Z","body":"$tag",""" +
            """"assets":[{"name":"$name","browser_download_url":"https://example.invalid/$name","size":$size}]}"""

    @Test
    fun `evaluate releases takes the highest across all pages`() {
        val page1 = "[${release("v3.4.13-gpl", "Ncrust-v3.4.13-gpl-release.apk", 100L)}]"
        val page2 = "[${release("v3.5.0-gpl", "Ncrust-v3.5.0-gpl-release.apk", 200L)}]"

        val outcome = evaluateReleases(listOf(page1, page2), AppVersion(3, 4, 12))
        assertTrue(outcome is CheckOutcome.Found)
        assertEquals(AppVersion(3, 5, 0), (outcome as CheckOutcome.Found).info.version)
    }

    @Test
    fun `evaluate releases filters the requested variant`() {
        val page = "[${release("v3.4.13-gpl", "Ncrust-v3.4.14-gpl-debug.apk", 200L)}]"
        assertEquals(
            CheckOutcome.UpToDate,
            evaluateReleases(listOf(page), AppVersion(3, 4, 12), ForkRelease.VARIANT_RELEASE)
        )
        val debug = evaluateReleases(listOf(page), AppVersion(3, 4, 12), ForkRelease.VARIANT_DEBUG)
        assertTrue(debug is CheckOutcome.Found)
        assertEquals(AppVersion(3, 4, 14), (debug as CheckOutcome.Found).info.version)
    }

    @Test
    fun `evaluate releases reports up to date when nothing is higher`() {
        val page = "[${release("v3.4.12-gpl", "Ncrust-v3.4.12-gpl-release.apk", 100L)}]"
        assertEquals(CheckOutcome.UpToDate, evaluateReleases(listOf(page), AppVersion(3, 4, 12)))
    }

    @Test
    fun `evaluate releases returns null on unparseable pages so it is never treated as up to date`() {
        val current = AppVersion(3, 4, 12)
        assertNull(evaluateReleases(listOf("not json"), current))
        assertNull(evaluateReleases(listOf("{}"), current))
        assertNull(evaluateReleases(listOf(""), current))
        assertNull(evaluateReleases(listOf("[{}]", "garbage"), current))
        assertNull("当前版本解析失败也判为不可判定", evaluateReleases(listOf("[{}]"), null))
    }

    // ---------- UpdateChecker 解析资产 id ----------

    @Test
    fun `checker keeps the asset id as the stable identity`() {
        val json = """[{"tag_name":"v3.4.13-gpl","html_url":"h","draft":false,"prerelease":false,
            |"published_at":"p","body":"b","assets":[
            |{"id":98765,"name":"Ncrust-v3.4.13-gpl-release.apk",
            |"browser_download_url":"https://example.invalid/3.4.13.apk","size":10}]}]""".trimMargin()
        val found = UpdateChecker.find(json, AppVersion(3, 4, 12))
        assertEquals("98765", found?.assetId)
        assertEquals("98765", found?.targetKey)
    }
}
