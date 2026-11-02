/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import android.content.Context
import java.util.Calendar

/**
 * 每日推荐的「什么时候该换一批」（4.0.0）。
 *
 * ## 为什么删掉刷新键
 *
 * 网易云的每日推荐是**按天生成**的：服务端每天 6:00 换一批。刷新键无论点多少次都只会拿到
 * 同一份（这也是之前"点了没反应"的观感来源）。与其留一个骗人的按钮，不如把"该换的时候
 * 自动换"做对：
 *
 *  · 白天（≥ 6:00）打开 ⇒ 今天这一批；此时缓存里的如果是昨天或更早，就自动拉一次；
 *  · 凌晨（< 6:00）打开 ⇒ 仍然属于**前一天**那一批（服务端口径），不触发刷新，
 *    避免用户熬夜听歌时列表莫名清空重排。
 *
 * 判定键是「日推日」：把 6:00 当天的零点。它只影响"要不要发一次请求"，
 * 不影响任何播放/缓存逻辑。
 */
object DailyRecommendation {

    /** 服务端换批的整点（本地时区小时）。

     * 取值依据：网易云每日推荐界面的"每日 6:00 更新"文案。
     */
    const val REFRESH_HOUR = 6

    const val KEY_LAST_DAY = "daily_reco_daykey"

    /** 当前属于哪一批（形如 `2026-11-02`，凌晨沿用前一天）。 */
    fun currentDayKey(nowMillis: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        if (cal.get(Calendar.HOUR_OF_DAY) < REFRESH_HOUR) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    /** 缓存里的那一批是否已经过期（需要重新拉取）。 */
    fun needsRefresh(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences("ncrust_settings", Context.MODE_PRIVATE)
        val last = prefs.getString(KEY_LAST_DAY, null)
        return last != currentDayKey(nowMillis)
    }

    /** 记录"当前内存里的这一批是哪一天的"。 */
    fun markFetched(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        context.getSharedPreferences("ncrust_settings", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DAY, currentDayKey(nowMillis))
            .apply()
    }
}
