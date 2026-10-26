package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.update.DownloadCancelledException
import com.takahashirinta.ncrust.update.UpdateDownloader
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScheduledDownloadAuditProbe {
    @Test fun separateDownloaderInstancesStillSharePartOneAndLoseTheNewPackage() = runBlocking {
        val dir=Files.createTempDirectory("arris-downloader-audit").toFile()
        val old=UpdateDownloader(dir); val next=UpdateDownloader(dir)
        val oldWrote=CountDownLatch(1); val newWrote=CountDownLatch(1)
        val releaseOld=CountDownLatch(1); val releaseNew=CountDownLatch(1)
        val clients=mutableListOf<OkHttpClient>()
        fun wire(d: UpdateDownloader, byte: Byte) {
            val fake=OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("audit")
                    .header("ETag","\"fixture-$byte\"").body(ByteArray(1024) { byte }.toResponseBody()).build()
            }.build()
            val f=d.javaClass.getDeclaredField("client").apply { isAccessible=true }; f.set(d,fake)
            clients+=fake
        }
        wire(old,65); wire(next,66)
        val oldTask=async(Dispatchers.IO) { runCatching {
            old.download("https://fixture.invalid/a","update.apk","asset-A",1024) { done,_ ->
                if(done>0) { oldWrote.countDown(); check(releaseOld.await(5,TimeUnit.SECONDS)) }
            }
        } }
        var newTask: Deferred<Result<java.io.File>>?=null
        try {
            assertTrue(oldWrote.await(4,TimeUnit.SECONDS))
            old.cancel()
            val job=async(Dispatchers.IO) { runCatching {
                next.download("https://fixture.invalid/b","update.apk","asset-B",1024) { done,_ ->
                    if(done>0) { newWrote.countDown(); check(releaseNew.await(5,TimeUnit.SECONDS)) }
                }
            } }
            newTask=job
            assertTrue(newWrote.await(4,TimeUnit.SECONDS))
            assertEquals(listOf("update.apk.part-1"),dir.listFiles()!!.filter { ".part-" in it.name }.map { it.name })
            releaseOld.countDown()
            assertTrue(withTimeout(5000) { oldTask.await() }.exceptionOrNull() is DownloadCancelledException)
            assertTrue("old cleanup moved the newer task's file",dir.resolve("update.apk").isFile)
            releaseNew.countDown()
            val outcome=withTimeout(5000) { job.await() }
            assertTrue("new download cannot deliver a part file already moved by the old instance",outcome.isFailure)
            assertFalse("new completion deletes the moved target then its rename fails",dir.resolve("update.apk").exists())
            println("AUDIT updater: two real service-style instances both use .part-1; old cancellation consumes new part and final APK disappears")
        } finally {
            releaseOld.countDown(); releaseNew.countDown()
            oldTask.cancelAndJoin(); newTask?.cancelAndJoin()
            clients.forEach { it.dispatcher.executorService.shutdown() }; dir.deleteRecursively()
        }
    }
}
