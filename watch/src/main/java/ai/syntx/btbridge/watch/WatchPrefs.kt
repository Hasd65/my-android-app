package ai.syntx.btbridge.watch

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки часов. Хранятся в SharedPreferences, без внешних зависимостей,
 * чтобы приложение работало даже при отсутствии сервисов Google.
 */
class WatchPrefs(ctx: Context) {

    private val p: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("btbridge_watch", Context.MODE_PRIVATE)

    /** Показывать карточку-уведомление при каждом сообщении. */
    var showCards: Boolean
        get() = p.getBoolean("show_cards", true)
        set(v) = p.edit().putBoolean("show_cards", v).apply()

    /** Вибрация при сообщении. */
    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) = p.edit().putBoolean("vibrate", v).apply()

    /** Будить экран при сообщении. */
    var wakeScreen: Boolean
        get() = p.getBoolean("wake", true)
        set(v) = p.edit().putBoolean("wake", v).apply()

    /** Автозапуск сервиса после перезагрузки часов. */
    var autoStart: Boolean
        get() = p.getBoolean("autostart", true)
        set(v) = p.edit().putBoolean("autostart", v).apply()

    /** Передавать шаги и пульс на телефон. */
    var shareHealth: Boolean
        get() = p.getBoolean("health", true)
        set(v) = p.edit().putBoolean("health", v).apply()

    /** Сколько сообщений хранить в истории. */
    var historyLimit: Int
        get() = p.getInt("hist_limit", 50)
        set(v) = p.edit().putInt("hist_limit", v.coerceIn(10, 200)).apply()
}
