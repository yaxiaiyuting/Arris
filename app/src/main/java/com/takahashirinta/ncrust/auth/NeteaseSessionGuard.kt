package com.takahashirinta.ncrust.auth

/** Credential identity includes a generation, so logout/relogin with identical bytes is distinct. */
class NeteaseSessionSnapshot internal constructor(val revision: Long, val cookie: String?, val loginGeneration: Long) {
    override fun toString() = "NeteaseSessionSnapshot(revision=$revision, credentialPresent=${!cookie.isNullOrBlank()})"
}

internal class NeteaseSessionGuard {
    private var revision = 0L
    private var cookie: String? = null
    private var loginGeneration = 0L
    @Synchronized fun snapshot() = NeteaseSessionSnapshot(revision, cookie, loginGeneration)
    @Synchronized fun replace(value: String?, renewal: Boolean = false) { cookie = value; revision++; if (!renewal) loginGeneration++ }
    @Synchronized fun isCurrent(s: NeteaseSessionSnapshot) = s.revision == revision && s.cookie == cookie
    @Synchronized fun <T> ifCurrent(s: NeteaseSessionSnapshot, block: () -> T): T? =
        if (isCurrent(s)) block() else null
}
