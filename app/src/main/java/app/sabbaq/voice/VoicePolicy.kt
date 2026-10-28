package app.sabbaq.voice

import app.sabbaq.LocaleManager
import app.sabbaq.network.LicenseState
import java.math.BigDecimal
import java.math.RoundingMode

/** What a spoken alert is about; each kind has its own switch, tone and repeat window. */
enum class VoiceKind(val repeatWindowMillis: Long) {
    /** Monitoring started, stopped, resumed. */
    Status(20_000),
    /** Something needs the driver: a permission was closed, the subscription or sign-in stopped monitoring. */
    Problem(60_000),
    /** Internet lost / back while monitoring. */
    Connection(120_000),
    /** Subscription ending soon. */
    Subscription(6 * 60 * 60 * 1000L),
    /** An offer was accepted (needs the engine to report it, see OfferEvents). */
    Offer(3_000),
}

/** The driver's choices for spoken alerts. */
data class VoicePreferences(
    val enabled: Boolean = true,
    val status: Boolean = true,
    val problems: Boolean = true,
    val connection: Boolean = true,
    val subscription: Boolean = true,
    val offers: Boolean = true,
    val evenWhenSilent: Boolean = false,
    val volume: Float = 1f,
) {
    fun allows(kind: VoiceKind): Boolean = enabled && when (kind) {
        VoiceKind.Status -> status
        VoiceKind.Problem -> problems
        VoiceKind.Connection -> connection
        VoiceKind.Subscription -> subscription
        VoiceKind.Offer -> offers
    }
}

/** Phone state that decides whether speaking is appropriate right now. */
data class PhoneAudioState(val inCall: Boolean, val ringerSilent: Boolean)

/**
 * Pure decision rules (unit-tested): never talk over a call, stay quiet in silent mode
 * unless the driver allowed it, and do not repeat the same sentence within its window.
 */
class VoiceGate(private val clock: () -> Long) {
    private val lastSpoken = HashMap<String, Long>()

    @Synchronized
    fun shouldSpeak(kind: VoiceKind, text: String, prefs: VoicePreferences, phone: PhoneAudioState): Boolean {
        if (!prefs.allows(kind)) return false
        if (phone.inCall) return false
        if (phone.ringerSilent && !prefs.evenWhenSilent) return false
        val key = "${kind.name}:$text"
        val now = clock()
        val last = lastSpoken[key]
        if (last != null && now - last < kind.repeatWindowMillis) return false
        lastSpoken[key] = now
        if (lastSpoken.size > 64) lastSpoken.entries.removeAll { now - it.value > 6 * 60 * 60 * 1000L }
        return true
    }
}

/** Why monitoring stopped on its own; decides the sentence the driver hears. */
enum class StopCause { Notifications, SignIn, DeviceMoved, LicenseEnded, LicenseUnverified }

object VoiceRules {
    /**
     * The cause, checked in the order the driver must fix things. A lease that ran out while
     * the server still says the subscription is valid means verification failed (offline),
     * not that the driver must pay.
     */
    fun stopCause(notificationsAllowed: Boolean, hasSavedSession: Boolean, serverState: LicenseState?): StopCause = when {
        !notificationsAllowed -> StopCause.Notifications
        !hasSavedSession -> StopCause.SignIn
        serverState == LicenseState.DEVICE_MISMATCH -> StopCause.DeviceMoved
        serverState == null || serverState == LicenseState.ACTIVE || serverState == LicenseState.TRIAL -> StopCause.LicenseUnverified
        else -> StopCause.LicenseEnded
    }

    fun line(cause: StopCause): String = when (cause) {
        StopCause.Notifications -> VoiceLines.STOPPED_NOTIFICATIONS
        StopCause.SignIn -> VoiceLines.SIGN_IN_NEEDED
        StopCause.DeviceMoved -> VoiceLines.STOPPED_DEVICE_MOVED
        StopCause.LicenseEnded -> VoiceLines.STOPPED_LICENSE
        StopCause.LicenseUnverified -> VoiceLines.STOPPED_UNVERIFIED
    }

    /** 2 within the last hour, 1 within the last day, 0 otherwise (or already ended). */
    fun subscriptionWarningLevel(millisLeft: Long): Int = when {
        millisLeft <= 0 -> 0
        millisLeft <= 60 * 60 * 1000L -> 2
        millisLeft <= 24 * 60 * 60 * 1000L -> 1
        else -> 0
    }

    /** Hours left, rounded up, so "59 minutes" is still announced as one hour. */
    fun hoursLeft(millisLeft: Long): Long = (millisLeft + 60 * 60 * 1000L - 1) / (60 * 60 * 1000L)

    /** Volume chosen by the driver, kept audible: never below 20 %. */
    fun clampVolume(value: Float): Float = if (value.isNaN()) 1f else value.coerceIn(0.2f, 1f)
}

/** The sentences, in one place, so wording stays consistent and testable. */
object VoiceLines {
    private val ar: Boolean get() = LocaleManager.isArabic()
    val STARTED: String get() = if (ar) "بدأت متابعة الطلبات." else "Order monitoring started."
    val STOPPED: String get() = if (ar) "توقفت متابعة الطلبات." else "Order monitoring stopped."
    val RESUMED: String get() = if (ar) "عادت متابعة الطلبات للعمل." else "Order monitoring is back on."
    val STOPPED_NOTIFICATIONS: String get() = if (ar) "توقف سبّاق. إذن التنبيهات أُغلق، افتح التطبيق للسماح به." else "Sabbaq stopped. The notifications permission is off; open the app to allow it."
    val STOPPED_LICENSE: String get() = if (ar) "توقف سبّاق. اشتراكك يحتاج إلى تجديد." else "Sabbaq stopped. Your subscription needs renewal."
    val STOPPED_UNVERIFIED: String get() = if (ar) "توقف سبّاق. تعذر التحقق من اشتراكك، تحقق من الإنترنت وافتح التطبيق." else "Sabbaq stopped. Your subscription could not be verified; check the internet and open the app."
    val STOPPED_DEVICE_MOVED: String get() = if (ar) "توقف سبّاق. حسابك مفعّل على جهاز آخر، افتح التطبيق لنقله إلى هذا الجوال." else "Sabbaq stopped. Your account is active on another device; open the app to move it to this phone."
    val SIGN_IN_NEEDED: String get() = if (ar) "توقف سبّاق. افتح التطبيق وسجّل الدخول لاستئناف المتابعة." else "Sabbaq stopped. Open the app and sign in to resume monitoring."
    val ACCESSIBILITY_OFF: String get() = if (ar) "تنبيه. إذن متابعة الطلبات مغلق، لن يقبل سبّاق أي طلب حتى تفعّله." else "Alert. The order-monitoring permission is off; Sabbaq will not accept any order until you enable it."
    val ACCESSIBILITY_BACK: String get() = if (ar) "عاد إذن متابعة الطلبات." else "The order-monitoring permission is back."
    val CONNECTION_LOST: String get() = if (ar) "انقطع الاتصال بالإنترنت. سيستمر سبّاق لفترة قصيرة، تحقق من اتصالك." else "The internet connection dropped. Sabbaq will continue for a short while; check your connection."
    val CONNECTION_BACK: String get() = if (ar) "عاد الاتصال بالإنترنت." else "The internet connection is back."
    val TEST: String get() = if (ar) "مرحبًا، هذا صوت سبّاق. ستسمع هنا تنبيهات متابعة الطلبات." else "Hello, this is Sabbaq's voice. You will hear order-monitoring alerts here."

    fun subscriptionEnding(hoursLeft: Long): String = if (ar) when {
        hoursLeft <= 1 -> "ينتهي اشتراكك خلال ساعة. جدّده لتستمر المتابعة."
        hoursLeft < 24 -> "ينتهي اشتراكك خلال ${hours(hoursLeft)}. جدّده لتستمر المتابعة."
        else -> "ينتهي اشتراكك خلال ${days((hoursLeft + 23) / 24)}. جدّده لتستمر المتابعة."
    } else when {
        hoursLeft <= 1 -> "Your subscription ends in 1 hour. Renew it to keep monitoring."
        hoursLeft < 24 -> "Your subscription ends in ${hours(hoursLeft)}. Renew it to keep monitoring."
        else -> "Your subscription ends in ${days((hoursLeft + 23) / 24)}. Renew it to keep monitoring."
    }

    /**
     * It reports the tap, not a promise the platform kept the trip. Numbers are read as words
     * by the voice.
     */
    fun offerAccepted(fare: BigDecimal?, pickupKm: BigDecimal?): String = if (ar) buildString {
        append("ضغطتُ قبول طلب")
        fare?.let { append(" بأجرة ${amount(it)} ريال") }
        pickupKm?.let { append("، الوصول ${amount(it)} كيلومتر") }
        append(".")
    } else buildString {
        append("I tapped accept on an order")
        fare?.let { append(" with a fare of ${amount(it)} SAR") }
        pickupKm?.let { append(", pickup ${amount(it)} km") }
        append(".")
    }

    private fun amount(value: BigDecimal): String = value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun hours(n: Long): String = if (ar) when {
        n == 2L -> "ساعتين"
        n in 3..10 -> "$n ساعات"
        else -> "$n ساعة"
    } else if (n == 1L) "1 hour" else "$n hours"

    private fun days(n: Long): String = if (ar) when {
        n == 1L -> "يوم"
        n == 2L -> "يومين"
        n in 3..10 -> "$n أيام"
        else -> "$n يومًا"
    } else if (n == 1L) "1 day" else "$n days"
}
