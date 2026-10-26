package ai.syntx.btbridge.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Главный экран часов: статус моста, переключатели, история.
 *
 * Разрешения запрашиваются одним блоком при старте. Если пользователь отказал —
 * экран остаётся работоспособным, но мост не запустится; статус это покажет.
 */
class MainActivity : ComponentActivity() {

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPerms()

        setContent {
            val prefs = remember { WatchPrefs(this) }
            val store = remember { HistoryStore(this) }

            var enabled by remember { mutableStateOf(WatchService.running) }
            var status by remember { mutableStateOf(WatchService.status) }
            var items by remember { mutableStateOf<List<HistoryStore.Item>>(emptyList()) }
            var vibrate by remember { mutableStateOf(prefs.vibrate) }
            var wake by remember { mutableStateOf(prefs.wakeScreen) }
            var health by remember { mutableStateOf(prefs.shareHealth) }

            LaunchedEffect(Unit) {
                while (true) {
                    status = WatchService.status
                    enabled = WatchService.running
                    items = withContext(Dispatchers.IO) { store.read().take(20) }
                    delay(1000)
                }
            }

            MaterialTheme {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    item {
                        Text("BT Bridge", style = MaterialTheme.typography.title1)
                        Text(status, style = MaterialTheme.typography.caption1)
                    }

                    item {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (WatchService.running) WatchService.stop(this@MainActivity)
                                else WatchService.start(this@MainActivity)
                            }
                        ) {
                            Text(if (enabled) "Остановить" else "Запустить")
                        }
                    }

                    item {
                        ToggleChip(
                            modifier = Modifier.fillMaxWidth(),
                            checked = vibrate,
                            onCheckedChange = {
                                vibrate = it
                                prefs.vibrate = it
                            },
                            label = { Text("Вибрация") },
                            toggleControl = {
                                ToggleChipDefaults.SwitchIcon(vibrate)
                            }
                        )
                    }

                    item {
                        ToggleChip(
                            modifier = Modifier.fillMaxWidth(),
                            checked = wake,
                            onCheckedChange = {
                                wake = it
                                prefs.wakeScreen = it
                            },
                            label = { Text("Будить экран") },
                            toggleControl = {
                                ToggleChipDefaults.SwitchIcon(wake)
                            }
                        )
                    }

                    item {
                        ToggleChip(
                            modifier = Modifier.fillMaxWidth(),
                            checked = health,
                            onCheckedChange = {
                                health = it
                                prefs.shareHealth = it
                                if (WatchService.running) {
                                    WatchService.stop(this@MainActivity)
                                    WatchService.start(this@MainActivity)
                                }
                            },
                            label = { Text("Пульс и шаги") },
                            toggleControl = {
                                ToggleChipDefaults.SwitchIcon(health)
                            }
                        )
                    }

                    if (items.isNotEmpty()) {
                        item {
                            Text(
                                "Последнее",
                                style = MaterialTheme.typography.title3,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        item {
                            val last = items.first()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = last.app,
                                    style = MaterialTheme.typography.caption2,
                                    maxLines = 1
                                )
                                if (last.title.isNotBlank()) {
                                    Text(
                                        text = last.title,
                                        style = MaterialTheme.typography.title3,
                                        maxLines = 2
                                    )
                                }
                                if (last.text.isNotBlank()) {
                                    Text(
                                        text = last.text,
                                        style = MaterialTheme.typography.body2,
                                        maxLines = 3
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestPerms() {
        val list = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) perms.launch(missing.toTypedArray())
    }
}
