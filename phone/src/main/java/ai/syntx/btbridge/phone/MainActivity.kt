package ai.syntx.btbridge.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Главный экран телефона.
 *
 * Логика: одна кнопка запуска/остановки, статус, проверка разрешений.
 * Всё остальное — переходы в системные настройки, где пользователь выдаёт
 * доступ к уведомлениям и снимает ограничения батареи.
 *
 * ВАЖНО: без «Доступ к уведомлениям» мост не работает вообще. Экран это
 * явно проверяет и показывает красным, чтобы не было молчаливого отказа.
 */
class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPerms()

        setContent {
            val prefs = remember { Prefs(this) }
            val health = remember { HealthStore(this) }

            var running by remember { mutableStateOf(BridgeService.running) }
            var status by remember { mutableStateOf(BridgeService.status) }
            var connected by remember { mutableStateOf(BridgeService.connected) }
            var notifAccess by remember { mutableStateOf(hasNotifAccess()) }
            var sent by remember { mutableStateOf(prefs.sentCount) }
            var hr by remember { mutableStateOf<HealthStore.Sample?>(null) }
            var steps by remember { mutableStateOf(0) }
            var sendContent by remember { mutableStateOf(prefs.sendContent) }

            LaunchedEffect(Unit) {
                while (true) {
                    running = BridgeService.running
                    status = BridgeService.status
                    connected = BridgeService.connected
                    notifAccess = hasNotifAccess()
                    sent = prefs.sentCount
                    hr = health.latest("hr")
                    steps = health.stepsToday()
                    delay(1000)
                }
            }

            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("BT Bridge", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Мост уведомлений и данных между телефоном и часами. " +
                                "Работает без сервисов Google.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    StatusCard(status, connected, running)

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (BridgeService.running) BridgeService.stop(this@MainActivity)
                            else BridgeService.start(this@MainActivity)
                        }
                    ) {
                        Text(if (running) "Остановить мост" else "Запустить мост")
                    }

                    if (!notifAccess) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "Нужен доступ к уведомлениям",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Без него телефон не сможет передавать сообщения на часы.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = { openNotifSettings() }) {
                                    Text("Открыть настройки")
                                }
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Статистика", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Отправлено уведомлений: $sent")
                            Text(
                                "Пульс: " + (hr?.let { "${it.value.toInt()} ${it.unit}" } ?: "нет данных")
                            )
                            Text("Шагов сегодня: $steps")
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Передавать текст",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "Только имя приложения, без содержимого",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = sendContent,
                                onCheckedChange = {
                                    sendContent = it
                                    prefs.sendContent = it
                                }
                            )
                        }
                    }

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openAppSettings() }
                    ) {
                        Text("Настройки приложения (батарея, разрешения)")
                    }

                    Text(
                        "Часы должны быть запущены и показывать «ожидание». " +
                                "Телефон сам найдёт их по BLE.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusCard(status: String, connected: Boolean, running: Boolean) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Состояние", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (!running) "мост остановлен" else status,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = if (connected) "канал активен" else "канал не установлен",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    private fun hasNotifAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    private fun openNotifSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun requestPerms() {
        val list = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }
}
