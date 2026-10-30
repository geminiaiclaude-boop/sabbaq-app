package app.sabbaq.notifications

import app.sabbaq.LocaleManager
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
    private val ar: Boolean get() = LocaleManager.isArabic()

    private fun local(key: String, title: String, body: String, kind: NotificationKind, action: NotificationAction?, now: Long, dedupeKey: String? = key) =
        AppNotification(id = "local:$key", title = title, body = body, kind = kind, action = action, createdAtEpochMs = now, dedupeKey = dedupeKey)

    private fun day(instant: Instant): String {
        val locale = if (ar) Locale.forLanguageTag("ar-u-nu-latn") else Locale.ENGLISH
        return instant.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMMM", locale))
    }

    private fun amount(value: BigDecimal): String = value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun offers(n: Long): String = if (ar) when {
        n == 1L -> "طلبًا واحدًا"
        n == 2L -> "طلبين"
        n in 3..10 -> "$n طلبات"
        else -> "$n طلبًا"
    } else if (n == 1L) "1 order" else "$n orders"

    fun monitoringStopped(reason: String, now: Long) = local("monitoring-stopped:$now",
        if (ar) "توقفت متابعة الطلبات" else "Order monitoring stopped", reason,
        NotificationKind.WARNING, NotificationAction.OPEN_SETUP, now, dedupeKey = "monitoring-stopped")

    fun accessibilityOff(now: Long) = local("accessibility-off:$now",
        if (ar) "إذن متابعة الطلبات مغلق" else "Order-monitoring permission is off",
        if (ar) "لن يقبل سبّاق أي طلب حتى تفعّل الإذن من الإعداد." else "Sabbaq will not accept any order until you enable the permission from setup.",
        NotificationKind.WARNING, NotificationAction.OPEN_SETUP, now, dedupeKey = "accessibility-off")

    fun updateRequired(now: Long) = local("update-required",
        if (ar) "تحديث سبّاق مطلوب" else "A Sabbaq update is required",
        if (ar) "هذه النسخة لم تعد مدعومة. حدّث التطبيق لتستمر متابعة الطلبات." else "This version is no longer supported. Update the app to keep order monitoring running.",
        NotificationKind.UPDATE, NotificationAction.UPDATE_APP, now)

    fun updateAvailable(code: Long, now: Long) = local("update-available-$code",
        if (ar) "تحديث جديد لسبّاق" else "A new Sabbaq update",
        if (ar) "نسخة أحدث متاحة بتحسينات وإصلاحات. افتح سبّاق واضغط «تحديث الآن»." else "A newer version with improvements and fixes is available. Open Sabbaq and tap \"Update now\".",
        NotificationKind.UPDATE, NotificationAction.UPDATE_APP, now)

    fun welcome(now: Long) = local("welcome",
        if (ar) "مرحبًا بك في سبّاق" else "Welcome to Sabbaq",
        if (ar) "اضبط شروطك ثم شغّل المتابعة. ستجد هنا كل طلب يقبله سبّاق وتنبيهات حسابك ورسائل الإدارة." else "Set your conditions, then start monitoring. Here you will find every order Sabbaq accepts, your account alerts and administration messages.",
        NotificationKind.INFO, NotificationAction.OPEN_FILTERS, now)

    fun trialStarted(until: Instant, now: Long) = local("trial-started:${until.epochSecond}",
        if (ar) "بدأت تجربتك المجانية" else "Your free trial has started",
        if (ar) "التجربة فعّالة حتى ${day(until)}. أكمل الإعداد لتبدأ المتابعة." else "The trial is active until ${day(until)}. Complete setup to start monitoring.",
        NotificationKind.SUCCESS, NotificationAction.OPEN_SETUP, now)

    fun subscriptionActivated(until: Instant, now: Long) = local("subscription-active:${until.epochSecond}",
        if (ar) "تم تفعيل اشتراكك" else "Your subscription is activated",
        if (ar) "اشتراكك فعّال حتى ${day(until)}. شكرًا لثقتك بسبّاق." else "Your subscription is active until ${day(until)}. Thank you for trusting Sabbaq.",
        NotificationKind.SUCCESS, null, now)

    fun subscriptionRenewed(until: Instant, now: Long) = local("subscription-renewed:${until.epochSecond}",
        if (ar) "تم تجديد اشتراكك" else "Your subscription was renewed",
        if (ar) "يمتد اشتراكك الآن حتى ${day(until)}." else "Your subscription now extends until ${day(until)}.",
        NotificationKind.SUCCESS, null, now)

    fun endingSoon(trial: Boolean, until: Instant, now: Long) = local("sub-ending:${until.epochSecond}",
        if (trial) (if (ar) "تنتهي تجربتك قريبًا" else "Your trial ends soon") else (if (ar) "ينتهي اشتراكك قريبًا" else "Your subscription ends soon"),
        if (trial) (if (ar) "تنتهي التجربة في ${day(until)}. اشترك قبلها حتى لا تتوقف متابعة الطلبات." else "The trial ends on ${day(until)}. Subscribe before then so order monitoring does not stop.")
            else (if (ar) "ينتهي اشتراكك في ${day(until)}. جدّده قبلها حتى لا تتوقف متابعة الطلبات." else "Your subscription ends on ${day(until)}. Renew it before then so order monitoring does not stop."),
        NotificationKind.ACCOUNT, NotificationAction.OPEN_ACCOUNT, now)

    fun expired(wasTrial: Boolean, now: Long) = local("expired:${now / DAY_MS}",
        if (wasTrial) (if (ar) "انتهت تجربتك المجانية" else "Your free trial has ended") else (if (ar) "انتهى اشتراكك" else "Your subscription has ended"),
        if (ar) "توقفت متابعة الطلبات. اشترك أو فعّل رمزًا من صفحة حسابي لتعود." else "Order monitoring stopped. Subscribe or activate a code from the My Account page to resume.",
        NotificationKind.ACCOUNT, NotificationAction.OPEN_ACCOUNT, now)

    fun deviceMismatch(now: Long) = local("device-mismatch:${now / DAY_MS}",
        if (ar) "حسابك مرتبط بجوال آخر" else "Your account is linked to another phone",
        if (ar) "لن تعمل المتابعة على هذا الجوال حتى تنقل الحساب إليه من صفحة حسابي." else "Monitoring will not work on this phone until you move the account to it from the My Account page.",
        NotificationKind.WARNING, NotificationAction.OPEN_ACCOUNT, now)

    fun accountSuspended(now: Long) = local("account-suspended:${now / DAY_MS}",
        if (ar) "تم إيقاف حسابك" else "Your account has been suspended",
        if (ar) "لا يمكن تشغيل المتابعة الآن. تواصل مع إدارة سبّاق لمعرفة السبب." else "Monitoring cannot run now. Contact Sabbaq administration to find out why.",
        NotificationKind.WARNING, null, now)

    fun acceptedRide(fare: BigDecimal?, pickupKm: BigDecimal?, now: Long): AppNotification {
        val details = listOfNotNull(
            fare?.let { if (ar) "الأجرة ${amount(it)} ر.س" else "Fare ${amount(it)} SAR" },
            pickupKm?.let { if (ar) "الوصول ${amount(it)} كم" else "Pickup ${amount(it)} km" },
        )
        return AppNotification(
            id = "local:ride:$now:${(0..9999).random()}",
            title = if (ar) "تم قبول طلب" else "An order was accepted",
            body = if (details.isEmpty()) (if (ar) "قبل سبّاق طلبًا يطابق شروطك." else "Sabbaq accepted an order matching your conditions.") else details.joinToString(" · "),
            kind = NotificationKind.SUCCESS,
            createdAtEpochMs = now,
        )
    }

    fun dailySummary(date: LocalDate, visible: Long, accepted: Long, now: Long) = local("summary:$date",
        if (ar) "ملخص أمس" else "Yesterday's summary",
        if (accepted > 0) (if (ar) "رأى سبّاق ${offers(visible)} وقبل ${offers(accepted)} منها." else "Sabbaq saw ${offers(visible)} and accepted ${offers(accepted)} of them.")
            else (if (ar) "رأى سبّاق ${offers(visible)} ولم يطابق أيٌّ منها شروطك. راجع شروطك إن كانت ضيقة." else "Sabbaq saw ${offers(visible)} and none matched your conditions. Review your conditions if they are too tight."),
        NotificationKind.INFO, if (accepted > 0) null else NotificationAction.OPEN_FILTERS, now)

    fun newSharedZones(version: Int, now: Long) = local("shared-zones-$version",
        if (ar) "مناطق استثناء جديدة" else "New exclude zones",
        if (ar) "تمت إضافة مناطق استثناء جديدة، يرجى الاطلاع وتحديث إعداداتك." else "New exclude zones were added. Please review them and update your settings.",
        NotificationKind.INFO, NotificationAction.OPEN_FILTERS, now)

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
    /** Newest release code already announced as available. */
    val updateAvailableNotified: Long = 0L,
)

/** The account facts the rules read; a plain copy of the license snapshot. */
data class AccountFacts(
    val state: LicenseState?,
    val validUntil: Instant?,
    val trialUntil: Instant?,
    val proUntil: Instant?,
    val updateRequired: Boolean,
    /** A newer release was published (0 = none newer than this build). */
    val updateAvailableCode: Long = 0L,
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
        else if (!facts.updateRequired && facts.updateAvailableCode > previous.updateAvailableNotified)
            out += NotificationTemplates.updateAvailable(facts.updateAvailableCode, now)
        return out to previous.copy(
            state = state.name,
            proUntilEpochSec = facts.proUntil?.epochSecond ?: previous.proUntilEpochSec,
            trialUntilEpochSec = facts.trialUntil?.epochSecond ?: previous.trialUntilEpochSec,
            welcomed = true,
            updateNotified = facts.updateRequired,
            updateAvailableNotified = maxOf(previous.updateAvailableNotified, facts.updateAvailableCode),
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
