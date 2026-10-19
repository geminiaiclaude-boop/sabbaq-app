package app.qannas.ui

import java.text.NumberFormat
import java.util.Locale

/**
 * نسخة مصغّرة من العرض العام للاستخدام المرئي فقط. لا تمنح تجربة أو اشتراكاً،
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
    val hasServerOffer: Boolean
)

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
            headline = "المتجر هو قناة الاشتراك",
            detail = "تلتزم هذه النسخة بخيارات المتجر داخله عند تهيئتها. لا تظهر هنا أسعار أو روابط أو إجراءات خارج المتجر.",
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
            headline = "العرض تحدده الخدمة",
            detail = "لا نستطيع عرض تفاصيل التجربة أو السعر الحالي الآن. بعد تسجيل الدخول، اعرض العرض المتاح من الخادم قبل اتخاذ أي خطوة. هذه الشاشة لا تمنح ترخيصاً أو تجربة.",
            hasServerOffer = false
        )
    }

    val price = NumberFormat.getNumberInstance(Locale.forLanguageTag("ar-SA")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(validOffer.priceMinor / 100.0)
    val trial = if (validOffer.trialEnabled) {
        "قد تتاح تجربة لمدة ${validOffer.trialDays} يومًا؛ يقرر الخادم أهليتها، وتبدأ فقط بإجراء صريح بعد تسجيل الدخول."
    } else {
        "لا توجد تجربة مفعّلة حالياً؛ يحدد الخادم الإتاحة عند المتابعة."
    }
    return OnboardingOfferCopy(
        headline = "عرض الاشتراك الحالي",
        detail = "$trial سعر الاشتراك الحالي $price ر.س لمدة ${validOffer.subscriptionDays} يومًا.",
        hasServerOffer = true
    )
}

/** عناصر المعاينة تعليمية محلية، وليست قواعد تشغيل أو إعدادات حساب. */
internal enum class DecisionSignal(val label: String) {
    PICKUP_DISTANCE("مسافة الوصول"),
    TRIP_DURATION("مدة المشوار"),
    DISPLAYED_FARE("السعر الظاهر")
}

internal fun localDecisionPreview(selected: Set<DecisionSignal>): String {
    val signals = selected.map { it.label }
    return when {
        signals.isEmpty() -> "اختر معلومة أو أكثر لترى كيف تُعرض قبل أن تقرر."
        else -> "ستراجع ${signals.joinToString("، ")} كما تظهر في الطلب، ثم تقرر بنفسك. هذه معاينة تعليمية محلية فقط؛ لا تحفظ قاعدة ولا تقبل أي طلب."
    }
}
