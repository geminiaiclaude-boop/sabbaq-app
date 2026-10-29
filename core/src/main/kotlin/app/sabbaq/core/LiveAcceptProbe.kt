package app.sabbaq.core

/**
 * The on-device acceptance check: a mock offer drawn by Sabbaq itself goes through the real
 * accessibility path (event delivery, live tree read, parser, tap, confirmation). The offer is
 * read with the Jeeny profile in effect (including administration updates), so the check also
 * proves the current reading rules can extract a fare and distances; only the button labels are
 * the probe's own, so a real Jeeny label can never be tapped by mistake.
 */
object LiveAcceptProbe {
    const val ACCEPT_LABEL = "قبول الطلب التجريبي"
    const val CONFIRM_LABEL = "تم قبول الطلب التجريبي"
    const val PROFILE_ID = "selftest"
    const val TIMEOUT_MS = 20_000L

    /**
     * The mock card's lines, in the order the real Jeeny card (Oct 2026) shows them: trip kind,
     * pickup time, pickup distance, earnings label, fare, the net-earnings note, two addresses.
     */
    val MOCK_CARD_LINES = listOf(
        "مشوار داخل المدينة",
        "يبعُد 4 دقائق",
        "يبعُد 1.5كم",
        "ستربح",
        "25.50 ر.س",
        "صافي الأرباح بعد الرسوم وضريبة القيمة المضافة",
        "Al-Rehab - Abdullah Bin Al-Ashhab,",
        "Near 3253, Awwad Al Qabidi",
    )

    /** Steps in the order a real acceptance passes them. */
    enum class Step { EVENT, READ, PARSED, ACCEPT_FOUND, DISPATCHED, CLICK_RECEIVED, CONFIRMED }

    enum class Method { SEMANTIC, GESTURE }

    fun profile(base: RideProfile?, ownPackage: String): RideProfile = (base ?: FALLBACK).copy(
        id = PROFILE_ID,
        name = "اختبار القبول",
        packages = listOf(ownPackage),
        acceptLabels = listOf(ACCEPT_LABEL),
        acceptViewIds = emptyList(),
        confirmLabels = listOf(CONFIRM_LABEL),
        weakConfirmLabels = emptyList(),
        enabled = true,
    )

    /**
     * What failed and what the driver should do, for the first step that was not reached; null
     * when every step passed.
     */
    fun diagnosis(reached: Set<Step>, method: Method?): String? {
        val missing = Step.entries.firstOrNull { it !in reached } ?: return null
        return when (missing) {
            Step.EVENT -> "لم تصل أحداث الشاشة إلى خدمة سبّاق. تأكد أن «إذن متابعة الطلبات» مفعّل، ثم أطفئه وشغّله من إعدادات الإتاحة، واستثنِ سبّاق من توفير البطارية."
            Step.READ -> "وصلت الأحداث لكن تعذرت قراءة الشاشة. أعد تشغيل إذن متابعة الطلبات؛ بعض الجوالات توقف القراءة بعد التحديث."
            Step.PARSED -> "قُرئت الشاشة لكن لم تُستخرج الأجرة والمسافات بقواعد جيني الحالية. أرسل النتيجة للدعم لتحديث قواعد القراءة."
            Step.ACCEPT_FOUND -> "لم يُعثر على زر القبول في الشاشة المقروءة. أرسل النتيجة للدعم."
            Step.DISPATCHED -> "وجد سبّاق زر القبول لكن النظام رفض النقر. أعد تشغيل إذن متابعة الطلبات وتأكد أن الجوال لا يقيّد سبّاق."
            Step.CLICK_RECEIVED -> if (method == Method.GESTURE)
                "أُرسلت لمسة على الزر لكنها لم تصل. أغلق أي فقاعة أو تطبيق يرسم فوق الشاشة ثم أعد الاختبار."
                else "أُرسل النقر لكن الزر لم يستجب. أعد الاختبار؛ إن تكرر أرسل النتيجة للدعم."
            Step.CONFIRMED -> "نُقر الزر لكن سبّاق لم يرَ شاشة التأكيد. أعد الاختبار؛ إن تكرر أرسل النتيجة للدعم."
        }
    }

    /** A compact, machine-only line for telemetry and support: steps reached, method, total time. */
    fun summary(reached: Set<Step>, method: Method?, totalMs: Long?): String = buildString {
        append("probe:")
        append(Step.entries.joinToString("") { if (it in reached) "1" else "0" })
        method?.let { append(",m=").append(it.name.lowercase()) }
        totalMs?.let { append(",ms=").append(it.coerceIn(0, 99_999)) }
    }

    private val FALLBACK = RideProfile(
        id = PROFILE_ID,
        name = "اختبار القبول",
        packages = emptyList(),
        acceptLabels = listOf(ACCEPT_LABEL),
        currencyTokens = listOf("ر.س", "sar", "ريال"),
        addressCues = listOf("شارع", "طريق", "حي"),
    )
}
