package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.qq.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ScheduledMqttAuditProbe {
    private class FakeSocket:WebSocket {
        var canceled=false;var closes=0
        override fun request()=Request.Builder().url("https://local-fixture.invalid/").build()
        override fun queueSize()=0L
        override fun send(text:String)=true
        override fun send(bytes:ByteString)=true
        override fun close(code:Int,reason:String?):Boolean { closes++;return true }
        override fun cancel() { canceled=true }
    }
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private fun connection(transport:Any,socket:FakeSocket):WebSocketListener {
        val type=transport.javaClass.declaredClasses.single { it.simpleName=="Connection" }
        val conn=type.declaredConstructors.single().apply { isAccessible=true }.newInstance(transport)
        field(conn,"socket").set(conn,socket)
        return conn as WebSocketListener
    }
    @Test fun failureFromTheResetSocketKillsTheNewQrSession()=runBlocking {
        val session=QqQrMqttSession("fixture-qr","fixture-client")
        val transport=field(session,"transport").get(session) as MqttWebSocketTransport
        val oldSocket=FakeSocket();val old=connection(transport,oldSocket)
        field(transport,"active").set(transport,old)
        transport.reset()
        assertTrue(oldSocket.canceled)
        val newSocket=FakeSocket();val current=connection(transport,newSocket)
        field(transport,"active").set(transport,current)
        try {
            // Mirrors an asynchronous cancel callback arriving after redirect/reset.
            old.onFailure(oldSocket,IOException("fixture canceled old socket"),null)
            val body=MqttCodec.encodeString("management.qrcode_login/fixture-qr")+
                MqttCodec.encodeProperties(listOf(MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY,"type","scanned")))
            current.onMessage(newSocket,MqttCodec.packet(MqttCodec.PUBLISH,0,body).toByteString())
            val event=session.awaitEvent(200)
            assertTrue(event is QqQrMqttSession.Event.Failed)
            assertTrue(transport.send(byteArrayOf(1)))
            assertEquals(QqQrMqttSession.Event.Scanned,session.awaitEvent(200))
            println("AUDIT QQ MQTT generation: late onFailure from the canceled old socket inserts DISCONNECT into the new session, before its valid scanned event")
        } finally { session.close() }
    }
    @Test fun serverClosingFrameIsNeitherAcknowledgedNorReportedToTheWaitingSession()=runBlocking {
        val session=QqQrMqttSession("fixture-qr","fixture-client")
        val transport=field(session,"transport").get(session) as MqttWebSocketTransport
        val socket=FakeSocket();val current=connection(transport,socket)
        field(transport,"active").set(transport,current)
        try {
            current.onClosing(socket,1000,"fixture server closes")
            assertEquals(0,socket.closes)
            assertFalse(socket.canceled)
            assertNull(session.awaitEvent(30))
            println("AUDIT QQ MQTT close: receiving onClosing produces neither WebSocket close acknowledgement nor a terminal event; the QR waiter only times out")
        } finally { session.close() }
    }
}
