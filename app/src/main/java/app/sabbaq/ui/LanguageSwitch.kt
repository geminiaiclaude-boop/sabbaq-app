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
 */
@Composable
fun LanguageSwitch(modifier: Modifier = Modifier, fill: Boolean = false) {
    val context = LocalContext.current
    val arabic = LocaleManager.isArabic()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val chip = if (fill) Modifier.weight(1f) else Modifier
        LanguageChip(stringResource(R.string.language_arabic), selected = arabic, chip, fill) {
            if (!arabic) { LocaleManager.set(context, LocaleManager.Language.ARABIC); (context as? android.app.Activity)?.recreate() }
        }
        LanguageChip(stringResource(R.string.language_english), selected = !arabic, chip, fill) {
            if (arabic) { LocaleManager.set(context, LocaleManager.Language.ENGLISH); (context as? android.app.Activity)?.recreate() }
        }
    }
}

/**
 * [fill]: the chip is stretched by its row (side menu, Settings), so its label is centred across it.
 * Without it the label keeps its own width: a full-width label in an unweighted row took the whole
 * welcome-tour top bar and pushed «تخطي الجولة» off screen.
 */
@Composable
private fun LanguageChip(label: String, selected: Boolean, modifier: Modifier, fill: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, modifier = if (fill) Modifier.fillMaxWidth() else Modifier, textAlign = TextAlign.Center) },
        shape = RoundedCornerShape(50),
        modifier = modifier.heightIn(min = 44.dp),
        colors = FilterChipDefaults.filterChipColors(),
    )
}
