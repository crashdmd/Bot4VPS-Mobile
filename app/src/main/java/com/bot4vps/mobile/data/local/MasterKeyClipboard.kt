package com.bot4vps.mobile.data.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import com.bot4vps.mobile.data.model.SecretClipboardLease
import javax.inject.Inject
import javax.inject.Singleton

/** Only the copy's id and deadline are retained; never the master-key value. */
@Singleton
class MasterKeyClipboard @Inject constructor(@ApplicationContext context: Context) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lease: SecretClipboardLease? = null
    private var expiry: Job? = null

    fun copy(value: String, deadline: Long) {
        clear()
        val remaining = (deadline - System.currentTimeMillis()).coerceIn(0, 30_000)
        val current = SecretClipboardLease(value, SystemClock.elapsedRealtime() + remaining)
        val clip = ClipData.newPlainText("Мастер-ключ Bot4VPS", value)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putString(COPY_ID, current.id)
        }
        clipboard.setPrimaryClip(clip)
        lease = current
        expiry = scope.launch { delay(remaining); clearExpired() }
    }

    fun clearExpired() { if (lease?.expired(SystemClock.elapsedRealtime()) == true) clear() }

    fun clear() {
        expiry?.cancel(); expiry = null
        val current = lease ?: return
        val clip = clipboard.primaryClip
        // Android may deny background reads. Retry on return to the app, rather
        // than clearing a clipboard value that may now belong to another app.
        if (clip == null) return
        if (current.owns(clip.description.extras?.getString(COPY_ID), clip.getItemAt(0)?.text?.toString())) {
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        lease = null
    }

    companion object { private const val COPY_ID = "com.bot4vps.mobile.masterkey_copy" }
}
