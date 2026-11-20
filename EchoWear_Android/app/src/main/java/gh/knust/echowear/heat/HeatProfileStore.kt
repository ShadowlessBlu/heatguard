package gh.knust.echowear.heat

import android.content.Context
import org.json.JSONObject

/**
 * Per-wearer heat settings, kept on the phone only (like the patient's name).
 *
 * Kept apart from PairedDevice on purpose: no Room migration, and a wearer
 * who is not an outdoor worker (the original EchoWear use) simply has heat
 * monitoring switched off.
 */
class HeatProfileStore(context: Context) {

    data class Entry(val enabled: Boolean, val profile: WorkerProfile)

    private val prefs = context.getSharedPreferences("echowear_heat_profiles", Context.MODE_PRIVATE)

    fun get(deviceId: String): Entry {
        val raw = prefs.getString(deviceId, null) ?: return Entry(true, WorkerProfile())
        return runCatching {
            val o = JSONObject(raw)
            Entry(
                enabled = o.optBoolean("enabled", true),
                profile = WorkerProfile(
                    age = if (o.has("age")) o.getInt("age") else null,
                    restingHr = if (o.has("resting_hr")) o.getDouble("resting_hr") else null,
                    daysAcclimatized = o.optDouble("days_acclimatized", 0.0),
                    clothing = o.optString("clothing", "work_clothes"),
                    hood = o.optBoolean("hood", false),
                    declaredWork = runCatching { WorkClass.valueOf(o.optString("declared_work")) }
                        .getOrDefault(WorkClass.MODERATE),
                ),
            )
        }.getOrDefault(Entry(true, WorkerProfile()))
    }

    fun put(deviceId: String, e: Entry) {
        val p = e.profile
        val o = JSONObject()
            .put("enabled", e.enabled)
            .put("days_acclimatized", p.daysAcclimatized)
            .put("clothing", p.clothing)
            .put("hood", p.hood)
            .put("declared_work", p.declaredWork.name)
        p.age?.let { o.put("age", it) }
        p.restingHr?.let { o.put("resting_hr", it) }
        prefs.edit().putString(deviceId, o.toString()).apply()
    }

    fun remove(deviceId: String) { prefs.edit().remove(deviceId).apply() }
}
