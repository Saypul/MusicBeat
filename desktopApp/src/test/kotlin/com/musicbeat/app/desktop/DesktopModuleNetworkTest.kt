package com.musicbeat.app.desktop

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopModuleNetworkTest {

    private fun ip(raw: String) = InetAddress.getByName(raw)

    private val dns: (String) -> List<InetAddress> = { host ->
        when (host) {
            "router.example" -> listOf(ip("192.168.1.1"))
            "mixed.example" -> listOf(ip("1.1.1.1"), ip("10.0.0.5"))
            else -> listOf(ip("8.8.8.8"))
        }
    }

    @Test
    fun `local addresses are local`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.1.1", "0.0.0.0", "::1", "fe80::1", "fd12::1")
            .forEach { assertTrue(DesktopModuleNetwork.isLocal(ip(it)), it) }
        listOf("1.1.1.1", "8.8.8.8", "172.32.0.1", "2606:4700::1111")
            .forEach { assertFalse(DesktopModuleNetwork.isLocal(ip(it)), it) }
    }

    @Test
    fun `a module may not reach the lan`() {
        DesktopModuleNetwork.check("https://api.example/search", allowLocal = false, resolve = dns)
        assertFailsWith<IllegalArgumentException> {
            DesktopModuleNetwork.check("http://router.example/admin", allowLocal = false, resolve = dns)
        }
        // Any local answer is enough to refuse: the client may connect to that one.
        assertFailsWith<IllegalArgumentException> {
            DesktopModuleNetwork.check("http://mixed.example/", allowLocal = false, resolve = dns)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopModuleNetwork.check("http://127.0.0.1:8080/", allowLocal = false)
        }
    }

    @Test
    fun `a module served from the lan keeps its access`() {
        assertTrue(DesktopModuleNetwork.isLocalUrl("http://router.example:8080/modules/", dns))
        DesktopModuleNetwork.check("http://router.example/admin", allowLocal = true, resolve = dns)
        assertFalse(DesktopModuleNetwork.isLocalUrl("not a url", dns))
    }
}
