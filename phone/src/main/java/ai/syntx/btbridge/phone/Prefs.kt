package ai.syntx.btbridge.phone

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки телефона.
 *
 * ОГРАНИЧЕНИЕ: список выбранных приложений хранится как строка с разделителем,
 * а не JSONObject в SharedPreferences: Set<String> нельзя надёжно
 * редактировать при большом числе пакетов, а JSON даёт лишние аллокации
 * при каждом уведомлении.
 */
class Prefs(ctx: Context) {

    private val p: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("btbridge_phone", Context.MODE_PRIVATE)

    var autoStart: Boolean
        get() = p.getBoolean("autostart", true)
        set(v) = p.edit().putBoolean("autostart", v).apply()

    /** Заголовок и текст передавать. Если выключено — только имя приложения. */
    var sendContent: Boolean
        get() = p.getBoolean("content", true)
        set(v) = p.edit().putBoolean("content", v).apply()

    /** Показывать сообщения, отправленные самой программой (защита от петли). */
    var dropOwn: Boolean
        get() = p.getBoolean("drop_own", true)
        set(v) = p.edit().putBoolean("drop_own", v).apply()

    /** Фильтр: пусто — пропускать всё. */
    var whitelist: Set<String>
        get() = (p.getString("whitelist", "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
            .toSet()
        set(v) = p.edit().putString("whitelist", v.joinToString("\n")).apply()

    var blacklist: Set<String>
        get() = (p.getString("blacklist", "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
            .toSet()
        set(v) = p.edit().putString("blacklist", v.joinToString("\n")).apply()

    var lastConnectedAt: Long
        get() = p.getLong("last_conn", -1)
        set(v) = p.edit().putLong("last_conn", v).apply()

    var sentCount: Long
        get() = p.getLong("sent", 0)
        set(v) = p.edit().putLong("sent", v).apply()
}
