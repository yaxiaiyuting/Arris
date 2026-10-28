package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import com.takahashirinta.ncrust.ui.settings.*
import org.junit.Assert.*
import org.junit.Test

class MediaBilingualLyricsTest {
    private fun line(at: Long, text: String) = LrcLine(timeMs = at, text = text)
    private val original = listOf(line(1000, "One"), line(2000, "Two"), line(3000, "Three"))
    private val translated = listOf(line(1000, "一"), line(2000, "二"), line(3000, "三"))
    private fun display(frame: MediaLyricFrame) = MediaDisplayLines.of(
        "Song", "Artist", frame.original, frame.translation, frame.options,
    )

    @Test fun defaultKeepsOriginalAboveTranslation() {
        val frame = MediaLyricTrack.from(original, translated).at(1500, true, MediaLyricOptions())
        assertEquals(MediaDisplayLines.Lines("One", "一"), display(frame))
    }

    @Test fun translationCanBeThePrimaryLine() {
        val frame = MediaLyricTrack.from(original, translated).at(2500, true, MediaLyricOptions(translationFirst = true))
        assertEquals(MediaDisplayLines.Lines("二", "Two"), display(frame))
    }

    @Test fun missingTranslationFallsBackToOriginalOnlyInEitherOrder() {
        for (first in listOf(false, true)) {
            val frame = MediaLyricTrack.from(original, emptyList()).at(2500, true, MediaLyricOptions(translationFirst = first))
            assertEquals(MediaDisplayLines.Lines("Two", ""), display(frame))
        }
    }

    @Test fun untranslatedSentenceDoesNotBorrowTheNextSentenceTranslation() {
        val track = MediaLyricTrack.from(original, listOf(line(1000, "一"), line(3000, "三")))
        assertEquals(MediaDisplayLines.Lines("Two", ""), display(track.at(2500, true, MediaLyricOptions())))
        assertEquals(MediaDisplayLines.Lines("Three", "三"), display(track.at(3500, true, MediaLyricOptions())))
    }

    @Test fun nearbyTranslationBelongsToTheNearestOriginalAndExactMatchWins() {
        val track = MediaLyricTrack.from(original, listOf(line(1990, "近似"), line(2000, "精确")))
        assertNull(track.at(1500, true, MediaLyricOptions()).translation)
        assertEquals("精确", track.at(2500, true, MediaLyricOptions()).translation)
    }

    @Test fun seekBothDirectionsSelectsOneCoherentSentencePair() {
        val track = MediaLyricTrack.from(original.reversed(), translated.reversed())
        for ((pos, expected) in listOf(3500L to ("Three" to "三"), 1500L to ("One" to "一"), 2500L to ("Two" to "二"))) {
            val frame = track.at(pos, true, MediaLyricOptions())
            assertEquals(expected.first, frame.original)
            assertEquals(expected.second, frame.translation)
        }
    }

    @Test fun disabledLyricsAndBeforeFirstSentenceKeepSongIdentity() {
        val track = MediaLyricTrack.from(original, translated)
        for (frame in listOf(track.at(500, true, MediaLyricOptions()), track.at(2500, false, MediaLyricOptions()))) {
            assertNull(frame.notificationKey)
            assertEquals(MediaDisplayLines.Lines("Song", "Artist"), display(frame))
        }
    }

    @Test fun clearingTrackClearsBothPublishedLines() {
        val frame = MediaLyricTrack.from(emptyList(), translated).at(5000, true, MediaLyricOptions())
        assertNull(frame.original)
        assertNull(frame.translation)
    }

    @Test fun blankOrDuplicateTranslationDoesNotCreateASecondLyricLine() {
        for (text in listOf("", "  ", "One", " One ")) {
            assertEquals(MediaDisplayLines.Lines("One", ""), display(MediaLyricFrame("One", text)))
        }
    }

    @Test fun bilingualOffPreservesOriginalOnlyLegacyLayout() {
        assertEquals(MediaDisplayLines.Lines("One", "Song · Artist"), display(MediaLyricFrame("One", "一", MediaLyricOptions(false, true))))
    }

    @Test fun translationArrivingLatePassesTheExistingNotificationGate() {
        val before = MediaLyricFrame("One")
        val after = MediaLyricFrame("One", "一")
        assertNotEquals(before.notificationKey, after.notificationKey)
        assertEquals(LyricNotifyGate.Decision.Post, LyricNotifyGate.decide(after.notificationKey, before.notificationKey, 2000, 0, true))
        assertEquals(LyricNotifyGate.Decision.SkipSameLine, LyricNotifyGate.decide(after.notificationKey, after.notificationKey, 3000, 0, true))
    }

    @Test fun changingPrimaryLineAlsoChangesPublicationIdentity() {
        val frame = MediaLyricFrame("One", "一")
        assertNotEquals(frame.notificationKey, frame.copy(options = MediaLyricOptions(translationFirst = true)).notificationKey)
    }

    @Test fun mediaSessionAndNotificationUseTheSameBilingualOrder() {
        val frame = MediaLyricFrame("One", "一", MediaLyricOptions(translationFirst = true))
        assertEquals(display(frame), MediaSessionMerge.sessionLines("Song", "Artist", frame.original, true, frame.translation, frame.options))
        assertEquals(MediaDisplayLines.Lines("Song", "Artist"), MediaSessionMerge.sessionLines("Song", "Artist", frame.original, false, frame.translation, frame.options))
    }

    @Test fun preferenceDefaultsAndMalformedValuesAreSafe() {
        val prefs = MemoryPrefs()
        assertEquals(MediaLyricOptions(), MediaLyricOptions.read(prefs))
        prefs.edit().putBoolean(MediaLyricOptions.KEY_BILINGUAL, false).putInt(MediaLyricOptions.KEY_PRIMARY, 1).commit()
        assertEquals(MediaLyricOptions(false, true), MediaLyricOptions.read(prefs))
        prefs.putRaw(MediaLyricOptions.KEY_BILINGUAL, "bad")
        prefs.putRaw(MediaLyricOptions.KEY_PRIMARY, "bad")
        assertEquals(MediaLyricOptions(), MediaLyricOptions.read(prefs))
    }

    @Test fun newSettingsAreReachableInLyricsAndPlaybackGroups() {
        assertEquals(setOf(MediaLyricOptions.KEY_BILINGUAL, MediaLyricOptions.KEY_PRIMARY),
            SettingsRegistry.allEntries().filter { it.isNewInV3712 }.map { it.id }.toSet())
        for (key in listOf(MediaLyricOptions.KEY_BILINGUAL, MediaLyricOptions.KEY_PRIMARY)) {
            val entry = SettingsRegistry.entryByKey(key)!!
            assertEquals(SettingsGroup.LYRICS, entry.group)
            assertEquals(SettingsGroup.PLAYBACK, entry.secondaryGroup)
            assertTrue(SettingsRenderPlan.isRenderedOnGroupPage(entry))
        }
        assertEquals(true, SettingsRegistry.entryByKey(MediaLyricOptions.KEY_BILINGUAL)!!.defaultValue)
        assertEquals(0, SettingsRegistry.entryByKey(MediaLyricOptions.KEY_PRIMARY)!!.defaultValue)
    }
}
