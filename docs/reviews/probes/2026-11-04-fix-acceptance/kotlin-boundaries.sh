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
cp "$src"/listen/{P2pLink,RoomWire,RoomState,CanonicalTrack}.kt "$src"/listen/p2p/{UdpReliable,StunClient}.kt "$tmp/"
cat > "$tmp/RoomLink.kt" <<'KT'
package com.takahashirinta.ncrust.listen
interface RoomLink {
    var onMessage: ((RoomWire) -> Unit)?
    var onClosed: ((Int, String) -> Unit)?
    var onOpen: (() -> Unit)?
    fun send(message: RoomWire): Boolean
    fun close()
}
KT
cat > "$tmp/Log.kt" <<'KT'
package android.util
object Log { fun i(tag: String, message: String) = 0 }
KT
cat > "$tmp/AppHttp.kt" <<'KT'
package com.takahashirinta.ncrust.network
object AppHttp { fun builder() = okhttp3.OkHttpClient.Builder() }
KT
cat > "$tmp/Probe.kt" <<'KT'
package com.takahashirinta.ncrust.update

import com.takahashirinta.ncrust.recognize.*
import com.takahashirinta.ncrust.listen.*
import com.takahashirinta.ncrust.listen.p2p.UdpReliable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody

fun main() = runBlocking {
    val current = checkNotNull(AppVersion.parseOrNull("3.7.24"))
    val mixed = """[{"tag_name":"rolling","assets":[
      {"name":"Arris-v4.0.2-gpl.apk","browser_download_url":"https://audit.invalid/main.apk"},
      {"name":"Arris-v3.7.25-gpl.apk","browser_download_url":"https://audit.invalid/lts.apk"}
    ]}]"""
    val ltsOnly = """[{"tag_name":"lts-3.7","assets":[{"name":"Arris-v3.7.25-gpl.apk","browser_download_url":"https://audit.invalid/lts.apk"}]}]"""
    check((evaluateReleases(listOf(mixed), current) as CheckOutcome.Found).info.assetName == "Arris-v3.7.25-gpl.apk")
    check(evaluateReleases(listOf(ltsOnly), current) is CheckOutcome.Found)
    check(evaluateReleases(listOf("[1]"), current) == CheckOutcome.UpToDate)
    println("Production update selector: mixed release now finds the eligible LTS result (I10); deferred malformed [1] still reports UpToDate (I09).")

    val input = RecogInput.Encoded(byteArrayOf(1, 2, 3), "audio/mp4", 10_000L)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val callRef = AtomicReference<Call>()
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        check(chain.request().url.host == "audit.invalid")
        callRef.set(chain.call())
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .message("Service Unavailable").code(503).body(byteArrayOf().toResponseBody()).build()
    }.build()
    val provider = NeteaseRecognizeProvider({ "fake=audit" }, client, "https://audit.invalid/recognize")
    val job = launch(Dispatchers.IO) { provider.recognize(input) }
    try {
        check(entered.await(4, TimeUnit.SECONDS))
        job.cancel()
        delay(150)
        check(!callRef.get().isCanceled() && !job.isCompleted)
        println("Production recognition provider: coroutine cancellation still leaves synchronous HTTP call active (I15).")
    } finally {
        release.countDown()
        job.join()
    }
    val failure = provider.recognize(input) as RecogOutcome.Failed
    check(failure.failure == RecogFailure.SERVER_ERROR)
    println("Production recognition provider: empty 503 now correctly classified as server error (I16).")
    val loopback = InetAddress.getByName("127.0.0.1")
    DatagramSocket(0, loopback).use { expected ->
        DatagramSocket(0, loopback).use { outsider ->
            val p2p = P2pLink.join("probe-token")
            try {
                val received = AtomicReference<RoomWire>()
                val arrived = CountDownLatch(1)
                p2p.onMessage = { received.set(it); arrived.countDown() }
                p2p.startMember("127.0.0.1", expected.localPort)
                expected.soTimeout = 2000
                val hello = DatagramPacket(ByteArray(100), 100)
                expected.receive(hello)
                check(String(hello.data, 0, hello.length) == "HELLO:probe-token")
                val packet = UdpReliable.fragment(17, RoomWire.Ping(123).encode().toByteArray()).single()
                expected.send(DatagramPacket(packet, packet.size, hello.socketAddress))
                check(arrived.await(2, TimeUnit.SECONDS) && received.get() == RoomWire.Ping(123))
                println("Configured peer DATA accepted before HELLO-ACK (I14).")
                val later = CountDownLatch(1)
                p2p.onMessage = { later.countDown() }
                outsider.send(DatagramPacket(packet, packet.size, hello.socketAddress))
                Thread.sleep(250)
                expected.send(DatagramPacket(packet, packet.size, hello.socketAddress))
                check(!later.await(500, TimeUnit.MILLISECONDS))
                println("After one wrong-source DATA packet, subsequent expected-peer DATA is not received (I14).")
            } finally {
                p2p.close()
            }
        }
    }
    println("All HTTP responses are interceptor doubles. UDP stayed on loopback; no real network, microphone, or account.")
}
KT
cp="$lib/kotlin-stdlib-2.2.21.jar:$lib/kotlinx-coroutines-core-jvm-1.10.2.jar:$jars"
java --enable-native-access=ALL-UNNAMED -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -classpath "$cp" -d "$tmp/probe.jar" "$tmp/"*.kt
java -cp "$tmp/probe.jar:$cp" com.takahashirinta.ncrust.update.ProbeKt
