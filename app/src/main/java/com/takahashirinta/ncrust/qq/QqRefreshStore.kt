/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：qm **续期凭证**的落盘。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * [QqRefreshCredential] 的落盘（v3.4.9）。
 *
 * ## 为什么不塞进 cookie 字符串
 *
 * cookie 是**每次请求都要带上网络**的东西（`QqCookie.requestCookie` 的过滤器就是
 * 「哪些字段该发出去」），而 `refresh_key` / `refresh_token` / `access_token`
 * 是**换票据用的凭证、不是身份**：QQ 的业务接口一个都不需要它们。
 * 混进 cookie 会有两个后果，而且都不会报错：
 * ① 每一条业务请求都在网络上多带一份可换出新票据的长期凭证（纯增加泄露面）；
 * ② [QqCookie.requestCookie] 的白名单会被迫加字段，那条白名单的语义
 *    （「只发认识的身份字段」）就被稀释了。
 *
 * 所以：**cookie 里放身份，这里放续期凭证**，两者都在 `ncrust_qq_prefs` 这一个
 * 私有文件里（同一个「只存本机、绝不上传」的约定）。
 *
 * ## 为什么与 cookie 同文件而不是单开一个
 *
 * 它们的生命周期**必须一起结束**：登出（[QqAuthStore.clear]）要同时清掉 cookie 与
 * 续期凭证。分两个文件就可能出现「登出了但续期凭证还在」——
 * 那份凭证属于上一个账号，下一个账号登录时若被误用，表现是
 * 「换了账号却还是老账号的数据」，而且看不出来是本地串了号。
 * 同文件 + 同一个 clear() 让这条不变量由**结构**保证，不靠纪律。
 *
 * ## 加字段 = 加迁移逻辑（AGENTS.md）
 *
 * 读出来的 JSON 先反序列化成 [QqRefreshCredential]，它的每个字段都可空 + 有默认值，
 * 老 JSON（或坏 JSON）读出来就是「缺字段」⇒ [QqRefreshCredential.isRefreshable] 为 false
 * ⇒ 走「照旧要重新登录」这条与 v3.4.8 完全一致的路径。**不会更糟**，这是本次迁移的验收口径。
 */
object QqRefreshStore {

    private const val TAG = "QqRefreshStore"

    /** 与 cookie 同一个 SharedPreferences 文件（见 KDoc 的「为什么同文件」）。 */
    private const val PREFS = QqAuthStore.PREFS
    private const val KEY_CREDENTIAL = "qq_refresh_credential"

    private val gson = Gson()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** JSON → 凭证。坏 JSON / 空串返回 `null`（调用方按「没有续期凭证」处理，**不抛**）。 */
    fun decode(json: String?): QqRefreshCredential? {
        if (json.isNullOrEmpty()) return null
        return try {
            gson.fromJson(json, QqRefreshCredential::class.java)
        } catch (e: Exception) {
            // 坏 JSON 只丢这一次续期机会，不影响登录态本身 —— 记一行就够。
            Log.w(TAG, "decode credential failed", e)
            null
        }
    }

    fun encode(credential: QqRefreshCredential): String = gson.toJson(credential)

    /** 读回落盘的续期凭证。没有（v3.4.9 之前的用户）返回 `null`。 */
    fun get(context: Context): QqRefreshCredential? =
        decode(prefs(context).getString(KEY_CREDENTIAL, null))

    /**
     * 写入一份凭证，返回实际落盘的那一份（含与旧值的字段级合并）。
     *
     * 合并规则见 [QqRefreshCredential.mergeWith]：刷新响应里缺的字段**保留旧值**，
     * 否则一次「响应只回了一半字段」的刷新会把下一次刷新的能力也带走。
     */
    fun save(context: Context, credential: QqRefreshCredential): QqRefreshCredential {
        val merged = QqRefreshCredential.mergeWith(get(context), credential)
        prefs(context).edit().putString(KEY_CREDENTIAL, encode(merged)).apply()
        return merged
    }

    /** 只清续期凭证（登出时与 cookie 一起清，见 [QqAuthStore.clear]）。 */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CREDENTIAL).apply()
    }
}
