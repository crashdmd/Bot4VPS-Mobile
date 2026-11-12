package com.bot4vps.mobile.data.local

import com.bot4vps.mobile.data.model.MobileRelease
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class MobileUpdateState(val checking: Boolean = false, val progress: Int? = null,
    val release: MobileRelease? = null, val file: File? = null, val dialog: Boolean = false,
    val error: String? = null, val notice: String? = null)

@Singleton
class MobileUpdateManager internal constructor(val prefs: AppUpdatePreferences, private val updater: AppReleaseUpdater,
    private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    @Inject constructor(prefs: PreferencesManager, updater: MobileAppUpdater) : this(
        prefs, updater, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    private val mutable = MutableStateFlow(MobileUpdateState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun check(manual: Boolean = true) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                if (!manual && (!prefs.appAutoUpdate.first() || clock() - prefs.appUpdateCheckAt.first() in 0L..86_400_000L)) return@launch
                mutable.value = mutable.value.copy(checking = true, error = null, notice = null)
                val release = updater.check()
                currentCoroutineContext().ensureActive()
                prefs.rememberAppUpdateCheck(clock())
                mutable.value = mutable.value.copy(release = release, dialog = release != null, file = null,
                    notice = if (manual && release == null) "Новых версий приложения нет" else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: "Не удалось проверить обновление приложения") }
            finally { mutable.value = mutable.value.copy(checking = false) }
        }
    }
    fun show() { if (mutable.value.release != null) mutable.value = mutable.value.copy(dialog = true) }
    fun close() {
        if (mutable.value.progress != null) job?.cancel()
        mutable.value = mutable.value.copy(dialog = false, progress = null)
    }
    fun error(message: String) { mutable.value = mutable.value.copy(error = message) }
    fun consumeNotice() { mutable.value = mutable.value.copy(notice = null) }
    fun automatic(value: Boolean) { scope.launch { prefs.setAppAutoUpdate(value) } }
    fun download() {
        if (job?.isActive == true) return
        val release = mutable.value.release ?: return
        job = scope.launch {
            mutable.value = mutable.value.copy(progress = 0, file = null, error = null)
            try {
                val file = updater.download(release) { mutable.value = mutable.value.copy(progress = it) }
                mutable.value = mutable.value.copy(file = file)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: "Не удалось скачать APK") }
            finally { mutable.value = mutable.value.copy(progress = null) }
        }
    }
}
