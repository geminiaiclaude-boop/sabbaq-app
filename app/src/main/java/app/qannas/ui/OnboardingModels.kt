package app.qannas.ui

import java.text.NumberFormat
import java.util.Locale

/**
 * نسخة مصغّرة من العرض العام للاستخدام المرئي فقط. لا تمنح تجربة أو اشتراكًا،
 * ولا تُخزَّن خارج تركيب الشاشة.
 */
internal data class OnboardingOffer(
    val trialEnabled: Boolean,
    val trialDays: Int,
    val priceMinor: Int,
    val currency: String,
    val subscriptionDays: Int
)

/** نصوص العرض التي لا تظهر إلا عند استلام عرض خادمي صالح. */
internal data class OnboardingOfferCopy(
    val headline: String,
    val detail: String,
    val hasServerOffer: Boolean,
    /** يفتح CTA مسار التحقق بعد الدخول فقط؛ لا يعني أهلية الحساب النهائية. */
    val canShowTrialJourney: Boolean = false
)

/** نص الزر الختامي؛ لا يستدعي هذا النص بدء التجربة ولا يغير حالة الترخيص. */
internal fun onboardingFinalCta(canShowTrialJourney: Boolean): String =
    if (canShowTrialJourney) "ابدأ تجربتك" else "متابعة لتسجيل الدخول"

/**
 * يعزل نص البيع عن واجهة Play: لا سعر ولا رمز ولا رابط خارج المتجر في نسخة Play.
 * حدود القيم دفاعية؛ عميل الشبكة هو المرجع في التحقق من العرض العام.
 */
internal fun onboardingOfferCopy(
    isDirectDistribution: Boolean,
    offer: OnboardingOffer?
): OnboardingOfferCopy {
    if (!isDirectDistribution) {
        return OnboardingOfferCopy(
            headline = "اكتشف قناص من المتجر",
            detail = "سجّل الدخول لاكتشاف الخدمة. خيارات الاشتراك داخل المتجر تظهر عند توفرها.",
            hasServerOffer = false
        )
    }

    val validOffer = offer?.takeIf {
        it.currency == "SAR" &&
            it.priceMinor in 1..1_000_000 &&
            it.subscriptionDays in 1..365 &&
            (!it.trialEnabled || it.trialDays in 1..365)
    }
    if (validOffer == null) {
        return OnboardingOfferCopy(
            headline = "اكتشفه أولًا",
            detail = "تابع بعد تسجيل الدخول لمعرفة خيارات حسابك. تفاصيل السعر والتجربة غير متاحة الآن.",
            hasServerOffer = false
        )
    }

    val price = NumberFormat.getNumberInstance(Locale.forLanguageTag("ar-SA")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(validOffer.priceMinor / 100.0)
    val trial = if (validOffer.trialEnabled) {
        "جرّب قناص ${validOffer.trialDays} يومًا دون رسوم للحسابات المؤهلة. تبدأ بعد تأكيدك عقب تسجيل الدخول."
    } else {
        "التجربة غير متاحة حاليًا."
    }
    return OnboardingOfferCopy(
        headline = if (validOffer.trialEnabled) "جرّبه قبل الاشتراك" else "اختر ما يناسب عملك",
        detail = "$trial سعر الاشتراك الحالي $price ر.س لمدة ${validOffer.subscriptionDays} يومًا.",
        hasServerOffer = true,
        canShowTrialJourney = validOffer.trialEnabled
    )
}

/** عناصر المعاينة تعليمية محلية، وليست قواعد تشغيل أو إعدادات حساب. */
internal enum class DecisionSignal(val label: String) {
    PICKUP_DISTANCE("مسافة الوصول"),
    TRIP_DURATION("مدة المشوار"),
    DISPLAYED_FARE("السعر الظاهر")
}

internal fun localDecisionPreview(selected: Set<DecisionSignal>): String {
    if (selected.isEmpty()) return "اختر تفصيلًا أو أكثر لتظهره في المثال."
    return buildString {
        append("في هذا المثال:\n")
        if (DecisionSignal.DISPLAYED_FARE in selected) append("السعر الظاهر: ٤٥ ر.س\n")
        if (DecisionSignal.PICKUP_DISTANCE in selected) append("مسافة الوصول: ٣ كم + مشوار ١٥ كم\n")
        if (DecisionSignal.TRIP_DURATION in selected) append("مدة المشوار: ٣٠ دقيقة\n")
        if (DecisionSignal.DISPLAYED_FARE in selected && DecisionSignal.PICKUP_DISTANCE in selected)
            append("العائد لكل كم مع الوصول: ٢٫٥ ر.س قبل التكاليف\n")
        append("لا يمثل طلبًا فعليًا ولا يغيّر إعداداتك.")
    }
}
