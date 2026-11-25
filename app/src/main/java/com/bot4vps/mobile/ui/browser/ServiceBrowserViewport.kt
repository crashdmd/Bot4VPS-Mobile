package com.bot4vps.mobile.ui.browser

/** Supply the native browser surface's height in the page's CSS pixels.
 * WebView can retain a stale vh/dvh size after its initial content measurement.
 * Viewport width supplies the CSS scale; height comes from the actual Android view.
 * This script carries only two dimensions and exposes no JavaScript bridge.
 */
internal fun serviceViewportScript(service: String, width: Int, height: Int): String? {
    if (service != "ssclash" || width <= 0 || height <= 0) return null
    return """
        (function () {
            if (!document.documentElement) return;
            var cssWidth = window.visualViewport && Number.isFinite(window.visualViewport.width) && window.visualViewport.width > 0
                ? window.visualViewport.width : window.innerWidth;
            if (!Number.isFinite(cssWidth) || cssWidth <= 0) return;
            var cssHeight = Math.round(cssWidth * $height / $width);
            document.documentElement.style.setProperty('--bot4vps-browser-height', cssHeight + 'px');
            var cssTop = window.visualViewport && Number.isFinite(window.visualViewport.offsetTop)
                ? Math.max(0, window.visualViewport.offsetTop) : 0;
            document.documentElement.style.setProperty('--bot4vps-browser-top', cssTop + 'px');
        })();
    """.trimIndent()
}
