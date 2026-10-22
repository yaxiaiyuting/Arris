package com.takahashirinta.ncrust.celebration

import com.takahashirinta.ncrust.ui.i18n.celebrationFor
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import com.takahashirinta.ncrust.ui.settings.SettingsRowKind
import org.junit.Assert.*
import org.junit.Test

class CelebrationRulesTest {
    @Test fun invalidDatesNeverMatch() {
        for((m,d) in listOf(0 to 1,13 to 1,1 to 0,4 to 31,2 to 30)) {
            assertFalse(BirthdayRule.valid(m,d));assertFalse(BirthdayRule.matches(m,d,2026,m,d))
        }
    }
    @Test fun february29UsesFebruary28OnlyInNonLeapYears() {
        assertTrue(BirthdayRule.matches(2,29,2026,2,28))
        assertFalse(BirthdayRule.matches(2,29,2028,2,28))
        assertTrue(BirthdayRule.matches(2,29,2028,2,29))
        assertTrue(BirthdayRule.matches(2,29,1900,2,28))
        assertTrue(BirthdayRule.matches(2,29,2000,2,29))
        assertFalse(BirthdayRule.matches(2,29,2026,3,1))
    }
    @Test fun annualDedupeSurvivesReturningToEarlierYear() {
        assertFalse(BirthdayRule.eligible(10,22,true,2026,10,22,setOf("2026","2027")))
        assertTrue(BirthdayRule.eligible(10,22,true,2028,10,22,setOf("2026","2027")))
        assertFalse(BirthdayRule.eligible(10,22,false,2028,10,22,emptySet()))
    }
    @Test fun exactSeventhTapTriggersOnceAndClosingRearms() {
        val gate=VersionTapGate()
        repeat(6) {assertFalse(gate.tap(it*300L))}
        assertTrue(gate.tap(1800));assertFalse(gate.tap(1900));assertEquals(7,gate.count)
        gate.reset();assertFalse(gate.tap(2000));assertEquals(1,gate.count)
    }
    @Test fun slowAndBackwardsClocksResetTheTapSequence() {
        val g=VersionTapGate();g.tap(100);g.tap(1301);assertEquals(1,g.count)
        g.tap(1200);assertEquals(1,g.count)
        g.reset();repeat(6) {g.tap(it*1100L)};assertFalse(g.tap(6600));assertEquals(1,g.count)
    }
    @Test fun boundarySixSecondSequenceIsAllowed() {
        val g=VersionTapGate();repeat(6) {g.tap(it*1000L)};assertTrue(g.tap(6000))
    }
    private fun calibrated():BlowGate = BlowGate().also {g -> repeat(40) {assertFalse(g.sample(.008,.7,.2,20))}}
    @Test fun silenceAndHarmonicLoudSignalsDoNotBlowOutCandles() {
        val g=calibrated();repeat(100) {assertFalse(g.sample(0.0,0.0,0.0,20));assertFalse(g.sample(.2,.1,.015,20,1.42))}
    }
    @Test fun transientNoiseDoesNotTriggerButSustainedPuffDoes() {
        val g=calibrated();repeat(3) {assertFalse(g.sample(.1,1.2,.25,20))}
        repeat(3) {assertFalse(g.sample(.008,.7,.2,20))}
        repeat(4) {assertFalse(g.sample(.1,1.2,.25,20))}
        assertTrue(g.sample(.1,1.2,.25,20))
    }
    @Test fun invalidFeaturesCannotTriggerAndCalibrationIgnoresPuffs() {
        val g=BlowGate();repeat(18) {assertFalse(g.sample(.2,1.2,.25,20))}
        assertFalse(g.sample(Double.NaN,2.0,.5,20));assertFalse(g.sample(.2,Double.POSITIVE_INFINITY,.5,20))
    }
    @Test fun gentleLowFrequencyPuffAndOneShortDropoutAreAccepted() {
        val g=calibrated()
        repeat(3) {assertFalse(g.sample(.018,.015,.008,20,2.3))}
        assertFalse(g.sample(.008,.7,.2,20))
        assertFalse(g.sample(.018,.015,.008,20,2.3))
        assertTrue(g.sample(.018,.015,.008,20,2.3))
    }
    @Test fun puffDuringCalibrationDoesNotRequireBlowingEvenHarderAfterwards() {
        val g=BlowGate()
        repeat(8) {assertFalse(g.sample(.003,.5,.1,20))}
        repeat(10) {assertFalse(g.sample(.1,.015,.008,20,2.5))}
        repeat(4) {assertFalse(g.sample(.012,.015,.008,20,2.5))}
        assertTrue(g.sample(.012,.015,.008,20,2.5))
    }
    @Test fun isolatedClicksAndSteadyRoomNoiseDoNotAccumulate() {
        val g=calibrated()
        repeat(30) {
            assertFalse(g.sample(.3,1.2,.25,20))
            repeat(4) {assertFalse(g.sample(.009,.7,.2,20))}
        }
    }
    @Test fun simulationLaunchesThenExplodesAndUsesBoundedPoolAtEveryRefreshRate() {
        for(hz in listOf(15,60,90,120)) {
            val m=FireworkSimulation(740f)
            repeat(hz/2) {m.advance(1f/hz)}
            assertEquals(0,m.burstCount);assertTrue(m.shells.first().launched)
            repeat(hz*9) {m.advance(1f/hz);assertTrue(m.activeCount in 0..3000)}
            assertEquals(7,m.burstCount);assertEquals(0,m.activeCount)
        }
    }
    @Test fun invalidSimulationDeltaDoesNotMoveTimeAndClearDropsParticles() {
        val m=FireworkSimulation(740f);m.advance(Float.NaN);m.advance(-1f);assertEquals(0f,m.elapsed)
        repeat(150) {m.advance(.016f)};assertTrue(m.activeCount>0);m.clear();assertEquals(0,m.activeCount)
    }
    @Test fun eightLocalesHaveAllNineStageMessagesAndThreeWishes() {
        for(code in listOf("zh-CN","zh-TW","en-US","ja-JP","ja-MY","ko-KP","de-DE","ru-RU")) {
            val s=celebrationFor(code);assertEquals(code,9,s.titles.size);assertEquals(code,9,s.captions.size);assertEquals(code,3,s.wishes.size)
            assertTrue(s.titles.all {it.isNotBlank()});assertTrue(s.captions.all {it.isNotBlank()});assertTrue(s.wishes.all {it.isNotBlank()})
            assertTrue(s.remaining(3).contains("3"));assertFalse(s.micCalibrating.isBlank())
        }
    }
    @Test fun registryHasExactlyTheSixBirthdayKeysAndFourVisibleControls() {
        val keys=SettingsRegistry.allEntries().filter {it.isNewInV360}.map {it.key}.toSet()
        assertEquals(setOf("birthday_month","birthday_day","birthday_shown_years","birthday_opened_years","birthday_celebration_enabled","easter_egg_animation_enabled"),keys)
        val rows=SettingsRegistry.allEntries().filter {SettingsRenderPlan.rowKindOf(it)==SettingsRowKind.CELEBRATION}
        assertEquals(setOf("action.birthday_date","action.birthday_preview","birthday_celebration_enabled","easter_egg_animation_enabled"),rows.map {it.id}.toSet())
    }
}
