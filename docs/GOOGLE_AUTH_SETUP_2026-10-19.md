# إعداد Google للمصادقة — 19 أكتوبر 2026

> **حالة بعد اعتماد اسم سبّاق (2026-10-21):** تغيّرت الحزمة إلى `app.sabbaq.driver`. العملاء المذكورون أدناه أُنشئوا للحزمة السابقة، ويجب إنشاء عميلي Android (Release وDebug) للحزمة الجديدة وتحديث العلامة في شاشة الموافقة قبل أي إصدار، وإلا يفشل الدخول بـ Google.


## تحديث 20 أكتوبر 2026 (الحالة الحالية)

- أُنشئ عميل Android باسم `Sabbaq Android Release` للحزمة `app.sabbaq.driver` ببصمة شهادة الإصدار `82:28:DC:E8:E6:C6:42:DC:4A:69:71:95:FB:0E:E7:3F:64:A1:36:23` (مستخرجة من APK الموقّع في CI، وهي نفسها لنسختي direct وplay).
- Branding: الصفحة الرئيسية `https://yahooyahoo99.github.io/`، سياسة الخصوصية `https://yahooyahoo99.github.io/privacy.html`، والنطاق المصرح `yahooyahoo99.github.io` إلى جانب نطاق Supabase.
- **نُشر تطبيق OAuth للإنتاج** (لم يعد Testing). النطاقات أساسية فقط، فلا يلزم تحقق Google.
- مزود Supabase Google مفعّل ويقبل Web client ID في Client IDs (مطلوب لدخول Credential Manager الأصلي).
- **Phone مفعّل** عبر Twilio Verify (خدمة «Sabbaq»، Fraud Guard)، وأكد `auth/v1/settings`: `phone=true`, `sms_provider=twilio_verify`. حساب Twilio تجريبي حتى يرقّيه المالك.
- **متبقٍ بعد الرفع إلى Google Play:** أضف عميل Android ثالثًا ببصمة **Play App Signing** من Play Console، وإلا يفشل Google في نسخة المتجر.

ما يلي سجل حالة 19 أكتوبر.

## الإعداد المنفذ

أُعدّت Google Auth Platform في مشروع المالك الموجود `kassab-387401` لأن الحساب بلغ حد إنشاء المشاريع الجديدة. العلامة في شاشة الموافقة «سبّاق | Sabbaq»، والجمهور External في وضع **Testing**، مع إضافة حساب المالك الحالي كمختبر. لم يُنشر OAuth للعامة ولم تُفعّل فوترة أو تجربة Cloud المدفوعة.

أُنشئ عميل Web باسم `Sabbaq Supabase Web`، وأضيف callback المحدد فقط:

`https://rxioktlmecdpuyokyatl.supabase.co/auth/v1/callback`

المعرّف العام موجود في `config/public-backend.properties`. سر العميل محفوظ في إعداد مزود Google داخل Supabase؛ لا يوجد في المصدر أو APK. اقتُصرت نطاقات الموافقة على `openid` و`userinfo.email` و`userinfo.profile`؛ لا نطاقات حساسة أو وصول إلى Google Cloud أو Drive.

أُنشئ عميل Android باسم `Sabbaq Android Debug` للحزمة `app.sabbaq.driver.debug` وبصمة SHA-1 للشهادة الفعلية المستخدمة في الحزم التجريبية المسلّمة:

`16:4C:FD:25:A3:FA:DA:EB:06:1B:59:50:14:10:22:DE:44:FA:1B:14`

Google مفعّل في Supabase، و**Skip nonce checks معطّل** و**Allow users without an email معطّل**. لم يُعطّل مزود البريد القديم في هذه المرحلة، لتجنب قطع دخول الحسابات السابقة قبل نجاح اختبار دخول Google فعلي؛ يتبقى إغلاقه بعد قبول الانتقال إلى Google/الهاتف حصريًا.

## التحقق المنفذ

- أكد `GET /auth/v1/settings` باستخدام المفتاح العام أن `external.google=true` وأن `external.phone=false`.
- أكد طلب التفويض غير المتبوع إلى `/auth/v1/authorize?provider=google` أن التوجيه يستخدم معرّف Web الصحيح وcallback المشروع الصحيح. هذا **ليس اختبار دخول مكتملًا** ولا ينشئ جلسة مستخدم.
- نجح بناء `assembleDirectDebug` و`assemblePlayDebug`، ونجحت 48 حالة اختبار لتطبيق direct و48 حالة لنسخة play دون إخفاقات أو أخطاء. النسختان تشتركان في كثير من الاختبارات؛ لا تُعدّ 96 حالة مستقلة.
- فُحصت الملفات غير المضغوطة داخل APK؛ لم يوجد سر Google في أي منها، ولا أصول `assets/admin/` في نسخة Play.

## حدود الإطلاق

تحتاج تجربة Google الفعلية إلى تثبيت APK المسلّم وتسجيل الدخول بحساب المالك المختبر. لا تزال شهادة توقيع Release وشهادة Play App Signing غير مقدمتين؛ يجب إنشاء Android OAuth client مستقل لكل بصمة مستخدمة مع حزمة `app.sabbaq.driver` قبل إصدارها. لا تستخدم شهادة Debug بدل شهادة الإنتاج.

حزم Debug الناتجة في بيئة أخرى، ومنها GitHub Actions، قد تُوقّع بشهادة Debug مختلفة؛ لذلك لا يكفي تطابق اسم الحزمة لعمل Google. أضف بصمة كل شهادة اختبار معتمدة إلى عميل Android مستقل، أو اختبر باستخدام APK المسلّم الذي سُجلت بصمته. لا ترفع مفاتيح توقيع خاصة إلى Git.

قبل فتح OAuth للعامة يجب استكمال صفحة الخصوصية العامة وهوية المطور ومتطلبات التحقق المناسبة. إعداد Google الحالي تجريبي وليس اعتمادًا عامًا أو ضمان قبول Google Play.

## SMS

لم يُفعّل Phone دون مزود يعمل أو أكواد اختبار ثابتة. المسار المقترح هو Twilio Verify تجريبي، لا SMS مخصص، لأن شروط Twilio التجريبية الحالية تقيّد قوالب الرسائل. يتطلب حساب Twilio يملكه المستخدم والتحقق من رقم سعودي واختبار التسليم الفعلي قبل ربطه.

تعرض Twilio حاليًا تجربة 30 يومًا بلا بطاقة، 100 SMS و40 تحققًا، حتى 5 مستلمين موثقين ومن بلد التسجيل. هذه حدود منشورة وليست رصيد حساب المستخدم أو ضمان إتاحة ووصول الرسائل السعودية. قيود الشبكات وتسجيل المرسل في السعودية قد تمنع التسليم؛ لا تُفعّل ترقية أو دفع تلقائيًا.

## المراجع

- https://supabase.com/docs/guides/auth/social-login/auth-google
- https://supabase.com/docs/guides/auth/phone-login
- https://www.twilio.com/docs/usage/trials
- https://www.twilio.com/en-us/guidelines/sa/sms
