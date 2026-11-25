package com.bot4vps.mobile.ui.browser

import org.junit.Assert.*
import org.junit.Test

class ServiceBrowserViewportTest {
    @Test fun viewportIsNotChangedForOtherServicesOrAnUnmeasuredView() {
        for (service in listOf("luci", "adguardhome", "3x-ui", "podkop"))
            assertNull(serviceViewportScript(service, 1080, 1920))
        assertNull(serviceViewportScript("ssclash", 0, 1920))
        assertNull(serviceViewportScript("ssclash", 1080, 0))
        assertNull(serviceViewportScript("ssclash", -1, 1920))
    }
}
