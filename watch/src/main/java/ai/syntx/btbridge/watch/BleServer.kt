package ai.syntx.btbridge.watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import ai.syntx.btbridge.common.FrameReader
import ai.syntx.btbridge.common.Msg
import ai.syntx.btbridge.common.Proto
import org.json.JSONObject

/**
 * BLE GATT-СЕРВЕР на часах.
 *
 * Роль: часы публикуют сервис [Proto.SERVICE] и рекламируются под именем
 * [Proto.DEVICE_NAME]. Телефон подключается как GATT-клиент и пишет кадры
 * в [Proto.CHAR_TO_WATCH]. Часы отвечают ACK через notify в [Proto.CHAR_TO_PHONE].
 *
 * ПОЧЕМУ GATT, А НЕ RFCOMM: SPP-профиль на Wear OS не гарантирован, а его
 * наличие на OnePlus Watch 3 не подтверждено. GATT работает на любом Android 8+
 * и НЕ требует сопряжения: телефон просто находит сервис по UUID.
 *
 * ОГРАНИЧЕНИЯ, КОТОРЫЕ УЧТЕНЫ:
 *  - Один клиент одновременно (connectedDevice). Второй подключившийся вытесняет первого.
 *  - MTU по умолчанию 23, поэтому кадр может прийти частями: см. FrameReader.
 *  - Запись без ответа (WRITE_TYPE_NO_RESPONSE) экономит время, но ACK всё равно
 *    отправляется на уровне протокола — иначе телефон не узнает о доставке.
 *  - Notify требует, чтобы клиент включил CCCD. Пока не включил — ACK не уходит,
 *    это нормально и обрабатывается: телефон не считает кадр доставленным.
 */
class BleServer(
    private val ctx: Context,
    private val onNotification: (Long, String, String, String, Long) -> Unit
) {

    companion object {
        private const val TAG = "BtBridgeGatt"
    }

    @Volatile var connectedName: String? = null
    @Volatile var connectedMac: String? = null
    @Volatile var framesReceived: Int = 0
    @Volatile var lastFrameAt: Long = -1
    @Volatile var isAdvertising: Boolean = false

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var charToWatch: BluetoothGattCharacteristic? = null
    private var charToPhone: BluetoothGattCharacteristic? = null
    private var charInfo: BluetoothGattCharacteristic? = null

    private val reader = FrameReader()
    private var device: BluetoothDevice? = null

    /** Кэш последнего показания датчиков — отдаётся по read из CHAR_INFO. */
    @Volatile var infoJson: String = "{}"

    fun start(): Boolean {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: return false
        if (!adapter.isEnabled) return false

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "нет BLUETOOTH_CONNECT")
            return false
        }

        stop()

        gattServer = mgr.openGattServer(ctx, callback) ?: run {
            Log.e(TAG, "openGattServer вернул null")
            return false
        }

        val service = BluetoothGattService(
            Proto.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        charToWatch = BluetoothGattCharacteristic(
            Proto.CHAR_TO_WATCH,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        charToPhone = BluetoothGattCharacteristic(
            Proto.CHAR_TO_PHONE,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            Proto.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        charToPhone.addDescriptor(cccd)

        charInfo = BluetoothGattCharacteristic(
            Proto.CHAR_INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(charToWatch)
        service.addCharacteristic(charToPhone)
        service.addCharacteristic(charInfo)

        if (!gattServer!!.addService(service)) {
            Log.e(TAG, "addService failed")
            return false
        }

        return advertise(adapter)
    }

    private fun advertise(adapter: android.bluetooth.BluetoothAdapter): Boolean {
        advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "нет LE advertiser")
            return false
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Имя в scan response, UUID сервиса в основном пакете:
        // так телефон фильтрует сканирование по сервису, не разбирая имена.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Proto.SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        return try {
            advertiser!!.startAdvertising(settings, data, scanResponse, advCallback)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "startAdvertising SecurityException", e)
            false
        }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            Log.i(TAG, "advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "advertising failed: $errorCode")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            this@BleServer.device = device
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedName = try {
                    device.name
                } catch (e: SecurityException) {
                    null
                }
                connectedMac = device.address
                reader.reset()
                Log.i(TAG, "connected: ${device.address}")
            } else {
                connectedName = null
                connectedMac = null
                reader.reset()
                Log.i(TAG, "disconnected")
            }
            onLinkChanged()
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == Proto.CCCD) {
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.i(TAG, "CCCD notify=$enable")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (characteristic.uuid != Proto.CHAR_TO_WATCH) return

            // Кадр может прийти частями: FrameReader собирает до целого.
            for (payload in reader.feed(value)) {
                framesReceived++
                lastFrameAt = System.currentTimeMillis()
                handle(payload)
            }
            onLinkChanged()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid) {
                Proto.CHAR_INFO -> infoJson.toByteArray(Charsets.UTF_8)
                Proto.CHAR_TO_PHONE -> "{}".toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }
            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
            )
        }
    }

    private fun handle(payload: String) {
        val o: JSONObject = Msg.parse(payload) ?: return
        when (o.optString("t")) {
            Proto.T_NOTIFY -> {
                val id = o.optLong("id")
                val app = o.optString("app")
                val title = o.optString("title")
                val text = o.optString("text")
                val at = o.optLong("at", System.currentTimeMillis())
                onNotification(id, app, title, text, at)
                sendAck(id)
            }

            Proto.T_PING -> {
                val id = o.optLong("id")
                send(Msg.pong(id))
            }

            Proto.T_HELLO -> Log.i(TAG, "hello from ${o.optString("from")}")
        }
    }

    private fun sendAck(id: Long) = send(Msg.ack(id))

    /** Отправляет кадр через notify. Если клиент отключён — молча выходим. */
    fun send(json: String) {
        val dev = device ?: return
        val ch = charToPhone ?: return
        val srv = gattServer ?: return

        if (!Proto.fit(json)) {
            Log.w(TAG, "кадр слишком велик, отбрасываю")
            return
        }
        try {
            ch.value = Proto.frame(json)
            srv.notifyCharacteristicChanged(dev, ch, false)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify SecurityException", e)
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }

    /** Отправляет показание датчика и обновляет INFO для чтения. */
    fun sendHealth(kind: String, value: Double, unit: String) {
        val at = System.currentTimeMillis()
        send(Msg.health(kind, value, unit, at))
        infoJson = Msg.health(kind, value, unit, at)
    }

    private fun onLinkChanged() {
        // Единая точка для сервиса: он перерисовывает уведомление-статус.
        linkListener?.invoke(connectedMac != null)
    }

    var linkListener: ((Boolean) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            advertiser?.stopAdvertising(advCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising", e)
        }
        advertiser = null
        isAdvertising = false

        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close gatt", e)
        }
        gattServer = null
        device = null
        connectedName = null
        connectedMac = null
        reader.reset()
    }
}
