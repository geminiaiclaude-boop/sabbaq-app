package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.sabbaq.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Separate, explicit consent. The host opens Android settings only after acceptance. */
@Composable
fun AccessibilityDisclosureDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adx_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.adx_read_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.adx_read_body), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.adx_accept_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.adx_accept_body), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.adx_privacy_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.adx_privacy_body), style = MaterialTheme.typography.bodyMedium)
                SabbaqLegalLinks(showDeletion = false)
                Text(stringResource(R.string.adx_consent_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.adx_consent_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        icon = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
        confirmButton = { SabbaqButton(text = stringResource(R.string.adx_accept_btn), onClick = onAccept, fillWidth = false) },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.adx_not_now)) } }
    )
}
