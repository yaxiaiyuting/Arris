package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.ui.player.ControlsDragSnap
import com.takahashirinta.ncrust.ui.player.PlayerCardDragSnap
import org.junit.Assert.*
import org.junit.Test

class NoonGestureAuditProbe {
    @Test fun theCardsProgressVelocityHasTheOppositeSignToTheSnapContract() {
        val from=0f;val end=0.04f;val distance=1000f;val seconds=0.01f
        val supplied=(end-from)*distance/seconds // PlayerCard.kt:492-495
        assertEquals(0f,PlayerCardDragSnap.target(from,end,supplied))
        assertEquals(1f,PlayerCardDragSnap.target(from,end,-supplied))
        println("AUDIT card gesture composition: upward 40px / 10ms increases progress; the caller passes +4000px/s to a contract requiring negative upward velocity, so a short fast upward fling returns collapsed. Pure decision/entry-formula evidence, no pointer event injected")
    }
    @Test fun aFastControlsUpwardDragSnapsBackEvenAfterCrossingTheDistanceThreshold() {
        val from=0f;val end=0.3f;val height=400f;val seconds=0.05f
        val supplied=(end-from)*height/seconds // PlayerCard.kt:989-992
        assertEquals(0f,ControlsDragSnap.target(from,end,supplied))
        assertEquals(1f,ControlsDragSnap.target(from,end,-supplied))
        println("AUDIT controls gesture composition: upward 120px / 50ms supplies positive velocity; velocity branch overrides the already-crossed 12% collapse distance and restores the controls. Correct screen-Y sign yields collapse")
    }
}
