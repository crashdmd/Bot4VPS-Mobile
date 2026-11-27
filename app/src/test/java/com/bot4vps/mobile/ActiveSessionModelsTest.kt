package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.ApiError
import com.bot4vps.mobile.data.model.ActiveSessionSample
import com.bot4vps.mobile.data.model.ActiveSessionUpdate
import com.bot4vps.mobile.data.model.SessionConnection
import com.bot4vps.mobile.data.model.SessionHistory
import com.bot4vps.mobile.data.model.activeSessionUpdates
import com.bot4vps.mobile.data.model.filterSessions
import com.bot4vps.mobile.data.model.sessionDevices
import com.bot4vps.mobile.data.model.sessionDeviceLabel
import com.bot4vps.mobile.data.model.sessionSpeed
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveSessionModelsTest {
    @Test fun decodesSeparateConnectionsAndFiltersByDevice() {
        val sample = Json { ignoreUnknownKeys = true }.decodeFromString<ActiveSessionSample>("""
            {"status":"ready","wan":{"rx_bps":50000000,"tx_bps":6400000},
             "total":{"rx_bps":30000000,"tx_bps":1200000},"connections":[
             {"id":"one","device_name":"Ноутбук","device_ip":"192.168.1.24","name":"YouTube",
              "domain":"youtube.com","destination_ip":"142.250.74.14","via_fake_ip":true,"rx_bps":30000000,"tx_bps":1200000},
             {"id":"two","device_name":"Ноутбук","device_ip":"192.168.1.24","name":"YouTube",
              "domain":"youtube.com","via_fake_ip":true,"rx_bps":2000000,"tx_bps":100000},
             {"id":"three","device_ip":"192.168.1.35","destination_ip":"1.1.1.1","via_fake_ip":false,"rx_bps":10000,"tx_bps":0}]}
        """.trimIndent())
        assertEquals(50_000_000L, sample.wan?.received)
        assertEquals(6_400_000L, sample.wan?.sent)
        val rows = requireNotNull(sample.connections)
        assertEquals(listOf("one", "two"), rows.filterSessions("192.168.1.24", "youtube.com", true).map { it.id })
        assertEquals(listOf("Ноутбук (192.168.1.24)", "192.168.1.35"),
            rows.sessionDevices().map { (ip, name) -> sessionDeviceLabel(ip, name) })
        assertEquals(listOf("three"), rows.filterSessions("192.168.1.35", "1.1.1.1", false).map { it.id })
        assertEquals(listOf("one"), rows.filterSessions("", "142.250.74.14", null).map { it.id })
        assertEquals(emptyList<String>(), rows.filterSessions("", "youtube", false).map { it.id })
        assertEquals(listOf(true, true, false), rows.map { it.viaFakeIp })
        assertEquals("142.250.74.14", rows.first().destinationIp)
        assertEquals(2_000_000L, rows[1].received)
        assertNull(rows[1].destinationIp)
    }

    @Test fun olderPanelStopsPolling() = runTest {
        var requests = 0
        val states = activeSessionUpdates { requests++; throw ApiError(404, "Старый API") }.toList()
        assertEquals(listOf(ActiveSessionUpdate.Unsupported), states)
        assertEquals(1, requests)
    }

    @Test fun historyRetainsEarlierConnectionsAndUpdatesTheSameConnectionInPlace() {
        val first = SessionConnection("first", "192.168.1.24", observedIp = "198.18.0.12",
            viaFakeIp = true, received = 200, sent = 100)
        val second = first.copy(id = "second")
        val history = SessionHistory()
        history.record(listOf(first))
        val updated = first.copy(received = 300)
        assertEquals(listOf(updated), history.record(listOf(updated)))
        assertEquals(listOf(updated, second), history.record(listOf(second)))
        assertEquals(listOf(updated, second), history.record(emptyList()).filterSessions("", "198.18.0.12", true))
        assertEquals(emptyList<SessionConnection>(), SessionHistory().record(emptyList()))
    }

    @Test fun slowingDownOnErrorsDoesNotStopLiveUpdates() = runTest {
        val requestedAt = mutableListOf<Long>()
        val warming = ActiveSessionSample("warming")
        val ready = ActiveSessionSample("ready")
        val states = activeSessionUpdates {
            requestedAt += testScheduler.currentTime
            when (requestedAt.size) { 1 -> throw ApiError(503, "Нет связи"); 2 -> warming; else -> ready }
        }.take(3).toList()
        assertEquals(listOf(0L, 10_000L, 13_000L), requestedAt)
        assertEquals(listOf(ActiveSessionUpdate.Unavailable, ActiveSessionUpdate.Received(warming), ActiveSessionUpdate.Received(ready)), states)
    }

    @Test fun aMissingRateIsDifferentFromZeroAndLowTraffic() {
        assertEquals("—", sessionSpeed(null))
        assertEquals("0,0", sessionSpeed(0))
        assertEquals("<0,1", sessionSpeed(12_000))
        assertEquals("30,0", sessionSpeed(30_000_000))
    }
}
