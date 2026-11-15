package com.bot4vps.mobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PanelProfile(val url: String, val name: String, val deviceId: String, val revoked: Boolean = false, val localDisconnected: Boolean = false)

fun panelProfileOrigin(url: String?): String? = url?.let {
    runCatching { com.bot4vps.mobile.data.api.mobileOrigin(com.bot4vps.mobile.data.api.PanelUrl.endpoint(it, "/api/me")) }.getOrNull()
}

fun hasOtherService(profiles: List<PanelProfile>, currentUrl: String?): Boolean {
    val current = panelProfileOrigin(currentUrl)
    return profiles.any { !it.revoked && !it.localDisconnected && panelProfileOrigin(it.url)?.let { origin -> origin != current } == true }
}
