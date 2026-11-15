package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Re-pairing replaces the device identity. An old transfer must never write
 * its keys or profile over that new binding. Archive only with positive local
 * evidence of replacement, keeping all sensitive data and recoverable transfers. */
internal suspend fun reconcileConnectionMigration(
    read: suspend () -> MobileConnectionChange?,
    devices: suspend () -> List<Pair<String, PairedMobileDevice>>,
    profiles: suspend () -> List<PanelProfile>,
    archive: suspend (MobileConnectionChange) -> Unit,
    persist: suspend (MobileConnectionChange?) -> Unit
): MobileConnectionChange? {
    val pending = read() ?: return null
    if (pending.deviceId.isBlank()) return pending
    val bindings = devices()
    val saved = profiles()
    if (bindings.any { it.second.id == pending.deviceId } || saved.any { it.deviceId == pending.deviceId }) return pending
    val affected = setOf(pending.sourceOrigin, pending.profileOrigin, pending.origin)
    val replaced = saved.any { profile ->
        val origin = panelProfileOrigin(profile.url)
        origin in affected && !profile.revoked && !profile.localDisconnected && bindings.any { (address, device) ->
            address == origin && device.id == profile.deviceId && device.serverPin == pending.serverPin
        }
    }
    if (!replaced) return pending
    withContext(NonCancellable) { archive(pending); persist(null) }
    return null
}

/** Shared by QR and manual entry. Read the selected profile after recovery,
 * resolve its actual retained key, then contact only the new endpoint. */
internal suspend fun previewConnectionMigration(
    value: String,
    mobile: MobileAccessClient,
    recover: suspend () -> Unit,
    selection: suspend () -> Pair<String?, List<PanelProfile>>,
    validateSensitive: suspend (String, String) -> Unit
): MobileConnectionChange {
    recover()
    val (current, profiles) = selection()
    val origin = mobileOrigin(PanelUrl.endpoint(current ?: error("Сначала подключитесь к сервису"), "/api/me"))
    val profile = profiles.singleOrNull { panelProfileOrigin(it.url) == origin && !it.revoked && !it.localDisconnected }
        ?: error("У выбранного сервиса нет сохранённой привязки телефона")
    val entered = value.trim()
    val qr = if (entered.startsWith("b4vps:")) readConnectionChange(entered) else null
    val source = mobile.sourceForChange(origin, profile.deviceId, qr?.serverPin)
    val verified = mobile.discoverChange(source.first, qr?.url ?: entered)
    if (qr != null && !verified.approved) {
        check(qr.id == verified.id && qr.nonce == verified.nonce && qr.oldOrigin == verified.oldOrigin && qr.origin == verified.origin && qr.serverPin == verified.serverPin) { "QR уже изменился. Отсканируйте актуальные данные." }
    }
    val change = verified.copy(profileOldUrl = origin)
    val device = mobile.validateChange(change)
    validateConnectionMigrationProfiles(profiles, change, device.id)
    validateSensitive(change.sourceOrigin, change.origin)
    return change.copy(deviceId = device.id)
}
