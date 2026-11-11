package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.NativePanelClient
import com.bot4vps.mobile.data.api.encodeSegment
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(private val panel: NativePanelClient, private val prefs: PreferencesManager) {
    private var currentPanel:String?=null
    private suspend fun ensurePanel():String? { val url=prefs.baseUrl.first(); if(currentPanel!=url) { currentPanel=url; _groups.value=null }; return url }
    private val lock = Mutex()
    private val _groups = MutableStateFlow<List<ServerGroup>?>(null)
    val groups = _groups.asStateFlow()
    private val _mutations = MutableSharedFlow<GroupMutation>(extraBufferCapacity = 8)
    val mutations = _mutations.asSharedFlow()
    suspend fun refresh(): List<ServerGroup> = lock.withLock {
        val base=ensurePanel()
        val rows = panel.checkedObject("GET", "/api/groups")["groups"] as? JsonArray ?: error("Не удалось прочитать группы")
        if(prefs.baseUrl.first()!=base) { ensurePanel(); return@withLock emptyList() }
        rows.mapNotNull { item -> (item as? JsonObject)?.let { row -> row.text("name")?.let { name ->
            ServerGroup(name, row.flag("ssl_monitor"), row["servers"]?.jsonPrimitive?.intOrNull ?: 0)
        } } }.also { _groups.value = it }
    }
    suspend fun save(old: String?, name: String) = lock.withLock {
        val base=ensurePanel()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Введите название группы" }
        require(_groups.value.orEmpty().none { it.name == trimmed && it.name != old }) { "Группа с таким названием уже есть" }
        panel.checkedObject(if (old == null) "POST" else "PATCH", if (old == null) "/api/groups" else "/api/groups/${encodeSegment(old)}",
            buildJsonObject { put("name", trimmed) })
        if(prefs.baseUrl.first()!=base) { ensurePanel(); return@withLock }
        val previous = _groups.value.orEmpty()
        _groups.value = if (old == null) previous + ServerGroup(trimmed)
            else previous.map { if (it.name == old) it.copy(name = trimmed) else it }
        _mutations.emit(GroupMutation(old, trimmed))
        try { prefs.editGroupDisplay { it.renamed(old ?: trimmed, trimmed) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Remote save is already accepted; do not offer to repeat it. */ }
    }
    suspend fun delete(group: ServerGroup) = lock.withLock {
        val base=ensurePanel()
        require(group.servers == 0) { "Сначала переместите серверы в другую группу" }
        panel.checkedObject("DELETE", "/api/groups/${encodeSegment(group.name)}")
        if(prefs.baseUrl.first()!=base) { ensurePanel(); return@withLock }
        _groups.value = _groups.value.orEmpty().filterNot { it.name == group.name }
        _mutations.emit(GroupMutation(deleted = group.name))
        try { prefs.editGroupDisplay { it.deleted(group.name) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }

    suspend fun setSslMonitor(group: ServerGroup, enabled: Boolean) = lock.withLock {
        val base = ensurePanel()
        panel.checkedObject("PATCH", "/api/groups/${encodeSegment(group.name)}",
            buildJsonObject { put("ssl_monitor", enabled) })
        if (prefs.baseUrl.first() != base) { ensurePanel(); return@withLock }
        _groups.value = _groups.value?.map { if (it.name == group.name) it.copy(ssl_monitor = enabled) else it }
    }
}
