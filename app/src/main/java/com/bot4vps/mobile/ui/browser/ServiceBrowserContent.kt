package com.bot4vps.mobile.ui.browser

/** Keep navigation and notifications inside the visible native browser surface. */
internal fun serviceContent(service: String, path: String, type: String, text: String): String {
    if (service != "ssclash" || path != "/static/css/main.css" || !type.startsWith("text/css")) return text
    return text + "\n" + ssclashDrawerCss
}

internal val ssclashDrawerCss = """
/* Bot4VPS WebView: visible bounds are supplied from the native view geometry. */
.shell > #sidebar.sidebar {
    height: calc(var(--bot4vps-browser-height, 100vh) - var(--topbar-h)) !important;
    max-height: calc(var(--bot4vps-browser-height, 100vh) - var(--topbar-h)) !important;
    min-height: 0 !important;
    overflow-y: auto;
    overscroll-behavior: contain;
}
.toast-wrap {
    top: calc(var(--bot4vps-browser-top, 0px) + var(--bot4vps-browser-height, 100vh) - 18px);
    bottom: auto;
    transform: translateY(-100%);
    max-height: calc(var(--bot4vps-browser-height, 100vh) - var(--topbar-h) - 36px);
    width: min(380px, calc(100% - 36px));
    overflow-y: auto;
    overscroll-behavior: contain;
}
.toast-wrap > .toast {
    min-width: 0;
    max-width: none;
    flex: none;
    overflow-wrap: anywhere;
}
@media (max-width: 820px) {
    .shell > #sidebar.sidebar {
        position: fixed;
        top: var(--topbar-h);
        bottom: auto;
        left: calc(0px - var(--sidebar-w));
        transform: none;
        transition: left .2s ease;
    }
    body.nav-open .shell > #sidebar.sidebar { left: 0; }
}
""".trimIndent()
