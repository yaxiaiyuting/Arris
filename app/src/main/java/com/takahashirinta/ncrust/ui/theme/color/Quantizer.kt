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

// 本文件派生自 material-foundation/material-color-utilities（Apache-2.0）。
// 上游源文件：
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/quantize/QuantizerCelebi.java
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/quantize/QuantizerWu.java
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/quantize/QuantizerWsmeans.java
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/quantize/QuantizerMap.java
//   https://github.com/material-foundation/material-color-utilities/blob/main/java/quantize/PointProviderLab.java
// 上游用 QuantizerResult 包一层 Map，这里直接返回 Map<ARGB, 人口>，其余逐行一致
// （5bit 直方图、moments/directions、Wu 的方差切分、Wsmeans 的三角不等式剪枝与 k-means 精修）。
// 确定性：Wsmeans 用 `java.util.Random(0x42688)`（与上游同一个种子、同一个 JDK 算法），
// 所有 Map 都是 LinkedHashMap ⇒ 同样的输入必然得到同样的输出。不得引入 android.* / androidx.*。

package com.takahashirinta.ncrust.ui.theme.color

import java.util.Arrays
import java.util.LinkedHashMap
import java.util.Random

/** 把像素集合压缩成「颜色 -> 像素数」。 */
interface Quantizer {
    /**
     * @param pixels ARGB 像素
     * @param maxColors 目标颜色数；实际可能更少
     * @return ARGB -> 该颜色在输入中出现的像素数
     */
    fun quantize(pixels: IntArray, maxColors: Int): Map<Int, Int>
}

/** 允许量化器切换色彩空间。对应上游 `quantize/PointProvider.java`。 */
interface PointProvider {
    /** sRGB 颜色在该色彩空间中的坐标。 */
    fun fromInt(argb: Int): DoubleArray

    /** 该色彩空间坐标对应的 ARGB。 */
    fun toInt(point: DoubleArray): Int

    /** 两个颜色的**平方**距离（省掉开方，排序结果不变）。 */
    fun distance(a: DoubleArray, b: DoubleArray): Double
}

/** 用 L*a*b* 作为量化空间。对应上游 `quantize/PointProviderLab.java`。 */
object PointProviderLab : PointProvider {

    override fun fromInt(argb: Int): DoubleArray {
        val lab = ColorUtils.labFromArgb(argb)
        return doubleArrayOf(lab[0], lab[1], lab[2])
    }

    override fun toInt(point: DoubleArray): Int =
        ColorUtils.argbFromLab(point[0], point[1], point[2])

    override fun distance(a: DoubleArray, b: DoubleArray): Double {
        val dL = a[0] - b[0]
        val dA = a[1] - b[1]
        val dB = a[2] - b[2]
        return dL * dL + dA * dA + dB * dB
    }
}

/**
 * 只做去重计数，不降维。对应上游 `quantize/QuantizerMap.java`。
 */
object QuantizerMap : Quantizer {
    override fun quantize(pixels: IntArray, maxColors: Int): Map<Int, Int> {
        val pixelByCount = LinkedHashMap<Int, Int>()
        for (pixel in pixels) {
            val currentPixelCount = pixelByCount[pixel]
            val newPixelCount = if (currentPixelCount == null) 1 else currentPixelCount + 1
            pixelByCount[pixel] = newPixelCount
        }
        return pixelByCount
    }
}

/**
 * Wu 量化器：按像素权重递归切分 RGB 立方体。对应上游 `quantize/QuantizerWu.java`。
 *
 * 算法出自 Xiaolin Wu, Graphics Gems II (1991)。
 */
class QuantizerWu : Quantizer {

    private var weights: IntArray = IntArray(0)
    private var momentsR: IntArray = IntArray(0)
    private var momentsG: IntArray = IntArray(0)
    private var momentsB: IntArray = IntArray(0)
    private var moments: DoubleArray = DoubleArray(0)
    private var cubes: Array<Box> = emptyArray()

    override fun quantize(pixels: IntArray, maxColors: Int): Map<Int, Int> {
        // 上游没有这两个前置判断；maxColors<=0 时 cubes[0] 会越界，空输入也切不出任何盒子。
        // 本 App 的调用点永远传正数且非空，这里只是让「绝不抛异常」的约定成立。
        if (pixels.isEmpty() || maxColors <= 0) {
            return emptyMap()
        }
        val mapResult = QuantizerMap.quantize(pixels, maxColors)
        constructHistogram(mapResult)
        createMoments()
        val createBoxesResult = createBoxes(maxColors)
        val colors = createResult(createBoxesResult.resultCount)
        val resultMap = LinkedHashMap<Int, Int>()
        for (color in colors) {
            // 与上游一致：Wu 的输出只提供「初始聚类中心」，人口一律记 0，
            // 真正的像素数由后续 Wsmeans 统计。
            resultMap[color] = 0
        }
        return resultMap
    }

    fun constructHistogram(pixels: Map<Int, Int>) {
        weights = IntArray(TOTAL_SIZE)
        momentsR = IntArray(TOTAL_SIZE)
        momentsG = IntArray(TOTAL_SIZE)
        momentsB = IntArray(TOTAL_SIZE)
        moments = DoubleArray(TOTAL_SIZE)

        for ((pixel, count) in pixels) {
            val red = ColorUtils.redFromArgb(pixel)
            val green = ColorUtils.greenFromArgb(pixel)
            val blue = ColorUtils.blueFromArgb(pixel)
            val bitsToRemove = 8 - INDEX_BITS
            val iR = (red shr bitsToRemove) + 1
            val iG = (green shr bitsToRemove) + 1
            val iB = (blue shr bitsToRemove) + 1
            val index = getIndex(iR, iG, iB)
            weights[index] += count
            momentsR[index] += (red * count)
            momentsG[index] += (green * count)
            momentsB[index] += (blue * count)
            // Kotlin 的 `+=` 在数组元素上找不到 Double.plusAssign，显式写成读-改-写。
            moments[index] = moments[index] +
                (count * ((red * red) + (green * green) + (blue * blue))).toDouble()
        }
    }

    fun createMoments() {
        for (r in 1 until INDEX_COUNT) {
            val area = IntArray(INDEX_COUNT)
            val areaR = IntArray(INDEX_COUNT)
            val areaG = IntArray(INDEX_COUNT)
            val areaB = IntArray(INDEX_COUNT)
            val area2 = DoubleArray(INDEX_COUNT)

            for (g in 1 until INDEX_COUNT) {
                var line = 0
                var lineR = 0
                var lineG = 0
                var lineB = 0
                var line2 = 0.0
                for (b in 1 until INDEX_COUNT) {
                    val index = getIndex(r, g, b)
                    line += weights[index]
                    lineR += momentsR[index]
                    lineG += momentsG[index]
                    lineB += momentsB[index]
                    line2 += moments[index]

                    area[b] += line
                    areaR[b] += lineR
                    areaG[b] += lineG
                    areaB[b] += lineB
                    area2[b] += line2

                    val previousIndex = getIndex(r - 1, g, b)
                    weights[index] = weights[previousIndex] + area[b]
                    momentsR[index] = momentsR[previousIndex] + areaR[b]
                    momentsG[index] = momentsG[previousIndex] + areaG[b]
                    momentsB[index] = momentsB[previousIndex] + areaB[b]
                    moments[index] = moments[previousIndex] + area2[b]
                }
            }
        }
    }

    fun createBoxes(maxColorCount: Int): CreateBoxesResult {
        cubes = Array(maxColorCount) { Box() }
        val volumeVariance = DoubleArray(maxColorCount)
        val firstBox = cubes[0]
        firstBox.r1 = INDEX_COUNT - 1
        firstBox.g1 = INDEX_COUNT - 1
        firstBox.b1 = INDEX_COUNT - 1

        var generatedColorCount = maxColorCount
        var next = 0
        var i = 1
        while (i < maxColorCount) {
            if (cut(cubes[next], cubes[i])) {
                volumeVariance[next] = if (cubes[next].vol > 1) variance(cubes[next]) else 0.0
                volumeVariance[i] = if (cubes[i].vol > 1) variance(cubes[i]) else 0.0
            } else {
                volumeVariance[next] = 0.0
                i--
            }

            next = 0

            var temp = volumeVariance[0]
            for (j in 1..i) {
                if (volumeVariance[j] > temp) {
                    temp = volumeVariance[j]
                    next = j
                }
            }
            if (temp <= 0.0) {
                generatedColorCount = i + 1
                break
            }
            i++
        }

        return CreateBoxesResult(maxColorCount, generatedColorCount)
    }

    fun createResult(colorCount: Int): List<Int> {
        val colors = ArrayList<Int>()
        for (i in 0 until colorCount) {
            val cube = cubes[i]
            val weight = volume(cube, weights)
            if (weight > 0) {
                val r = volume(cube, momentsR) / weight
                val g = volume(cube, momentsG) / weight
                val b = volume(cube, momentsB) / weight
                val color = (255 shl 24) or ((r and 0x0ff) shl 16) or ((g and 0x0ff) shl 8) or (b and 0x0ff)
                colors.add(color)
            }
        }
        return colors
    }

    fun variance(cube: Box): Double {
        val dr = volume(cube, momentsR)
        val dg = volume(cube, momentsG)
        val db = volume(cube, momentsB)
        val xx = moments[getIndex(cube.r1, cube.g1, cube.b1)] -
            moments[getIndex(cube.r1, cube.g1, cube.b0)] -
            moments[getIndex(cube.r1, cube.g0, cube.b1)] +
            moments[getIndex(cube.r1, cube.g0, cube.b0)] -
            moments[getIndex(cube.r0, cube.g1, cube.b1)] +
            moments[getIndex(cube.r0, cube.g1, cube.b0)] +
            moments[getIndex(cube.r0, cube.g0, cube.b1)] -
            moments[getIndex(cube.r0, cube.g0, cube.b0)]

        // J15：`dr/dg/db` 是 Int，`dr * dr` 先在 Int 里算完才参与 Double 运算 ——
        // 通道差上万时三项之和会溢出成负数（Wu 距离被截断 ⇒ 两种颜色被误判为同色）。
        val hypotenuse = dr.toDouble() * dr + dg.toDouble() * dg + db.toDouble() * db
        val cubeVolume = volume(cube, weights)
        return xx - hypotenuse / cubeVolume.toDouble()
    }

    fun cut(one: Box, two: Box): Boolean {
        val wholeR = volume(one, momentsR)
        val wholeG = volume(one, momentsG)
        val wholeB = volume(one, momentsB)
        val wholeW = volume(one, weights)

        val maxRResult = maximize(one, Direction.RED, one.r0 + 1, one.r1, wholeR, wholeG, wholeB, wholeW)
        val maxGResult = maximize(one, Direction.GREEN, one.g0 + 1, one.g1, wholeR, wholeG, wholeB, wholeW)
        val maxBResult = maximize(one, Direction.BLUE, one.b0 + 1, one.b1, wholeR, wholeG, wholeB, wholeW)
        val cutDirection: Direction
        val maxR = maxRResult.maximum
        val maxG = maxGResult.maximum
        val maxB = maxBResult.maximum
        if (maxR >= maxG && maxR >= maxB) {
            if (maxRResult.cutLocation < 0) {
                return false
            }
            cutDirection = Direction.RED
        } else if (maxG >= maxR && maxG >= maxB) {
            cutDirection = Direction.GREEN
        } else {
            cutDirection = Direction.BLUE
        }

        two.r1 = one.r1
        two.g1 = one.g1
        two.b1 = one.b1

        when (cutDirection) {
            Direction.RED -> {
                one.r1 = maxRResult.cutLocation
                two.r0 = one.r1
                two.g0 = one.g0
                two.b0 = one.b0
            }
            Direction.GREEN -> {
                one.g1 = maxGResult.cutLocation
                two.r0 = one.r0
                two.g0 = one.g1
                two.b0 = one.b0
            }
            Direction.BLUE -> {
                one.b1 = maxBResult.cutLocation
                two.r0 = one.r0
                two.g0 = one.g0
                two.b0 = one.b1
            }
        }

        one.vol = (one.r1 - one.r0) * (one.g1 - one.g0) * (one.b1 - one.b0)
        two.vol = (two.r1 - two.r0) * (two.g1 - two.g0) * (two.b1 - two.b0)

        return true
    }

    fun maximize(
        cube: Box,
        direction: Direction,
        first: Int,
        last: Int,
        wholeR: Int,
        wholeG: Int,
        wholeB: Int,
        wholeW: Int,
    ): MaximizeResult {
        val bottomR = bottom(cube, direction, momentsR)
        val bottomG = bottom(cube, direction, momentsG)
        val bottomB = bottom(cube, direction, momentsB)
        val bottomW = bottom(cube, direction, weights)

        var max = 0.0
        var cut = -1

        var halfR: Int
        var halfG: Int
        var halfB: Int
        var halfW: Int
        for (i in first until last) {
            halfR = bottomR + top(cube, direction, i, momentsR)
            halfG = bottomG + top(cube, direction, i, momentsG)
            halfB = bottomB + top(cube, direction, i, momentsB)
            halfW = bottomW + top(cube, direction, i, weights)
            if (halfW == 0) {
                continue
            }

            var tempNumerator = (halfR * halfR + halfG * halfG + halfB * halfB).toDouble()
            var tempDenominator = halfW.toDouble()
            var temp = tempNumerator / tempDenominator

            halfR = wholeR - halfR
            halfG = wholeG - halfG
            halfB = wholeB - halfB
            halfW = wholeW - halfW
            if (halfW == 0) {
                continue
            }

            tempNumerator = (halfR * halfR + halfG * halfG + halfB * halfB).toDouble()
            tempDenominator = halfW.toDouble()
            temp += (tempNumerator / tempDenominator)

            if (temp > max) {
                max = temp
                cut = i
            }
        }
        return MaximizeResult(cut, max)
    }

    /** Direction of the cut, RED/GREEN/BLUE. */
    enum class Direction { RED, GREEN, BLUE }

    /** 切分结果：cutLocation < 0 表示不可切。 */
    class MaximizeResult(var cutLocation: Int, var maximum: Double)

    class CreateBoxesResult(val requestedCount: Int, val resultCount: Int)

    /** RGB 立方体中的一个盒子（含 5bit 直方图下标范围）。 */
    class Box {
        var r0 = 0
        var r1 = 0
        var g0 = 0
        var g1 = 0
        var b0 = 0
        var b1 = 0
        var vol = 0
    }

    companion object {
        // 直方图是 RGB 立方体。若用 8bit，体积是 1600 万，太大；业界惯例是每通道取 5bit，
        // 直方图缩小到 ~32000（上游注释同样是这个理由）。
        private const val INDEX_BITS = 5
        private const val INDEX_COUNT = 33 // ((1 << INDEX_BITS) + 1)
        private const val TOTAL_SIZE = 35937 // INDEX_COUNT * INDEX_COUNT * INDEX_COUNT

        fun getIndex(r: Int, g: Int, b: Int): Int =
            (r shl (INDEX_BITS * 2)) + (r shl (INDEX_BITS + 1)) + r + (g shl INDEX_BITS) + g + b

        fun volume(cube: Box, moment: IntArray): Int =
            moment[getIndex(cube.r1, cube.g1, cube.b1)] -
                moment[getIndex(cube.r1, cube.g1, cube.b0)] -
                moment[getIndex(cube.r1, cube.g0, cube.b1)] +
                moment[getIndex(cube.r1, cube.g0, cube.b0)] -
                moment[getIndex(cube.r0, cube.g1, cube.b1)] +
                moment[getIndex(cube.r0, cube.g1, cube.b0)] +
                moment[getIndex(cube.r0, cube.g0, cube.b1)] -
                moment[getIndex(cube.r0, cube.g0, cube.b0)]

        fun bottom(cube: Box, direction: Direction, moment: IntArray): Int = when (direction) {
            Direction.RED ->
                -moment[getIndex(cube.r0, cube.g1, cube.b1)] +
                    moment[getIndex(cube.r0, cube.g1, cube.b0)] +
                    moment[getIndex(cube.r0, cube.g0, cube.b1)] -
                    moment[getIndex(cube.r0, cube.g0, cube.b0)]
            Direction.GREEN ->
                -moment[getIndex(cube.r1, cube.g0, cube.b1)] +
                    moment[getIndex(cube.r1, cube.g0, cube.b0)] +
                    moment[getIndex(cube.r0, cube.g0, cube.b1)] -
                    moment[getIndex(cube.r0, cube.g0, cube.b0)]
            Direction.BLUE ->
                -moment[getIndex(cube.r1, cube.g1, cube.b0)] +
                    moment[getIndex(cube.r1, cube.g0, cube.b0)] +
                    moment[getIndex(cube.r0, cube.g1, cube.b0)] -
                    moment[getIndex(cube.r0, cube.g0, cube.b0)]
        }

        fun top(cube: Box, direction: Direction, position: Int, moment: IntArray): Int =
            when (direction) {
                Direction.RED ->
                    (moment[getIndex(position, cube.g1, cube.b1)] -
                        moment[getIndex(position, cube.g1, cube.b0)] -
                        moment[getIndex(position, cube.g0, cube.b1)] +
                        moment[getIndex(position, cube.g0, cube.b0)])
                Direction.GREEN ->
                    (moment[getIndex(cube.r1, position, cube.b1)] -
                        moment[getIndex(cube.r1, position, cube.b0)] -
                        moment[getIndex(cube.r0, position, cube.b1)] +
                        moment[getIndex(cube.r0, position, cube.b0)])
                Direction.BLUE ->
                    (moment[getIndex(cube.r1, cube.g1, position)] -
                        moment[getIndex(cube.r1, cube.g0, position)] -
                        moment[getIndex(cube.r0, cube.g1, position)] +
                        moment[getIndex(cube.r0, cube.g0, position)])
            }
    }
}

/**
 * Wsmeans（Weighted Square Means）：带三角不等式剪枝的 k-means。
 *
 * 算法出自 M. Emre Celebi, "Improving the Performance of K-Means for Color Quantization" (2011),
 * https://arxiv.org/abs/1101.0395 。对应上游 `quantize/QuantizerWsmeans.java`。
 */
object QuantizerWsmeans {

    private const val MAX_ITERATIONS = 10
    private const val MIN_MOVEMENT_DISTANCE = 3.0

    /** 可排序的距离项；上游用 `Arrays.sort`（稳定排序）决定剪枝顺序，这里保持一致。 */
    private class Distance : Comparable<Distance> {
        var index = -1
        var distance = -1.0

        override fun compareTo(other: Distance): Int = this.distance.compareTo(other.distance)
    }

    /**
     * @param inputPixels ARGB 像素
     * @param startingClusters 初始聚类中心（传 Wu 的结果质量更好）；传空数组时上游实现不可用（见下方注释）
     * @param maxColors 目标颜色数
     * @return ARGB -> 像素数
     */
    fun quantize(
        inputPixels: IntArray,
        startingClusters: IntArray,
        maxColors: Int,
    ): Map<Int, Int> {
        // 空输入时 clusterCount 会是 0，`random.nextInt(0)` 会抛异常；上游没有这个判断。
        if (inputPixels.isEmpty() || maxColors <= 0) {
            return emptyMap()
        }
        // Uses a seeded random number generator to ensure consistent results.
        // 必须用 java.util.Random（Kotlin 的 kotlin.random.Random 是不同的算法，会改变聚类结果）。
        val random = Random(0x42688)

        val pixelToCount = LinkedHashMap<Int, Int>()
        val points = arrayOfNulls<DoubleArray>(inputPixels.size)
        val pixels = IntArray(inputPixels.size)
        val pointProvider: PointProvider = PointProviderLab

        var pointCount = 0
        for (inputPixel in inputPixels) {
            val pixelCount = pixelToCount[inputPixel]
            if (pixelCount == null) {
                points[pointCount] = pointProvider.fromInt(inputPixel)
                pixels[pointCount] = inputPixel
                pointCount++

                pixelToCount[inputPixel] = 1
            } else {
                pixelToCount[inputPixel] = pixelCount + 1
            }
        }

        val counts = IntArray(pointCount)
        for (i in 0 until pointCount) {
            val pixel = pixels[i]
            val count = pixelToCount[pixel]
            counts[i] = count ?: 0
        }

        var clusterCount = minOf(maxColors, pointCount)
        if (startingClusters.isNotEmpty()) {
            clusterCount = minOf(clusterCount, startingClusters.size)
        }

        val clusters = Array(clusterCount) { doubleArrayOf(0.0, 0.0, 0.0) }
        var clustersCreated = 0
        for (i in 0 until minOf(startingClusters.size, clusterCount)) {
            clusters[i] = pointProvider.fromInt(startingClusters[i])
            clustersCreated++
        }

        val additionalClustersNeeded = clusterCount - clustersCreated
        if (additionalClustersNeeded > 0) {
            // 上游这里是一个空循环，clusters[i] 保持 null，后续 `clusters[previousClusterIndex]`
            // 必然 NPE —— 也就是说上游在 startingClusters 为空时本身不可用。
            // 本移植做最小修复：用前若干个去重后的像素点补位，仍然由输入唯一决定，不引入随机性。
            // 本 App 的调用链（QuantizerCelebi）永远传入非空的 Wu 结果，此分支不会被执行。
            for (i in 0 until additionalClustersNeeded) {
                clusters[clustersCreated + i] =
                    if (i < pointCount) points[i]!! else doubleArrayOf(0.0, 0.0, 0.0)
            }
        }

        val clusterIndices = IntArray(pointCount)
        for (i in 0 until pointCount) {
            clusterIndices[i] = random.nextInt(clusterCount)
        }

        val indexMatrix = Array(clusterCount) { IntArray(clusterCount) }

        val distanceToIndexMatrix = Array(clusterCount) { Array(clusterCount) { Distance() } }

        val pixelCountSums = IntArray(clusterCount)
        for (iteration in 0 until MAX_ITERATIONS) {
            for (i in 0 until clusterCount) {
                for (j in i + 1 until clusterCount) {
                    val distance = pointProvider.distance(clusters[i], clusters[j])
                    distanceToIndexMatrix[j][i].distance = distance
                    distanceToIndexMatrix[j][i].index = i
                    distanceToIndexMatrix[i][j].distance = distance
                    distanceToIndexMatrix[i][j].index = j
                }
                Arrays.sort(distanceToIndexMatrix[i])
                for (j in 0 until clusterCount) {
                    indexMatrix[i][j] = distanceToIndexMatrix[i][j].index
                }
            }

            var pointsMoved = 0
            for (i in 0 until pointCount) {
                val point = points[i]!!
                val previousClusterIndex = clusterIndices[i]
                val previousCluster = clusters[previousClusterIndex]
                val previousDistance = pointProvider.distance(point, previousCluster)

                var minimumDistance = previousDistance
                var newClusterIndex = -1
                for (j in 0 until clusterCount) {
                    // 三角不等式剪枝：距离已经 >= 4 倍当前距离的中心不可能更近。
                    // J15（后半）：官方 MCU 端口同样在 `Arrays.sort` 之后按位置 j 剪枝。
                    // 这里**保持与官方端口逐字节一致**（QuantizerTest/ScoreTest 是 parity 守卫）；
                    // 偏离它属于产品决策，不是单方面"修 bug"。
                    if (distanceToIndexMatrix[previousClusterIndex][j].distance >= 4 * previousDistance) {
                        continue
                    }
                    val distance = pointProvider.distance(point, clusters[j])
                    if (distance < minimumDistance) {
                        minimumDistance = distance
                        newClusterIndex = j
                    }
                }
                if (newClusterIndex != -1) {
                    val distanceChange =
                        Math.abs(Math.sqrt(minimumDistance) - Math.sqrt(previousDistance))
                    if (distanceChange > MIN_MOVEMENT_DISTANCE) {
                        pointsMoved++
                        clusterIndices[i] = newClusterIndex
                    }
                }
            }

            if (pointsMoved == 0 && iteration != 0) {
                break
            }

            val componentASums = DoubleArray(clusterCount)
            val componentBSums = DoubleArray(clusterCount)
            val componentCSums = DoubleArray(clusterCount)
            pixelCountSums.fill(0)
            for (i in 0 until pointCount) {
                val clusterIndex = clusterIndices[i]
                val point = points[i]!!
                val count = counts[i]
                pixelCountSums[clusterIndex] += count
                componentASums[clusterIndex] += (point[0] * count)
                componentBSums[clusterIndex] += (point[1] * count)
                componentCSums[clusterIndex] += (point[2] * count)
            }

            for (i in 0 until clusterCount) {
                val count = pixelCountSums[i]
                if (count == 0) {
                    clusters[i] = doubleArrayOf(0.0, 0.0, 0.0)
                    continue
                }
                val a = componentASums[i] / count
                val b = componentBSums[i] / count
                val c = componentCSums[i] / count
                clusters[i][0] = a
                clusters[i][1] = b
                clusters[i][2] = c
            }
        }

        val argbToPopulation = LinkedHashMap<Int, Int>()
        for (i in 0 until clusterCount) {
            val count = pixelCountSums[i]
            if (count == 0) {
                continue
            }

            val possibleNewCluster = pointProvider.toInt(clusters[i])
            if (argbToPopulation.containsKey(possibleNewCluster)) {
                continue
            }

            argbToPopulation[possibleNewCluster] = count
        }

        return argbToPopulation
    }
}

/**
 * 用 Wu 的结果做 k-means 初始中心，再交给 Wsmeans 精修。
 * 对应上游 `quantize/QuantizerCelebi.java`。
 */
object QuantizerCelebi {
    /**
     * @param pixels ARGB 像素
     * @param maxColors 目标颜色数
     * @return ARGB -> 像素数
     */
    fun quantize(pixels: IntArray, maxColors: Int): Map<Int, Int> {
        val wu = QuantizerWu()
        val wuResult = wu.quantize(pixels, maxColors)
        // LinkedHashMap 的迭代顺序 = 插入顺序 ⇒ 交给 Wsmeans 的初始中心顺序稳定。
        val wuClusters = wuResult.keys.toIntArray()
        return QuantizerWsmeans.quantize(pixels, wuClusters, maxColors)
    }
}
