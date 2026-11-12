package com.bot4vps.mobile.ui.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.api.NativePanelClient
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.checkedObject
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ServerChecksViewModel @Inject constructor(panel: NativePanelClient) : ViewModel() {
    private val checks = ServerChecksController(viewModelScope, { kind ->
        ServerCheckReport.parse(panel.checkedObject("POST", "/api/monitor/check/${kind.id}"), kind, panel.json)
    }, load = { kind ->
        ServerCheckReport.parse(panel.checkedObject("GET", "/api/monitor/check/${kind.id}"), kind, panel.json)
    })
    val state = checks.state
    fun select(kind: ServerCheckKind) = checks.select(kind)
    fun run() = checks.run()
    fun refresh() = checks.refresh()
    fun hideResults() = checks.hideResults()
    fun showResults() = checks.showResults()
    fun sort() = checks.sort()
}
