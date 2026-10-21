package app.qannas.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Public legal pages. Google Play requires the privacy policy and the account-deletion
 * request page to be reachable from inside the app as well as from the store listing.
 */
internal object PublicLinks {
    const val PRIVACY_POLICY = "https://yahooyahoo99.github.io/privacy.html"
    const val ACCOUNT_DELETION = "https://yahooyahoo99.github.io/delete-account.html"

    /** Opens [url] in the browser; false when no app can handle it, so the caller can say so. */
    fun open(context: Context, url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/** Compact privacy / deletion links; reused on sign-in, the account page and the disclosure. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QannasLegalLinks(modifier: Modifier = Modifier, showDeletion: Boolean = true) {
    val context = LocalContext.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { PublicLinks.open(context, PublicLinks.PRIVACY_POLICY) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("سياسة الخصوصية", style = MaterialTheme.typography.labelLarge)
        }
        if (showDeletion) {
            TextButton(onClick = { PublicLinks.open(context, PublicLinks.ACCOUNT_DELETION) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("طلب حذف الحساب", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
