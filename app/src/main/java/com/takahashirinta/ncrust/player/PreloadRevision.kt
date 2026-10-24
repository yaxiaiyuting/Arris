package com.takahashirinta.ncrust.player

import java.util.concurrent.atomic.AtomicLong

/** Shared by the request producer and Service consumer, including already queued Intents. */
internal class PreloadGeneration {
    private val value = AtomicLong()
    fun current(): Long = value.get()
    fun invalidate(): Long = value.incrementAndGet()
    fun accepts(captured: Long): Boolean = captured == value.get()
}
internal val preloadGeneration = PreloadGeneration()
