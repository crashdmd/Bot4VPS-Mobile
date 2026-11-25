package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WirelessScanTest {
    private fun data(raw: String) = Json.parseToJsonElement(raw).jsonObject
    @Test fun signedOverflowAndMissingFrequencyAreResolvedInRadioBand() {
        val scan = wifiScan(data("""{"selected_radio":{"band":"5g"},"networks":[{"ssid":"router","channel":36,"signal":4294967236}],"survey":[]}"""))
        assertEquals(5180.0, scan.networks.single().frequency!!, 0.0)
        assertEquals(-60.0, scan.networks.single().signal!!, 0.0)
    }
    @Test fun missingMeasurementsStayUnknown() {
        val scan = wifiScan(data("""{"frequencies":[{"channel":1,"mhz":2412}],"networks":[],"survey":[{"frequency":2412,"busy_pct":null,"noise":null}]}"""))
        assertNull(scan.channels.single().busy); assertNull(scan.channels.single().noise)
    }
    @Test fun zeroBusyIsARealMeasurementAndCurrentChannelIsPreserved() {
        val scan = wifiScan(data("""{"networks":[],"survey":[{"frequency":2412,"busy_pct":0,"in_use":true}]}"""))
        assertEquals(0.0, scan.channels.single().busy!!, 0.0); assertEquals(2412.0, scan.currentFrequency!!, 0.0)
    }
    @Test fun outOfRangeBusyDoesNotBecomeValidData() {
        val scan = wifiScan(data("""{"networks":[],"survey":[{"frequency":2412,"busy_pct":101}]}"""))
        assertNull(scan.channels.single().busy)
    }
    @Test fun sixGhzAndChannelFourteenUseCorrectFrequencies() {
        assertEquals(2484.0, wifiFrequency(14,"2g")!!, 0.0)
        assertEquals(5935.0, wifiFrequency(2,"6g")!!, 0.0)
        assertEquals(6115.0, wifiFrequency(33,"6g")!!, 0.0)
    }
    @Test fun unknownWidthIsMarkedAsUnknown() {
        assertFalse(wifiSegments(data("{}"), 2412.0).single().known)
    }
    @Test fun eightyPlusEightyKeepsBothSegments() {
        val result = wifiSegments(data("""{"vht_operation":{"channel_width":8080,"center_freq_1":42,"center_freq_2":155}}"""), 5180.0)
        assertEquals(2, result.size); assertEquals(5210.0, result[0].center, 0.0); assertEquals(5775.0, result[1].center, 0.0)
        assertTrue(result.all { it.width == 80.0 && it.known })
    }
}
