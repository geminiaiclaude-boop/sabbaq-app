package app.qannas.network

import java.time.Instant

/** ساعة أحادية الاتجاه قابلة للحقن حتى تبقى مهلة الترخيص مستقلة عن ساعة الجهاز. */
internal fun interface MonotonicClock {
    fun nowMillis(): Long
}

/** الحالات التي تعيدها دالة license_status؛ لا تُعامل أي حالة غير صالحة كترخيص. */
enum class LicenseState {
    ACTIVE,
    TRIAL,
    PENDING,
    EXPIRED,
    DEVICE_MISMATCH,
    BANNED,
    REJECTED,
    UNREGISTERED;

    val isValidState: Boolean
        get() = this == ACTIVE || this == TRIAL

    val canBindCandidateDevice: Boolean
        get() = this == ACTIVE || this == TRIAL || this == PENDING || this == EXPIRED

    companion object {
        fun fromWire(value: String): LicenseState? = when (value) {
            "active" -> ACTIVE
            "trial" -> TRIAL
            "pending" -> PENDING
            "expired" -> EXPIRED
            "device_mismatch" -> DEVICE_MISMATCH
            "banned" -> BANNED
            "rejected" -> REJECTED
            "unregistered" -> UNREGISTERED
            else -> null
        }
    }
}

/** بيانات حالة الترخيص اللازمة لبناء مهلة محلية آمنة. */
internal data class LicenseStatus(
    val state: LicenseState,
    val serverTime: Instant? = null,
    val validUntil: Instant? = null,
    val minimumVersionCode: Int? = null,
    val trialUntil: Instant? = null,
    val proUntil: Instant? = null,
    val trialEligible: Boolean? = null,
    val trialEnabled: Boolean? = null,
    val trialDays: Int? = null
)

/** The server refused a device move: two moves per 30 days. [retryAfter] is yyyy-MM-dd when known. */
class DeviceTransferLimitException(val retryAfter: String?) :
    IllegalStateException("وصلت إلى حد نقل الحساب بين الأجهزة")

/** خطأ حالة قابل للمعالجة ولا يتضمن رموزًا أو تفاصيل خادم غير موثوقة. */
class LicenseStateException(
    val state: LicenseState,
    message: String
) : IllegalStateException(message)

/** الترخيص محلي الذاكرة فقط، ويحتاج إعادة تحقق ناجحة كل 15 دقيقة على الأكثر. */
internal class LicenseLease(private val clock: MonotonicClock) {
    companion object {
        const val MAX_REVALIDATION_AGE_MILLIS: Long = 15L * 60L * 1000L
    }

    private var lease: LeaseSnapshot? = null

    /**
     * يستبدل المهلة بالحالة المستلمة. يُرجع true فقط إن كان الترخيص صالحًا الآن.
     * تبدأ المهلة عند بداية طلب الشبكة، لا وقت وصول الرد، كي لا تصبح مهلة النقل
     * سماحًا بعد وقت انتهاء الاشتراك الذي حدده الخادم.
     */
    fun replace(
        status: LicenseStatus,
        appVersionCode: Int,
        requestStartedAtMillis: Long = clock.nowMillis()
    ): Boolean {
        lease = buildLease(status, appVersionCode, requestStartedAtMillis)
        return isValid()
    }

    fun isValid(nowMillis: Long = clock.nowMillis()): Boolean {
        val current = lease ?: return false
        return nowMillis < current.subscriptionDeadlineMillis &&
            nowMillis < current.revalidationDeadlineMillis
    }

    fun invalidate() {
        lease = null
    }

    private fun buildLease(
        status: LicenseStatus,
        appVersionCode: Int,
        requestStartedAtMillis: Long
    ): LeaseSnapshot? {
        if (!status.state.isValidState || appVersionCode < 1) return null
        val requiredVersion = status.minimumVersionCode ?: return null
        if (requiredVersion < 1 || appVersionCode < requiredVersion) return null

        val serverTime = status.serverTime ?: return null
        val validUntil = status.validUntil ?: return null
        val subscriptionDuration = durationMillis(serverTime, validUntil) ?: return null
        if (subscriptionDuration <= 0L) return null

        val startedAt = requestStartedAtMillis.coerceAtLeast(0L)
        val subscriptionDeadline = saturatedAdd(startedAt, subscriptionDuration)
        val revalidationDeadline = saturatedAdd(
            startedAt,
            minOf(subscriptionDuration, MAX_REVALIDATION_AGE_MILLIS)
        )
        return LeaseSnapshot(subscriptionDeadline, revalidationDeadline)
    }

    private fun durationMillis(serverTime: Instant, validUntil: Instant): Long? = try {
        val serverMillis = serverTime.toEpochMilli()
        val validMillis = validUntil.toEpochMilli()
        if (validMillis <= serverMillis) null else Math.subtractExact(validMillis, serverMillis)
    } catch (_: ArithmeticException) {
        // تاريخ خارج المجال لا يكوّن ترخيصًا محليًا صالحًا.
        null
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private data class LeaseSnapshot(
        val subscriptionDeadlineMillis: Long,
        val revalidationDeadlineMillis: Long
    )
}
