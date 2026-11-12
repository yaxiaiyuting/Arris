package com.takahashirinta.ncrust.auth

import com.takahashirinta.ncrust.network.crypto.lowerHex
import java.security.MessageDigest

/** Credential identity includes a generation, so logout/relogin with identical bytes is distinct. */
class NeteaseSessionSnapshot internal constructor(
    val revision: Long,
    val cookie: String?,
    val loginGeneration: Long,
) {
    internal val usable: Boolean by lazy { NeteaseCookie.isUsable(cookie) }

    /** 与已落盘的首页归属键保持相同口径。 */
    internal val accountOwner: String? by lazy {
        val musicU = cookie?.splitToSequence(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("MUSIC_U=") }
            ?.substringAfter('=')
            ?.takeIf(String::isNotBlank)
            ?: return@lazy null
        MessageDigest.getInstance("SHA-256").digest(musicU.toByteArray(Charsets.UTF_8)).lowerHex(endIndex = 8)
    }

    override fun toString() = "NeteaseSessionSnapshot(revision=$revision, credentialPresent=${!cookie.isNullOrBlank()})"
}

internal class NeteaseSessionGuard {
    private var current = NeteaseSessionSnapshot(0L, null, 0L)

    @Synchronized fun snapshot() = current

    @Synchronized fun replace(value: String?, renewal: Boolean = false) {
        current = NeteaseSessionSnapshot(
            current.revision + 1L,
            value,
            if (renewal) current.loginGeneration else current.loginGeneration + 1L,
        )
    }

    @Synchronized fun isCurrent(s: NeteaseSessionSnapshot) =
        s.revision == current.revision && s.cookie == current.cookie

    @Synchronized fun <T> ifCurrent(s: NeteaseSessionSnapshot, block: () -> T): T? =
        if (isCurrent(s)) block() else null
}
