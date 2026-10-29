package com.takahashirinta.ncrust.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 识曲 · 跨源候选排序（v3.8.0）：必须挡住"把 Live/翻唱当原唱"。 */
class CandidateRankerTest {

    private val match = RecogMatch(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = 269_000,
        confidence = 0.9f,
        provider = "fingerprint",
    )

    private fun candidate(
        title: String = "晴天",
        artist: String = "周杰伦",
        durationMs: Long = 269_500,
        editions: Set<String> = emptySet(),
        versionTag: String = "ORIGINAL",
        availability: String = "PLAYABLE",
        source: String = "netease",
    ) = RecogCandidate(
        title = title,
        artist = artist,
        durationMs = durationMs,
        editionKinds = editions,
        versionTag = versionTag,
        availability = availability,
        source = source,
        sourceId = "1",
    )

    @Test
    fun `完全一致给 HIGH`() {
        assertEquals(MatchConfidence.HIGH, CandidateRanker.grade(match, candidate()).confidence)
    }

    @Test
    fun `Live 版本被拒_即使歌名相同时长相近`() {
        val live = candidate(title = "晴天 (Live)", editions = setOf("live"))
        assertEquals(MatchConfidence.REJECT, CandidateRanker.grade(match, live).confidence)
    }

    @Test
    fun `识别到的本来就是 Live 时接受 Live 候选`() {
        val liveMatch = match.copy(title = "晴天 Live")
        val live = candidate(title = "晴天 Live", editions = setOf("live"))
        assertTrue(CandidateRanker.grade(liveMatch, live).confidence != MatchConfidence.REJECT)
    }

    @Test
    fun `服务端判翻唱时拒绝`() {
        assertEquals(MatchConfidence.REJECT, CandidateRanker.grade(match, candidate(versionTag = "COVER")).confidence)
    }

    @Test
    fun `艺人不同但歌名时长一致给 MEDIUM`() {
        val other = candidate(artist = "某翻唱歌手", versionTag = "UNKNOWN")
        assertEquals(MatchConfidence.MEDIUM, CandidateRanker.grade(match, other).confidence)
    }

    @Test
    fun `歌名相似但艺人时长都不符只给 LOW`() {
        val weak = candidate(artist = "完全不同的歌手", durationMs = 300_000)
        assertEquals(MatchConfidence.LOW, CandidateRanker.grade(match, weak).confidence)
    }

    @Test
    fun `排序把能播的放在会员专享之前`() {
        val ranked = CandidateRanker.rank(
            match,
            listOf(
                candidate(source = "qqmusic", availability = "MEMBER_ONLY"),
                candidate(source = "netease", availability = "PLAYABLE"),
            ),
        )
        assertEquals(2, ranked.size)
        assertEquals("netease", ranked.first().candidate.source)
    }

    @Test
    fun `归一化去标点与空白`() {
        assertEquals("晴天", CandidateRanker.normalize(" 《晴 天》 "))
        assertEquals(1f, CandidateRanker.similarity("晴天", "晴天"), 1e-6f)
    }
}
