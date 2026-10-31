package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * The four quick conditions on «حالة العمل»: the same condition rows as «طلبات تناسبك»,
 * unframed and without subtitles, inside the dashboard card.
 */
@Composable
internal fun QuickConditionsPanel(
    draft: FilterDraft,
    errors: Map<String, String>,
    onDraftChange: (FilterDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TargetAppCondition(draft, onDraftChange, keyPrefix = QUICK_PREFIX, framed = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FareRangeCondition(draft, errors, onDraftChange, keyPrefix = QUICK_PREFIX, framed = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PickupTimeCondition(draft, errors, onDraftChange, keyPrefix = QUICK_PREFIX, framed = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HighFareOverrideCondition(draft, errors, onDraftChange, keyPrefix = QUICK_PREFIX, framed = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NoDestinationCondition(draft, onDraftChange, keyPrefix = QUICK_PREFIX, framed = false)
    }
}

private const val QUICK_PREFIX = "quick-"
