package app.fieldwatch.radio

import android.os.Build
import android.util.Log
import app.fieldwatch.domain.AppSettings
import app.fieldwatch.domain.Fleet
import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.domain.Sighting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object StationDefaults {
    /** Fieldwatch Station over `adb reverse tcp:8741 tcp:8741` at the desk. */
    const val URL = "http://127.0.0.1:8741/api/batch"
    const val MIN_INTERVAL_MS = 1_000L
    const val MAX_HEARS = 400
    const val TIMEOUT_MS = 2_500

    /** "Pixel 3" -> "pixel-3". The station keys everything on this id. */
    fun sensorId(): String =
        Build.MODEL.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "phone" }
}

data class StationFeedStatus(
    val at: Long = 0L,
    val sent: Int = 0,
    val batches: Int = 0,
    val dest: String = "",
    val error: String? = null,
    val paused: Boolean = false,
    val detail: String = "Off",
)

/**
 * Posts every hear since the last send, plus this phone's fix, to a Fieldwatch Station
 * (`POST /api/batch`, station SPEC §5.2). One batch per second at most, on its own thread,
 * never blocking the scan loop. A send still in flight skips the tick; nothing queues.
 * Class and signature are Fieldwatch's own match; the station does not re-match.
 * Privacy mode pauses the feed, like TAK.
 */
class StationPublisher {
    private var io = Executors.newSingleThreadExecutor { r -> Thread(r, "fieldwatch-station") }
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastTickAt = 0L
    /** Hears with lastSeen after this have not reached the station yet. Advances only on success. */
    @Volatile private var sentThrough = 0L
    @Volatile private var batches = 0
    private val _status = MutableStateFlow(StationFeedStatus())
    val status: StateFlow<StationFeedStatus> = _status.asStateFlow()

    fun publish(
        devices: List<Sighting>,
        fleets: List<Fleet>,
        settings: AppSettings,
        fix: Pair<Double, Double>?,
        accuracyM: Float?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!settings.stationEnabled) {
            if (_status.value.detail != "Off") _status.value = StationFeedStatus()
            return
        }
        if (settings.demoMode) {
            _status.value = StationFeedStatus(paused = true, detail = "Privacy mode — feed paused")
            return
        }
        if (now - lastTickAt < StationDefaults.MIN_INTERVAL_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastTickAt = now
        val url = settings.stationUrl.trim().ifBlank { StationDefaults.URL }
        val since = if (sentThrough == 0L) now - 5_000L else sentThrough
        val fresh = devices.asSequence()
            .filter { !it.gone && it.lastSeen > since }
            .sortedByDescending { it.lastSeen }
            .take(StationDefaults.MAX_HEARS)
            .toList()
        val through = fresh.maxOfOrNull { it.lastSeen } ?: sentThrough
        val body = batchJson(fresh, fleets, settings, fix, accuracyM, now).toString()
        io.execute {
            try {
                val code = post(url, body)
                if (code in 200..299) {
                    sentThrough = maxOf(sentThrough, through)
                    batches++
                    _status.value = StationFeedStatus(now, fresh.size, batches, url, detail = "")
                } else {
                    _status.value = StationFeedStatus(now, 0, batches, url, error = "station answered HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "station post to $url failed: $e")
                _status.value = StationFeedStatus(now, 0, batches, url, error = e.javaClass.simpleName +
                    (e.message?.let { ": ${it.take(80)}" } ?: ""))
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun close() {
        // Let any queued batch finish, then retire the thread so an idle
        // executor doesn't linger; a fresh one takes over if scanning restarts.
        // close() and publish() run on the same main thread, so the swap is safe.
        io.shutdown()
        io = Executors.newSingleThreadExecutor { r -> Thread(r, "fieldwatch-station") }
        sentThrough = 0L
        lastTickAt = 0L
        _status.value = StationFeedStatus()
    }

    private fun post(url: String, body: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = StationDefaults.TIMEOUT_MS
            conn.readTimeout = StationDefaults.TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
            return code
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "FieldwatchStation"

        fun batchJson(
            devices: List<Sighting>,
            fleets: List<Fleet>,
            settings: AppSettings,
            fix: Pair<Double, Double>?,
            accuracyM: Float?,
            now: Long,
        ): JSONObject {
            val byId = fleets.associateBy { it.id }
            val hears = JSONArray()
            for (d in devices) hears.put(hearJson(d, byId))
            return JSONObject().apply {
                put("sensor_id", settings.stationSensorId.trim().ifBlank { StationDefaults.sensorId() })
                put("sensor_label", Build.MODEL)
                put("sensor_kind", "phone")
                put("at", now)
                if (fix != null && PayloadLocation.validCoord(fix.first, fix.second)) {
                    put("lat", fix.first)
                    put("lon", fix.second)
                    if (accuracyM != null && accuracyM.isFinite()) put("acc_m", accuracyM.toDouble())
                }
                put("hears", hears)
            }
        }

        private fun hearJson(d: Sighting, fleets: Map<String, Fleet>): JSONObject = JSONObject().apply {
            val matched = d.fleetIds.mapNotNull { fleets[it] }
            put("kind", d.kind.name)
            put("radio_id", d.mac)
            d.name.trim().takeIf { it.isNotEmpty() && !it.equals(d.mac, ignoreCase = true) }?.let { put("name", it) }
            put("rssi", d.rssi.coerceIn(-140, 20))
            if (d.channel > 0) put("channel", d.channel)
            if (d.frequencyMhz > 0) put("frequency_mhz", d.frequencyMhz)
            matched.firstOrNull()?.let { put("class", it.kind.name) }
            if (matched.isNotEmpty()) put("signature", matched.joinToString(" + ") { it.name })
            d.vendor?.takeIf { it.isNotBlank() }?.let { put("vendor", it) }
            put("t", d.lastSeen)
            if (PayloadLocation.validCoord(d.payloadLat, d.payloadLon)) {
                put("self_lat", d.payloadLat!!)
                put("self_lon", d.payloadLon!!)
                d.payloadAlt?.takeIf { it.isFinite() }?.let { put("self_alt_ft", it * 3.28084) }
            }
            d.payloadHeading?.takeIf { it.isFinite() && it >= 0 && it < 360 }?.let { put("track_deg", it) }
            d.payloadSpeed?.takeIf { it.isFinite() && it >= 0 }?.let { put("speed_kt", it * 1.9438444924406) }
            put("extra", JSONObject().apply {
                put("randomized", d.randomized)
                if (d.hiddenSsid) put("hidden_ssid", true)
                d.manufacturerId?.let { put("manufacturer_id", it) }
                if (d.serviceUuids.isNotEmpty()) put("service_uuids", JSONArray(d.serviceUuids.take(8)))
                if (d.vendorIeOuis.isNotEmpty()) put("vendor_ie_ouis", JSONArray(d.vendorIeOuis.take(8)))
                if (matched.size > 1) put("classes", JSONArray(matched.map { it.kind.name }.distinct()))
                if (d.fastPairPairing) put("fast_pair_pairing", true)
                d.payloadUasId?.let { put("uas_id", it) }
                d.payloadAircraft?.let { put("aircraft", it) }
                d.payloadSelfId?.let { put("self_id", it) }
                d.payloadVspeed?.takeIf { it.isFinite() }?.let { put("vertical_speed_mps", it) }
                if (d.liveDecode.isNotEmpty()) put("live_decode", JSONArray(d.liveDecode.map { chip ->
                    JSONObject().apply {
                        put("text", chip.text)
                        put("emphasis", chip.emphasis)
                        put("note", chip.note)
                    }
                }))
                if (d.payloadTrail.isNotEmpty()) put("payload_trail", JSONArray(d.payloadTrail.filter {
                    PayloadLocation.validCoord(it.lat, it.lon)
                }.map { fix ->
                    JSONObject().apply {
                        put("t", fix.at)
                        put("lat", fix.lat)
                        put("lon", fix.lon)
                        fix.alt?.takeIf { it.isFinite() }?.let { put("alt_m", it) }
                        fix.heading?.takeIf { it.isFinite() }?.let { put("track_deg", it) }
                        fix.speed?.takeIf { it.isFinite() }?.let { put("speed_mps", it) }
                    }
                }))
                if (PayloadLocation.validCoord(d.payloadOpLat, d.payloadOpLon)) {
                    put("pilot_lat", d.payloadOpLat!!)
                    put("pilot_lon", d.payloadOpLon!!)
                }
                put("fieldwatch_key", d.key)
            })
        }
    }
}
