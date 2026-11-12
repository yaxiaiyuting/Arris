/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.audio

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer

internal data class EqualizerPreset(val name: String, val gainsMb: List<Int>)

internal object EqualizerPresetFile {
    private const val FORMAT = "arris-equalizer"
    private const val VERSION = 1
    private const val MAX_BYTES = 16 * 1024
    private const val MAX_NAME_LENGTH = 48
    const val MAX_PRESETS = 64

    fun read(input: InputStream): EqualizerPreset {
        val content = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(content.size() + count <= MAX_BYTES)
            content.write(buffer, 0, count)
        }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content.toByteArray()))
        return JsonReader(StringReader(text.toString().removePrefix("\uFEFF"))).use { reader ->
            val preset = readPreset(reader)
            require(reader.peek() == JsonToken.END_DOCUMENT)
            preset
        }
    }

    fun encode(preset: EqualizerPreset): String = writeJson(pretty = true) { writePreset(this, preset) }

    fun encodeStored(presets: List<EqualizerPreset>): String = writeJson {
        beginArray()
        for (preset in presets) writePreset(this, preset)
        endArray()
    }

    fun decodeStored(text: String): List<EqualizerPreset> = try {
        JsonReader(StringReader(text)).use { reader ->
            buildList {
                reader.beginArray()
                while (reader.hasNext()) {
                    require(size < MAX_PRESETS)
                    add(readPreset(reader))
                }
                reader.endArray()
                require(reader.peek() == JsonToken.END_DOCUMENT)
            }
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: IllegalStateException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    /** Reuse identical imports and preserve both curves when two files share a name. */
    fun nameForImport(preset: EqualizerPreset, existing: List<EqualizerPreset>): String {
        val names = existing.associateBy(EqualizerPreset::name)
        var candidate = preset.name
        var count = 1
        while (true) {
            val match = names[candidate]
            if (match == null || match.gainsMb == preset.gainsMb) return candidate
            val suffix = " (${++count})"
            candidate = preset.name.take(MAX_NAME_LENGTH - suffix.length).trimEnd() + suffix
        }
    }

    private fun writeJson(pretty: Boolean = false, write: JsonWriter.() -> Unit): String {
        val out = StringWriter()
        JsonWriter(out).use {
            if (pretty) it.setIndent("  ")
            it.write()
        }
        return out.toString()
    }

    private fun writePreset(writer: JsonWriter, preset: EqualizerPreset) = with(writer) {
        beginObject()
        name("format").value(FORMAT)
        name("version").value(VERSION)
        name("name").value(preset.name)
        name("frequenciesHz").beginArray()
        EqualizerPrefs.frequenciesHz.forEach { value(it) }
        endArray()
        name("gainsMb").beginArray()
        preset.gainsMb.forEach { value(it) }
        endArray()
        endObject()
    }

    private fun readPreset(reader: JsonReader): EqualizerPreset = with(reader) {
        var format: String? = null
        var version: Int? = null
        var presetName: String? = null
        var frequencies: List<Int>? = null
        var gains: List<Int>? = null
        val seen = mutableSetOf<String>()
        beginObject()
        while (hasNext()) {
            val key = nextName()
            require(seen.add(key))
            when (key) {
                "format" -> format = readString()
                "version" -> version = readInt()
                "name" -> presetName = readString().trim()
                "frequenciesHz" -> frequencies = readBands()
                "gainsMb" -> gains = readBands()
                else -> skipValue()
            }
        }
        endObject()
        require(format == FORMAT && version == VERSION)
        val name = requireNotNull(presetName)
        require(name.length in 1..MAX_NAME_LENGTH && name.none { it.isISOControl() })
        require(frequencies?.toIntArray()?.contentEquals(EqualizerPrefs.frequenciesHz) == true)
        val checkedGains = requireNotNull(gains)
        require(checkedGains.all { it in EqualizerController.GAIN_MIN_MB..EqualizerController.GAIN_MAX_MB })
        EqualizerPreset(name, checkedGains)
    }

    private fun JsonReader.readString(): String {
        require(peek() == JsonToken.STRING)
        return nextString()
    }

    private fun JsonReader.readInt(): Int {
        require(peek() == JsonToken.NUMBER)
        return requireNotNull(nextString().toIntOrNull())
    }

    private fun JsonReader.readBands(): List<Int> = buildList {
        beginArray()
        repeat(EqualizerController.MAX_BANDS) { add(readInt()) }
        endArray()
    }
}
