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
cp "$src"/listen/{P2pLink,RoomWire,RoomState,CanonicalTrack}.kt "$src"/listen/p2p/UdpReliable.kt "$src/network/BufferedHttpCall.kt" "$tmp/"
cat > "$tmp/StunClient.kt" <<'KT'
package com.takahashirinta.ncrust.listen.p2p
import java.net.DatagramSocket
import java.net.InetSocketAddress
object StunClient {
    fun queryMapped(socket: DatagramSocket) = InetSocketAddress("127.0.0.1", socket.localPort)
}
KT
cat > "$tmp/SystemClock.kt" <<'KT'
package android.os
object SystemClock { fun elapsedRealtime() = System.nanoTime() / 1_000_000L }
KT
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
import okio.buffer
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
    check(evaluateReleases(listOf("[1]"), current) == null)
    println("I09/I10: malformed feeds are rejected and an eligible LTS asset survives a mixed release.")

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
        check(callRef.get().isCanceled() && job.isCompleted)
        println("I15: cancellation cancels the HTTP Call before its interceptor is released.")
    } finally {
        release.countDown()
        job.join()
    }
    val failure = provider.recognize(input) as RecogOutcome.Failed
    check(failure.failure == RecogFailure.SERVER_ERROR)
    println("Production recognition provider: empty 503 now correctly classified as server error (I16).")
    val readCount = java.util.concurrent.atomic.AtomicInteger()
    val unreadableErrorClient = OkHttpClient.Builder().addInterceptor { chain ->
        val bytes = object : okio.Source {
            override fun timeout() = okio.Timeout.NONE
            override fun close() {}
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                readCount.incrementAndGet()
                throw java.io.IOException("error body must not be read")
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = bytes
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .message("Service Unavailable").code(503).body(body).build()
    }.build()
    val unreadable = NeteaseRecognizeProvider({ "fake=audit" }, unreadableErrorClient, "https://audit.invalid/recognize")
    check((unreadable.recognize(input) as RecogOutcome.Failed).failure == RecogFailure.SERVER_ERROR)
    check(readCount.get() == 0)
    println("I16: unreadable 503 body is not opened and status stays a server error.")

    val loopback = InetAddress.getByName("127.0.0.1")
    val token = P2pLink.newToken()
    val unsigned = UdpReliable.fragment(17, RoomWire.Ping(999).encode().toByteArray()).single()
    DatagramSocket(0, loopback).use { expected ->
        val member = P2pLink.join(token)
        try {
            val arrived = CountDownLatch(1)
            member.onMessage = { arrived.countDown() }
            member.startMember("127.0.0.1", expected.localPort)
            expected.soTimeout = 2_000
            val hello = DatagramPacket(ByteArray(200), 200)
            expected.receive(hello)
            check(String(hello.data, 0, hello.length).startsWith("H2:"))
            check(!String(hello.data, 0, hello.length).contains(token))
            expected.send(DatagramPacket(unsigned, unsigned.size, hello.socketAddress))
            check(!arrived.await(400, TimeUnit.MILLISECONDS))
        } finally {
            member.close()
        }
    }
    val host = P2pLink.host(token)
    val member = P2pLink.join(token)
    try {
        val opened = CountDownLatch(2)
        host.onOpen = { opened.countDown() }
        member.onOpen = { opened.countDown() }
        val address = checkNotNull(P2pLink.parseCode(checkNotNull(host.startHost())))
        member.startMember(address.first, address.second)
        check(opened.await(3, TimeUnit.SECONDS))
        val received = AtomicReference<RoomWire>()
        member.onMessage = { received.set(it) }
        val hostReceived = AtomicReference<RoomWire>()
        host.onMessage = { hostReceived.set(it) }
        DatagramSocket(0, loopback).use { outsider ->
            outsider.send(DatagramPacket(unsigned, unsigned.size, loopback, address.second))
        }
        check(member.send(RoomWire.Ping(21)))
        check(host.send(RoomWire.Pong(21, 22)))
        repeat(20) {
            if (received.get() == null || hostReceived.get() == null) delay(20)
        }
        check(hostReceived.get() == RoomWire.Ping(21))
        check(received.get() == RoomWire.Pong(21, 22))
        println("I14: pre-handshake data rejected; two real links authenticate, deliver and acknowledge after an outsider packet.")
    } finally {
        host.close()
        member.close()
    }
    println("All HTTP responses are interceptor doubles. UDP stayed on loopback; no real network, microphone, or account.")
}
KT
cp="$lib/kotlin-stdlib-2.2.21.jar:$lib/kotlinx-coroutines-core-jvm-1.10.2.jar:$jars"
java --enable-native-access=ALL-UNNAMED -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -classpath "$cp" -d "$tmp/probe.jar" "$tmp/"*.kt
java -cp "$tmp/probe.jar:$cp" com.takahashirinta.ncrust.update.ProbeKt
