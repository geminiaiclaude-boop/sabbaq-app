package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sabbaq.LocaleManager
import app.sabbaq.R

/**
 * Arabic / English toggle: on the welcome tour, in the side menu and in Settings, Appearance. Picking a language applies it immediately
 * (the activity is recreated and [LocaleManager] persists the choice), so the whole app switches at once.
 * The current language reads from the resources themselves, so it is correct whether the user
 * chose it or it was inherited from the device.
 *
 * Compact (the welcome tour's top bar) shows «ع» / «EN» so the bar keeps its single row and
 * height; [fill] (side menu, Settings) has the room for the full names. Screen readers always
 * hear the full name.
 */
@Composable
fun LanguageSwitch(modifier: Modifier = Modifier, fill: Boolean = false) {
    val context = LocalContext.current
    val arabic = LocaleManager.isArabic()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val chip = if (fill) Modifier.weight(1f) else Modifier
        val arabicName = stringResource(R.string.language_arabic)
        val englishName = stringResource(R.string.language_english)
        LanguageChip(if (fill) arabicName else stringResource(R.string.language_arabic_short), arabicName, selected = arabic, chip, fill) {
            if (!arabic) { LocaleManager.set(context, LocaleManager.Language.ARABIC); (context as? android.app.Activity)?.recreate() }
        }
        LanguageChip(if (fill) englishName else stringResource(R.string.language_english_short), englishName, selected = !arabic, chip, fill) {
            if (arabic) { LocaleManager.set(context, LocaleManager.Language.ENGLISH); (context as? android.app.Activity)?.recreate() }
        }
    }
}

/** [fill]: the chip is stretched by its row, so its label is centred across it; otherwise it keeps its own width. */
@Composable
private fun LanguageChip(label: String, name: String, selected: Boolean, modifier: Modifier, fill: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, modifier = if (fill) Modifier.fillMaxWidth() else Modifier, textAlign = TextAlign.Center, maxLines = 1) },
        shape = RoundedCornerShape(50),
        modifier = modifier.heightIn(min = 40.dp).semantics { contentDescription = name },
        colors = FilterChipDefaults.filterChipColors(),
    )
}
