/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * v1.5.1 · C —— 「现在有没有网」的唯一判据。
 *
 * 无网冷启动时，首页三条请求要在 OkHttp 的 connectTimeout（30s）上白等，
 * 期间首屏只有一个转圈。这里在**发请求之前**先问一次系统，没网就直接走降级路径
 * （显示上次的快照或空态），一个字节都不发。
 *
 * 判据只用 [NetworkCapabilities.NET_CAPABILITY_INTERNET]，**刻意不要求
 * NET_CAPABILITY_VALIDATED**：国内 ROM / 部分网络下 Google 的连通性探测本来就不可达，
 * validated 常年为 false，用它会把「有网」误判成「没网」，直接把功能关掉。
 * 代价是「连上了但实际不通」这种半死网络仍会走请求 + 超时，那种情况由首页的
 * 超时降级（见 HomeScreen 的 HOME_LOAD_TIMEOUT_MS）兜住。
 *
 * 任何异常（权限、系统服务缺失、ROM 定制）一律当作「有网」—— 宁可多试一次网络，
 * 也不能因为判断逻辑本身出错而让用户永远看不到内容。
 */
object NetworkAvailability {

    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            true
        }
    }
}
