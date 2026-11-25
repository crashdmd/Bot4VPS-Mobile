package com.bot4vps.mobile.ui.browser

import org.junit.Assert.*
import org.junit.Test

class ServiceBrowserContentTest {
    @Test fun drawerCompatibilityAppliesOnlyToSsclashMainStylesheet() {
        val css = ".sidebar { background: white; }"
        for (service in listOf("luci", "adguardhome", "3x-ui", "podkop"))
            assertEquals(css, serviceContent(service, "/static/css/main.css", "text/css", css))
        assertEquals(css, serviceContent("ssclash", "/static/css/auth.css", "text/css", css))
        assertEquals(css, serviceContent("ssclash", "/api/status", "application/json", css))
        assertTrue(serviceContent("ssclash", "/static/css/main.css", "text/css; charset=utf-8", css).startsWith(css + "\n"))
    }
}
