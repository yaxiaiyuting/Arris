package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.player.*
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.i18n.*
import org.junit.Assert.*
import org.junit.Test

class NoonFailureTextAuditProbe {
    @Test fun sourceSwitchHintsExposeAnUnexpandedPlaceholderInEveryLocale() {
        val presets=listOf(zhCN,zhTW,en,jpJP,jpMY,koNK,deDE,ruRU)
        for(strings in presets) {
            val text=resolveFailureText(ResolveFailure(ResolveFailureKind.COPYRIGHT_GONE,MusicSource.NETEASE),strings,MusicSource.QQMUSIC)
            assertTrue(text,text.contains("%s"))
        }
        println("AUDIT localized failure hint: real resolveFailureText leaves literal %s in all eight locale suffixes; switchSource accepts only the other-source label and no caller formats its original-source placeholder")
    }
}
