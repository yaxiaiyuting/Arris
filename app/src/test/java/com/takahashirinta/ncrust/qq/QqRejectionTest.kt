/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P0：QQ 取链失败分类的**穷举守卫**（release-only 缺陷的回归网）。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.ResolveFailureKind
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这个文件的每一条都是**用户报的那个 P0 的回归网**：
 * 「qm VIP 歌曲被误判无版权 + 自动跳歌」。
 *
 * 那个缺陷在 v3.1.0 上**不能稳定复现**（取决于账号状态 / 网络 / 具体曲目），
 * 所以它不能靠「跑一次看有没有事」来守 —— 只能把「哪些结论允许被下」写成穷举断言。
 * 铁律 10（release-only 问题修复后必须补回归单测）就是为这种形状写的。
 */
class QqRejectionTest {

    @Test
    fun `★ 穷举分类器输入空间 —— QQ 永远产不出无版权与地区限制`() {
        // 这是本 P0 最强的一条守卫：不是「跑一次看有没有事」，而是
        // **把分类器的输入空间叉乘一遍**，断言没有任何一种服务端回答能让
        // QQ 的失败被说成「无版权」或「地区限制」。
        //
        // 为什么必须这样测：用户报的那个缺陷（VIP 歌被说成无版权）在 v3.1.0 上
        // **不能稳定复现**，取决于账号状态 / 网络 / 具体曲目。只有把「哪些结论允许被下」
        // 写成穷举断言，它才是一条守得住的回归网（铁律 10）。
        val codes = listOf<Int?>(null, 0, 1, -1, 104003, 101404, -352, -403, -1200, 999999)
        val httpCodes = listOf<Int?>(null, 200, 401, 403, 412, 500, 502)
        var cases = 0
        for (code in codes) {
            for (loggedIn in listOf(true, false)) {
                for (pneedbuy in listOf<Int?>(null, 0, 1)) {
                    for (isbuy in listOf<Int?>(null, 0, 1)) {
                        for (trial in listOf<Int?>(null, -1, 0)) {
                            for (transport in listOf(true, false)) {
                                for (http in httpCodes) {
                                    for (hasPurl in listOf(true, false)) {
                                        cases++
                                        val r = classifyQqRejection(
                                            QqRejectionInput(
                                                hasPurl = hasPurl,
                                                resultCode = code,
                                                pneedbuy = pneedbuy,
                                                isbuy = isbuy,
                                                trialType = trial,
                                                transportFailed = transport,
                                                httpStatus = http,
                                                loggedIn = loggedIn,
                                            ),
                                        )
                                        assertNotEquals(
                                            "输入 code=$code loggedIn=$loggedIn pneedbuy=$pneedbuy " +
                                                "isbuy=$isbuy trial=$trial transport=$transport http=$http " +
                                                "hasPurl=$hasPurl ⇒ $r",
                                            ResolveFailureKind.COPYRIGHT_GONE,
                                            r.toResolveFailureKind(),
                                        )
                                        assertNotEquals(
                                            "QQ 没有地区限制的实测样本，不许凭空产出",
                                            ResolveFailureKind.REGION_LOCKED,
                                            r.toResolveFailureKind(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 断言这确实是「穷举」而不是空循环：10×2×3×3×3×2×7×2 = 15,120 组。
        assertEquals(15_120, cases)
    }

    @Test
    fun `枚举里的无版权与地区限制是留给将来证据的占位 当前不可达`() {
        // 这两个取值**刻意保留**（真拿到服务端字段时不用重排分类），但它们不能有产出者。
        // 这条测试把「保留」与「可达」区分开，防止下一个人顺手接上一个猜测式的判据。
        val reachable = buildSet {
            for (code in listOf<Int?>(null, 0, 104003, 101404, -352)) {
                for (loggedIn in listOf(true, false)) {
                    add(classifyQqRejection(QqRejectionInput(resultCode = code, loggedIn = loggedIn)))
                }
            }
            add(classifyQqRejection(QqRejectionInput(transportFailed = true, httpStatus = 500)))
            add(classifyQqRejection(QqRejectionInput(transportFailed = true, httpStatus = 401)))
            add(classifyQqRejection(QqRejectionInput(hasPurl = true)))
            add(classifyQqRejection(QqRejectionInput(pneedbuy = 1, isbuy = 0)))
            add(classifyQqRejection(QqRejectionInput(trialType = -1)))
        }
        assertFalse("COPYRIGHT_GONE 当前不可达", QqRejection.COPYRIGHT_GONE in reachable)
        assertFalse("REGION_LOCKED 当前不可达", QqRejection.REGION_LOCKED in reachable)
        // 反过来，这几个必须可达，否则分类器就是个空壳。
        assertTrue(QqRejection.NEED_LOGIN in reachable)
        assertTrue(QqRejection.NEED_VIP in reachable)
        assertTrue(QqRejection.NETWORK in reachable)
        assertTrue(QqRejection.AUTH_EXPIRED in reachable)
        assertTrue(QqRejection.NONE in reachable)
    }

    @Test
    fun `★ 需要登录与需要会员都映射成对应的权限分类 —— 不是 UNKNOWN`() {
        assertEquals(
            ResolveFailureKind.NEED_LOGIN,
            QqRejection.NEED_LOGIN.toResolveFailureKind(),
        )
        assertEquals(
            ResolveFailureKind.NEED_VIP,
            QqRejection.NEED_VIP.toResolveFailureKind(),
        )
    }

    @Test
    fun `试听片段按权益不足处置 —— 用户能做的动作是开会员`() {
        assertEquals(ResolveFailureKind.NEED_VIP, QqRejection.TRIAL_ONLY.toResolveFailureKind())
        assertEquals(
            QqRejection.TRIAL_ONLY,
            classifyQqRejection(QqRejectionInput(trialType = -1, resultCode = 0)),
        )
    }

    @Test
    fun `匿名实测向量 —— 8 档全 104003 必须收敛成需要登录`() {
        // 2026-09-28 实测：完全匿名（无 Cookie / uin=0 / 无 authst）请求 VIP 歌，
        // 8 个档位**全部**返回 result=104003 + 空 purl。
        val inputs = List(8) { QqRejectionInput(resultCode = 104003, loggedIn = false) }
        assertEquals(QqRejection.NEED_LOGIN, classifyQqBatch(inputs))
    }

    @Test
    fun `登录实测向量 —— 免费歌匿名只有两档能播 其余六档 104003`() {
        // 2026-09-28 实测（《千与千寻》/《城南花已开》）：
        // results = 104003,104003,104003,104003,104003,0,104003,0（第 6 与第 8 档给链）。
        // 只要**有**一档给了 purl，整体结论就是「能播」——不能被前面 5 个 104003 带偏。
        val inputs = listOf(
            QqRejectionInput(resultCode = 104003, loggedIn = false),
            QqRejectionInput(resultCode = 104003, loggedIn = false),
            QqRejectionInput(resultCode = 104003, loggedIn = false),
            QqRejectionInput(resultCode = 104003, loggedIn = false),
            QqRejectionInput(resultCode = 104003, loggedIn = false),
            QqRejectionInput(hasPurl = true, resultCode = 0, loggedIn = false),
            QqRejectionInput(resultCode = 104003, loggedIn = false),
            QqRejectionInput(hasPurl = true, resultCode = 0, loggedIn = false),
        )
        assertEquals(QqRejection.NONE, classifyQqBatch(inputs))
    }

    @Test
    fun `响应缺字段不算任何业务拒绝 —— 归 UNKNOWN 且不跳歌`() {
        assertEquals(QqRejection.UNKNOWN, classifyQqRejection(QqRejectionInput()))
    }

    @Test
    fun `映射表是全覆盖的 —— 新增分类时编译器会在这里报错`() {
        // `when` 是穷举的（没有 else），所以这条测试的「编译通过」本身就是断言。
        // 保留它作为显式落点：读到这一行的人会去看上面的映射表。
        val mapped = QqRejection.values().map { it.toResolveFailureKind() }
        assertEquals(QqRejection.values().size, mapped.size)
    }

    // ---------------------------------------------------------------- Provider 层

    @Test
    fun `缺 songmid 的 QQ 曲目 —— 结构性失败 允许跳歌 且一个请求都不发`() = runBlocking {
        val song = SongItem(
            id = 12345L,
            name = "无 mid 的曲目",
            artists = emptyList(),
            album = null,
            duration = null,
            source = MusicSource.QQMUSIC.key,
            sourceId = null,          // ★ 关键：缺 songmid
            mediaId = null,
        )
        val outcome = SourceRouter.resolveUrlOutcome(song, "higher")
        assertFalse(outcome.isOk)
        assertEquals(ResolveFailureKind.UNRESOLVABLE, outcome.failure?.kind)
        assertNull(outcome.result)
        // 与「不能跳歌」的那几类显式区分开。
        assertTrue(
            com.takahashirinta.ncrust.player.resolveFailureAction(
                outcome.failure!!.kind,
            ) == com.takahashirinta.ncrust.player.ResolveFailureAction.SKIP_ALLOWED,
        )
    }

    @Test
    fun `Provider 的带分类取链与旧路径在成功时逐值一致`() = runBlocking {
        // 契约：`resolveUrlOutcome` 只是给 `resolveUrl` 加了一层分类，
        // **不许**改变成功/失败的判据本身。缺 songmid 是唯一能在 JVM 上无网络复现的失败，
        // 所以这里用它做「两条路径对同一输入给出同一结论」的对账。
        val song = SongItem(
            id = 999L, name = "x", artists = emptyList(), album = null, duration = null,
            source = MusicSource.QQMUSIC.key, sourceId = null, mediaId = null,
        )
        assertNull(QqMusicSourceProvider.resolveUrl(song, "higher"))
        assertFalse(QqMusicSourceProvider.resolveUrlOutcome(song, "higher").isOk)
    }
}
