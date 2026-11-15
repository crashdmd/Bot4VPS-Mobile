package com.bot4vps.mobile.notifications

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bot4vps.mobile.MainActivity
import com.bot4vps.mobile.R
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.local.AppLockManager
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.checkedObject
import com.bot4vps.mobile.data.repository.pendingSecurityNotifications
import com.bot4vps.mobile.data.repository.securityNotificationCheckpoint
import com.bot4vps.mobile.data.repository.dismissSecurityNotification
import com.bot4vps.mobile.data.repository.securityNotificationSnapshots
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import javax.inject.Inject

@AndroidEntryPoint
class PhoneNotificationService : Service() {
    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var appLock: AppLockManager
    @Inject lateinit var panel: NativePanelClient
    @Inject lateinit var security: com.bot4vps.mobile.data.repository.SecurityRepository
    @Inject lateinit var auth: com.bot4vps.mobile.data.repository.AuthRepository
    @Inject lateinit var accessStatus: MobileAccessStatus
    @Inject lateinit var realtime: RealtimeHub
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restarts = MutableStateFlow(0)
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val appIcon by lazy {
        Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { bitmap ->
            ContextCompat.getDrawable(this, R.mipmap.ic_launcher)?.let { drawable ->
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(EVENTS, "События Bot4VPS", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(SECURITY, "Безопасность Bot4VPS", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CONNECTION, "Подключение к панели", NotificationManager.IMPORTANCE_LOW))
        val notification = connection("Подключение к панели…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(FOREGROUND_ID, notification)
        scope.launch { accessStatus.revoked.filterNotNull().collect { origin ->
            try { showRevoked(origin) }
            catch (_: SecurityException) { /* Revocation cleanup must not depend on Android notification permission. */ }
            try { auth.recordRevocation(origin) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { manager.notify(FOREGROUND_ID, connection("Доступ телефона отозван. Откройте Authenticator.")) }
        } }
        scope.launch {
            coroutineScope {
                        launch {
                            try { auth.refreshProfiles() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                            security.pollAll(5000)
                        }
                        launch { combine(security.inboxes, prefs.profiles, ::securityNotificationSnapshots).collect { inboxes ->
                            val seen = prefs.allSecuritySeen.first()
                            val posted = mutableSetOf<String>()
                            if (NotificationManagerCompat.from(this@PhoneNotificationService).areNotificationsEnabled()
                                && manager.getNotificationChannel(SECURITY)?.importance != NotificationManager.IMPORTANCE_NONE) {
                                pendingSecurityNotifications(seen, inboxes).forEach { (origin, request) ->
                                    try { showSecurity(origin, request); posted += securityPromptKey(origin, request.id) }
                                    catch (_: SecurityException) { /* Keep it unseen; Android permission can be restored later. */ }
                                }
                            }
                            prefs.rememberAllSecuritySeen(securityNotificationCheckpoint(seen, inboxes, posted))
                            manager.activeNotifications.filter { it.notification.channelId == SECURITY && it.id == 2
                                && it.tag?.let { key -> dismissSecurityNotification(key, inboxes) } == true }.forEach { manager.cancel(it.tag, it.id) }
                        } }
                    }
        }
        scope.launch {
            prefs.phoneRead.distinctUntilChanged().collect { dismissEvents(this@PhoneNotificationService, it) }
        }
        scope.launch {
            combine(prefs.phoneNotifications.distinctUntilChanged(), prefs.baseUrl.distinctUntilChanged(), prefs.username.distinctUntilChanged()) { enabled, url, user -> Triple(enabled, url, user) }
                .distinctUntilChanged().combine(restarts) { config, _ -> config }.collectLatest { (enabled, url, user) ->
                    if (!NotificationManagerCompat.from(this@PhoneNotificationService).areNotificationsEnabled()) {
                        return@collectLatest
                    }
                    if (!enabled || url == null || user == null) { manager.notify(FOREGROUND_ID, connection("Запросы безопасности подключённых сервисов включены")); return@collectLatest }
                    val tracker = PhoneEventTracker(prefs.phoneSeen.first())
                    while (currentCoroutineContext().isActive) {
                        try {
                            fetch(tracker)
                            realtime.reconnecting.collect { event ->
                                if (event.type == "snapshot") {
                                    val snapshot = event.data as? JsonObject ?: return@collect
                                    if (snapshot.objects("events").any { it.text("id")?.let { id -> !tracker.knows(id) } == true }) fetch(tracker)
                                    manager.notify(FOREGROUND_ID, connection("Получение событий включено"))
                                }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: MobileAccessRevoked) {
                            manager.notify(FOREGROUND_ID, connection("Доступ этого телефона отозван в панели")); return@collectLatest
                        } catch (e: ApiError) {
                            if (e.status == 401) {
                                manager.notify(FOREGROUND_ID, connection("Сессия истекла — войдите в панель"))
                                return@collectLatest
                            }
                            manager.notify(FOREGROUND_ID, connection("Нет связи с панелью. Ожидаем подключения…"))
                        } catch (_: Exception) {
                            manager.notify(FOREGROUND_ID, connection("Нет связи с панелью. Ожидаем подключения…"))
                        }
                        delay(15000)
                    }
                }
        }
    }

    private suspend fun fetch(tracker: PhoneEventTracker) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val events = panel.checkedObject("GET", "/api/events", query = mapOf("limit" to listOf("1000"))).objects("events")
        val categories = prefs.phoneCategories.first()
        val read = prefs.phoneRead.first()
        val fresh = tracker.accept(events)
        fresh.filter { it.text("id") !in read && phoneEventCategory(it) in categories }.forEach(::showEvent)
        // A page can acknowledge an event while the fetch is in flight.
        dismissEvents(this, prefs.phoneRead.first())
        prefs.rememberPhoneSeen(tracker.checkpoint(events))
    }

    @Suppress("MissingPermission")
    private fun showEvent(event: JsonObject) {
        val id = event.text("id") ?: return
        val preview = phoneEventNotification(event)
        val notification = NotificationCompat.Builder(this, EVENTS).setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
            .setContentTitle(preview.first)
            .setContentText(preview.second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.second))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(NotificationCompat.Builder(this, EVENTS).setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
                .setContentTitle(preview.first).setContentText(preview.second).build())
            .setContentIntent(openPanel(id)).setAutoCancel(true).build()
        manager.notify(id, 1, notification)
    }
    @Suppress("MissingPermission")
    private fun showSecurity(origin: String, request: SecurityRequest) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("security_id", request.id); putExtra("security_origin", origin)
            data = android.net.Uri.Builder().scheme("bot4vps").authority("security").appendPath(request.id).appendQueryParameter("origin", origin).build()
        }
        val open = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val details = listOf(origin,
            request.message.takeIf { it.isNotBlank() },
            request.targetName.takeIf { it.isNotBlank() }?.let { "Телефон: $it" },
            request.username.takeIf { it.isNotBlank() }?.let { "Пользователь: $it" },
            request.agent.takeIf { it.isNotBlank() }?.let { "Браузер: $it" },
            request.ip.takeIf { it.isNotBlank() }?.let { "IP: $it" },
            request.started?.let { "Время: ${com.bot4vps.mobile.ui.security.securityTime(it)}" }).filterNotNull().joinToString("\n")
        val notification = NotificationCompat.Builder(this, SECURITY).setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
            .setContentTitle(securityTitle(request))
            .setContentText(details).setStyle(NotificationCompat.BigTextStyle().bigText(details)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setContentIntent(open).setAutoCancel(true).build()
        manager.notify(securityPromptKey(origin, request.id), 2, notification)
    }
    private fun connection(message: String) = NotificationCompat.Builder(this, CONNECTION)
        .setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon).setContentTitle("Bot4VPS · уведомления")
        .setContentText(message).setContentIntent(openPanel(null)).setOngoing(true).setOnlyAlertOnce(true).build()

    @Suppress("MissingPermission")
    private fun showRevoked(origin: String) {
        val preview = revokedPhoneNotification(origin)
        val notification = NotificationCompat.Builder(this, SECURITY).setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
            .setContentTitle(preview.first).setContentText(preview.second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.second)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setContentIntent(openPanel(null)).setAutoCancel(true).build()
        manager.notify("revoked:$origin", 3, notification)
    }
    private fun openPanel(id: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            id?.let { putExtra("event_id", it); data = android.net.Uri.parse("bot4vps://event/$it") }
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { restarts.value += 1; return START_STICKY }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val SECURITY = "bot4vps_security"
        private const val EVENTS = "bot4vps_events"
        private const val CONNECTION = "bot4vps_connection"
        private const val FOREGROUND_ID = 9001
        fun dismissEvents(context: Context, ids: Set<String>) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.notification.channelId == EVENTS && it.tag in ids }
                .forEach { manager.cancel(it.tag, it.id) }
        }
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, PhoneNotificationService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, PhoneNotificationService::class.java)) }
    }
}
