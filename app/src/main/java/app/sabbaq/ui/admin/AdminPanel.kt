package app.sabbaq.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sabbaq.LocaleManager
import app.sabbaq.R
import androidx.compose.ui.res.stringResource
import app.sabbaq.admin.AdminRepository
import app.sabbaq.network.SupabaseClient
import app.sabbaq.network.SupabaseClient.FeatureFailure
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqCard
import app.sabbaq.ui.SabbaqMessage
import kotlinx.coroutines.delay

enum class AdminSection(val labelRes: Int) {
    DRIVERS(R.string.adm_sec_drivers),
    SUPPORT(R.string.adm_sec_support),
    NOTIFICATIONS(R.string.adm_sec_notifications),
    CODES(R.string.adm_sec_codes),
    POLICY(R.string.adm_sec_policy),
    TELEMETRY(R.string.adm_sec_telemetry),
    PROFILES(R.string.adm_sec_profiles),
}

/** What one section asks another to open: a driver's diagnosis, a notification to one driver, a chat. */
sealed interface AdminRequest {
    data class DiagnoseDriver(val driverId: String, val name: String) : AdminRequest
    data class DiagnoseSupportId(val supportId: String) : AdminRequest
    data class NotifyDriver(val driverId: String, val name: String) : AdminRequest
    data class MessageDriver(val driverId: String) : AdminRequest
    data class OpenDriver(val driverId: String) : AdminRequest
}

private enum class Access { CHECKING, GRANTED, DENIED, FAILED }

private const val SUPPORT_POLL_MILLIS = 20_000L

/**
 * The native administration. The account's admin role is confirmed once on entry; after that
 * every call carries the app's own session and the server's require_admin() checks it, so there
 * is no second sign-in and no web page in between. Each section loads only when opened.
 */
@Composable
fun AdminPanel() {
    val session = remember { AdminSession() }
    var attempt by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf(Access.CHECKING) }
    var accessFailure by remember { mutableStateOf<FeatureFailure?>(null) }

    LaunchedEffect(attempt) {
        access = Access.CHECKING
        val result = SupabaseClient.adminAccess()
        accessFailure = result.exceptionOrNull()?.let(SupabaseClient::featureFailureOf)
        access = when (result.getOrNull()) {
            true -> Access.GRANTED.also { session.unlock() }
            false -> Access.DENIED
            null -> if (accessFailure == FeatureFailure.SESSION) Access.DENIED else Access.FAILED
        }
    }
    // Inactivity locks the panel; the lock is checked again whenever the screen comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, access) {
        if (access != Access.GRANTED) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (session.idleMillis() >= AdminSession.IDLE_LIMIT_MILLIS) session.lock(if (LocaleManager.isArabic()) "انتهت الجلسة بسبب عدم النشاط." else "The session ended due to inactivity.")
                delay(30_000)
            }
        }
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); session.touch() } }
    }) {
        when {
            access == Access.CHECKING -> AdminCentered {
                CircularProgressIndicator()
                Text(stringResource(R.string.adm_checking), style = MaterialTheme.typography.bodyMedium)
            }
            access == Access.DENIED -> AdminGate(
                if (accessFailure == FeatureFailure.SESSION) stringResource(R.string.adm_denied_session)
                else stringResource(R.string.adm_denied),
                retry = { attempt++ },
            )
            access == Access.FAILED -> AdminGate(
                if (accessFailure == FeatureFailure.OFFLINE) stringResource(R.string.adm_failed_offline)
                else stringResource(R.string.adm_failed),
                retry = { attempt++ },
            )
            session.locked != null -> AdminGate(session.locked.orEmpty(), retry = { attempt++ }, retryText = stringResource(R.string.adm_continue))
            else -> AdminWorkspace(session)
        }
    }
}

@Composable
private fun AdminCentered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

@Composable
private fun AdminGate(message: String, retry: () -> Unit, retryText: String = "") {
    AdminCentered {
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SabbaqCard(title = stringResource(R.string.adm_panel_title), icon = Icons.Outlined.Lock) {
                SabbaqMessage(message, isError = true)
                SabbaqButton(retryText.ifEmpty { stringResource(R.string.ss_retry) }, onClick = retry, modifier = Modifier.testTag("admin-retry"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminWorkspace(session: AdminSession) {
    var section by rememberSaveable { mutableStateOf(AdminSection.DRIVERS) }
    var request by remember { mutableStateOf<AdminRequest?>(null) }
    var supportUnread by remember { mutableIntStateOf(0) }
    val open: (AdminRequest) -> Unit = { next ->
        request = next
        section = when (next) {
            is AdminRequest.DiagnoseDriver, is AdminRequest.DiagnoseSupportId -> AdminSection.TELEMETRY
            is AdminRequest.NotifyDriver -> AdminSection.NOTIFICATIONS
            is AdminRequest.MessageDriver -> AdminSection.SUPPORT
            is AdminRequest.OpenDriver -> AdminSection.DRIVERS
        }
    }
    BackHandler(enabled = section != AdminSection.DRIVERS) { section = AdminSection.DRIVERS }

    // The support badge stays current in every section; the support section reports it itself.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, section) {
        if (section == AdminSection.SUPPORT) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                AdminRepository.threads("open").onSuccess { list -> supportUnread = list.sumOf { it.unread } }
                    .onFailure { session.failureOf(it, "") }
                delay(SUPPORT_POLL_MILLIS)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adm_panel_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.adm_panel_sub),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PrimaryScrollableTabRow(selectedTabIndex = section.ordinal, edgePadding = 8.dp) {
                    val supportUnreadCd = stringResource(R.string.adm_support_unread_cd, supportUnread)
                    AdminSection.entries.forEach { entry ->
                        Tab(
                            selected = entry == section,
                            onClick = { section = entry },
                            modifier = Modifier.testTag("admin-tab-${entry.name.lowercase()}").semantics {
                                if (entry == AdminSection.SUPPORT && supportUnread > 0) contentDescription = supportUnreadCd
                            },
                            text = {
                                if (entry == AdminSection.SUPPORT && supportUnread > 0) {
                                    BadgedBox(badge = { Badge { Text(if (supportUnread > 99) "99+" else supportUnread.toString()) } }) { Text(stringResource(entry.labelRes)) }
                                } else Text(stringResource(entry.labelRes))
                            },
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val consume: () -> AdminRequest? = { request.also { request = null } }
            when (section) {
                AdminSection.DRIVERS -> AdminDriversSection(session, consume, open)
                AdminSection.SUPPORT -> AdminSupportSection(session, consume, open, onUnread = { supportUnread = it })
                AdminSection.NOTIFICATIONS -> AdminNotificationsSection(session, consume)
                AdminSection.CODES -> AdminCodesSection(session)
                AdminSection.POLICY -> AdminPolicySection(session)
                AdminSection.TELEMETRY -> AdminTelemetrySection(session, consume)
                AdminSection.PROFILES -> AdminProfilesSection(session)
            }
        }
    }
}
