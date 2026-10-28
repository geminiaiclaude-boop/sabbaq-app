package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sabbaq.LocaleManager
import app.sabbaq.R
import androidx.compose.ui.res.stringResource
import app.sabbaq.core.OfferStatsTotals
import app.sabbaq.core.ReasonCode
import app.sabbaq.stats.OfferStatsStore
import app.sabbaq.ui.theme.LocalSabbaqBrand
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun OfferStatsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(context) { OfferStatsStore.init(context) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) OfferStatsStore.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val snapshot by OfferStatsStore.snapshots.collectAsState()
    val numberFormat = NumberFormat.getIntegerInstance(SabbaqLocale)
    val displayDate = runCatching {
        LocalDate.parse(snapshot.date).format(DateTimeFormatter.ofPattern("d MMMM yyyy", sabbaqDateLocale()))
    }.getOrDefault(snapshot.date)

    SabbaqCard(
        title = stringResource(R.string.osc_title),
        subtitle = stringResource(R.string.osc_sub),
        icon = Icons.Outlined.BarChart,
        modifier = modifier,
    ) {
        Text(stringResource(R.string.osc_today_date, displayDate), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        PeriodStatsPanel(stringResource(R.string.osc_today), snapshot.today, numberFormat)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PeriodStatsPanel(stringResource(R.string.osc_week), snapshot.week, numberFormat)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PeriodStatsPanel(stringResource(R.string.osc_month), snapshot.month, numberFormat)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.osc_top_reject), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val topReasons = snapshot.today.topRejectionReasons(3)
        if (topReasons.isEmpty()) {
            Text(stringResource(R.string.osc_no_rejected), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            topReasons.forEach { (reason, count) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(reason.displayName(), style = MaterialTheme.typography.bodyMedium)
                    Text(numberFormat.format(count), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            stringResource(R.string.osc_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun PeriodStatsPanel(label: String, totals: OfferStatsTotals, numberFormat: NumberFormat) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatValue(stringResource(R.string.osc_visible), totals.visibleOffers, numberFormat, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatValue(stringResource(R.string.osc_rejected), totals.rejectedOffers, numberFormat, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                StatValue(stringResource(R.string.osc_accepted), totals.acceptedOffers, numberFormat, LocalSabbaqBrand.current.success, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatValue(label: String, count: Long, numberFormat: NumberFormat, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(numberFormat.format(count), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = tint)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ReasonCode.displayName(): String = (when (this) {
    ReasonCode.NO_FILTERS -> "لم تُحدد شروط قبول" to "No acceptance conditions set"
    ReasonCode.OUTSIDE_SCHEDULE -> "خارج وقت العمل" to "Outside working hours"
    ReasonCode.INVALID_FILTER -> "شرط غير صالح" to "Invalid condition"
    ReasonCode.FARE_MISSING -> "الأجرة غير واضحة" to "Fare not clear"
    ReasonCode.FARE_TOO_LOW -> "الأجرة أقل من الحد" to "Fare below the limit"
    ReasonCode.FARE_TOO_HIGH -> "الأجرة أعلى من الحد" to "Fare above the limit"
    ReasonCode.PER_KM_MISSING -> "بيانات العائد لكل كم ناقصة" to "Return-per-km data missing"
    ReasonCode.PER_KM_TOO_LOW -> "العائد لكل كم أقل من المطلوب" to "Return per km below target"
    ReasonCode.PICKUP_KM_MISSING -> "مسافة الوصول غير واضحة" to "Pickup distance not clear"
    ReasonCode.PICKUP_TOO_FAR -> "مسافة الوصول أبعد من الحد" to "Pickup distance beyond the limit"
    ReasonCode.PICKUP_MIN_MISSING -> "وقت الوصول غير واضح" to "Pickup time not clear"
    ReasonCode.PICKUP_TOO_LONG -> "وقت الوصول أطول من الحد" to "Pickup time above the limit"
    ReasonCode.PICKUP_TOO_SHORT -> "وقت الوصول أقصر من الحد" to "Pickup time below the limit"
    ReasonCode.DESTINATION_MISSING -> "الوجهة غير ظاهرة" to "Destination not shown"
    ReasonCode.TRIP_KM_MISSING -> "مسافة الرحلة غير واضحة" to "Trip distance not clear"
    ReasonCode.TRIP_TOO_SHORT -> "مسافة الرحلة أقصر من الحد" to "Trip distance below the limit"
    ReasonCode.TRIP_TOO_LONG -> "مسافة الرحلة أطول من الحد" to "Trip distance above the limit"
    ReasonCode.TRIP_MIN_MISSING -> "وقت الرحلة غير واضح" to "Trip time not clear"
    ReasonCode.TRIP_MIN_TOO_SHORT -> "وقت الرحلة أقصر من الحد" to "Trip time below the limit"
    ReasonCode.TRIP_MIN_TOO_LONG -> "وقت الرحلة أطول من الحد" to "Trip time above the limit"
    ReasonCode.EXCLUDED_KEYWORD -> "كلمة مستبعدة" to "Excluded word"
}).let { if (LocaleManager.isArabic()) it.first else it.second }
