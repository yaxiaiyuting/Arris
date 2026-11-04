#!/usr/bin/env bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
src="$root/app/src/main/java/com/takahashirinta/ncrust"
cache=${GRADLE_USER_HOME:-$HOME/.gradle}
lib=$(find "$cache/wrapper/dists/gradle-9.3.1-bin" -path '*/lib/kotlin-compiler-embeddable-2.2.21.jar' -print -quit)
lib=${lib%/*}
jars=$(find "$cache/caches/modules-2/files-2.1" \( -name 'okhttp-4.12.0.jar' -o -name 'okio-jvm-3.9.1.jar' -o -name 'json-20231013.jar' -o -name 'gson-2.11.0.jar' \) -print | paste -sd :)
cp "$src"/update/{AppVersion,ForkRelease,UpdateChecker,ReleaseFeed}.kt "$src"/recognize/{RecogModels,NeteaseRecognizeProvider}.kt "$src/network/crypto/EapiCrypto.kt" "$tmp/"
{
  printf '%s\n' 'package com.takahashirinta.ncrust.update'
  sed -n '/^sealed interface CheckOutcome/,/^}/p' "$src/update/UpdateService.kt"
} > "$tmp/CheckOutcome.kt"
cat > "$tmp/Log.kt" <<'KT'
package android.util
object Log { fun i(tag: String, message: String) = 0 }
KT
cat > "$tmp/AppHttp.kt" <<'KT'
package com.takahashirinta.ncrust.network
object AppHttp { fun builder() = okhttp3.OkHttpClient.Builder() }
KT
{
  printf '%s\n' 'package com.takahashirinta.ncrust.player' 'import android.util.Log' 'object LevelProbe {' 'private const val TAG = "Audit"'
  sed -n '/^    private class LevelDenial(/,/^    private val transportFailures/{ /^    private val transportFailures/d; p; }' "$src/player/SongUrlFetcher.kt"
  printf '%s\n' 'fun run() {' 'check(!levelDenied("lossless"))' \
    'noteLevelRejected("lossless", "42")' 'check(levelDenied("lossless"))' \
    'com.takahashirinta.ncrust.auth.CookieManager.generation++' 'check(!levelDenied("lossless"))' \
    'println("I08: one track rejection already denies the entire level; switching account generation clears it.")' '}' '}'
} > "$tmp/LevelProbe.kt"
cat > "$tmp/Auth.kt" <<'KT'
package com.takahashirinta.ncrust.auth
object CookieManager {
    var generation = 1
    data class Snapshot(val loginGeneration: Int)
    fun snapshot() = Snapshot(generation)
}
KT
cat > "$tmp/Clock.kt" <<'KT'
package android.os
object SystemClock { fun elapsedRealtime() = System.nanoTime() / 1_000_000 }
KT
cat > "$tmp/Probe.kt" <<'KT'
package com.takahashirinta.ncrust.update
import com.takahashirinta.ncrust.player.LevelProbe
import com.takahashirinta.ncrust.recognize.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

fun main() = runBlocking {
    LevelProbe.run()
    val current = checkNotNull(AppVersion.parseOrNull("3.7.25"))
    val feed = """[{"tag_name":"lts-3.7","assets":[
      {"name":"Arris-v4.0.3-gpl.apk","browser_download_url":"https://audit.invalid/main.apk"},
      {"name":"Arris-v3.7.26-gpl.apk","browser_download_url":"https://audit.invalid/lts.apk"}
    ]}]"""
    val selected = evaluateReleases(listOf(feed), current) as CheckOutcome.Found
    check(selected.info.assetName == "Arris-v4.0.3-gpl.apk")
    println("I10: an lts-* release that contains both assets offers the 4.0.3 mainline APK to a 3.7.25 client.")

    val input = RecogInput.Encoded(byteArrayOf(1,2,3), "audio/mp4", 10_000L)
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        check(req.url.host == "audit.invalid")
        val status = req.url.encodedPath.removePrefix("/").substringBefore("-").toInt()
        val body = if (req.url.encodedPath.endsWith("-broken")) {
            object : ResponseBody() {
                private val broken = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("simulated body failure")
                    override fun timeout(): Timeout = Timeout.NONE
                    override fun close() {}
                }.buffer()
                override fun source() = broken
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
            }
        } else byteArrayOf().toResponseBody()
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
            .message("synthetic").code(status).body(body).build()
    }.build()
    val expected = mapOf("401" to RecogFailure.QUOTA_EXCEEDED, "403" to RecogFailure.QUOTA_EXCEEDED,
        "503" to RecogFailure.SERVER_ERROR, "200" to RecogFailure.PROTOCOL_CHANGED,
        "401-broken" to RecogFailure.NO_NETWORK, "503-broken" to RecogFailure.NO_NETWORK)
    for ((path, failure) in expected) {
        val result = NeteaseRecognizeProvider({ "fake=probe" }, client, "https://audit.invalid/$path").recognize(input)
        check((result as RecogOutcome.Failed).failure == failure)
    }
    println("I16: empty 401/403/503 and 200 controls pass, but 401 and 503 with body-read failure are reported as network failures.")
    println("Production level functions, update selector and recognition provider; fake account, intercepted HTTP.")
}
KT
cp="$lib/kotlin-stdlib-2.2.21.jar:$lib/kotlinx-coroutines-core-jvm-1.10.2.jar:$jars"
java --enable-native-access=ALL-UNNAMED -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -classpath "$cp" -d "$tmp/probe.jar" "$tmp/"*.kt
java -cp "$tmp/probe.jar:$cp" com.takahashirinta.ncrust.update.ProbeKt
