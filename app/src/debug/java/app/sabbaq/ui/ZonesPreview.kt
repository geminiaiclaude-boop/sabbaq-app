package app.sabbaq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sabbaq.ui.map.GeoPoint

/** Debug only: the zones page with sample zones in memory (nothing is saved), or the zone editor searching «العليا». */
@Composable
internal fun ZonesPreview(editor: Boolean) {
    val olaya = EditableZone("العليا", true, listOf(GeoPoint(24.700, 46.670), GeoPoint(24.700, 46.700), GeoPoint(24.680, 46.700), GeoPoint(24.680, 46.670)))
    val airport = EditableZone("المطار", true, listOf(GeoPoint(24.970, 46.680), GeoPoint(24.970, 46.730), GeoPoint(24.940, 46.730), GeoPoint(24.940, 46.680)))
    var draft by remember {
        mutableStateOf(FilterDraft(zonesEnabled = true, zonesJson = ZoneJson.write(listOf(olaya)),
            excludeZonesEnabled = true, excludeZonesJson = ZoneJson.write(listOf(airport))))
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ZonesPage(draft, emptyMap(), { draft = it }, onBack = {})
    }
    if (editor) ZoneEditorDialog(initial = null, initialExclude = false, onDismiss = {}, onSave = { _, _ -> }, initialQuery = "العليا")
}
