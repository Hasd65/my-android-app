package ai.syntx.btbridge.common

import java.util.UUID

/**
 * Общие константы протокола для обеих сторон.
 *
 * ТРАНСПОРТ: BLE GATT. Часы — GATT-сервер (advertiser), телефон — GATT-клиент.
 * Почему BLE, а не RFCOMM/SPP: RFCOMM на Wear OS требует системного SPP-профиля,
 * и его наличие на OnePlus Watch 3 не подтверждено. BLE GATT доступен на любом
 * Android 8+ и не требует сопряжения (bond) — телефон просто подключается к сервису.
 *
 * ФОРМАТ КАДРА (одно сообщение на одну запись в характеристику):
 *   4 байта  — длина payload (big-endian, int)
 *   N байт   — payload: UTF-8 JSON
 * Максимальный размер payload — 4000 байт (с запасом под MTU 517).
 * JSON обрезается по полям до отправки, см. Frame.truncate().
 */
object Proto {

    /** Головной байт: структура кадра. Версия протокола для совместимости. */
    const val VERSION = 3

    /**
     * UUID сервиса моста. Случайный, но стабильный: меняется только при
     * несовместимом изменении протокола.
     */
    val SERVICE: UUID = UUID.fromString("a1b2c3d4-0001-4e5f-9a01-7c1d2e3f4a01")

    /** Телефон → часы: запись кадров (write / write-no-response). */
    val CHAR_TO_WATCH: UUID = UUID.fromString("a1b2c3d4-0002-4e5f-9a01-7c1d2e3f4a01")

    /** Часы → телефон: подтверждения и статус (notify + read). */
    val CHAR_TO_PHONE: UUID = UUID.fromString("a1b2c3d4-0003-4e5f-9a01-7c1d2e3f4a01")

    /** Информация об устройстве: имя, версия, счётчики. Только чтение. */
    val CHAR_INFO: UUID = UUID.fromString("a1b2c3d4-0004-4e5f-9a01-7c1d2e3f4a01")

    /** Клиентская характеристика конфигурации (CCCD для notify). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Имя BLE-устройства часов: по нему телефон находит нужный сервис. */
    const val DEVICE_NAME = "BtBridgeW"

    const val MAX_PAYLOAD = 4000
    const val HEADER = 4

    // Типы сообщений
    const val T_NOTIFY = "notify"
    const val T_HEALTH = "health"
    const val T_ACK = "ack"
    const val T_PING = "ping"
    const val T_PONG = "pong"
    const val T_HELLO = "hello"

    /**
     * Собирает кадр: 4 байта длины + JSON.
     * Вызывающая сторона обязана проверить размер через [fit].
     */
    fun frame(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_PAYLOAD) { "payload ${body.size} > $MAX_PAYLOAD" }
        val out = ByteArray(HEADER + body.size)
        out[0] = ((body.size ushr 24) and 0xFF).toByte()
        out[1] = ((body.size ushr 16) and 0xFF).toByte()
        out[2] = ((body.size ushr 8) and 0xFF).toByte()
        out[3] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, out, HEADER, body.size)
        return out
    }

    /** Длина из заголовка. Возвращает -1, если данных меньше 4 байт. */
    fun lengthOf(buf: ByteArray): Int {
        if (buf.size < HEADER) return -1
        return ((buf[0].toInt() and 0xFF) shl 24) or
                ((buf[1].toInt() and 0xFF) shl 16) or
                ((buf[2].toInt() and 0xFF) shl 8) or
                (buf[3].toInt() and 0xFF)
    }

    /** Проверяет, что JSON помещается в кадр. Используется перед отправкой. */
    fun fit(json: String): Boolean = json.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD

    /**
     * Обрезает текст, чтобы кадр точно поместился.
     * Длина в байтах, не в символах: кириллица занимает 2 байта на символ.
     */
    fun truncate(s: String?, maxBytes: Int): String {
        if (s == null) return ""
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        var end = s.length
        while (end > 0 && s.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end--
        }
        return if (end > 1) s.substring(0, end - 1) + "…" else ""
    }
}
