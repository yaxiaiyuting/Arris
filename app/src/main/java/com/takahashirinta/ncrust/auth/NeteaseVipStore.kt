/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.4 · ncm 会员状态的读取与缓存。缺失的那一块。
 */

package com.takahashirinta.ncrust.auth

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ncm 账号的会员状态（v2.1.4）。
 *
 * ## 为什么现在才需要它
 *
 * 在此之前全仓库**没有任何「ncm 会员」的概念** —— 唯一与会员有关的东西是 QQ 侧的
 * `QqProfile` 角标。v2.1.4 的聚合搜索排序要回答「该把哪一家的会员专享曲排前面」，
 * 才发现这一块是空的。
 *
 * ## 判据的选择（实测依据，2026-09）
 *
 * 走 `/api/music-vip-membership/front/vip/info`。实测（本机登录态）返回：
 *
 * ```json
 * {"code":200, "data":{
 *   "redVipLevel":7, "redVipAnnualCount":1,
 *   "associator":{"vipCode":100,"vipLevel":7,"expireTime":1832169599000},
 *   "musicPackage":{"vipCode":220,"vipLevel":7,"expireTime":1832169599000}}}
 * ```
 *
 * 判据取 **`data.redVipLevel > 0`**，理由：
 * - 它是「有没有红V」的直接答案，不需要解释 `vipType` 的位域语义
 *   （实测同一账号 `account.vipType=11` 而 `profile.vipType=110`，两个数不一致 ——
 *   拿位域去推「>0 就是会员」在多档会员并存时有踩坑的空间）；
 * - `associator` / `musicPackage` 是**具体权益包**，可能单独过期而红V仍在，
 *   用它们判「有没有会员」会把「红V在但音乐包到期」误判成非会员。
 *
 * ## 缓存
 *
 * 会员状态是**低频变化**的数据，而搜索是高频操作 —— 每次搜索都发一次会员查询
 * 既慢又会被限流。所以落盘 + TTL，与 `QqAuthStore` 的 `PROFILE_TTL_MS` 同一口径。
 *
 * ## 失败时的语义：**保守**
 *
 * 取不到（未登录 / 网络失败 / 结构不认识）一律按**非会员**处理。
 * 代价只是「排序没有变」，而不是「把点不开的歌排到用户脸上」。
 */
object NeteaseVipStore {

    private const val TAG = "NeteaseVipStore"

    private const val PREFS = "ncrust_netease_vip"

    /** 是否红V（1/0）。读不到就是 0。 */
    private const val KEY_IS_VIP = "is_vip"

    /** 上次查询成功的时间戳（毫秒）。0 = 从未成功过。 */
    private const val KEY_CHECKED_AT = "checked_at"

    /** 原始 `redVipLevel`，只为诊断留痕（业务判断不用它，见 KDoc）。 */
    private const val KEY_LEVEL = "red_vip_level"

    /** 缓存 TTL：30 分钟。会员到期/续费是低频事件，不值得每次搜索都问一次。 */
    private const val TTL_MS = 30 * 60 * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 缓存的会员状态；**未登录时恒为 false**（与 `QqAuthStore.profile` 同一口径）。 */
    fun isVip(context: Context): Boolean {
        if (!CookieManager.hasCookie(context)) return false
        return prefs(context).getInt(KEY_IS_VIP, 0) == 1
    }

    /** 缓存是否需要刷新。未登录时恒为 false（没登录就没有会员可查，别发注定 401 的请求）。 */
    fun needsRefresh(context: Context): Boolean {
        if (!CookieManager.hasCookie(context)) return false
        val at = prefs(context).getLong(KEY_CHECKED_AT, 0L)
        return System.currentTimeMillis() - at > TTL_MS
    }

    /**
     * 查一次并落盘。**返回是否成功拿到权威答案**（失败时不清缓存 ——
     * 一次网络抖动不该把已知的会员状态抹成非会员）。
     */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!CookieManager.hasCookie(context)) return@withContext false
        val level = runCatching { fetchRedVipLevel() }
            .onFailure { Log.w(TAG, "vip info failed", it) }
            .getOrNull() ?: return@withContext false

        prefs(context).edit()
            .putInt(KEY_IS_VIP, if (level > 0) 1 else 0)
            .putInt(KEY_LEVEL, level)
            .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            .apply()
        if (BuildConfig.DEBUG) Log.d(TAG, "redVipLevel=$level => isVip=${level > 0}")
        true
    }

    /** 登录/登出时清掉，避免把上一个账号的会员状态带给下一个账号。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * `/api/...` 而**不是** `/eapi/...` —— 这一点是实测出来的，不是随手写的：
     * 同一条路径走 `eapiPost`（`/eapi/music-vip-membership/front/vip/info` →
     * `interface.music.163.com`）实测返回 **`404 接口未找到！`**，
     * 而普通 GET `/api/music-vip-membership/front/vip/info` → `music.163.com`
     * 返回 `200` + 完整数据。所以这里用 [RetrofitClient.get]（明文 GET + 现有 cookie），
     * 既走得通又不引进 eapi 签名这一层没必要的东西 —— 会员查询是只读且幂等的。
     */
    private const val VIP_INFO_PATH = "/api/music-vip-membership/front/vip/info"

    /** 取 `data.redVipLevel`。结构不认识时抛异常（由 [refresh] 统一吞掉并保留旧缓存）。 */
    private suspend fun fetchRedVipLevel(): Int {
        val body = RetrofitClient.get(VIP_INFO_PATH)
        if (body.isEmpty()) throw IllegalStateException("empty vip info response")
        val json = JSONObject(body)
        if (json.optInt("code", -1) != 200) {
            throw IllegalStateException("vip info code=${json.optInt("code", -1)}")
        }
        val data = json.optJSONObject("data") ?: throw IllegalStateException("no data")
        // `redVipLevel` 缺失时用 `associator.vipLevel` 兜底：实测两者同为 7，
        // 但服务端字段历史上挪过位置，多认一条路比整块失效好。
        return data.optInt("redVipLevel", -1).takeIf { it >= 0 }
            ?: data.optJSONObject("associator")?.optInt("vipLevel", 0)
            ?: 0
    }
}
