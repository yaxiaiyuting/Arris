/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 移植并改写自上游 Ncrust 的 UpdateFixtureTest：夹具换成 Arris 的
 * `vX-gpl` tag 与 `Ncrust-v<version>-gpl-<release|debug>.apk` 资产契约。
 */

package com.takahashirinta.ncrust.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.takahashirinta.ncrust.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `spec/fixtures/update/releases.json` 是 Arris「挑更新」规则的权威答案。
 * 每条用例都断言完整 expect（版本 / 资产 / tag / 页面 / 大小 / 正文），
 * 避免只测版本号而漏掉「挑错资产」这类真实缺陷。
 */
class UpdateFixtureTest {

    private fun cases(): JsonArray =
        JsonParser.parseString(Fixtures.readText("spec/fixtures/update/releases.json")).asJsonArray

    @Test
    fun `all cases match the Arris fixture`() {
        val all = cases()
        var checked = 0
        for (element in all) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            val input = case.getAsJsonObject("input")
            val current = ForkRelease.parseVersionName(input.get("currentVersion").asString)
            assertNotNull("$id: currentVersion 解析失败", current)
            val variant = input.get("variant")?.asString ?: ForkRelease.VARIANT_RELEASE
            val info = UpdateChecker.find(
                input.getAsJsonArray("releases").toString(),
                current,
                variant
            )

            val expect = case.get("expect")
            if (expect.isJsonNull) {
                assertNull("$id: 期望无更新", info)
            } else {
                val e: JsonObject = expect.asJsonObject
                assertNotNull("$id: 期望命中更新", info)
                assertEquals("$id: 版本（显示三段）", e.get("version").asString, info!!.version.toString())
                assertEquals("$id: 资产名", e.get("assetName").asString, info.assetName)
                assertEquals("$id: 资产 URL", e.get("assetUrl").asString, info.assetUrl)
                assertEquals("$id: 资产大小", e.get("assetSize").asLong, info.assetSize)
                assertEquals("$id: tag", e.get("releaseTag").asString, info.releaseTag)
                assertEquals("$id: release 页", e.get("releasePageUrl").asString, info.releasePageUrl)
                assertEquals("$id: 发布日期", e.get("publishedAt").asString, info.publishedAt)
                assertEquals("$id: 正文", e.get("body").asString, info.body)
            }
            checked++
        }
        assertEquals("每条用例都必须跑到", all.size(), checked)
        assertTrue("Arris 用例太少：$checked", checked >= 14)
    }

    @Test
    fun `fixture is reachable`() {
        assertTrue(cases().size() >= 14)
    }
}
