package com.bot4vps.mobile.data.model

data class PanelRemoval(val removed: PanelProfile?, val profiles: List<PanelProfile>, val nextOrigin: String?, val switched: Boolean, val choicesRequired: Boolean = false)
fun removePanelConnection(profiles: List<PanelProfile>, origin: String, expectedDeviceId: String, current: String?): PanelRemoval {
    val removed = profiles.firstOrNull { it.url == origin && it.deviceId == expectedDeviceId }
        ?: return PanelRemoval(null, profiles, current, false)
    val remaining = profiles.filterNot { it.url == origin && it.deviceId == expectedDeviceId }
    val available = remaining.filter { !it.revoked && !it.localDisconnected }
    val currentOrigin = panelProfileOrigin(current)
    val switched = current == origin || currentOrigin != null && currentOrigin == panelProfileOrigin(origin)
    val next = if (switched) available.singleOrNull()?.url else current
    return PanelRemoval(removed, remaining, next, switched, switched && available.size > 1)
}

data class ConnectionNotice(val origin: String, val nextOrigin: String?, val switched: Boolean, val revoked: Boolean, val choicesRequired: Boolean = false, val id: Long = System.nanoTime(), val roleMessage: String? = null)
