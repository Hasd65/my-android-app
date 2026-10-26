package ai.syntx.btbridge.common

import java.io.ByteArrayOutputStream

/**
 * Приёмник кадров из потока байт.
 *
 * Нужен потому, что BLE отдаёт данные кусками (по MTU, обычно 20-244 байта),
 * а логический кадр может быть больше. Класс накапливает байты и выдаёт
 * готовые кадры целиком.
 *
 * Потокобезопасность: методы synchronized. Вызывать из одного потока желательно,
 * но не обязательно.
 */
class FrameReader {

    private val buf = ByteArrayOutputStream()
    private var expected = -1

    /** Скармливает очередной кусок. Возвращает список готовых payload-строк. */
    @Synchronized
    fun feed(chunk: ByteArray): List<String> {
        val out = ArrayList<String>(2)
        buf.write(chunk)
        while (true) {
            val data = buf.toByteArray()
            if (expected < 0) {
                if (data.size < Proto.HEADER) return out
                expected = Proto.lengthOf(data)
                if (expected <= 0 || expected > Proto.MAX_PAYLOAD) {
                    // Битый заголовок: сбрасываем буфер, иначе застрянем навсегда.
                    buf.reset()
                    expected = -1
                    return out
                }
            }
            if (data.size < Proto.HEADER + expected) return out
            val body = String(data, Proto.HEADER, expected, Charsets.UTF_8)
            out.add(body)
            buf.reset()
            val rest = data.copyOfRange(Proto.HEADER + expected, data.size)
            if (rest.isNotEmpty()) buf.write(rest)
            expected = -1
        }
    }

    @Synchronized
    fun reset() {
        buf.reset()
        expected = -1
    }
}
