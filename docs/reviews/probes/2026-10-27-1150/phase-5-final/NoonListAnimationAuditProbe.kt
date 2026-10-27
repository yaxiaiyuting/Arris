package com.takahashirinta.ncrust.audit

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.takahashirinta.ncrust.ui.components.listItemAppear
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NoonListAnimationAuditProbe {
    private class EmptyApplier:AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index:Int,instance:Unit) {}
        override fun insertTopDown(index:Int,instance:Unit) {}
        override fun move(from:Int,to:Int,count:Int) {}
        override fun remove(index:Int,count:Int) {}
        override fun onClear() {}
    }
    private fun animation(modifier:Modifier):Animatable<*,*> = modifier.foldIn<Animatable<*,*>?>(null) { old,element ->
        val functions=element.javaClass.declaredFields.filter { kotlin.jvm.functions.Function1::class.java.isAssignableFrom(it.type) }
        val result=functions.firstNotNullOfOrNull { f ->
            f.isAccessible=true;val fn=f.get(element) ?: return@firstNotNullOfOrNull null
            fn.javaClass.declaredFields.firstOrNull { Animatable::class.java.isAssignableFrom(it.type) }?.let { a ->a.isAccessible=true;a.get(fn) as Animatable<*,*> }
        }
        result ?: old
    }!!
    @Test fun movingAnEnteringItemBeyondTheAnimationRangeLeavesItPermanentlyTranslucent()=runBlocking {
        withTimeout(6000) {
            val clock=BroadcastFrameClock()
            val recomposer=Recomposer(coroutineContext+clock)
            val composition=Composition(EmptyApplier(),recomposer)
            val runner=launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val index=mutableIntStateOf(0);var modifier:Modifier=Modifier
            suspend fun frame(nanos:Long) { Snapshot.sendApplyNotifications();repeat(3) {yield()};clock.sendFrame(nanos);repeat(8) {yield()} }
            try {
                composition.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {modifier=Modifier.listItemAppear(index.intValue)} }
                frame(0);frame(40_000_000L)
                val progress=animation(modifier)
                index.intValue=20;frame(60_000_000L)
                val stopped=progress.value as Float
                assertTrue("stopped=$stopped",stopped<1f)
                frame(1_000_000_000L);frame(2_000_000_000L)
                assertEquals(stopped,progress.value as Float,0f)
                assertSame(progress,animation(modifier))
                println("AUDIT real Compose runtime/no UI: index0 enters, changes to20 before completion; conditional LaunchedEffect is cancelled but the remembered alpha stays $stopped even after 2s. No window, Canvas, browser, emulator, or device")
            } finally {composition.dispose();recomposer.close();runner.cancelAndJoin()}
        }
    }
}
