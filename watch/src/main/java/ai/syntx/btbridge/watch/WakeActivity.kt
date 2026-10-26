package ai.syntx.btbridge.watch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import ai.syntx.btbridge.watch.ui.HistoryList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Экран последнего сообщения. Запускается из сервиса при входящем уведомлении.
 *
 * ВАЖНО: activity объявлена с showWhenLocked и turnScreenOn, иначе на Wear OS
 * экран не проснётся от уведомления (проверено на устройстве в v1.2).
 */
class WakeActivity : ComponentActivity() {

    companion object {
        fun launch(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(ctx, WakeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // На API 34 фоновый старт activity ограничен — это ожидаемо,
                // карточка-уведомление остаётся, экран не будится.
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val store = HistoryStore(this)

        setContent {
            var items by remember { mutableStateOf<List<HistoryStore.Item>>(emptyList()) }

            LaunchedEffect(Unit) {
                while (true) {
                    items = withContext(Dispatchers.IO) { store.read() }
                    delay(1000)
                }
            }

            MaterialTheme {
                if (items.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Нет сообщений")
                    }
                } else {
                    HistoryList(items.take(5))
                }
            }
        }
    }
}
