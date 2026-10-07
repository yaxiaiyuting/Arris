/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.3 · 一次性探针：把 v2.5.2（拆分前）全部 8 种语言的**全部可达文案**
 * 逐值导出成 JSON 黄金快照，供拆分后的 `StringsMigrationTest` 做「搬家 ≠ 改文案」比对。
 *
 * ⚠️ 这是一次性工具，不是长期守卫：跑完一次、快照落盘后它就被
 * `StringsMigrationTest` 取代（后者才是常驻用例）。保留它是因为
 * **快照必须由真机之外的同一套运行时产生** —— 手抄 2000 个字符串一定会抄错。
 *
 * 用法（HEAD = v2.5.2）：
 *     ./gradlew :app:testDebugUnitTest --tests '*StringsSnapshotDumpTest*'
 */

package com.takahashirinta.ncrust.ui.i18n

import com.google.gson.GsonBuilder
import org.junit.Test
import java.io.File

class StringsSnapshotDumpTest {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Test
    fun dump_v2_5_2_snapshot() {
        val out = LinkedHashMap<String, Any>()
        out["__meta"] = linkedMapOf(
            "version" to "2.5.2-gpl",
            "note" to "拆分前的全部可达文案。键 = 反射路径（`组.字段` 或扁平属性名）。",
        )
        languagePresets.forEach { preset ->
            out[preset.code] = StringsSnapshot.capture(preset.strings)
        }
        val target = File("src/test/resources/i18n/strings-snapshot-v2.5.2.json")
        target.parentFile.mkdirs()
        target.writeText(gson.toJson(out), Charsets.UTF_8)
        println("SNAPSHOT WRITTEN: ${target.absolutePath} (${target.length()} bytes)")
    }
}
