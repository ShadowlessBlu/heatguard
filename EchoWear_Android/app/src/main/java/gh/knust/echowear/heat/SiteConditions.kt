package gh.knust.echowear.heat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class SiteSource(val label: String) {
    BEACON("Site beacon"), MANUAL("Entered by supervisor"), WEATHER("Weather service (shade air)")
}

data class SiteReading(
    val taC: Double? = null,
    val rhPct: Double? = null,
    val tgC: Double? = null,
    val wbgtC: Double? = null,
    val source: SiteSource,
    val at: Long = System.currentTimeMillis(),
) {
    /** The WBGT the engine will use, for display. */
    val wbgtEstimate: Double? get() = wbgtC ?: HeatEnvironment.wbgtEstimate(taC, rhPct, tgC)
}

/**
 * Where site heat comes from, best first:
 *   1. the site beacon (Day 3 of the plan), if heard in the last 5 minutes;
 *   2. a reading the supervisor typed in, for 4 hours;
 *   3. Open-Meteo current weather, if switched on, for 90 minutes.
 * None of them -> null, and the engine reports "site heat not measured".
 *
 * Weather is opt-in because it sends the site's position to a third party.
 * The position is rounded to 0.01° (about 1 km) first, and no worker data goes
 * with it. Weather-station air temperature is measured in shade, so it misses
 * the sun's load on someone working in it; a beacon with a black globe does not.
 */
class SiteConditions(context: Context) {

    companion object {
        private const val TAG = "SiteConditions"
        const val BEACON_MAX_AGE_MS = 5L * 60 * 1000
        const val MANUAL_MAX_AGE_MS = 4L * 3600 * 1000
        const val WEATHER_MAX_AGE_MS = 90L * 60 * 1000
        const val WEATHER_REFRESH_MS = 15L * 60 * 1000
    }

    private val prefs = context.getSharedPreferences("echowear_site", Context.MODE_PRIVATE)

    private var beacon: SiteReading? = null
    private var manual: SiteReading? = loadManual()
    private var weather: SiteReading? = null

    private val _current = MutableStateFlow<SiteReading?>(null)
    val current: StateFlow<SiteReading?> = _current.asStateFlow()

    private val _weatherEnabled = MutableStateFlow(prefs.getBoolean("weather_enabled", false))
    val weatherEnabled: StateFlow<Boolean> = _weatherEnabled.asStateFlow()

    init { refresh() }

    /** The reading the engine should use right now, or null. */
    @Synchronized
    fun refresh(now: Long = System.currentTimeMillis()): SiteReading? {
        val pick = beacon?.takeIf { now - it.at <= BEACON_MAX_AGE_MS }
            ?: manual?.takeIf { now - it.at <= MANUAL_MAX_AGE_MS }
            ?: weather?.takeIf { _weatherEnabled.value && now - it.at <= WEATHER_MAX_AGE_MS }
        _current.value = pick
        return pick
    }

    @Synchronized
    fun onBeacon(r: SiteReading) { beacon = r.copy(source = SiteSource.BEACON); refresh() }

    @Synchronized
    fun setManual(taC: Double, rhPct: Double, tgC: Double?) {
        val r = SiteReading(taC = taC, rhPct = rhPct, tgC = tgC, source = SiteSource.MANUAL)
        manual = r
        prefs.edit()
            .putFloat("m_ta", taC.toFloat()).putFloat("m_rh", rhPct.toFloat())
            .putFloat("m_tg", tgC?.toFloat() ?: Float.NaN).putLong("m_at", r.at)
            .apply()
        refresh()
    }

    @Synchronized
    fun clearManual() {
        manual = null
        prefs.edit().remove("m_ta").remove("m_rh").remove("m_tg").remove("m_at").apply()
        refresh()
    }

    fun setWeatherEnabled(on: Boolean) {
        _weatherEnabled.value = on
        prefs.edit().putBoolean("weather_enabled", on).apply()
        if (!on) synchronized(this) { weather = null }
        refresh()
    }

    private fun loadManual(): SiteReading? {
        if (!prefs.contains("m_at")) return null
        val tg = prefs.getFloat("m_tg", Float.NaN)
        return SiteReading(
            taC = prefs.getFloat("m_ta", 0f).toDouble(),
            rhPct = prefs.getFloat("m_rh", 0f).toDouble(),
            tgC = if (tg.isNaN()) null else tg.toDouble(),
            source = SiteSource.MANUAL,
            at = prefs.getLong("m_at", 0L),
        )
    }

    /** Fetch current weather if it is switched on and due. Safe to call every minute. */
    suspend fun maybeRefreshWeather(lat: Double?, lon: Double?, now: Long = System.currentTimeMillis()) {
        if (!_weatherEnabled.value || lat == null || lon == null) return
        val last = synchronized(this) { weather?.at ?: 0L }
        if (now - last < WEATHER_REFRESH_MS) return
        val r = runCatching { fetchOpenMeteo(lat, lon) }
            .onFailure { Log.w(TAG, "weather fetch failed", it) }
            .getOrNull() ?: return
        synchronized(this) { weather = r }
        refresh()
    }

    private suspend fun fetchOpenMeteo(lat: Double, lon: Double): SiteReading =
        withContext(Dispatchers.IO) {
            val url = URL(String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,relative_humidity_2m",
                lat, lon,
            ))
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val cur = JSONObject(body).getJSONObject("current")
                SiteReading(
                    taC = cur.getDouble("temperature_2m"),
                    rhPct = cur.getDouble("relative_humidity_2m"),
                    source = SiteSource.WEATHER,
                )
            } finally {
                conn.disconnect()
            }
        }
}
