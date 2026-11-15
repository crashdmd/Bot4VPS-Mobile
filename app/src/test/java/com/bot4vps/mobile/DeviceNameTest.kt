package com.bot4vps.mobile

import com.bot4vps.mobile.data.local.resolveDeviceName
import org.junit.Assert.*
import org.junit.Test

class DeviceNameTest {
    @Test fun configuredAndroidNameTakesPrecedenceOverModel() { assertEquals("Телефон Ивана", resolveDeviceName(" Телефон Ивана ", "Google", "Pixel 9")) }
    @Test fun modelIsUsedWhenTheSystemDoesNotExposeItsName() {
        assertEquals("Google Pixel 9", resolveDeviceName(null, "Google", "Pixel 9"))
        assertEquals("Samsung Galaxy", resolveDeviceName("", "Samsung", "Samsung Galaxy"))
        assertEquals("Android", resolveDeviceName(null, "unknown", "unknown"))
    }
    @Test fun nameDoesNotContainControlsAndFitsServerLimit() { assertEquals(80, resolveDeviceName("a".repeat(100) + "\n", null, null).length) }
}
