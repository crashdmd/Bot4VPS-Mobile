package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.model.MobileConnectionChange
import com.bot4vps.mobile.data.model.PanelProfile
import com.bot4vps.mobile.data.model.panelProfileOrigin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun validateConnectionMigrationProfiles(profiles: List<PanelProfile>, change: MobileConnectionChange, deviceId: String) {
    val affected = setOf(change.sourceOrigin, change.profileOrigin, change.origin)
    check(profiles.none { panelProfileOrigin(it.url) in affected && it.deviceId != deviceId }) { "Адрес уже занят другой привязкой. Новые данные не подтверждены." }
}

/** An old approved transfer must finish before reading the selected profile
 * for a new QR; failure must keep both the journal and the new transfer blocked. */
internal suspend fun resumeApprovedConnectionMigration(read: suspend () -> MobileConnectionChange?,
    resume: suspend (MobileConnectionChange) -> Unit): MobileConnectionChange? {
    val pending = read()?.takeIf { it.approved } ?: return null
    resume(pending)
    check(read() == null) { "Прежняя смена адреса ещё не сохранена. Новые данные не подтверждены." }
    return pending
}

/** Remote approval precedes local mutations; the approved journal survives
 * any interrupted storage step and allows replay without changing device keys. */
internal suspend fun runConnectionMigration(change: MobileConnectionChange,
    persist: suspend (MobileConnectionChange?) -> Unit, confirm: suspend () -> Unit,
    moveSensitive: suspend () -> Unit, moveTotp: suspend () -> Unit,
    moveDevice: suspend () -> Unit, publishProfile: suspend () -> Unit,
    preflight: suspend () -> Unit = {}) {
    preflight()
    if (!change.approved) confirm()
    withContext(NonCancellable) {
        persist(change.copy(approved = true))
        preflight()
        moveSensitive(); moveTotp(); moveDevice(); publishProfile()
        persist(null)
    }
}
