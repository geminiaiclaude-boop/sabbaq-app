package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.sabbaq.admin.AdminProfile
import app.sabbaq.admin.AdminRepository
import app.sabbaq.admin.str
import app.sabbaq.ui.SabbaqAnimatedMessage
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqButtonStyle
import app.sabbaq.ui.SabbaqCard
import app.sabbaq.ui.SabbaqTone
import kotlinx.coroutines.launch

private const val NEW_PROFILE = ""

/**
 * «ملفات التطبيقات»: how Jeeny, Bolt and Petra screens are read. A published profile reaches every
 * driver within 6 hours without a new release, and only when its version is higher than the one
 * already published, so the editor refuses a lower or equal version before it reaches the server.
 */
@Composable
internal fun AdminProfilesSection(session: AdminSession) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<AdminProfile>?>(null) }
    var selected by remember { mutableStateOf(NEW_PROFILE) }
    var body by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<AdminNotice?>(null) }
    var loading by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var selectAfterLoad by remember { mutableStateOf<String?>(null) }

    fun select(id: String, list: List<AdminProfile>?) {
        selected = id
        val profile = list?.firstOrNull { it.id == id }
        body = profile?.body.orEmpty()
        enabled = profile?.enabled ?: true
    }

    LaunchedEffect(reload) {
        loading = true
        notice = info("يجري تحميل ملفات التطبيقات…")
        AdminRepository.profiles().fold(
            onSuccess = { list ->
                profiles = list
                val keep = selectAfterLoad ?: selected.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id ?: NEW_PROFILE
                select(keep, list)
                notice = if (selectAfterLoad != null) notice else info(
                    if (list.isNotEmpty()) "الملفات المنشورة تتقدّم على الملفات المضمّنة في التطبيق فقط إذا كان رقم إصدارها أكبر."
                    else "لا توجد ملفات منشورة بعد؛ التطبيق يستعمل الملفات المضمّنة (الإصدار 3). الصق ملفًا برقم إصدار 4 أو أكبر لنشر إصلاح.")
                selectAfterLoad = null
            },
            onFailure = { notice = session.failureOf(it, "تعذر تحميل ملفات التطبيقات. تحقق من الاتصال.") },
        )
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("admin-profiles"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SabbaqCard(title = "ملفات التطبيقات", icon = Icons.Outlined.Description,
            subtitle = "تعديل طريقة قراءة جيني/بولت/بترا يصل لكل السائقين خلال 6 ساعات دون إصدار جديد. ارفع رقم الإصدار عند كل تعديل.") {
            val options = profiles.orEmpty().map { it.id to "${it.id} · الإصدار ${it.version}${if (it.enabled) "" else " · معطّل"}" } + (NEW_PROFILE to "ملف جديد…")
            AdminChoice("الملف", options, selected, onSelect = { select(it, profiles) }, enabled = !publishing)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("مفعّل", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !publishing)
            }
            AdminField("محتوى الملف (JSON)", body, { body = it }, maxLength = 200_000, ltr = true, singleLine = false, minLines = 14,
                textStyle = monoStyle, enabled = !publishing, modifier = Modifier.testTag("admin-profile-body"))
            SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info)
            SabbaqButton("نشر الملف", busy = publishing, busyText = "جارٍ النشر…", enabled = !loading, modifier = Modifier.testTag("admin-profile-publish"), onClick = {
                val draft = AdminRepository.validateProfile(body, profiles.orEmpty())
                draft.exceptionOrNull()?.let { notice = failure(it.message ?: "المحتوى غير صالح."); return@SabbaqButton }
                val profileBody = draft.getOrThrow()
                scope.launch {
                    publishing = true
                    AdminRepository.publishProfile(profileBody, enabled).fold(
                        onSuccess = {
                            val id = profileBody.str("id").orEmpty()
                            val version = profileBody.str("version").orEmpty()
                            notice = success("نُشر $id الإصدار $version. يصل للسائقين خلال 6 ساعات أو عند تشغيل المتابعة.")
                            selectAfterLoad = id
                            reload++
                        },
                        onFailure = {
                            notice = if (app.sabbaq.network.SupabaseClient.featureFailureOf(it) == app.sabbaq.network.SupabaseClient.FeatureFailure.REJECTED)
                                failure("رفض الخادم الملف. تحقق من الحقول ورقم الإصدار ثم أعد المحاولة.")
                            else session.failureOf(it, "تعذر نشر الملف. تحقق من الاتصال ثم أعد المحاولة.")
                        },
                    )
                    publishing = false
                }
            })
            TextButton(onClick = { reload++ }, enabled = !loading && !publishing) { Text("تحديث") }
        }
    }
}
