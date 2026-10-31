package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sabbaq.LocaleManager
import app.sabbaq.admin.AdminFleet
import app.sabbaq.core.ProcessExit
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqButtonStyle
import app.sabbaq.ui.SabbaqCard
import kotlinx.coroutines.launch

/** «الإصدارات والأجهزة»: per version what the fleet did, which phones Android kills and why. */
@Composable
internal fun AdminVersionReportCard(session: AdminSession) {
    val ar = LocaleManager.isArabic()
    var report by remember { mutableStateOf<AdminFleet.VersionReport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        busy = true
        AdminFleet.versionReport().fold({ report = it; message = null }, {
            message = session.failureOf(it, if (ar) "تعذر تحميل تقرير الإصدارات." else "Could not load the version report.").text
        })
        busy = false
    }
    SabbaqCard(title = if (ar) "الإصدارات والأجهزة (7 أيام)" else "Versions and devices (7 days)", icon = Icons.Outlined.Insights,
        modifier = Modifier.testTag("admin-version-report")) {
        report?.let { r ->
            r.versions.take(8).forEach { v ->
                val rate = if (v.accepted > 0) "${v.confirmed * 100 / v.accepted}%" else "—"
                Text(v.version, style = MaterialTheme.typography.labelLarge)
                Text(
                    if (ar) "أجهزة ${v.devices} · سائقون ${v.drivers} · قبول مؤكد ${v.confirmed}/${v.accepted} ($rate) · غير مؤكد ${v.unconfirmed} · " +
                        "فشل قراءة ${v.readMisses} · رفض بالشروط ${v.rejected} · انهيار ${v.crashes} · إنهاء من أندرويد ${v.systemKills}" +
                        (v.tapP50Ms?.let { " · وسيط الضغط ${it}ms" } ?: "")
                    else "devices ${v.devices} · drivers ${v.drivers} · confirmed ${v.confirmed}/${v.accepted} ($rate) · unconfirmed ${v.unconfirmed} · " +
                        "read misses ${v.readMisses} · rejected by conditions ${v.rejected} · crashes ${v.crashes} · killed by Android ${v.systemKills}" +
                        (v.tapP50Ms?.let { " · tap p50 ${it}ms" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (r.makers.isNotEmpty()) {
                Text(if (ar) "الشركات المصنعة" else "Manufacturers", style = MaterialTheme.typography.labelLarge)
                r.makers.take(10).forEach { m ->
                    Text(if (ar) "${m.maker}: أجهزة ${m.devices} · معرضة للإيقاف ${m.atRisk} · إنهاء من أندرويد ${m.systemKills}"
                         else "${m.maker}: devices ${m.devices} · at risk ${m.atRisk} · killed by Android ${m.systemKills}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            val kills = r.exits.filter { ProcessExit.isSystemKill(it.reason) }
            if (kills.isNotEmpty()) {
                Text(if (ar) "أسباب الإنهاء من أندرويد" else "Why Android ended Sabbaq", style = MaterialTheme.typography.labelLarge)
                kills.take(10).forEach { Text("${it.version} · ${it.reason}: ${it.count}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        SabbaqButton(if (ar) "إعادة التحميل" else "Reload", onClick = { reload++ }, style = SabbaqButtonStyle.Secondary, enabled = !busy, busy = busy)
    }
}

/**
 * «تعلّم المحرك»: how confirmed accepts were found, where reading fails per ride-app version,
 * and the words seen on unread offer screens; a word added here reaches every app's reader.
 */
@Composable
internal fun AdminLearningCard(session: AdminSession) {
    val ar = LocaleManager.isArabic()
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<AdminFleet.LearningReport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var accept by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("") }
    var noise by remember { mutableStateOf("") }
    var proposals by remember { mutableStateOf<List<AdminFleet.Proposal>>(emptyList()) }
    var accepts by remember { mutableStateOf<app.sabbaq.admin.AdminAcceptLearning?>(null) }

    fun show(r: AdminFleet.LearningReport) {
        report = r
        accept = r.lexicon.accept.joinToString(", "); time = r.lexicon.time.joinToString(", ")
        currency = r.lexicon.currency.joinToString(", "); noise = r.lexicon.noise.joinToString(", ")
    }
    fun words(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun append(list: String, word: String) = (words(list) + word).distinct().joinToString(", ")

    LaunchedEffect(reload) {
        busy = true
        AdminFleet.learningReport().fold({ show(it); message = null }, {
            message = session.failureOf(it, if (ar) "تعذر تحميل تقرير التعلم." else "Could not load the learning report.").text
        })
        AdminFleet.proposals().onSuccess { proposals = it }
        app.sabbaq.admin.AdminRepository.acceptLearning().onSuccess { accepts = it }
        busy = false
    }
    SabbaqCard(title = if (ar) "تعلّم المحرك من شاشات الأسطول" else "Engine learning from fleet screens", icon = Icons.Outlined.Psychology,
        modifier = Modifier.testTag("admin-learning")) {
        Text(if (ar) "التعلم التلقائي: كل ساعة تُقترح الكلمات التي رآها 3 سائقين، وتُختبر على الهواتف دون ضغط. كلمات الوقت والأجرة تُعتمد تلقائيًا بعد 5 قراءات ناجحة من 3 سائقين، وكلمات القبول تنتظر موافقتك."
             else "Automatic learning: every hour words seen by 3 drivers are proposed and tested on the phones without tapping. Time and fare words go live after 5 successful reads from 3 drivers; accept words wait for your approval.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        accepts?.let { a ->
            Text(if (ar) "التعلم من الطلبات المقبولة" else "Learning from accepted orders", style = MaterialTheme.typography.titleSmall)
            Text(if (ar) "كل قبول يؤكده أندرويد يُسجَّل معه زر القبول الذي نجح. الزر الذي يؤكده 3 سائقين مفعّلين في 5 قبولات يصبح «مثبتًا»، ويصل لكل الهواتف بعد اعتمادك فقط، فيجده المحرك حتى لو غيّر تطبيق الرحلات نصه. خلال 30 يومًا: ${a.confirmed30d} قبول مؤكد."
                 else "Every accept Android confirms records the button that worked. A button confirmed by 3 active drivers in 5 accepts becomes proven and reaches every phone only after you approve it, so the engine finds it even if the ride app renames it. Last 30 days: ${a.confirmed30d} confirmed accepts.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AdminFact(if (ar) "أزرار معتمدة لكل الهواتف" else "Buttons live on every phone", a.live.joinToString(", ").ifEmpty { if (ar) "لا شيء بعد" else "None yet" }, ltr = a.live.isNotEmpty())
            (a.live.map { id -> a.candidates.firstOrNull { it.viewId == id } ?: app.sabbaq.admin.AdminAcceptCandidate(id, 0, 0, true) } +
                a.candidates.filter { it.viewId !in a.live }).take(8).forEach { c ->
                val live = c.viewId in a.live
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(c.viewId, style = MaterialTheme.typography.bodyMedium)
                        Text((if (ar) "${c.accepts} قبول من ${c.drivers} سائق" else "${c.accepts} accepts from ${c.drivers} drivers") +
                            when { live -> if (ar) " · معتمد" else " · live"; c.proven -> if (ar) " · مثبت" else " · proven"; else -> if (ar) " · يجمع الإثبات" else " · gathering proof" },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (live || c.proven) TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            app.sabbaq.admin.AdminRepository.setAcceptId(c.viewId, publish = !live).fold(
                                { message = if (live) (if (ar) "سُحب الزر من الهواتف." else "Button withdrawn.") else (if (ar) "اعتُمد الزر لكل الهواتف." else "Button approved for every phone."); reload++ },
                                { message = session.failureOf(it, if (ar) "تعذر حفظ القرار." else "Could not save the decision.").text },
                            )
                            busy = false
                        }
                    }) { Text(if (live) (if (ar) "سحب" else "Withdraw") else (if (ar) "اعتماد" else "Approve"), color = if (live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                }
            }
        }
        proposals.forEach { p ->
            val category = when (p.category) { "accept" -> if (ar) "قبول" else "accept"; "time" -> if (ar) "وقت" else "time"; else -> if (ar) "أجرة" else "fare" }
            val status = when (p.status) {
                "shadow" -> if (ar) "قيد الاختبار" else "testing"
                "awaiting_approval" -> if (ar) "بانتظار موافقتك" else "awaiting your approval"
                "adopted" -> if (ar) "اعتُمدت تلقائيًا" else "adopted automatically"
                "approved" -> if (ar) "اعتمدتها" else "approved"
                "rejected" -> if (ar) "مرفوضة" else "rejected"
                "reverted" -> if (ar) "سُحبت" else "reverted"
                else -> if (ar) "انتهت" else "expired"
            }
            Text("«${p.word}» · $category · $status · ${if (ar) "سائقون" else "drivers"} ${p.drivers} · ${if (ar) "قراءات ناجحة" else "successful reads"} ${p.shadowHits}/${p.shadowDrivers}",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fun decide(d: String) { scope.launch { busy = true; AdminFleet.decide(p.id, d).onFailure {
                    message = session.failureOf(it, if (ar) "لم يُنفّذ القرار." else "The decision was not applied.").text }; reload++; busy = false } }
                if (p.status in setOf("shadow", "awaiting_approval", "rejected", "expired", "reverted"))
                    AssistChip(onClick = { decide("approve") }, enabled = !busy, label = { Text(if (ar) "اعتماد" else "Approve") })
                if (p.status in setOf("shadow", "awaiting_approval"))
                    AssistChip(onClick = { decide("reject") }, enabled = !busy, label = { Text(if (ar) "رفض" else "Reject") })
                if (p.status in setOf("adopted", "approved"))
                    AssistChip(onClick = { decide("revert") }, enabled = !busy, label = { Text(if (ar) "سحب" else "Revert") })
            }
        }
        SabbaqButton(if (ar) "تشغيل التعلم الآن" else "Run learning now", enabled = !busy, style = SabbaqButtonStyle.Secondary,
            modifier = Modifier.testTag("admin-learning-run"), onClick = {
                scope.launch { busy = true; AdminFleet.runLearning().onFailure {
                    message = session.failureOf(it, if (ar) "تعذر تشغيل التعلم." else "Could not run learning.").text }; reload++; busy = false }
            })
        report?.let { r ->
            Text(if (ar) "المعجم المشترك: النسخة ${r.lexicon.version} · عينات جديدة ${r.newSamples}"
                 else "Shared lexicon: version ${r.lexicon.version} · new samples ${r.newSamples}", style = MaterialTheme.typography.labelMedium)
            if (r.signatures.isNotEmpty()) {
                Text(if (ar) "كيف وُجد زر القبول في القبولات المؤكدة" else "How the accept control was found in confirmed accepts",
                    style = MaterialTheme.typography.labelLarge)
                r.signatures.take(6).forEach { Text("${it.provider} ${it.targetVersion} · ${it.detail}: ${it.count}", style = MaterialTheme.typography.bodySmall) }
            }
            if (r.misses.isNotEmpty()) {
                Text(if (ar) "أين تفشل القراءة (لكل نسخة من تطبيق الرحلات)" else "Where reading fails (per ride-app version)",
                    style = MaterialTheme.typography.labelLarge)
                r.misses.take(8).forEach { Text("${it.provider} ${it.targetVersion} · ${it.stage}/${it.reason}: ${it.count}", style = MaterialTheme.typography.bodySmall) }
            }
            if (r.candidates.isNotEmpty()) {
                Text(if (ar) "كلمات جديدة على شاشات لم تُقرأ (أضفها للمعجم إن كانت صحيحة)" else "New words on unread screens (add the right ones)",
                    style = MaterialTheme.typography.labelLarge)
                r.candidates.take(15).forEach { c ->
                    Text("«${c.word}»${if (c.onButton) (if (ar) " (زر)" else " (button)") else ""} · ${c.count} / ${c.drivers}",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = { accept = append(accept, c.word) }, label = { Text(if (ar) "قبول" else "Accept") })
                        AssistChip(onClick = { time = append(time, c.word) }, label = { Text(if (ar) "وقت" else "Time") })
                        AssistChip(onClick = { currency = append(currency, c.word) }, label = { Text(if (ar) "عملة" else "Currency") })
                        AssistChip(onClick = { noise = append(noise, c.word) }, label = { Text(if (ar) "تجاهل" else "Noise") })
                    }
                }
            }
        }
        AdminField(if (ar) "كلمات القبول (مفصولة بفواصل)" else "Accept words (comma-separated)", accept, { accept = it }, enabled = !busy, maxLength = 800)
        AdminField(if (ar) "كلمات الوقت" else "Time words", time, { time = it }, enabled = !busy, maxLength = 800)
        AdminField(if (ar) "كلمات العملة/الأجرة" else "Currency/fare words", currency, { currency = it }, enabled = !busy, maxLength = 800)
        AdminField(if (ar) "كلمات تُتجاهل" else "Noise words", noise, { noise = it }, enabled = !busy, maxLength = 800)
        Text(if (ar) "كلمة القبول الخاطئة قد تجعل سبّاق يضغط زرًا غير زر القبول؛ أضف فقط ما رأيته على زر القبول فعلًا."
             else "A wrong accept word can make Sabbaq tap a button that is not accept; add only words you saw on the accept button.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        SabbaqButton(if (ar) "نشر المعجم لكل السائقين" else "Publish the lexicon to every driver", enabled = report != null && !busy, busy = busy,
            modifier = Modifier.testTag("admin-lexicon-publish"), onClick = {
                scope.launch {
                    busy = true
                    AdminFleet.publishLexicon(words(accept), words(time), words(currency), words(noise)).fold({ reload++; message = null }, {
                        message = session.failureOf(it, if (ar) "كل كلمة 1–24 حرفًا بلا أرقام، وحتى 30 كلمة لكل قائمة."
                            else "Each word 1–24 characters without digits, up to 30 per list.").text
                    })
                    busy = false
                }
            })
        SabbaqButton(if (ar) "إعادة التحميل" else "Reload", onClick = { reload++ }, style = SabbaqButtonStyle.Secondary, enabled = !busy)
    }
}

/** «نشر تحديث»: the release every app offers (and, below the minimum, requires), plus adoption per version. */
@Composable
internal fun AdminReleaseCard(session: AdminSession) {
    val ar = LocaleManager.isArabic()
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<AdminFleet.Release?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var notesAr by remember { mutableStateOf("") }
    var notesEn by remember { mutableStateOf("") }
    var minCode by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        busy = true
        AdminFleet.release().fold({ current = it; message = null }, {
            message = session.failureOf(it, if (ar) "تعذر تحميل التحديث المنشور." else "Could not load the published release.").text
        })
        busy = false
    }
    SabbaqCard(title = if (ar) "نشر تحديث التطبيق" else "Publish an app update", icon = Icons.Outlined.SystemUpdate,
        modifier = Modifier.testTag("admin-release")) {
        current?.let { c ->
            val r = c.release
            Text(if (ar) "المنشور: ${r.latestName.ifEmpty { "—" }} (${r.latestCode}) · الحد الأدنى ${r.minCode}"
                 else "Published: ${r.latestName.ifEmpty { "—" }} (${r.latestCode}) · minimum ${r.minCode}", style = MaterialTheme.typography.labelMedium)
            if (c.adoption.isNotEmpty()) {
                Text(if (ar) "السائقون حسب النسخة (آخر 3 أيام)" else "Drivers per version (last 3 days)", style = MaterialTheme.typography.labelLarge)
                c.adoption.take(8).forEach { Text("${it.version}: ${it.drivers}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        AdminField(if (ar) "رقم الإصدار (versionCode)" else "Version code", code, { code = it.filter(Char::isDigit) }, enabled = !busy, keyboard = KeyboardType.Number, ltr = true)
        AdminField(if (ar) "اسم الإصدار" else "Version name", name, { name = it.trim() }, enabled = !busy, ltr = true, maxLength = 32)
        AdminField(if (ar) "رابط ملف APK (https)" else "APK link (https)", url, { url = it.trim() }, enabled = !busy, ltr = true, maxLength = 300)
        AdminField("SHA-256", sha, { sha = it.trim().lowercase() }, enabled = !busy, ltr = true, maxLength = 64)
        AdminField(if (ar) "الحجم بالبايت" else "Size in bytes", size, { size = it.filter(Char::isDigit) }, enabled = !busy, keyboard = KeyboardType.Number, ltr = true)
        AdminField(if (ar) "ما الجديد (عربي)" else "What's new (Arabic)", notesAr, { notesAr = it }, enabled = !busy, maxLength = 1000, singleLine = false, minLines = 2)
        AdminField(if (ar) "ما الجديد (إنجليزي)" else "What's new (English)", notesEn, { notesEn = it }, enabled = !busy, maxLength = 1000, singleLine = false, minLines = 2)
        AdminField(if (ar) "الحد الأدنى المسموح (اختياري: أقل منه يتوقف القبول)" else "Minimum allowed (optional: below it accepting stops)",
            minCode, { minCode = it.filter(Char::isDigit) }, enabled = !busy, keyboard = KeyboardType.Number, ltr = true)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        SabbaqButton(if (ar) "نشر التحديث" else "Publish the update", enabled = !busy, busy = busy,
            modifier = Modifier.fillMaxWidth().testTag("admin-release-publish"), onClick = {
                scope.launch {
                    busy = true
                    AdminFleet.publishRelease(code.toLongOrNull() ?: 0, name, url, sha, size.toLongOrNull() ?: 0, notesAr, notesEn, minCode.toLongOrNull())
                        .fold({ reload++; message = null }, {
                            message = session.failureOf(it, if (ar) "تحقق من الحقول: رابط https ينتهي بـ .apk، وبصمة من 64 خانة، والحد الأدنى لا يتجاوز رقم الإصدار."
                                else "Check the fields: an https link ending in .apk, a 64-character hash, and a minimum not above the version code.").text
                        })
                    busy = false
                }
            })
        SabbaqButton(if (ar) "إعادة التحميل" else "Reload", onClick = { reload++ }, style = SabbaqButtonStyle.Secondary, enabled = !busy)
    }
}
