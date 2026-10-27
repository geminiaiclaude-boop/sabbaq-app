package app.sabbaq.notifications

import app.sabbaq.network.LicenseState
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Every automatic notification the app writes, in one catalogue: the wording, kind, action and
 * dedupe key of each lives here so the texts stay consistent and reviewable in one place.
 * Administration messages are written in the admin panel and are not templated.
 */
object NotificationTemplates {
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ar-u-nu-latn"))

    private fun local(key: String, title: String, body: String, kind: NotificationKind, action: NotificationAction?, now: Long, dedupeKey: String? = key) =
        AppNotification(id = "local:$key", title = title, body = body, kind = kind, action = action, createdAtEpochMs = now, dedupeKey = dedupeKey)

    private fun day(instant: Instant): String = instant.atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormat)

    private fun amount(value: BigDecimal): String = value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun offers(n: Long): String = when {
        n == 1L -> "طلبًا واحدًا"
        n == 2L -> "طلبين"
        n in 3..10 -> "$n طلبات"
        else -> "$n طلبًا"
    }

    // ---- التطبيق
    fun monitoringStopped(reason: String, now: Long) = local("monitoring-stopped:$now", "توقفت متابعة الطلبات", reason,
        NotificationKind.WARNING, NotificationAction.OPEN_SETUP, now, dedupeKey = "monitoring-stopped")

    fun accessibilityOff(now: Long) = local("accessibility-off:$now", "إذن متابعة الطلبات مغلق",
        "لن يقبل سبّاق أي طلب حتى تفعّل الإذن من الإعداد.", NotificationKind.WARNING, NotificationAction.OPEN_SETUP, now,
        dedupeKey = "accessibility-off")

    fun updateRequired(now: Long) = local("update-required", "تحديث سبّاق مطلوب",
        "هذه النسخة لم تعد مدعومة. حدّث التطبيق لتستمر متابعة الطلبات.", NotificationKind.UPDATE, NotificationAction.UPDATE_APP, now)

    // ---- الحساب
    fun welcome(now: Long) = local("welcome", "مرحبًا بك في سبّاق",
        "اضبط شروطك ثم شغّل المتابعة. ستجد هنا كل طلب يقبله سبّاق وتنبيهات حسابك ورسائل الإدارة.",
        NotificationKind.INFO, NotificationAction.OPEN_FILTERS, now)

    fun trialStarted(until: Instant, now: Long) = local("trial-started:${until.epochSecond}", "بدأت تجربتك المجانية",
        "التجربة فعّالة حتى ${day(until)}. أكمل الإعداد لتبدأ المتابعة.", NotificationKind.SUCCESS, NotificationAction.OPEN_SETUP, now)

    fun subscriptionActivated(until: Instant, now: Long) = local("subscription-active:${until.epochSecond}", "تم تفعيل اشتراكك",
        "اشتراكك فعّال حتى ${day(until)}. شكرًا لثقتك بسبّاق.", NotificationKind.SUCCESS, null, now)

    fun subscriptionRenewed(until: Instant, now: Long) = local("subscription-renewed:${until.epochSecond}", "تم تجديد اشتراكك",
        "يمتد اشتراكك الآن حتى ${day(until)}.", NotificationKind.SUCCESS, null, now)

    fun endingSoon(trial: Boolean, until: Instant, now: Long) = local("sub-ending:${until.epochSecond}",
        if (trial) "تنتهي تجربتك قريبًا" else "ينتهي اشتراكك قريبًا",
        if (trial) "تنتهي التجربة في ${day(until)}. اشترك قبلها حتى لا تتوقف متابعة الطلبات."
            else "ينتهي اشتراكك في ${day(until)}. جدّده قبلها حتى لا تتوقف متابعة الطلبات.",
        NotificationKind.ACCOUNT, NotificationAction.OPEN_ACCOUNT, now)

    fun expired(wasTrial: Boolean, now: Long) = local("expired:${now / DAY_MS}",
        if (wasTrial) "انتهت تجربتك المجانية" else "انتهى اشتراكك",
        "توقفت متابعة الطلبات. اشترك أو فعّل رمزًا من صفحة حسابي لتعود.", NotificationKind.ACCOUNT, NotificationAction.OPEN_ACCOUNT, now)

    fun deviceMismatch(now: Long) = local("device-mismatch:${now / DAY_MS}", "حسابك مرتبط بجوال آخر",
        "لن تعمل المتابعة على هذا الجوال حتى تنقل الحساب إليه من صفحة حسابي.", NotificationKind.WARNING, NotificationAction.OPEN_ACCOUNT, now)

    fun accountSuspended(now: Long) = local("account-suspended:${now / DAY_MS}", "تم إيقاف حسابك",
        "لا يمكن تشغيل المتابعة الآن. تواصل مع إدارة سبّاق لمعرفة السبب.", NotificationKind.WARNING, null, now)

    // ---- نشاط السائق
    fun acceptedRide(fare: BigDecimal?, pickupKm: BigDecimal?, now: Long): AppNotification {
        val details = listOfNotNull(fare?.let { "الأجرة ${amount(it)} ر.س" }, pickupKm?.let { "الوصول ${amount(it)} كم" })
        return AppNotification(
            id = "local:ride:$now:${(0..9999).random()}",
            title = "تم قبول طلب",
            body = if (details.isEmpty()) "قبل سبّاق طلبًا يطابق شروطك." else details.joinToString(" · "),
            kind = NotificationKind.SUCCESS,
            createdAtEpochMs = now,
        )
    }

    fun dailySummary(date: LocalDate, visible: Long, accepted: Long, now: Long) = local("summary:$date", "ملخص أمس",
        if (accepted > 0) "رأى سبّاق ${offers(visible)} وقبل ${offers(accepted)} منها."
            else "رأى سبّاق ${offers(visible)} ولم يطابق أيٌّ منها شروطك. راجع شروطك إن كانت ضيقة.",
        NotificationKind.INFO, if (accepted > 0) null else NotificationAction.OPEN_FILTERS, now)

    internal const val DAY_MS = 24L * 60 * 60 * 1000
}

/** What the inbox last knew about the account, to turn changes into notifications once. */
@Serializable
data class AccountMark(
    val state: String? = null,
    val proUntilEpochSec: Long? = null,
    val trialUntilEpochSec: Long? = null,
    val welcomed: Boolean = false,
    val summaryDay: String? = null,
    val updateNotified: Boolean = false,
)

/** The account facts the rules read; a plain copy of the license snapshot. */
data class AccountFacts(
    val state: LicenseState?,
    val validUntil: Instant?,
    val trialUntil: Instant?,
    val proUntil: Instant?,
    val updateRequired: Boolean,
)

object NotificationRules {
    private const val ENDING_SOON_MS = 2L * NotificationTemplates.DAY_MS

    /**
     * Compares the account with what was last seen and returns the notifications due now plus
     * the new mark. The first look at an account only welcomes it and records the state, so
     * signing in never replays old transitions.
     */
    fun accountChanges(previous: AccountMark, facts: AccountFacts, now: Long): Pair<List<AppNotification>, AccountMark> {
        val state = facts.state ?: return emptyList<AppNotification>() to previous
        val out = mutableListOf<AppNotification>()
        val before = previous.state?.let { name -> LicenseState.entries.firstOrNull { it.name == name } }
        if (!previous.welcomed) out += NotificationTemplates.welcome(now)
        if (previous.welcomed) {
            when (state) {
                LicenseState.TRIAL -> if (before != LicenseState.TRIAL) facts.trialUntil?.let { out += NotificationTemplates.trialStarted(it, now) }
                LicenseState.ACTIVE -> facts.proUntil?.let { until ->
                    val previousUntil = previous.proUntilEpochSec
                    when {
                        before != LicenseState.ACTIVE -> out += NotificationTemplates.subscriptionActivated(until, now)
                        previousUntil != null && until.epochSecond > previousUntil -> out += NotificationTemplates.subscriptionRenewed(until, now)
                        else -> Unit
                    }
                }
                LicenseState.EXPIRED -> if (before == LicenseState.ACTIVE || before == LicenseState.TRIAL)
                    out += NotificationTemplates.expired(wasTrial = before == LicenseState.TRIAL, now = now)
                LicenseState.DEVICE_MISMATCH -> if (before != LicenseState.DEVICE_MISMATCH) out += NotificationTemplates.deviceMismatch(now)
                LicenseState.BANNED, LicenseState.REJECTED ->
                    if (before != LicenseState.BANNED && before != LicenseState.REJECTED) out += NotificationTemplates.accountSuspended(now)
                LicenseState.PENDING, LicenseState.UNREGISTERED -> Unit
            }
        }
        endingSoon(state, facts.validUntil, now)?.let { out += it }
        if (facts.updateRequired && !previous.updateNotified) out += NotificationTemplates.updateRequired(now)
        return out to previous.copy(
            state = state.name,
            proUntilEpochSec = facts.proUntil?.epochSecond ?: previous.proUntilEpochSec,
            trialUntilEpochSec = facts.trialUntil?.epochSecond ?: previous.trialUntilEpochSec,
            welcomed = true,
            updateNotified = facts.updateRequired,
        )
    }

    /** A reminder while the trial or subscription has two days or less left; one per end date. */
    fun endingSoon(state: LicenseState?, validUntil: Instant?, now: Long): AppNotification? {
        if (state == null || validUntil == null || !state.isValidState) return null
        val left = validUntil.toEpochMilli() - now
        if (left <= 0 || left > ENDING_SOON_MS) return null
        return NotificationTemplates.endingSoon(trial = state == LicenseState.TRIAL, until = validUntil, now = now)
    }

    /** Yesterday's activity, once per day, only when the app actually saw offers. */
    fun dailySummary(previous: AccountMark, today: LocalDate, yesterdayVisible: Long, yesterdayAccepted: Long, now: Long): Pair<AppNotification?, AccountMark> {
        if (previous.summaryDay == today.toString()) return null to previous
        val mark = previous.copy(summaryDay = today.toString())
        if (yesterdayVisible <= 0) return null to mark
        return NotificationTemplates.dailySummary(today.minusDays(1), yesterdayVisible, yesterdayAccepted, now) to mark
    }
}
