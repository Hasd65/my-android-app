package ai.syntx.btbridge.common

import org.json.JSONObject

/**
 * Сборка и разбор сообщений. Обе стороны используют один код,
 * поэтому расхождение формата исключено.
 *
 * Все сообщения — плоский JSON с полем "t" (тип).
 * Правило: не добавлять поля без увеличения Proto.VERSION,
 * если старая сторона может их не понять.
 */
object Msg {

    fun hello(from: String, ver: Int, name: String): String =
        JSONObject()
            .put("t", Proto.T_HELLO)
            .put("from", from)
            .put("ver", ver)
            .put("name", name)
            .toString()

    fun ping(id: Long): String =
        JSONObject().put("t", Proto.T_PING).put("id", id).toString()

    fun pong(id: Long): String =
        JSONObject().put("t", Proto.T_PONG).put("id", id).toString()

    fun ack(id: Long): String =
        JSONObject().put("t", Proto.T_ACK).put("id", id).toString()

    /**
     * Уведомление. Поля обрезаются под MTU: длинный текст — основная причина
     * превышения размера кадра.
     */
    fun notify(
        id: Long,
        app: String,
        title: String?,
        text: String?,
        at: Long,
        pkg: String?
    ): String {
        val t = Proto.truncate(title, 400)
        val b = Proto.truncate(text, 1600)
        return JSONObject()
            .put("t", Proto.T_NOTIFY)
            .put("id", id)
            .put("app", Proto.truncate(app, 120))
            .put("title", t)
            .put("text", b)
            .put("at", at)
            .put("pkg", Proto.truncate(pkg, 120))
            .toString()
    }

    /**
     * Показание здоровья, снятое на часах.
     * kind: hr | steps | spo2 | kcal | dist
     */
    fun health(kind: String, value: Double, unit: String, at: Long): String =
        JSONObject()
            .put("t", Proto.T_HEALTH)
            .put("kind", kind)
            .put("v", value)
            .put("u", unit)
            .put("at", at)
            .toString()

    /** Разбирает JSON, возвращает null при любой ошибке — вызывающий решает, что делать. */
    fun parse(raw: String): JSONObject? = try {
        val o = JSONObject(raw)
        if (o.optString("t").isEmpty()) null else o
    } catch (e: Exception) {
        null
    }
}
