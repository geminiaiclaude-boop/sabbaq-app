package app.sabbaq.ui.map

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import app.sabbaq.LocaleManager
import app.sabbaq.R
import app.sabbaq.service.maps.MapPackStore

/** One line under the map: the city map downloading (with progress) or available offline. */
@Composable
fun MapPackLine(status: MapPackStore.Status) {
    val ar = LocaleManager.isArabic()
    when (status) {
        is MapPackStore.Status.Downloading -> Column(Modifier.fillMaxWidth().testTag("map-pack-downloading")) {
            val name = if (ar) status.pack.name_ar else status.pack.name_en
            Text(stringResource(R.string.map_pack_downloading, name, (status.done / 1_000_000L).toInt(), ((status.pack.bytes + 999_999) / 1_000_000L).toInt()),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (status.done.toFloat() / status.pack.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        is MapPackStore.Status.Ready -> Text(stringResource(R.string.map_pack_ready, if (ar) status.pack.name_ar else status.pack.name_en),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("map-pack-ready"))
        else -> Unit
    }
}
