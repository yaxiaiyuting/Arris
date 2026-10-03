/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v1.2.0 · A1：取链请求的客户端身份。服务端按 **Cookie 里的客户端身份** 判定音质
 *     上限：只带 MUSIC_U/__csrf 的会话被封顶在 lossless，请求 hires / 母带会被静默回落
 *     成 lossless（code 仍是 200）；追加 os/appver 后同一请求才真正返回 hires。
 *     实测脚本见仓库外的 tools/probe-quality.py（不入 git）。
 */

package com.takahashirinta.ncrust.network

import android.content.Context
import android.util.Log
import java.security.SecureRandom

object ClientIdentity {
    private const val TAG = "ClientIdentity"
    private const val PREFS = "ncrust_device"
    private const val KEY_DEVICE_ID = "client_device_id"

    /**
     * **cookie 里的设备指纹字段名**（v3.3.0 补）。
     *
     * ⚠️ 它与 [KEY_DEVICE_ID] 是**两个不同的东西**，绝不能互相代替：
     * - [KEY_DEVICE_ID] 是**我们自己的 SharedPreferences 键名**（存这个 id 用）；
     * - 本常量是**发给服务端的 cookie 字段名**，官方客户端用的就是 `deviceId`。
     *
     * 我在本版重构时曾用 [KEY_DEVICE_ID] 去拼 cookie，于是实际发出去的是
     * `client_device_id=…` —— 服务端不认识这个字段，**设备指纹等于没发**，
     * 而它在请求里看起来完全正常（有键有值）。这类「键名语义错配」不会报错，
     * 只会让服务端按「没有指纹」处理，属于最难发现的一类缺陷。
     * `ClientIdentityCookieTest` 有一条用例专门钉住字段名。
     */
    private const val COOKIE_DEVICE_ID = "deviceId"

    /** 客户端类型。实测 pc 与 android 都能解锁 Hi-Res，A1 先用风险更低的 pc。 */
    const val OS = "pc"

    // appver 取自 2026-09 ncm PC 官方客户端，实测有效
    // 若未来服务端降权，参考 music.163.com/release/ 更新
    const val APPVER = "3.0.6"

    /** Win10 版本号。实测非必需（去掉仍返回 hires），保留只为客户端指纹自洽。 */
    const val OSVER = "10.0.19045"

    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * 幂等初始化：先读一次 prefs，为空才生成并落盘；**生成后立刻写内存缓存**。
     * 缓存这步不能省 —— apply() 是异步的，若 init() 之后 deviceId() 又回头读 prefs，
     * 可能读到空串，同一进程内的指纹就前后不一致了。
     */
    fun init(context: Context) {
        if (cachedDeviceId != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DEVICE_ID, null)
        val id = stored?.takeIf { it.isNotBlank() } ?: generateDeviceId().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        cachedDeviceId = id
        Log.i(TAG, "client identity: os=$OS appver=$APPVER osver=$OSVER deviceId=${id.take(4)}… (len=${id.length})")
    }

    /** 32 位小写 hex。只做客户端指纹，不读 ANDROID_ID / 序列号，不需要任何权限。 */
    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 未初始化时退回进程内临时值并告警：宁可指纹不稳定，也绝不抛异常打断播放。 */
    fun deviceId(): String = cachedDeviceId ?: generateDeviceId().also {
        cachedDeviceId = it
        Log.w(TAG, "deviceId() before init(); falling back to a process-local id")
    }

    /**
     * 把本客户端的**身份字段**并入用户 cookie 串。
     *
     * 顺序固定「用户字段在前、身份字段在后」，`MUSIC_U` / `__csrf` 等会话字段**永远原样保留**
     * —— 这个函数只碰 [OVERRIDDEN_KEYS] 里那几个键，其余一个字节都不动。
     *
     * ## v3.3.0：从「用户有同名键就跳过」改成「**覆盖值、保留位置**」
     *
     * 旧实现是 `.filterNot { k in existing }`：只要用户 cookie 里出现同名键就整个跳过。
     * 而服务端的音质上限**恰恰按这几个键判定**（见文件头：缺 `os`/`appver` 会被静默封顶在
     * lossless，`code` 仍是 200）—— 于是「用户 cookie 里有一个**空值**的 `appver=`」
     * 就等于**我们永久不再声明身份**：高清/母带从此静默降级，而客户端看不出任何异常。
     *
     * 这正是用户反馈第 6 条「需要很多次退出登录再登录才能播放 VIP 资源或者音质」
     * 的机制之一：三条登录路径（WebView 整串 / 扫码只回增量 / 局域网回传）写入的
     * cookie 形状不同，命中这条的几率因此不确定 —— 表现就是时好时坏。
     *
     * **`deviceId` 是例外**：它标识「这台设备上的这个会话」，用户 cookie 里带的那个
     * 才是服务端认识的那个。所以只在用户**没有**时才补我们自己的，
     * 绝不把已有的换掉（换掉等于换了个会话指纹）。
     */
    fun extraCookieFor(userCookie: String?): String {
        val base = userCookie?.trim().orEmpty()
        val parts = base.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val seen = HashSet<String>()
        val out = ArrayList<String>(parts.size + IDENTITY_FIELDS.size + 1)

        for (part in parts) {
            val key = part.substringBefore('=', "").trim()
            val lower = key.lowercase()
            if (lower.isEmpty()) continue
            seen.add(lower)
            out.add(
                when {
                    // 设备指纹与其它所有字段：原样保留。
                    lower !in OVERRIDDEN_KEYS && lower != COOKIE_DEVICE_ID.lowercase() -> part
                    // 身份字段：**保留这个键原来的位置**，只换值。
                    else -> identityValueFor(lower)?.let { "$key=$it" } ?: part
                }
            )
        }
        // 用户 cookie 里没有的，补在末尾（顺序与旧实现一致：os / appver / osver / deviceId）。
        for ((k, v) in IDENTITY_FIELDS) {
            if (k !in seen) out.add("$k=$v")
        }
        if (COOKIE_DEVICE_ID.lowercase() !in seen) out.add("$COOKIE_DEVICE_ID=${deviceId()}")
        return out.joinToString("; ")
    }

    /**
     * 被本客户端**声明**的身份字段：列在这里的键会被我们的值覆盖（保留原位置）。
     *
     * 不含 `deviceId` —— 它的处置与这几个不同，见 [extraCookieFor] 的 KDoc。
     */
    private val OVERRIDDEN_KEYS = setOf("os", "appver", "osver")

    /** 身份字段的声明顺序（同时也是「用户没有时补在末尾」的顺序）。 */
    private val IDENTITY_FIELDS = listOf("os" to OS, "appver" to APPVER, "osver" to OSVER)

    private fun identityValueFor(lowerKey: String): String? = when (lowerKey) {
        "os" -> OS
        "appver" -> APPVER
        "osver" -> OSVER
        else -> null
    }
}
