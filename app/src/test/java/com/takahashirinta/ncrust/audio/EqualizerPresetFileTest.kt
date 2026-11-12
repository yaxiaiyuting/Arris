/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerPresetFileTest {
    private val preset = EqualizerPreset("人声 · 夜间", listOf(100, -200, 0, 30, 40, 1200, -1200, 70, 80, 90))

    @Test
    fun `file and local list preserve the band order and name`() {
        val exported = EqualizerPresetFile.encode(preset)
        assertEquals(preset, EqualizerPresetFile.read(exported.byteInputStream()))
        assertEquals(preset, EqualizerPresetFile.read(("\uFEFF" + exported).byteInputStream()))
        assertEquals(listOf(preset), EqualizerPresetFile.decodeStored(EqualizerPresetFile.encodeStored(listOf(preset))))
    }

    @Test
    fun `unknown schema and malformed curves are rejected`() {
        val exported = EqualizerPresetFile.encode(preset)
        listOf(
            exported.replace("\"version\": 1", "\"version\": 2"),
            exported.replace("31,", "32,"),
            exported.replace("1200,", "1201,"),
            exported.replace("100,", "100.1,"),
            exported.replace("100,", ""),
            exported + "{}",
            exported.dropLast(1),
            " ".repeat(17 * 1024) + exported,
        ).forEach { malformed ->
            assertTrue(runCatching { EqualizerPresetFile.read(malformed.byteInputStream()) }.isFailure)
        }
        assertEquals(emptyList<EqualizerPreset>(), EqualizerPresetFile.decodeStored("[{}]"))
    }

    @Test
    fun `a collision keeps both curves and importing again selects the existing copy`() {
        val differentCurve = preset.copy(gainsMb = List(preset.gainsMb.size) { 0 })
        val renamed = preset.copy(name = "${preset.name} (2)")
        assertEquals(renamed.name, EqualizerPresetFile.nameForImport(preset, listOf(differentCurve)))
        assertEquals(renamed.name, EqualizerPresetFile.nameForImport(preset, listOf(differentCurve, renamed)))
    }
}
