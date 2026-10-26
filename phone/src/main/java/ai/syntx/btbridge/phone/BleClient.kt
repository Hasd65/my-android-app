package ai.syntx.btbridge.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import ai.syntx.btbridge.common.FrameReader
import ai.syntx.btbridge.common.Msg
import ai.syntx.btbridge.common.Proto
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BLE GATT-КЛИЕНТ на телефоне.
 *
 * Алгоритм:
 *  1. Сканируем по UUID сервиса [Proto.SERVICE] (фильтр, а не разбор имён —
 *     так работает даже когда имя обрезано в scan response).
 *  2. Нашли — connectGatt, ждём onConnectionStateChange(CONNECTED).
 *  3. requestMtu(517): больший MTU = меньше фрагментов на кадр.
 *  4. discoverServices → находим характеристики → включаем notify (CCCD).
 *  5. Отправляем HELLO. Дальше — очередь кадров.
 *
 * ПОЧЕМУ ОЧЕРЕДЬ: BLE не умеет писать параллельно. Если отправить второй кадр
 * до onCharacteristicWrite, Android теряет первый. Поэтому строго по одному,
 * следующий — из колбэка предыдущего.
 *
 * ПЕРЕПОДКЛЮЧЕНИЕ: при разрыве — экспоненциальная пауза 2→4→8→…→60 с,
 * сбрасывается после успешного подключения.
 */
class BleClient(
    private val ctx: Context,
    private val onStatus: (String) -> Unit,
    private val onHealth: (kind: String, value: Double, unit: String, at: Long) -> Unit
) {

    companion object {
        private const val TAG = "BtBridgeClient"
        private const val MTU_REQUEST = 517
        private const val WRITE_TIMEOUT_MS = 4000L
        private const val MAX_RETRY_MS = 60_000L
        private const val MIN_RETRY_MS = 2_000L
    }

    @Volatile var connected = false
        private set
    @Volatile var deviceName: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var charToWatch: BluetoothGattCharacteristic? = null
    private var charToPhone: BluetoothGattCharacteristic? = null

    private val scanning = AtomicBoolean(false)
    private val reader = FrameReader()

    /** Очередь исходящих кадров. Доступ только с main-потока. */
    private val queue = ArrayDeque<String>()
    private var writing = false

    /**
     * Очередь готовых байтовых фрагментов для записи.
     *
     * Кадр может быть больше (MTU-3), и тогда GATT отвергнет запись целиком.
     * Поэтому кадр режется на куски по (mtu-3) и отправляется по одному;
     * часы собирают обратно через FrameReader. MTU по умолчанию 23,
     * после requestMtu обычно 247 или 517.
     */
    private val pending = ArrayDeque<ByteArray>()
    private var mtu = 23
    private var retryMs = MIN_RETRY_MS
    private var wantConnection = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            Log.i(TAG, "нашли ${device.address} rssi=${result.rssi}")
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning.set(false)
            onStatus("сканирование не удалось: $errorCode")
            scheduleRetry()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPerms()) {
            onStatus("нет разрешений Bluetooth")
            return
        }
        wantConnection = true
        retryMs = MIN_RETRY_MS
        startScan()
    }

    fun stop() {
        wantConnection = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        disconnect()
        onStatus("остановлено")
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning.get()) return
        if (!hasPerms()) return

        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth выключен")
            scheduleRetry()
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("сканер недоступен")
            scheduleRetry()
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Фильтр по сервису: телефон не будет реагировать на чужие BLE-устройства.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Proto.SERVICE))
            .build()

        onStatus("поиск часов…")
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning.set(true)
            // Сканирование бесконечно расходует батарею, поэтому ограничиваем.
            handler.postDelayed({
                if (scanning.get()) {
                    stopScan()
                    onStatus("часы не найдены")
                    scheduleRetry()
                }
            }, 20_000)
        } catch (e: SecurityException) {
            onStatus("нет доступа к сканеру")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning.getAndSet(false)) return
        try {
            val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            mgr?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        disconnect()
        reader.reset()
        onStatus("подключение…")

        // TRANSPORT_LE обязателен: иначе Android может пойти по BR/EDR и не найти GATT.
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(ctx, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        try {
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "gatt close", e)
        }
        gatt = null
        charToWatch = null
        charToPhone = null
        connected = false
        writing = false
        queue.clear()
        pending.clear()
        reader.reset()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                retryMs = MIN_RETRY_MS
                g.requestMtu(MTU_REQUEST)
            } else {
                connected = false
                deviceName = null
                onStatus("нет связи")
                try {
                    g.close()
                } catch (e: Exception) {
                    // уже закрыт
                }
                if (gatt === g) gatt = null
                if (wantConnection) scheduleRetry()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // Запоминаем согласованный MTU: от него зависит размер фрагмента.
            // При отказе остаётся 23 — работать будет, но мельче.
            this@BleClient.mtu = if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) mtu else 23
            Log.i(TAG, "MTU = ${this@BleClient.mtu}")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(Proto.SERVICE)
            if (service == null) {
                onStatus("сервис не найден на часах")
                disconnect()
                scheduleRetry()
                return
            }

            charToWatch = service.getCharacteristic(Proto.CHAR_TO_WATCH)
            charToPhone = service.getCharacteristic(Proto.CHAR_TO_PHONE)

            if (charToWatch == null || charToPhone == null) {
                onStatus("характеристики не найдены")
                disconnect()
                scheduleRetry()
                return
            }

            // Включаем notify на стороне часов: без этого ACK не дойдут.
            g.setCharacteristicNotification(charToPhone, true)
            val cccd = charToPhone.getDescriptor(Proto.CCCD)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }

            connected = true
            deviceName = try {
                g.device.name
            } catch (e: SecurityException) {
                null
            }
            onStatus("подключено: ${deviceName ?: "часы"}")

            enqueue(Msg.hello("phone", Proto.VERSION, android.os.Build.MODEL))
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // После включения notify можно отправлять данные.
            if (status == BluetoothGatt.GATT_SUCCESS) flush()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writing = false
            flush()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            writing = false
            flush()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleIncoming(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(value)
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        for (payload in reader.feed(bytes)) {
            val o: JSONObject = Msg.parse(payload) ?: continue
            when (o.optString("t")) {
                Proto.T_ACK -> Unit
                Proto.T_PONG -> Unit
                Proto.T_HEALTH -> {
                    onHealth(
                        o.optString("kind"),
                        o.optDouble("v", 0.0),
                        o.optString("u"),
                        o.optLong("at", System.currentTimeMillis())
                    )
                }
            }
        }
    }

    /** Кладёт кадр в очередь и запускает отправку, если канал свободен. */
    fun enqueue(json: String) {
        if (!Proto.fit(json)) {
            Log.w(TAG, "кадр не влезает, отбрасываю")
            return
        }
        handler.post {
            queue.addLast(json)
            // Защита от роста очереди при долгом отсутствии связи.
            while (queue.size > 200) queue.removeFirst()
            flush()
        }
    }

    /**
     * Отправляет следующий фрагмент. Вызывается из колбэка предыдущей записи,
     * поэтому одновременно в полёте не больше одного фрагмента.
     */
    @SuppressLint("MissingPermission")
    private fun flush() {
        if (writing) return
        if (!connected) return

        val g = gatt ?: return
        val ch = charToWatch ?: return

        // Сначала добиваем уже нарезанный кадр, только потом берём следующий.
        if (pending.isEmpty()) {
            val next = queue.pollFirst() ?: return
            val frame = Proto.frame(next)
            val chunk = (mtu - 3).coerceAtLeast(20)
            var off = 0
            while (off < frame.size) {
                val len = minOf(chunk, frame.size - off)
                pending.addLast(frame.copyOfRange(off, off + len))
                off += len
            }
        }

        val piece = pending.pollFirst() ?: return
        writing = true

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                ch,
                piece,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.value = piece
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(ch)
            }
        }

        if (!ok) {
            writing = false
            Log.w(TAG, "writeCharacteristic не принят")
            handler.postDelayed({ flush() }, 200)
            return
        }

        // Таймаут: колбэк может не прийти при разрыве на середине записи.
        handler.postDelayed({
            if (writing) {
                writing = false
                flush()
            }
        }, WRITE_TIMEOUT_MS)
    }

    private fun scheduleRetry() {
        if (!wantConnection) return
        val delay = retryMs
        retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        onStatus("повтор через ${delay / 1000} с")
        handler.postDelayed({
            if (wantConnection && !connected) startScan()
        }, delay)
    }

    private fun hasPerms(): Boolean {
        val need = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        return need.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
