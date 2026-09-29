package dev.aide.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.ui.strings.Strings

/** Доля ширины у последней строки скелетона: строки разной длины читаются как текст, а не как таблица. */
private const val LAST_SKELETON_ROW_FRACTION = 0.6f

/**
 * Пять состояний экрана из § 6.1. Каждое состояние обязано объяснять, что
 * произошло и что делать, — иначе это не состояние, а пустой экран.
 */

/** Загрузка: скелетон структуры, а не пустой экран. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, rows: Int = 4) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Strings.text(Strings.stateLoadingTitle), style = MaterialTheme.typography.titleMedium)
        repeat(rows) { index ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (index == rows - 1) LAST_SKELETON_ROW_FRACTION else 1f)
                    .height(20.dp)
                    .testTag("skeleton-row-$index"),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
        Text(
            Strings.text(Strings.stateLoadingSkeleton),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Пусто: объясняет, почему здесь ничего нет. */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Strings.text(Strings.stateEmptyTitle), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Ошибка: что случилось и кнопка повтора. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Strings.text(Strings.stateErrorTitle), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, modifier = Modifier.testTag("state-retry")) {
            Text(Strings.text(Strings.stateErrorRetry))
        }
    }
}

/** Нет связи: кэшированные данные с явной пометкой (§ 3.5). */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("offline-banner"),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Strings.text(Strings.stateOfflineTitle), style = MaterialTheme.typography.titleSmall)
            Text(Strings.text(Strings.stateOfflineBody), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Нет прав: что именно и почему закрыто (§ 10.1). */
@Composable
fun NoPermissionState(path: String, reason: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Strings.text(Strings.stateNoPermissionTitle), style = MaterialTheme.typography.titleMedium)
        Text(Strings.text(Strings.stateNoPermissionBody, path, reason), style = MaterialTheme.typography.bodyMedium)
    }
}
