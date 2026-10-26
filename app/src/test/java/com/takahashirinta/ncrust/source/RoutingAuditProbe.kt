package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.library.SavedSongCodec
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.ResolveFailureKind
import com.takahashirinta.ncrust.player.ResolveOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Route-level fixtures for the production construction paths; no service/device/network. */
class RoutingAuditProbe {
    @Test fun carMediaIdRoundTripDiscardsQqPayloadRequiredForResolution() = runBlocking {
        val id = SourceIds.qqId(101, "fixture-mid")
        val original = SongItem(id, "fixture", null, null, 200000,
            source = "qqmusic", sourceId = "fixture-mid", mediaId = "fixture-media")
        assertTrue(original.isResolvable)
        // PlaybackService.songItem emits this mediaId, and resolveMediaItem builds this ref.
        val mediaId = SourceIds.mediaId(original.musicSource, original.id)!!
        val (source, parsedId) = SourceIds.parseMediaId(mediaId)!!
        val ref = songRefOf(source, parsedId)
        assertEquals(MusicSource.QQMUSIC, ref.musicSource)
        assertFalse(ref.isResolvable)
        assertNull(SourceRouter.resolveUrl(ref, "standard"))
        println("AUDIT car route: valid saved QQ song becomes unresolvable after browse mediaId round trip discards songmid/media_mid")
    }

    @Test fun legacyQqCollectionIdentityAndResolvedProviderDisagree() = runBlocking {
        val id = SourceIds.qqId(101, "fixture-mid")
        val entry = SavedSongCodec.decode("""[{"id":$id,"name":"fixture-legacy","sourceId":"fixture-mid","mediaId":"fixture-media"}]""").single()
        assertEquals(MusicSource.QQMUSIC, entry.trackKey.source)
        assertEquals(MusicSource.NETEASE, entry.song.musicSource)
        val original = SourceRouter.provider(MusicSource.NETEASE)!!
        var reachedNetease = false
        SourceRouter.register(object : MusicSourceProvider by original {
            override suspend fun resolveUrlOutcome(song: SongItem, level: String): ResolveOutcome {
                reachedNetease = true
                return ResolveOutcome.failed(ResolveFailureKind.UNKNOWN, MusicSource.NETEASE)
            }
        })
        try {
            SourceRouter.resolveUrlOutcome(entry.song, "standard")
            assertTrue(reachedNetease)
            println("AUDIT legacy route: decoded collection identity is QQ but unchanged payload dispatches QQ synthetic id to Netease provider")
        } finally { SourceRouter.register(original) }
    }
}
