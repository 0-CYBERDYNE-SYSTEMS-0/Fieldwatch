package app.fieldwatch.radio

import app.fieldwatch.domain.*
import org.junit.Assert.*
import org.junit.Test

class StationPublisherTest {
    private fun radio() = Sighting(
        key = "BLE:AA:BB:CC:DD:EE:01", kind = RadioKind.BLE,
        mac = "AA:BB:CC:DD:EE:01", name = "", rssi = -60, rssiMin = -60, rssiMax = -60,
        channel = 0, frequencyMhz = 2402, vendor = null, randomized = true, hiddenSsid = false,
        serviceUuids = emptyList(), manufacturerId = null, manufacturerDataHex = "", rawHex = "",
        extras = "", firstSeen = 1L, lastSeen = 2L, hitCount = 1, fleetIds = setOf("rid"),
        rssiHistory = emptyList(), presence = emptyList(),
        payloadLat = 40.0, payloadLon = -74.0, payloadAlt = 100.0,
        payloadOpLat = 40.1, payloadOpLon = -74.1, payloadUasId = "TEST-UAS",
        payloadAircraft = "Skydio", payloadHeading = 270.0, payloadSpeed = 10.0,
        payloadVspeed = -2.0, liveDecode = listOf(LiveDecodeChip("airborne", false)),
        payloadTrail = listOf(PayloadFix(1L, 40.0, -74.0, 100.0, 270.0, 10.0)),
    )

    private fun batch(d: Sighting) = StationPublisher.batchJson(
        listOf(d), listOf(Fleet(id = "rid", name = "Remote ID", kind = SignatureClass.DRONE)),
        AppSettings(stationSensorId = "test-phone"), 35.0 to -106.0, 6f, 3L,
    )

    @Test fun droneMotionStatusAndReportedFixSurviveFeed() {
        val b = batch(radio())
        assertEquals(35.0, b.getDouble("lat"), 0.0)
        val h = b.getJSONArray("hears").getJSONObject(0)
        assertEquals(40.0, h.getDouble("self_lat"), 0.0)
        assertEquals(328.084, h.getDouble("self_alt_ft"), 0.001)
        assertEquals(270.0, h.getDouble("track_deg"), 0.0)
        assertEquals(19.438444924406, h.getDouble("speed_kt"), 0.000001)
        val e = h.getJSONObject("extra")
        assertEquals("TEST-UAS", e.getString("uas_id"))
        assertEquals("Skydio", e.getString("aircraft"))
        assertEquals(40.1, e.getDouble("pilot_lat"), 0.0)
        assertEquals(-2.0, e.getDouble("vertical_speed_mps"), 0.0)
        assertEquals("airborne", e.getJSONArray("live_decode").getJSONObject(0).getString("text"))
        assertEquals(1L, e.getJSONArray("payload_trail").getJSONObject(0).getLong("t"))
    }

    @Test fun invalidMotionDoesNotBreakJsonBatch() {
        val h = batch(radio().copy(payloadHeading = Double.NaN, payloadSpeed = -1.0,
            payloadVspeed = Double.POSITIVE_INFINITY)).getJSONArray("hears").getJSONObject(0)
        assertFalse(h.has("track_deg"))
        assertFalse(h.has("speed_kt"))
        assertFalse(h.getJSONObject("extra").has("vertical_speed_mps"))
    }
}
