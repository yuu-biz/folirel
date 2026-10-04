package io.github.yuubiz.folirel.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Decides whether a resolved address may be contacted. Production uses [PUBLIC_ONLY]. */
fun interface AddressPolicy {
    fun isAllowed(address: InetAddress): Boolean

    companion object {
        /** Public unicast internet addresses only (docs/fetch-and-security.md section 6). */
        val PUBLIC_ONLY = AddressPolicy { PublicAddresses.isPublic(it) }
    }
}

object PublicAddresses {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        return when (address) {
            is Inet4Address -> isPublicV4(address.address)
            is Inet6Address -> isPublicV6(address.address)
            else -> false
        }
    }

    private fun isPublicV4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        val a2 = b[2].toInt() and 0xFF
        return when {
            a0 == 0 -> false // "this network"
            a0 == 10 -> false
            a0 == 100 && a1 in 64..127 -> false // CGNAT shared space
            a0 == 127 -> false
            a0 == 169 && a1 == 254 -> false // link-local incl. cloud metadata 169.254.169.254
            a0 == 172 && a1 in 16..31 -> false
            a0 == 192 && a1 == 0 && a2 == 0 -> false // IETF protocol assignments
            a0 == 192 && a1 == 0 && a2 == 2 -> false // TEST-NET-1
            a0 == 192 && a1 == 88 && a2 == 99 -> false // 6to4 relay anycast (deprecated)
            a0 == 192 && a1 == 168 -> false
            a0 == 198 && (a1 == 18 || a1 == 19) -> false // benchmarking
            a0 == 198 && a1 == 51 && a2 == 100 -> false // TEST-NET-2
            a0 == 203 && a1 == 0 && a2 == 113 -> false // TEST-NET-3
            a0 >= 224 -> false // multicast, reserved, broadcast
            else -> true
        }
    }

    private fun isPublicV6(b: ByteArray): Boolean {
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d): judge the embedded IPv4 address.
        if (b.sliceArray(0 until 10).all { it == 0.toByte() }) {
            val tail = b.sliceArray(12 until 16)
            val mapped = (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
            val compatible = b[10] == 0.toByte() && b[11] == 0.toByte()
            if (mapped || compatible) return isPublicV4(tail) && !InetAddress.getByAddress(tail).isLoopbackAddress
        }
        return when {
            first and 0xFE == 0xFC -> false // ULA fc00::/7
            first == 0xFE && second and 0xC0 == 0x80 -> false // link-local fe80::/10
            first == 0xFE && second and 0xC0 == 0xC0 -> false // site-local fec0::/10 (deprecated)
            first == 0xFF -> false // multicast
            first == 0x20 && second == 0x01 && (b[2].toInt() and 0xFF) == 0x0D && (b[3].toInt() and 0xFF) == 0xB8 -> false // 2001:db8::/32 documentation
            first == 0x20 && second == 0x02 -> isPublicV4(b.sliceArray(2 until 6)) // 6to4 embeds IPv4
            first == 0x00 && second == 0x64 && (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B ->
                isPublicV4(b.sliceArray(12 until 16)) // NAT64 64:ff9b::/96
            first and 0xE0 != 0x20 -> false // only global unicast 2000::/3
            else -> true
        }
    }
}
