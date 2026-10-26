package ai.syntx.btbridge.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import ai.syntx.btbridge.watch.HistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Список сообщений. Общий для MainActivity и WakeActivity,
 * чтобы формат отображения не расходился между экранами.
 *
 * Параметр назван rows, а не items: иначе конфликт с функцией items {}
 * из ScalingLazyColumn и компилятор путается в области видимости.
 */
@Composable
fun HistoryList(rows: List<HistoryStore.Item>) {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(rows) { item ->
            Card(
                onClick = { },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "${item.app} · ${fmt.format(Date(item.at))}",
                        style = MaterialTheme.typography.caption2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.title.isNotBlank()) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.title3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.text.isNotBlank()) {
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.body2,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
