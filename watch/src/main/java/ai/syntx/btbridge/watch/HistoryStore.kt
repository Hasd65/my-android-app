package ai.syntx.btbridge.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * История входящих уведомлений на часах.
 *
 * ХРАНЕНИЕ: один JSON-массив в SharedPreferences. Объём ограничен
 * (по умолчанию 50 записей), поэтому отдельная БД не нужна и не оправдана.
 *
 * ВАЖНО: метод add() вызывается из потока GATT-сервера, а read() — из UI.
 * Оба synchronized на объекте класса: SharedPreferences не гарантирует
 * атомарность read-modify-write между потоками.
 */
class HistoryStore(ctx: Context) {

    private val p = ctx.applicationContext
        .getSharedPreferences("btbridge_history", Context.MODE_PRIVATE)

    data class Item(
        val id: Long,
        val app: String,
        val title: String,
        val text: String,
        val at: Long
    )

    @Synchronized
    fun add(item: Item, limit: Int) {
        val arr = load()
        val obj = JSONObject()
            .put("id", item.id)
            .put("app", item.app)
            .put("title", item.title)
            .put("text", item.text)
            .put("at", item.at)

        // Дедупликация по id: телефон может повторить кадр при переподключении.
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optLong("id") == item.id) return
        }
        arr.put(obj)

        // Обрезаем старые записи сверху.
        val keep = JSONArray()
        val from = (arr.length() - limit).coerceAtLeast(0)
        for (i in from until arr.length()) keep.put(arr.get(i))

        p.edit().putString("items", keep.toString()).apply()
    }

    @Synchronized
    fun read(): List<Item> {
        val arr = load()
        val out = ArrayList<Item>(arr.length())
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Item(
                    o.optLong("id"),
                    o.optString("app"),
                    o.optString("title"),
                    o.optString("text"),
                    o.optLong("at")
                )
            )
        }
        return out
    }

    @Synchronized
    fun clear() = p.edit().remove("items").apply()

    @Synchronized
    fun count(): Int = load().length()

    private fun load(): JSONArray = try {
        JSONArray(p.getString("items", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }
}
