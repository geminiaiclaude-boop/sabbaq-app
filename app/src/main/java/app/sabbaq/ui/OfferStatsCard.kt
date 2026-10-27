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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sabbaq.core.OfferStatsTotals
import app.sabbaq.core.ReasonCode
import app.sabbaq.stats.OfferStatsStore
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val numberFormat = NumberFormat.getIntegerInstance(Locale("ar", "SA"))
    val displayDate = runCatching {
        LocalDate.parse(snapshot.date).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar")))
    }.getOrDefault(snapshot.date)

    SabbaqCard(
        title = "إحصاءات سبّاق",
        subtitle = "ملخص نشاط المتابعة على هذا الجهاز: اليوم والأسبوع والشهر.",
        icon = Icons.Outlined.BarChart,
        modifier = modifier,
    ) {
        Text("تاريخ اليوم: $displayDate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        PeriodStatsPanel("اليوم", snapshot.today, numberFormat)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PeriodStatsPanel("هذا الأسبوع · من الاثنين", snapshot.week, numberFormat)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PeriodStatsPanel("هذا الشهر", snapshot.month, numberFormat)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("أكثر أسباب الرفض اليوم", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val topReasons = snapshot.today.topRejectionReasons(3)
        if (topReasons.isEmpty()) {
            Text("لا توجد طلبات مرفوضة مسجلة اليوم.", style = MaterialTheme.typography.bodySmall,
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
            "تُحفظ الأعداد وأسباب الرفض على هذا الجهاز فقط، دون عناوين أو نصوص الطلبات. قد يُحتسب الطلب نفسه مرة أخرى بعد إعادة تشغيل التطبيق.",
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
                StatValue("مرئي", totals.visibleOffers, numberFormat, Modifier.weight(1f))
                StatValue("مرفوض", totals.rejectedOffers, numberFormat, Modifier.weight(1f))
                StatValue("مقبول", totals.acceptedOffers, numberFormat, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatValue(label: String, count: Long, numberFormat: NumberFormat, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(numberFormat.format(count), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ReasonCode.displayName(): String = when (this) {
    ReasonCode.NO_FILTERS -> "لم تُحدد شروط قبول"
    ReasonCode.OUTSIDE_SCHEDULE -> "خارج وقت العمل"
    ReasonCode.INVALID_FILTER -> "شرط غير صالح"
    ReasonCode.FARE_MISSING -> "الأجرة غير واضحة"
    ReasonCode.FARE_TOO_LOW -> "الأجرة أقل من الحد"
    ReasonCode.FARE_TOO_HIGH -> "الأجرة أعلى من الحد"
    ReasonCode.PER_KM_MISSING -> "بيانات العائد لكل كم ناقصة"
    ReasonCode.PER_KM_TOO_LOW -> "العائد لكل كم أقل من المطلوب"
    ReasonCode.PICKUP_KM_MISSING -> "مسافة الوصول غير واضحة"
    ReasonCode.PICKUP_TOO_FAR -> "مسافة الوصول أبعد من الحد"
    ReasonCode.PICKUP_MIN_MISSING -> "وقت الوصول غير واضح"
    ReasonCode.PICKUP_TOO_LONG -> "وقت الوصول أطول من الحد"
    ReasonCode.PICKUP_TOO_SHORT -> "وقت الوصول أقصر من الحد"
    ReasonCode.DESTINATION_MISSING -> "الوجهة غير ظاهرة"
    ReasonCode.TRIP_KM_MISSING -> "مسافة الرحلة غير واضحة"
    ReasonCode.TRIP_TOO_SHORT -> "مسافة الرحلة أقصر من الحد"
    ReasonCode.TRIP_TOO_LONG -> "مسافة الرحلة أطول من الحد"
    ReasonCode.TRIP_MIN_MISSING -> "وقت الرحلة غير واضح"
    ReasonCode.TRIP_MIN_TOO_SHORT -> "وقت الرحلة أقصر من الحد"
    ReasonCode.TRIP_MIN_TOO_LONG -> "وقت الرحلة أطول من الحد"
    ReasonCode.EXCLUDED_KEYWORD -> "كلمة مستبعدة"
}
