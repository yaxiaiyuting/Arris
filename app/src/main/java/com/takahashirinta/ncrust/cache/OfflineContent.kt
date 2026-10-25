package com.takahashirinta.ncrust.cache

import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.account.FreeTrialInfo
import com.takahashirinta.ncrust.account.PlaybackItem

/** Belongs to a cached FILE, not a song's current account entitlement. Old records stay UNKNOWN. */
data class OfflineContent(
    @SerializedName("kind") val kind: String = UNKNOWN,
    @SerializedName("trial_start") val trialStart: Int? = null,
    @SerializedName("trial_end") val trialEnd: Int? = null,
    @SerializedName("fee") val fee: Int? = null,
    // Gson defaults a missing primitive to 0. Pre-v3.7.4 FULL records cannot prove which bytes occupied a legacy key.
    @SerializedName("file_epoch") val fileEpoch: Int = 1,
) {
    val known: Boolean get() = (kind == FULL && fileEpoch >= 1) || kind == TRIAL
    fun playbackItem(url: String, level: String): PlaybackItem? = when(kind) {
        FULL -> if (known) PlaybackItem(url = url, code = 200, level = level, fee = fee) else null
        TRIAL -> PlaybackItem(url = url, code = 200, level = level, fee = fee, freeTrialInfo = FreeTrialInfo(trialStart, trialEnd))
        else -> null
    }
    /**
     * FULL keeps the historical key shape `song:<id>:<level>` on purpose: every file already on
     * disk was cached under it, and a new suffix would strand all existing downloads offline.
     * Only trial bytes need their own file, because a 30 s clip must never answer a full-song read.
     */
    val variant: String get() = when(kind) { TRIAL -> "trial_${trialStart ?: 0}_${trialEnd ?: 0}"; else -> "" }
    companion object {
        const val UNKNOWN = "unknown"
        const val FULL = "full"
        const val TRIAL = "trial"
        fun from(item: PlaybackItem?): OfflineContent = when {
            item == null -> OfflineContent()
            item.freeTrialInfo != null -> OfflineContent(TRIAL, item.freeTrialInfo.start, item.freeTrialInfo.end, item.fee)
            !item.url.isNullOrBlank() && (item.code == null || item.code == 200) -> OfflineContent(FULL, fee = item.fee)
            else -> OfflineContent()
        }
    }
}

data class OfflineUrlRecord(
    @SerializedName("url") val url: String,
    @SerializedName("content") val content: OfflineContent = OfflineContent(),
)

/** Reuse verified full files; isolate only legacy bytes whose content has never been proven. */
internal fun verifiedCacheKey(key: String, next: OfflineContent, previous: OfflineContent?, hasBytes: Boolean): String =
    if (next.kind == OfflineContent.FULL && next.known && hasBytes &&
        !(previous?.kind == OfflineContent.FULL && previous.known)) key + "~full-v1" else key
