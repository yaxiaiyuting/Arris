/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.lyric

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.NeteaseSessionChangedException
import com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.model.LyricResponse

/** 预取与起播共享同曲、同登录代次的一次取词尝试。 */
internal object NeteaseLyricReads {
    private data class Key(val songId: Long, val login: Long)
    private val requests = SharedLyricReads<Key, CachedLyrics?>()

    suspend fun read(
        context: Context,
        songId: Long,
        session: NeteaseSessionSnapshot,
        refresh: Boolean,
        fetch: suspend (Long, NeteaseSessionSnapshot) -> LyricResponse = { id, owner ->
            RetrofitClient.api.getLyric(id = id, session = owner)
        },
    ): CachedLyrics? {
        requireSameLogin(session)
        val app = context.applicationContext
        val response = requests.read(Key(songId, session.loginGeneration)) load@{
            if (!refresh) {
                LyricsCache.get(app, songId)?.takeUnless(LyricsCache::needsRomalrcRefetch)
                    ?.let { return@load it }
            }
            requireSameLogin(session)
            val reply = fetch(songId, session)
            requireSameLogin(session)
            if (reply.code == 200) {
                LyricsCache.putNetease(app, songId, reply, session)
            } else {
                Log.d("NeteaseLyricReads", "Unsettled lyrics id=$songId code=${reply.code}")
                null
            }
        }
        requireSameLogin(session)
        return response
    }

    @VisibleForTesting
    fun readerCount(songId: Long, session: NeteaseSessionSnapshot): Int =
        requests.readerCount(Key(songId, session.loginGeneration))

    private fun requireSameLogin(session: NeteaseSessionSnapshot) {
        if (CookieManager.snapshot().loginGeneration != session.loginGeneration) {
            throw NeteaseSessionChangedException("Netease login changed during lyric read")
        }
    }
}
