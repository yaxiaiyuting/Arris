package com.takahashirinta.ncrust.auth

import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.Response
import org.json.JSONObject

/**
 * The credential generation changed while a response was in flight.
 *
 * Deliberately NOT a [CancellationException]: that type means "this coroutine is being torn down"
 * and callers swallow it, which would silently stop the session monitor instead of letting it
 * re-run against the new login. A transport-shaped failure lets every existing catch path keep
 * working while still refusing to hand an old account's response to the new one.
 */
class NeteaseSessionChangedException(message: String) : IOException(message)

/** Explicit read-only entry point. Responses remain owned by the caller, except the replaced one. */
object NeteaseSessionRequests {
    suspend fun read(once: suspend () -> Response): Response {
        NeteaseTokenRefresher.scheduleIfNeeded()
        return renewRead(
            once = once,
            snapshot = { it.request.tag(NeteaseSessionSnapshot::class.java) },
            current = CookieManager::snapshot,
            rejected = { response ->
                try {
                    val json = JSONObject(response.peekBody(131072).string())
                    json.optInt("code", -1) == 301 ||
                        (response.request.url.encodedPath.endsWith("/w/nuser/account/get") && json.optInt("code", -1) == 200 &&
                            json.isNull("account") && json.isNull("profile"))
                } catch (_: Exception) { false }
            },
            refresh = NeteaseTokenRefresher::refreshAfterRejection,
            discard = Response::close,
        )
    }
}

/** Pure orchestration seam for concurrent renewal, account switches, and response ownership tests. */
internal suspend fun <T> renewRead(
    once: suspend () -> T,
    snapshot: (T) -> NeteaseSessionSnapshot?,
    current: () -> NeteaseSessionSnapshot,
    rejected: (T) -> Boolean,
    refresh: suspend (NeteaseSessionSnapshot) -> NeteaseTokenRefresher.Result,
    discard: (T) -> Unit,
): T {
    fun checked(value: T): T {
        val started = snapshot(value)
        if (started != null && started.loginGeneration != current().loginGeneration) {
            discard(value)
            throw NeteaseSessionChangedException("Netease login changed during read")
        }
        return value
    }
    val first = checked(once())
    val started = snapshot(first) ?: return first
    if (!rejected(first) || started.cookie.isNullOrBlank()) return first
    val result = try { refresh(started) }
        catch (e: CancellationException) { discard(first); throw e }
    val now = current()
    if (started.loginGeneration == now.loginGeneration && !now.cookie.isNullOrBlank() &&
        (result == NeteaseTokenRefresher.Result.REFRESHED || result == NeteaseTokenRefresher.Result.STALE)) {
        discard(first)
        return checked(once()) // No recursive wrapper: a second rejection is returned to the caller.
    }
    return checked(first)
}
