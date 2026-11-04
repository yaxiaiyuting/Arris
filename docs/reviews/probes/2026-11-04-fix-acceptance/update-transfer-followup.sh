#!/usr/bin/env bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
src="$root/app/src/main/java/com/takahashirinta/ncrust"
cache=${GRADLE_USER_HOME:-$HOME/.gradle}
lib=$(find "$cache/wrapper/dists/gradle-9.3.1-bin" -path '*/lib/kotlin-compiler-embeddable-2.2.21.jar' -print -quit)
lib=${lib%/*}
jars=$(find "$cache/caches/modules-2/files-2.1" \( -name 'okhttp-4.12.0.jar' -o -name 'okio-jvm-3.9.1.jar' \) -print | paste -sd :)
cp "$src/update/SegmentedDownload.kt" "$tmp/"
{
  printf '%s\n' 'package com.takahashirinta.ncrust.update' 'import java.io.File' 'import java.io.IOException' 'import java.util.concurrent.atomic.AtomicLong'
  sed -n '/^class DownloadCancelledException /p; /^internal data class ContentRange/,/^}$/p' "$src/update/UpdateDownloader.kt"
  printf '%s\n' 'object UpdateService {' '    private val downloadGeneration = AtomicLong(1)' '    fun bumpGeneration() = downloadGeneration.incrementAndGet()'
  sed -n '/^    const val USER_AGENT /p; /^    private fun downloadWithFallback(/,/^    }/p; /^    private fun activeGenerationSnapshot()/p; /^    private fun fileNameOf(/,/^    }/p' "$src/update/UpdateService.kt" |
    sed 's/private fun downloadWithFallback(/fun downloadWithFallback(/'
  printf '%s\n' '}' 'data class UpdateInfo(val assetUrl: String, val assetSize: Long, val assetName: String)' 'class UpdateDownloader {'     'fun download(url: String, fileName: String, identity: String, expectedSize: Long, onProgress: (Long, Long) -> Unit): File = error("Unexpected fallback")' '}'
} >"$tmp/Boundary.kt"
cat >"$tmp/AndroidLog.kt" <<'KT'
package android.util
object Log {
    fun i(tag: String, message: String) = 0
    fun w(tag: String, message: String) = 0
}
KT
cat >"$tmp/AndroidClock.kt" <<'KT'
package android.os
object SystemClock { fun elapsedRealtime() = System.nanoTime() / 1_000_000 }
KT
cat >"$tmp/AppHttp.kt" <<'KT'
package com.takahashirinta.ncrust.network

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object AddressPreference
object AppHttp {
    const val TOTAL = 5 * 1024 * 1024
    val probeEntered = CountDownLatch(1)
    val releaseProbe = CountDownLatch(1)
    val activeSegment = AtomicReference<Call>()
    val segmentEntered = CountDownLatch(1)
    val releaseSegment = CountDownLatch(1)

    fun builder() = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        check(request.url.host == "audit.invalid")
        val path = request.url.encodedPath
        val (start, end) = checkNotNull(request.header("Range")).removePrefix("bytes=").split("-").map(String::toInt)
        val probe = start == 0 && end == 0
        if (probe && path == "/old") {
            probeEntered.countDown()
            check(releaseProbe.await(5, TimeUnit.SECONDS))
        }
        if (!probe && path == "/replacement") {
            activeSegment.compareAndSet(null, chain.call())
            segmentEntered.countDown()
            check(releaseSegment.await(5, TimeUnit.SECONDS))
        }
        val range = if (!probe && path == "/bad-range") "bytes 1-$end/$TOTAL" else "bytes $start-$end/$TOTAL"
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .message("Partial Content").code(206).header("Content-Range", range)
            .body(ByteArray(end - start + 1) { if (path == "/old") 3 else 7 }.toResponseBody())
        if (path != "/no-etag" && (probe || path != "/missing-segment-etag")) {
            response.header("ETag", if (!probe && path == "/bad-etag") "\"changed\"" else "\"current\"")
        }
        response.build()
    }
}
KT
cat >"$tmp/Probe.kt" <<'KT'
package com.takahashirinta.ncrust.update

import com.takahashirinta.ncrust.network.AppHttp
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val dir = File(args.single())
    val base = "https://audit.invalid"
    val oldResult = AtomicReference<Result<File>>()
    val old = thread {
        oldResult.set(runCatching {
            UpdateService.downloadWithFallback(
                UpdateDownloader(), UpdateInfo("$base/old", AppHttp.TOTAL.toLong(), "latest.apk"), dir, "old"
            ) { _, _ -> }
        })
    }
    check(AppHttp.probeEntered.await(4, TimeUnit.SECONDS))
    UpdateService.bumpGeneration() // cancel old
    SegmentedDownload.cancelAll()
    UpdateService.bumpGeneration() // same-target retry
    val replacement = thread {
        runCatching {
            UpdateService.downloadWithFallback(
                UpdateDownloader(), UpdateInfo("$base/replacement", AppHttp.TOTAL.toLong(), "latest.apk"), dir, "new"
            ) { _, _ -> }
        }
    }
    try {
        check(AppHttp.segmentEntered.await(4, TimeUnit.SECONDS))
        check(File(dir, "latest.apk.seg.3").isFile)
        AppHttp.releaseProbe.countDown()
        old.join(4000)
        check(!old.isAlive)
        val stale = oldResult.get().getOrThrow()
        check(stale.readBytes().all { it == 3.toByte() })
        check(!File(dir, "latest.apk.seg.3").exists())
        println("I03: a pre-cancel probe can resume after a same-target retry, take its .seg.3 path, and publish old bytes while the retry is in flight.")
    } finally {
        AppHttp.releaseProbe.countDown()
        AppHttp.releaseSegment.countDown()
        old.join(4000)
        replacement.join(4000)
    }

    for (path in listOf("bad-range", "bad-etag", "no-etag", "missing-segment-etag")) {
        val url = "$base/$path"
        val probe = checkNotNull(SegmentedDownload.probe(url))
        val dst = File(dir, "$path.part")
        val result = runCatching { SegmentedDownload.download(url, dst, probe, { false }) { _, _ -> } }
        if (path.startsWith("bad-")) {
            check(result.exceptionOrNull() is IOException)
            check(dst.readBytes().all { it == 0.toByte() })
        } else {
            result.getOrThrow()
            check(dst.readBytes().all { it == 7.toByte() })
        }
    }
    println("I04: explicit incorrect Content-Range and changed ETag are rejected before writing. Missing validators at probe or segment time still allow assembly.")
    println("Production SegmentedDownload and extracted UpdateService branch; no network or real APK.")
}
KT
cp="$lib/kotlin-stdlib-2.2.21.jar:$jars"
java --enable-native-access=ALL-UNNAMED -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -classpath "$cp" -d "$tmp/probe.jar" "$tmp/"*.kt
java -cp "$tmp/probe.jar:$cp" com.takahashirinta.ncrust.update.ProbeKt "$tmp"
