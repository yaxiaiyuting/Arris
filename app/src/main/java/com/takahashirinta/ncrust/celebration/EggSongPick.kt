/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.celebration

import android.content.Context
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.SongItem

/**
 * 彩蛋（自由听众席）里的「推荐一首」（v3.8.16）。
 *
 * 彩蛋原本只有一句标题和「继续听歌」，点开之后没什么可做的。这里从**你自己的收藏**里
 * 挑一首递到面前 —— 不联网、不猜口味，只是把你存过的东西随机翻出来一次。
 *
 * 取舍：
 *  - 只读本地收藏（`LibraryManager.getSavedSongEntries`），收藏为空时返回 null，
 *    由界面显示「还没有收藏」而不是假装推荐；
 *  - 连续两次不推同一首（用 [lastPicked] 记住上一次的 id）。
 */
internal object EggSongPick {

    /** 上一次推过的歌曲 id，用来避免连点「换一首」时原地打转。 */
    @Volatile
    private var lastPicked: Long? = null

    fun pick(context: Context): SongItem? {
        val songs = runCatching {
            LibraryManager.getSavedSongEntries(context.applicationContext)
                .asSequence()
                .filter { it.isVisible }
                .map { it.song }
                .toList()
        }.getOrNull() ?: return null
        if (songs.isEmpty()) return null
        val candidates = songs.filter { it.id != lastPicked }.ifEmpty { songs }
        val chosen = candidates.random()
        lastPicked = chosen.id
        return chosen
    }
}
