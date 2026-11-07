package com.musicbeat.app

import com.musicbeat.app.data.sources.module.ModuleNetwork
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ModuleNetworkTest {

    private fun ip(raw: String) = InetAddress.getByName(raw)

    @Test
    fun localAddressesAreLocal() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.1.1", "0.0.0.0", "::1", "fe80::1", "fd12::1")
            .forEach { assertTrue(it, ModuleNetwork.isLocal(ip(it))) }
        listOf("1.1.1.1", "8.8.8.8", "172.32.0.1", "2606:4700::1111")
            .forEach { assertFalse(it, ModuleNetwork.isLocal(ip(it))) }
    }

    private val dns = Dns { host ->
        when (host) {
            "router.example" -> listOf(ip("192.168.1.1"))
            "mixed.example" -> listOf(ip("192.168.1.1"), ip("1.1.1.1"))
            else -> listOf(ip("8.8.8.8"))
        }
    }

    @Test
    fun aNameResolvingToTheLanIsRefused() {
        val public = ModuleNetwork.publicOnly(dns)
        assertEquals(listOf(ip("8.8.8.8")), public.lookup("api.example"))
        assertEquals(listOf(ip("1.1.1.1")), public.lookup("mixed.example"))
        try {
            public.lookup("router.example")
            throw AssertionError("a LAN-only name resolved")
        } catch (_: UnknownHostException) {
        }
    }

    @Test
    fun aModuleServedFromTheLanKeepsItsAccess() {
        assertTrue(ModuleNetwork.isLocalUrl("http://router.example:8080/modules", dns))
        assertTrue(ModuleNetwork.isLocalUrl("http://192.168.1.20/modules", Dns.SYSTEM))
        assertFalse(ModuleNetwork.isLocalUrl("https://api.example/modules", dns))
        assertFalse(ModuleNetwork.isLocalUrl("not a url", dns))
    }
}
