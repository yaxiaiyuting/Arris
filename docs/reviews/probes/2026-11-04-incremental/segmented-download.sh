#!/usr/bin/env bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
src="$root/app/src/main/java/com/takahashirinta/ncrust/update"
lib=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-9.3.1-bin" -path '*/lib/kotlin-compiler-embeddable-2.2.21.jar' -print -quit)
lib=${lib%/*}
okhttp=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/4.12.0" -name 'okhttp-4.12.0.jar' -print -quit)
okio=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.squareup.okio/okio-jvm/3.9.1" -name 'okio-jvm-3.9.1.jar' -print -quit)
cp "$src/SegmentedDownload.kt" "$tmp/"
{
  printf '%s\n' 'package com.takahashirinta.ncrust.update' 'import java.io.IOException' 'import java.util.concurrent.atomic.AtomicLong' 'object UpdateService { const val USER_AGENT = "ArrisAudit" }'
  sed -n '/^class DownloadCancelledException /p; /^internal data class ContentRange/,/^}$/p' "$src/UpdateDownloader.kt"
  sed -n '/^    private fun activeGenerationSnapshot()/p' "$src/UpdateService.kt"
  printf '%s\n' 'private val downloadGeneration = AtomicLong(1)' 'fun bumpGeneration() = downloadGeneration.incrementAndGet()'
  printf 'fun cancelPredicate(): () -> Boolean = '
  sed -n 's/^ *cancelled = \(.*\),$/\1/p' "$src/UpdateService.kt"
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
import com.takahashirinta.ncrust.update.bumpGeneration
object AddressPreference
object AppHttp {
    private const val TOTAL = 4 * 1024 * 1024
    fun builder() = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        check(request.url.host == "audit.invalid")
        val range = checkNotNull(request.header("Range")).removePrefix("bytes=").split("-")
        val start = range[0].toInt()
        val end = range[1].toInt()
        val probe = start == 0 && end == 0
        if (!probe) bumpGeneration()
        val size = end - start + 1
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Partial Content").code(206)
            .header("Content-Range", if (probe) "bytes 0-0/$TOTAL" else "bytes 0-${size - 1}/${TOTAL + 1}")
            .header("ETag", if (probe) "\"old\"" else "\"replacement\"")
            .body(ByteArray(size) { if (probe) 1 else 7 }.toResponseBody()).build()
    }
}
KT
cat >"$tmp/Probe.kt" <<'KT'
package com.takahashirinta.ncrust.update
import java.io.File
fun main(args: Array<String>) {
    val url = "https://audit.invalid/asset.apk"
    val file = File(args.single(), "asset.seg")
    val probe = checkNotNull(SegmentedDownload.probe(url))
    val cancel = cancelPredicate()
    var bytes = 0L
    SegmentedDownload.download(url, file, probe, cancel) { received, _ ->
        synchronized(file) { bytes = maxOf(bytes, received) }
    }
    check(!cancel() && bytes == probe.total)
    check(file.readBytes().all { it == 7.toByte() })
    println("Defect: after generation changes, all segments wrote $bytes bytes and returned success.")
    println("Defect: 206 segments with changed ETag and incorrect Content-Range accepted.")
    println("Production SegmentedDownload, parser and extracted cancellation expression; synthetic interceptor, no network or APK verification.")
}
KT
cp="$lib/kotlin-stdlib-2.2.21.jar:$okhttp:$okio"
java -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -classpath "$cp" -d "$tmp/probe.jar" "$tmp/"*.kt
java -cp "$tmp/probe.jar:$cp" com.takahashirinta.ncrust.update.ProbeKt "$tmp"
