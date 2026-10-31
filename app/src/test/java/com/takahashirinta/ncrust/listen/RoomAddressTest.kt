package com.takahashirinta.ncrust.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 房间码的取值顺序（v3.8.1）。
 *
 * 房间码就是 `IP:端口`，但一台设备可能有多个 IPv4（WiFi 内网 + 虚拟网卡）。
 * 分享时的优先顺序：**Tailscale 的 100.64/10 优先**（跨网可达），其次家用内网段。
 * 这样房主发出去的就是"对方真能连的那个"，而不是看着像内网的那个。
 */
class RoomAddressTest {

    @Test
    fun `有 Tailscale 地址时优先它`() {
        val got = RoomLink.Lan.preferredAddress(listOf("192.168.5.70", "100.101.7.9"))
        assertEquals("100.101.7.9", got)
    }

    @Test
    fun `没有 Tailscale 时用内网段`() {
        assertEquals("192.168.5.70", RoomLink.Lan.preferredAddress(listOf("192.168.5.70", "172.17.0.1")))
        assertEquals("10.0.0.5", RoomLink.Lan.preferredAddress(listOf("10.0.0.5")))
    }

    @Test
    fun `公网地址不会被误选`() {
        // 只有公网/回环时返回 null —— 房主界面上会显示"没找到局域网地址"
        assertNull(RoomLink.Lan.preferredAddress(listOf("8.8.8.8")))
        assertNull(RoomLink.Lan.preferredAddress(emptyList()))
    }

    @Test
    fun `100 网段但不在 64到127 的地址不算 Tailscale`() {
        // 100.63.x / 100.128.x 不是 Tailscale 的 CGNAT 段（100.64.0.0/10）
        assertEquals("192.168.1.2", RoomLink.Lan.preferredAddress(listOf("100.63.1.1", "192.168.1.2")))
        assertEquals("192.168.1.2", RoomLink.Lan.preferredAddress(listOf("100.128.1.1", "192.168.1.2")))
    }
}
