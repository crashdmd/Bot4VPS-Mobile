package com.bot4vps.mobile.data.local

import com.bot4vps.mobile.data.model.MobileRelease
import kotlinx.coroutines.flow.Flow
import java.io.File

interface AppUpdatePreferences {
    val appAutoUpdate: Flow<Boolean>
    val appUpdateCheckAt: Flow<Long>
    suspend fun setAppAutoUpdate(value: Boolean)
    suspend fun rememberAppUpdateCheck(value: Long)
}

interface AppReleaseUpdater {
    suspend fun check(): MobileRelease?
    suspend fun download(release: MobileRelease, onProgress: (Int) -> Unit): File
}
