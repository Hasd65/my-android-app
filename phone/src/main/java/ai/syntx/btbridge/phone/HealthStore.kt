package ai.syntx.btbridge.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранилище показаний здоровья, пришедших с часов.
 *
 * ХРАНЕНИЕ: JSON-массив в SharedPreferences, ограничение 500 записей.
 * Для полноценной аналитики нужна БД, но для просмотра последних значений
 * и выгрузки — достаточно, и не тянет зависимость Room.
 */
class HealthStore(ctx: Context) {

    private val p = ctx.applicationContext
        .getSharedPreferences("btbridge_health", Context.MODE_PRIVATE)

    data class Sample(val kind: String, val value: Double, val unit: String, val at: Long)

    @Synchronized
    fun add(kind: String, value: Double, unit: String, at: Long) {
        if (kind.isBlank()) return
        val arr = load()
        val o = JSONObject()
            .put("k", kind)
            .put("v", value)
            .put("u", unit)
            .put("at", at)
        arr.put(o)

        val keep = JSONArray()
        val from = (arr.length() - 500).coerceAtLeast(0)
        for (i in from until arr.length()) keep.put(arr.get(i))

        p.edit().putString("samples", keep.toString()).apply()
    }

    /** Последнее значение по типу: hr, steps и т.д. */
    @Synchronized
    fun latest(kind: String): Sample? {
        val arr = load()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("k") == kind) {
                return Sample(
                    o.optString("k"),
                    o.optDouble("v", 0.0),
                    o.optString("u"),
                    o.optLong("at")
                )
            }
        }
        return null
    }

    /** Сумма шагов за сегодня (по локальной дате). */
    @Synchronized
    fun stepsToday(): Int {
        val arr = load()
        val startOfDay = startOfToday()
        var sum = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("k") == "steps" && o.optLong("at") >= startOfDay) {
                sum += o.optDouble("v", 0.0)
            }
        }
        return sum.toInt()
    }

    @Synchronized
    fun clear() = p.edit().remove("samples").apply()

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun load(): JSONArray = try {
        JSONArray(p.getString("samples", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }
}
