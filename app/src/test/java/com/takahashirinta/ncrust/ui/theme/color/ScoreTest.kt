/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// 上游 java/score/ScoreTest.java 在 main 分支已不存在。本文件断言的是 Score 的**排序语义**
// （不是复刻某个具体分数），全部期望值都由上游常量直接推算并已实测确认：
//   WEIGHT_PROPORTION=0.7 / TARGET_CHROMA=48 / WEIGHT_CHROMA_ABOVE=0.3 / WEIGHT_CHROMA_BELOW=0.1
//   CUTOFF_CHROMA=5 / CUTOFF_EXCITED_PROPORTION=0.01
// 计算示例（等人口 blue vs #E0E0E0，各 100）：
//   蓝  hue≈266 chroma 62.27 -> 0.1*100*0.7 + (62.27-48)*0.3 = 7.00 + 4.28 = 11.28
//   灰  hue≈209 chroma  2.65 -> 0.9*100*0.7 + (2.65-48)*0.1 = 63.0 - 4.54 = 58.46  ← 人口占主导
// 所以"更彩"只在人口相当时才赢；人口 9:1 时近灰色反而排前面 —— 这是上游的设计，不是 bug。

package com.takahashirinta.ncrust.ui.theme.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreTest {

    private val googleBlue = 0xFF4285F4.toInt()
    private val nearGray = 0xFFE0E0E0.toInt()
    private val midGray = 0xFF9E9E9E.toInt()

    private fun mapOfPopulations(vararg pairs: Pair<Int, Int>): Map<Int, Int> {
        val map = LinkedHashMap<Int, Int>()
        for ((color, population) in pairs) {
            map[color] = population
        }
        return map
    }

    /** 人口相同时，彩度合适的颜色排在近灰色之前。 */
    @Test
    fun vibrantColorOutranksNearGrayAtEqualPopulation() {
        val ranked = Score.score(
            mapOfPopulations(googleBlue to 100, nearGray to 100),
            2,
            googleBlue,
            false,
        )
        assertEquals(2, ranked.size)
        assertEquals("Google Blue(chroma 62.3) 必须排在 #E0E0E0(chroma 2.65) 之前", googleBlue, ranked[0])
        assertEquals(nearGray, ranked[1])
    }

    /** 人口占绝对优势时，近灰色可以反超彩色（WEIGHT_PROPORTION = 0.7 的直接后果）。 */
    @Test
    fun populationDominatesChromaWhenOverwhelming() {
        val ranked = Score.score(
            mapOfPopulations(nearGray to 900, googleBlue to 100),
            2,
            googleBlue,
            false,
        )
        assertEquals(nearGray, ranked[0])
        assertEquals(googleBlue, ranked[1])
    }

    /** filter=true 时 chroma < CUTOFF_CHROMA(5) 的颜色被直接剔除。 */
    @Test
    fun filterRemovesColorsBelowChromaCutoff() {
        val ranked = Score.score(
            mapOfPopulations(googleBlue to 100, nearGray to 100),
            4,
            googleBlue,
            true,
        )
        assertEquals(1, ranked.size)
        assertEquals(googleBlue, ranked[0])
    }

    /**
     * 全灰输入 + filter=true：一个都不剩，返回兜底色。
     * 这正是 CoverPaletteExtractor 不能直接用 `score(map, 1)`（filter 默认 true）的原因 ——
     * 否则纯灰封面会被"救"成 Google Blue，灰度降级规则失效。
     */
    @Test
    fun grayOnlyMapWithFilterFallsBackToProvidedColor() {
        val grayOnly = mapOfPopulations(nearGray to 100, midGray to 50)
        assertEquals(listOf(googleBlue), Score.score(grayOnly, 2))
        assertEquals(listOf(midGray), Score.score(grayOnly, 2, midGray, true))
    }

    /**
     * 全灰输入 + filter=false：返回真实灰色。
     * 两个灰的 HCT 色相都是 209.5（floor 后同为 209），色相分散逻辑只会选出一个 ——
     * 期望 2 个也只得到 1 个，这是上游"必须拉开 >= 15 度色相"的规则，不是丢结果。
     */
    @Test
    fun grayOnlyMapWithoutFilterKeepsTheTopGray() {
        val grayOnly = mapOfPopulations(nearGray to 100, midGray to 50)
        val ranked = Score.score(grayOnly, 2, midGray, false)
        assertEquals(1, ranked.size)
        assertEquals(nearGray, ranked[0])
        assertFalse(ranked.contains(googleBlue))
    }

    @Test
    fun emptyMapReturnsFallback() {
        assertEquals(listOf(googleBlue), Score.score(LinkedHashMap(), 1))
        assertEquals(listOf(googleBlue), Score.score(LinkedHashMap(), 4, googleBlue, false))
    }

    /**
     * 与官方 Python 实现（PyPI material-color-utilities 0.2.6）对齐：
     * `prominent_colors_from_array([e53935 x100, 43a047 x60, 1e88e5 x40], 4)`
     * 返回 ['#e53935', '#43a047', '#1e88e5']，顺序也一致。
     */
    @Test
    fun rankingMatchesUpstreamPythonImplementation() {
        val map = mapOfPopulations(
            0xFFE53935.toInt() to 100,
            0xFF43A047.toInt() to 60,
            0xFF1E88E5.toInt() to 40,
        )
        val ranked = Score.score(map, 4)
        assertEquals(
            listOf(0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt()),
            ranked,
        )
        for (color in ranked) {
            assertTrue("Score 返回了输入之外的颜色 0x${Integer.toHexString(color)}", map.containsKey(color))
        }
    }

    /**
     * 8 色合成图（population 分别 400/260/180/120/90/60/300/200）的排序。
     *
     * ## ⚠️ 这里**有意偏离官方 Kotlin 端口**（J15 后半）
     *
     * 官方端口在同一份输入上给出
     *   score4 -> #E53935 #8793AC #3E9C52 #8E24AA
     * score8 -> 上面 4 个 + #FDD835
     * 它那两个"混合色"（#8793AC 人口 680、#3E9C52 人口 320）**不是量化阶段的正确结果**，而是
     * `QuantizerWsmeans` 在 `Arrays.sort` 之后按位置剪枝、读错距离后把 4 个输入色硬合出来的。
     *
     * 本仓库修好剪枝（J15 后半）**和** `maximize` 的 Int 平方溢出（J15 前半）后，
     * 量化输出是 8 个簇 —— 8 个输入原色全部按真实权重还原（旧路径会把深灰与灰合掉）。
     * 排序随之改变（期望值由修复后的真实输出生成）：
     *   score4 -> #E53935 #43A047 #1E88E5 #8E24AA
     *   score8 -> 上面 4 个 + #FDD835 #00897B
     * 偏离点、理由与对照证据见 [QuantizerTest.celebiDeviatesFromOfficialKotlinPortOnEightColorImage]
     * 与 [QuantizerTest.pruningMatchesFullScan]（后者证明关掉剪枝做全量扫描时结果不变）。
     */
    @Test
    fun rankingDeviatesFromOfficialKotlinPortOnEightColorImage() {
        val spec = listOf(
            0xFFE53935.toInt() to 400,
            0xFF43A047.toInt() to 260,
            0xFF1E88E5.toInt() to 180,
            0xFFFDD835.toInt() to 120,
            0xFF8E24AA.toInt() to 90,
            0xFF00897B.toInt() to 60,
            0xFFBDBDBD.toInt() to 300,
            0xFF616161.toInt() to 200,
        )
        val pixels = ArrayList<Int>(1610)
        for ((color, count) in spec) {
            repeat(count) { pixels.add(color) }
        }
        val quantized = QuantizerCelebi.quantize(pixels.toIntArray(), 128)
        assertEquals(
            listOf(0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt()),
            Score.score(quantized, 4),
        )
        assertEquals(
            listOf(
                0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(),
                0xFF8E24AA.toInt(), 0xFFFDD835.toInt(), 0xFF00897B.toInt(),
            ),
            Score.score(quantized, 8),
        )
    }

    @Test
    fun desiredTruncatesRanking() {
        val map = mapOfPopulations(
            0xFFE53935.toInt() to 100,
            0xFF43A047.toInt() to 60,
            0xFF1E88E5.toInt() to 40,
        )
        val top1 = Score.score(map, 1)
        val top2 = Score.score(map, 2)
        assertEquals(1, top1.size)
        assertEquals(2, top2.size)
        assertEquals(top2.first(), top1.first())
    }

    /** 色相靠得很近的一组颜色，即使期望 3 个也只会选出一个（色相分散规则）。 */
    @Test
    fun similarHuesCollapseToOneChoice() {
        val map = mapOfPopulations(
            0xFF4285F4.toInt() to 100,
            0xFF3B82F6.toInt() to 100,
            0xFF1565C0.toInt() to 100,
        )
        assertEquals(1, Score.score(map, 3, googleBlue, false).size)
        assertEquals(1, Score.score(map, 1, googleBlue, false).size)
    }

    /** 色相拉得开的颜色可以同时入选。 */
    @Test
    fun separatedHuesAreBothChosen() {
        val ranked = Score.score(
            mapOfPopulations(0xFFE53935.toInt() to 100, 0xFF43A047.toInt() to 100),
            2,
            googleBlue,
            false,
        )
        assertEquals(2, ranked.size)
        assertEquals(0xFFE53935.toInt(), ranked[0])
        assertEquals(0xFF43A047.toInt(), ranked[1])
    }
}
