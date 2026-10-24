package com.takahashirinta.ncrust.player

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ShuffleSnapshotTest {
    private val keys = listOf("netease:1", "qqmusic:1", "bilibili:1")
    @Test fun `reopening retains unplayed order and cursor`() {
        val saved = ShuffleSnapshot(keys, listOf(2, 0, 1), 1)
        val loaded = Gson().fromJson(Gson().toJson(saved), ShuffleSnapshot::class.java)
        assertTrue(loaded.matches(keys, 0))
        assertEquals(1, loaded.order!![loaded.position + 1])
    }
    @Test fun `changed source or queue order invalidates saved indices`() {
        val saved = ShuffleSnapshot(keys, listOf(2, 0, 1), 1)
        assertFalse(saved.matches(listOf("qqmusic:1", "netease:1", "bilibili:1"), 0))
    }
    @Test fun `corrupt or legacy snapshot never restores duplicate indices or wrong current song`() {
        assertFalse(ShuffleSnapshot().matches(keys, 0))
        assertFalse(ShuffleSnapshot(keys, listOf(0, 0, 1), 0).matches(keys, 0))
        assertFalse(ShuffleSnapshot(keys, listOf(2, 0, 1), 1).matches(keys, 2))
        assertFalse(ShuffleSnapshot(keys, listOf(2, 0, 1), 4).matches(keys, 0))
    }

    @Test fun `stable serialized field names restore across obfuscated release upgrades`() {
        val loaded = Gson().fromJson("""{"queue_keys":["netease:1","qqmusic:1","bilibili:1"],"order":[2,0,1],"position":1}""", ShuffleSnapshot::class.java)
        assertTrue(loaded.matches(keys, 0))
    }
}
