package com.musicbeat.app.desktop

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Keeps a module's `fetch()` off the listener's own network.
 *
 * A module is code from whoever published its index, handed a fetch so it can
 * reach that publisher's service. It has no business with the router's admin
 * page, a NAS or anything on `localhost`, which answer a request from this PC
 * as if its owner sent it. Every hop of a redirect is checked, since a public
 * URL can redirect inward. A module that was itself downloaded from the local
 * network keeps local access: it already came from there.
 */
internal object DesktopModuleNetwork {

    fun isLocal(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            // IPv6 unique-local, fc00::/7, which Java does not count as site-local.
            (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)

    /** Whether [url]'s host resolves anywhere on the local network. False when it can't be resolved. */
    fun isLocalUrl(url: String, resolve: (String) -> List<InetAddress> = ::lookup): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.trim('[', ']') ?: return false
        return runCatching { resolve(host).any(::isLocal) }.getOrDefault(false)
    }

    /** Throws when a module without local access asks for a local [url]. */
    fun check(url: String, allowLocal: Boolean, resolve: (String) -> List<InetAddress> = ::lookup) {
        if (allowLocal) return
        require(!isLocalUrl(url, resolve)) { "modules may not reach the local network" }
    }

    private fun lookup(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}
