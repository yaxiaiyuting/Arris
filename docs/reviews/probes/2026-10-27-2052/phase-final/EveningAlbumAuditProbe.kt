package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.crosssource.CatalogAggregator
import com.takahashirinta.ncrust.qq.QqAlbum
import com.takahashirinta.ncrust.source.MusicSource
import org.junit.Assert.*
import org.junit.Test

class EveningAlbumAuditProbe {
    @Test fun neteaseAlbumGetsQqImageTemplateInEitherSideOfProductionAssembler() {
        val ne = QqAlbum(12345L, "12345", "fixture", singerName = "fixture artist")
        val primary = CatalogAggregator.assembleAlbums(listOf(ne), MusicSource.NETEASE, emptyList(), MusicSource.QQMUSIC).single()
        val secondary = CatalogAggregator.assembleAlbums(emptyList(), MusicSource.QQMUSIC, listOf(ne), MusicSource.NETEASE).single()
        for (actual in listOf(primary, secondary)) {
            assertEquals(MusicSource.NETEASE, actual.key.source)
            assertEquals("12345", actual.key.id)
            assertEquals("https://y.qq.com/music/photo_new/T002R500x500M00012345.jpg", actual.picUrl)
        }
        println("AUDIT real assembleAlbums routes a NETEASE numeric album ID into QQ cover URL in both primary and secondary branches. No HTTP/CDN request; this proves wrong URL construction, not a measured HTTP failure")
    }
}
