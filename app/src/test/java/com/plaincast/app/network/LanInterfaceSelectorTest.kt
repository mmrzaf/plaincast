package com.plaincast.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanInterfaceSelectorTest {
    @Test
    fun hotspotAndWifiBeatEthernetAndUnknownInterfaces() {
        val selected = LanInterfaceSelector.select(
            listOf(
                candidate("eth0", "192.168.1.20"),
                candidate("wlan0", "192.168.1.30"),
                candidate("ap0", "192.168.43.1"),
            )
        )
        assertEquals("192.168.43.1", selected)
    }

    @Test
    fun vpnCellularAndPublicAddressesAreRejected() {
        val selected = LanInterfaceSelector.select(
            listOf(
                candidate("tun0", "192.168.9.2"),
                candidate("rmnet0", "10.0.0.2"),
                candidate("wlan0", "8.8.8.8", siteLocal = false),
            )
        )
        assertNull(selected)
    }

    private fun candidate(name: String, address: String, siteLocal: Boolean = true) = LanAddressCandidate(
        interfaceName = name,
        address = address,
        isSiteLocal = siteLocal,
        isUp = true,
        isLoopback = false,
    )
}
