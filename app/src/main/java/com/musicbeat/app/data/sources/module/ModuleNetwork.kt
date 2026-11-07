package com.musicbeat.app.data.sources.module

import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Keeps a module's `fetch()` off the listener's own network.
 *
 * A module is code from whoever published its index, and it is handed a
 * fetch so it can reach that publisher's service. It has no business with the
 * router's admin page, a NAS or a camera on the same Wi-Fi, which answer a
 * request from the phone as if the owner sent it. Filtering in [Dns] rather
 * than on the URL covers redirects and hostnames that resolve to a private
 * address, since every connection OkHttp opens goes through it.
 *
 * A module that was itself downloaded from the local network (a self-hosted
 * index on a NAS, say) keeps local access: it already came from there.
 */
internal object ModuleNetwork {

    fun isLocal(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            // IPv6 unique-local, fc00::/7, which Java does not count as site-local.
            (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)

    /** Whether [url]'s host resolves to the local network. False when it can't be resolved. */
    fun isLocalUrl(url: String, dns: Dns = Dns.SYSTEM): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.trim('[', ']') ?: return false
        return runCatching { dns.lookup(host).any(::isLocal) }.getOrDefault(false)
    }

    /** [upstream], with every local address taken out of the answer. */
    fun publicOnly(upstream: Dns = Dns.SYSTEM): Dns = Dns { host ->
        upstream.lookup(host).filterNot(::isLocal).ifEmpty {
            throw UnknownHostException("$host is on the local network, which modules may not reach")
        }
    }
}
