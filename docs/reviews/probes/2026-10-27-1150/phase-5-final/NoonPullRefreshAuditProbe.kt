package com.takahashirinta.ncrust.audit

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.takahashirinta.ncrust.ui.components.PullToRefreshState
import com.takahashirinta.ncrust.ui.components.pullToRefresh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NoonPullRefreshAuditProbe {
    @Test fun twoIndependentSubthresholdPullsAccumulateAndTheFirstReleaseLeavesTheIndicatorArmed()=runBlocking {
        val state=PullToRefreshState();var refreshes=0
        val modifier=Modifier.pullToRefresh(state,{true},{refreshes++})
        val connection=modifier.foldIn<NestedScrollConnection?>(null) { previous,element ->
            val f=element.javaClass.declaredFields.firstOrNull { NestedScrollConnection::class.java.isAssignableFrom(it.type) }
            if(f==null) previous else { f.isAccessible=true;f.get(element) as NestedScrollConnection }
        }!!
        connection.onPostScroll(Offset.Zero,Offset(0f,80f),NestedScrollSource.Drag)
        assertEquals(80f,state.dragPx);assertEquals(0,refreshes)
        connection.onPreFling(Velocity.Zero);connection.onPostFling(Velocity.Zero,Velocity.Zero)
        assertEquals(80f,state.dragPx);assertFalse(state.refreshing)
        connection.onPostScroll(Offset.Zero,Offset(0f,80f),NestedScrollSource.Drag)
        assertEquals(1,refreshes);assertTrue(state.refreshing)
        println("AUDIT pull refresh: actual nested-scroll connection keeps dragPx80 after release (indicator condition remains true); a separate 80px gesture triggers the 160px refresh. No Compose UI, pointer injection, or device used")
    }
}
