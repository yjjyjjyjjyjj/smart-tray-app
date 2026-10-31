package kr.ac.gachon.smarttray.bluetooth

import org.junit.Assert.*
import org.junit.Test

class TrayBluetoothNameTest {
    @Test fun discoveryAndPairedDevicesAcceptOnlyExactNames() {
        for (name in listOf("HC-06", "AMATTA")) {
            assertEquals(name, trayBluetoothName(name, null))
            assertEquals(name, trayBluetoothName(null, " $name "))
        }
        for (name in listOf(null, "", " ", "Headphones", "HC-05", "HC-06-1", "AMATTA_TEST", "hc-06", "amatta")) {
            assertNull(trayBluetoothName(name, null))
            assertNull(trayBluetoothName(null, name))
        }
    }
    @Test fun nameChangesOverrideOldCachedMatchesIncludingEmptyNames() {
        for (name in listOf("Other device", "", " ")) assertNull(trayBluetoothName(name, "HC-06"))
        assertEquals("AMATTA", trayBluetoothName("AMATTA", "Other device"))
    }
}
