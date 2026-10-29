package com.takahashirinta.ncrust.ui.player

import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.*
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DisabledQueuePointerHandlerTest {
    private class BoundedStop : RuntimeException()
    @Suppress("UNCHECKED_CAST")
    @Test fun compiledDisabledQueueHandlerReturnsWithoutEnteringTheGestureLoop() = runBlocking {
        var reads = 0
        var awaits = 0
        val awaitScope = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AwaitPointerEventScope::class.java)) { _, method, _ ->
            when {
                method.name == "getCurrentEvent" -> {
                    reads++
                    if (reads == 64) throw BoundedStop()
                    PointerEvent(emptyList())
                }
                method.name == "awaitPointerEvent" -> { awaits++; throw AssertionError("unexpected event suspension") }
                method.name == "getDensity" || method.name == "getFontScale" -> 1f
                method.returnType == java.lang.Long.TYPE -> 0L
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> null
            }
        } as AwaitPointerEventScope
        val pointerScope = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PointerInputScope::class.java)) { _, method, args ->
            when {
                method.name == "awaitPointerEventScope" -> {
                    val block = args!![0] as Function2<AwaitPointerEventScope, Continuation<Any?>, Any?>
                    val outer = args[1] as Continuation<Any?>
                    val restricted = object : Continuation<Any?> {
                        override val context = EmptyCoroutineContext
                        override fun resumeWith(result: Result<Any?>) { outer.resumeWith(result) }
                    }
                    block.invoke(awaitScope, restricted)
                }
                method.name == "getDensity" || method.name == "getFontScale" -> 1f
                method.returnType == java.lang.Long.TYPE -> 0L
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> null
            }
        } as PointerInputScope
        val cls = Class.forName("com.takahashirinta.ncrust.ui.player.QueueViewKt\$QueueView\$dragGesturesModifier\$1")
        val constructor = cls.declaredConstructors.single().apply { isAccessible = true }
        val args = constructor.parameterTypes.map { type ->
            when {
                type == java.lang.Boolean.TYPE -> false
                type == MutableIntState::class.java -> mutableIntStateOf(-1)
                type == MutableFloatState::class.java -> mutableFloatStateOf(0f)
                type == MutableState::class.java || type == State::class.java -> mutableStateOf<Any?>(null)
                else -> null
            }
        }.toTypedArray()
        val actual = constructor.newInstance(*args) as (suspend PointerInputScope.() -> Unit)
        var bounded = false
        try { actual.invoke(pointerScope) } catch (_: BoundedStop) { bounded = true }
        assertFalse("disabled handler must return normally", bounded)
        assertEquals(0, reads)
        assertEquals(0, awaits)
    }
}
