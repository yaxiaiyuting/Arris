/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.2 · P1：连接预热目标清单（纯逻辑）的单测。
 */

package com.takahashirinta.ncrust.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WarmupTargetPlanner] 的边界，逐条证伪式覆盖。
 *
 * 这些用例**不碰 Android、不碰网络、不建 `OkHttpClient`**：全部是对字符串清单的纯判定，
 * 所以能在 JVM 上跑（`WarmupTargetPlanner` 刻意不依赖 `java.net.URI` 也有这个原因）。
 *
 * ## 为什么这条判定值得这样钉
 *
 * 它守的是一个**不会报错的错**：v3.1.0 的预热清单是手抄常量，与业务端点之间没有任何
 * 一致性保证 —— 预热了一个没人调的 host、或漏掉取链那个 host，代码全都编译得过、
 * 运行也不抛异常，只是让优化**静默地等于零**（v3.3.2 复核时发现的正是后者的一层：
 * 连接建在了一个业务侧永远不会去查的池子里）。
 * 所以「清单里必须有取链域、且必须有界」这两条必须以断言的形式固定在测试里。
 */
class WarmupTargetPlannerTest {

    // ---------------------------------------------------------------- 优先级 ----

    /**
     * 顺序 = 优先级，**入参顺序原样保留**。
     *
     * 这条是「取链域最先」的实现基础：`ConnectionWarmup.HOSTS` 把
     * `INTERFACE_URL`（取链域）放在 candidates 的第一位，于是它排第一。
     * 本函数**不做**任何自己的排序（它不知道哪个域更重要，那是业务知识），
     * 所以这里钉的是「不擅自重排」——重排会让调用方的优先级声明静默失效。
     */
    @Test
    fun 保留入参的优先级顺序() {
        val plan = WarmupTargetPlanner.plan(
            candidates = listOf(
                RetrofitClient.INTERFACE_URL,   // ConnectionWarmup 里就是第一位
                RetrofitClient.API_URL,
                RetrofitClient.BASE_URL,
            )
        )
        assertEquals(
            listOf(
                "interface3.music.163.com",
                "interface.music.163.com",
                "music.163.com",
            ),
            plan,
        )
    }

    /**
     * 真实的三项输入（与 `ConnectionWarmup.HOSTS` 同源）→ 三项、且顺序就是入参顺序。
     *
     * ⚠️ `interface.music.163.com` 与 `interface3.music.163.com` 是**两个不同的 host**
     * （eapi 默认域 vs 取链域），绝不能被当成"同一个域"折叠掉 —— 折叠了取链就退化成冷连接，
     * 而这正是本次要修的形态之一。
     */
    @Test
    fun 三个业务域都保留且不误折叠() {
        val plan = WarmupTargetPlanner.plan(
            candidates = listOf(
                RetrofitClient.INTERFACE_URL,
                RetrofitClient.API_URL,
                RetrofitClient.BASE_URL,
            )
        )
        assertEquals(3, plan.size)
        assertTrue("取链域必须在清单里", "interface3.music.163.com" in plan)
        assertTrue("eapi 默认域必须在清单里", "interface.music.163.com" in plan)
        assertTrue("REST 域必须在清单里", "music.163.com" in plan)
    }

    // ------------------------------------------------------------------ 去重 ----

    /** 同一个 host 的三种写法（带 scheme / 带结尾斜杠 / 裸 host）只能预热一次。 */
    @Test
    fun 同一host的多种写法只留一次() {
        val plan = WarmupTargetPlanner.plan(
            candidates = listOf(
                "https://music.163.com/",
                "http://music.163.com",
                "music.163.com",
                "  HTTPS://Music.163.COM/  ",
            )
        )
        assertEquals(listOf("music.163.com"), plan)
    }

    /** 去重后保留的是**首次出现**的位置（顺序 = 优先级，不能被后来的写法顶掉）。 */
    @Test
    fun 去重保留首次出现的位置() {
        val plan = WarmupTargetPlanner.plan(
            candidates = listOf("b.example.com", "a.example.com", "https://b.example.com/"),
            limit = 4,
        )
        assertEquals(listOf("b.example.com", "a.example.com"), plan)
    }

    // ------------------------------------------------------------------ 有界 ----

    /**
     * 上限是硬的：预热是旁路，清单无界等于「加一个音源就多几通冷启动请求」，
     * 与铁律 24（未启用的音源不产生流量）冲突。
     */
    @Test
    fun 上限是硬的() {
        val many = (1..10).map { "h$it.example.com" }
        assertEquals(2, WarmupTargetPlanner.plan(many, limit = 2).size)
        assertEquals(WarmupTargetPlanner.MAX_TARGETS, WarmupTargetPlanner.plan(many).size)
    }

    /**
     * `limit <= 0` 返回**空**而不是「全部」。
     * 配置错误时宁可什么都不预热（预热本来就是尽力而为），
     * 也不能因为一个 0 被当成「无上限」而把整张表都发出去 ——
     * 这与 `BoundedParallel` 对非法 limit 取 `coerceAtLeast(1)` 是**不同**的取舍：
     * 那里少一个并发会让功能不完整，这里少预热一分钱损失都没有。
     */
    @Test
    fun 非法上限返回空而不是全部() {
        val two = listOf("a.example.com", "b.example.com")
        assertTrue(WarmupTargetPlanner.plan(two, limit = 0).isEmpty())
        assertTrue(WarmupTargetPlanner.plan(two, limit = -3).isEmpty())
    }

    /** 空入参 → 空清单（不抛、不补默认值）。 */
    @Test
    fun 空入参返回空清单() {
        assertTrue(WarmupTargetPlanner.plan(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------ 跳过 ----

    /** 跳过集按规范化后的 host 比较，大小写与 scheme 都不影响命中（B 站护栏靠它）。 */
    @Test
    fun 跳过集大小写与写法不敏感() {
        val plan = WarmupTargetPlanner.plan(
            candidates = listOf("https://API.BiliBili.com/", "interface3.music.163.com"),
            skip = setOf("api.bilibili.com"),
        )
        assertEquals(listOf("interface3.music.163.com"), plan)
    }

    /** 跳过项**不占**上限名额：跳过的不能把后面真正该预热的挤掉。 */
    @Test
    fun 跳过项不占上限名额() {
        val plan = WarmupTargetPlanner.plan(
            candidates = listOf("skip.example.com", "keep1.example.com", "keep2.example.com"),
            skip = setOf("skip.example.com"),
            limit = 2,
        )
        assertEquals(listOf("keep1.example.com", "keep2.example.com"), plan)
    }

    // -------------------------------------------------------------- 规范化 ----

    /** 非法/空白项被丢弃，且不影响其它项。 */
    @Test
    fun 空白项被丢弃() {
        val plan = WarmupTargetPlanner.plan(
            candidates = listOf("", "   ", "https://", "///", "music.163.com"),
        )
        assertEquals(listOf("music.163.com"), plan)
    }

    /**
     * [WarmupTargetPlanner.normalizeHost] 的逐项边界。
     * 输入形状是常量，但端点常量将来可能带上 path/端口 —— 那些都不该改变"预热哪个 host"。
     */
    @Test
    fun 规范化覆盖scheme路径端口与userinfo() {
        assertEquals("music.163.com", WarmupTargetPlanner.normalizeHost("https://music.163.com/"))
        assertEquals("music.163.com", WarmupTargetPlanner.normalizeHost("music.163.com:443"))
        assertEquals("music.163.com", WarmupTargetPlanner.normalizeHost("https://music.163.com/api/x?y=1#z"))
        assertEquals("music.163.com", WarmupTargetPlanner.normalizeHost("https://user:pw@music.163.com/"))
        assertEquals("interface3.music.163.com", WarmupTargetPlanner.normalizeHost(" interface3.music.163.com "))
        // IPv6 字面量：方括号内的冒号不是端口分隔符，所以【保留端口】。
        // 预热目标的判重粒度是「client 的 Address」，带端口反而更精确 ——
        // 刻意不把它剪成 "[::1]"（剪了会把不同端口的两条目标误判成同一个）。
        assertEquals("[::1]:8443", WarmupTargetPlanner.normalizeHost("https://[::1]:8443/"))
    }

    /** 非 host 输入一律 null（调用方据此丢弃），不抛异常。 */
    @Test
    fun 非host输入返回null() {
        assertNull(WarmupTargetPlanner.normalizeHost(""))
        assertNull(WarmupTargetPlanner.normalizeHost("    "))
        assertNull(WarmupTargetPlanner.normalizeHost("https://"))
        assertNull(WarmupTargetPlanner.normalizeHost("///"))
    }
}
