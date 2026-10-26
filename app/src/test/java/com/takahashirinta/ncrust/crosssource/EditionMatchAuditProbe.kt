package com.takahashirinta.ncrust.crosssource

import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.*
import org.junit.Assert.*
import org.junit.Test

/** Current matching policy is intentionally exercised; no network or audio. */
class EditionMatchAuditProbe {
    private fun song(source: MusicSource, name: String, n: Long) = SongItem(
        id = if (source == MusicSource.QQMUSIC) SourceIds.qqId(n, "fixture-$n") else n,
        name = name, artists = listOf(ArtistItem(null, "fixture-artist")), album = null,
        duration = 240000, source = source.key,
        sourceId = if (source == MusicSource.QQMUSIC) "fixture-$n" else null,
    )
    @Test fun instrumentalCanReplaceTheVocalTrackWhenDurationMatches() {
        val vocal = song(MusicSource.NETEASE, "fixture-song", 101)
        val instrumental = song(MusicSource.QQMUSIC, "fixture-song(伴奏)", 202)
        val verdict = CrossSourceMatcher.gradeTrack(CatalogAggregator.trackCandidateOf(vocal), CatalogAggregator.trackCandidateOf(instrumental))
        assertEquals(MatchConfidence.HIGH, verdict.confidence)
        assertTrue(verdict.mergeable)
        val (rows, _, _) = CatalogAggregator.assembleSongsWithProbe(listOf(vocal), listOf(instrumental),
            mapOf(TrackKey.fromSong(vocal) to TrackAvailability.NO_COPYRIGHT,
                TrackKey.fromSong(instrumental) to TrackAvailability.PLAYABLE))
        assertEquals(1, rows.size)
        assertEquals(instrumental.id, rows.single().song.id)
        println("AUDIT edition match: same-duration vocal vs instrumental graded HIGH, original row replaced by playable instrumental and collapsed")
    }
    @Test fun distinctLiveAndRemixEditionsAreGradedExactAndCollapsed() {
        val live = song(MusicSource.NETEASE, "fixture-song (Live)", 101)
        val remix = song(MusicSource.QQMUSIC, "fixture-song (Remix)", 202)
        val verdict = CrossSourceMatcher.gradeTrack(CatalogAggregator.trackCandidateOf(live), CatalogAggregator.trackCandidateOf(remix))
        assertEquals(MatchConfidence.EXACT, verdict.confidence)
        val plan = PlayAllDedup.plan(listOf(AggregatedSong(song=live), AggregatedSong(song=remix)))
        assertEquals(1, plan.songs.size)
        println("AUDIT edition match: Live vs Remix both have a marker, graded EXACT and play-all drops one distinct edition")
    }
}
