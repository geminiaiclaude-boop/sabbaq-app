# إعداد مشروع Sabbaq

يوثق هذا الدليل إعداد قاعدة البيانات ولوحة التحكم وبناء Android المحلي وCI للإصدار. لا تنسخ قيماً حقيقية للمفاتيح أو كلمات المرور إلى المستودع أو إلى سجل طرفية مشترك.

## المتطلبات والبناء المحلي

يتطلب المشروع **JDK 17** وAndroid SDK الموافق لـ `compileSdk = 36`. بعد استنساخ المستودع، امنح Gradle Wrapper صلاحية التنفيذ ثم نفّذ الفحوصات التي لا تحتاج أسراراً:

```bash
chmod +x gradlew
./gradlew --no-daemon :core:test :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest :app:lint
./gradlew --no-daemon :app:assembleDirectDebug :app:assemblePlayDebug
```

ملف `local.properties` محلي فقط. أنشئه بصلاحيات مقيدة عند الحاجة إلى تشغيل التطبيق على خدمة حقيقية، أو مرر القيم نفسها كمتغيرات بيئة؛ لمتغيرات البيئة أسبقية على الملف:

```properties
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-KEY
GOOGLE_WEB_CLIENT_ID=YOUR-WEB-CLIENT-ID.apps.googleusercontent.com
```

مفتاح `anon` قيمة عميل مضمنة في التطبيق وليست بديلاً عن سياسات RLS السليمة في Supabase. لا تضع مطلقاً `service_role` أو أي مفتاح إداري في تطبيق Android أو ملف إعداد عميل.

في Supabase Auth فعّل Google وأدخل إعداداته الصحيحة، وسجّل هوية Android وSHA المناسبة في مشروعGoogle الخاص بالمالك، مع استخدام **Web client ID** في التطبيق. فعّل Phone Auth ومزودSMS قبل اعتمادالهاتف السعودي. لا يُنشأ حسابعميل جديد بكلمة مرور من واجهةAndroid. لا تكفي صحةصيغةالهاتف؛ يبدأالترخيص بعد تحققOTPالفعلي ومزودهوية موثوق. راجع تقاريرالعقود السعودية ومصادرالمزودين قبلالإطلاق، ولا تختبرإرسالSMSبأرقامحيّة دونتفويض.

`direct` و`play` بديلان للحزمة نفسها، وليستا تطبيقين يثبتان معًا. حدّد قيمة `DIRECT_DISTRIBUTION` في flavor أثناء التجميع ولا تُغيّرها بإعداد خادم. `direct` تعرض أكوادالإدارة وسياسةالسعر؛ `play` لا تعرض تدفقاتبيعخارجي أو أسعارالسيرفر على أنهادفعالمتجر. لا توجد منتجاتPlayBilling مفعّلة ضمنهذاالتسليم.

## إعداد قاعدة بيانات Supabase

1. أنشئ مشروعاً في [Supabase](https://supabase.com).
2. افتح **SQL Editor** ونفذ ملفات `supabase/migrations/` بالترتيب المعجمي، بدءاً من `0001_init.sql`.
3. تحقق من أن سياسات RLS والأدوار موجودة قبل ربط بيئة إنتاج؛ لا يعتمد التطبيق على إخفاء مفتاح `anon` كحاجز أمني.

الترحيل `0003_registration_admin_contract.sql` يؤسس عقدالإدارة القديم. الترحيل `0004_saudi_commercial_identity.sql` يضيف هويةالعميل الموثوقة وسياسةالتجربة والأسعار ويستبدل مسارالتسجيل الجديد المعتمد علىmetadata بقواعدهويةخادمية. نفّذ الترحيلات بالترتيب بعد نسخةاحتياطيةومراجعة، ولا تنفذ `supabase/tests/bootstrap.sql` أوfixtures علىالإنتاج. لا تُعاد تجربةالحسابات السابقة أو تُلغىاشتراكاتها المدفوعة تلقائيًا عندالترحيل.

## إنشاء حساب المشرف

لوحة الإدارة تتطلب صلاحيات المشرف. بعد إنشاء مستخدم من **Authentication**، نفذ الاستعلام التالي بعد استبدال البريد:

```sql
insert into public.admins (user_id)
select id from auth.users
where email = 'YOUR_ADMIN_EMAIL@example.com';
```

## إعداد لوحة الإدارة

لوحة الإدارة تستخدم ملفاً محلياً غير متعقب:

1. انسخ `admin/config.example.js` إلى `admin/config.js`.
2. ضع رابط المشروع ومفتاح `anon` فقط. لا تستخدم مفتاح `service_role` في JavaScript يعمل داخل المتصفح.

## توقيع إصدار Android

**تحذير:** المفتاح القديم `keystore.jks` كان مكشوفاً في تاريخ المستودع؛ لا تستخدمه للإنتاج. يجب على المالك التحقق من هوية توقيع الإصدارات المثبتة وPlay App Signing قبل استبدال أي مفتاح إنتاج.

ينشئ البناء الآن توقيع `release` صراحةً ولا يسمح بالرجوع إلى توقيع debug. ولّد المفتاح على جهاز موثوق مزود بـ JDK 17، ولا تدخله في المستودع:

```bash
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
chmod 600 upload.jks
```

احفظ ملف المفتاح وكلمتي المرور في مخزن أسرار منفصلين عن المستودع. فقدان مفتاح النشر بعد أول إصدار في Google Play يمنع تحديث التطبيق. عيّن المتغيرات التالية في بيئة محلية آمنة أو في GitHub Actions؛ لا تطبع قيمها:

| المتغير | الغرض |
|---|---|
| `SUPABASE_URL` | رابط مشروع HTTPS الكامل. |
| `SUPABASE_ANON_KEY` | مفتاح العميل `anon`. |
| `GOOGLE_WEB_CLIENT_ID` | معرّف WebOAuth عام صحيح لدخولGoogle، لا clientsecret. |
| `SABBAQ_KEYSTORE` | المسار المطلق إلى ملف `.jks` أو `.keystore`. |
| `SABBAQ_KEYSTORE_PASSWORD` | كلمة مرور ملف التوقيع. |
| `SABBAQ_KEY_ALIAS` | اسم المفتاح داخل الملف. |
| `SABBAQ_KEY_PASSWORD` | كلمة مرور الـ alias. |
| `SABBAQ_VERSION_CODE` | اختياري؛ عدد صحيح موجب. |
| `SABBAQ_VERSION_NAME` | اختياري؛ اسم إصدار غير فارغ. |

نفّذ فحصاً غير منتج قبل إنتاج أي ملف. يفشل الفحص برسائل تحدد **اسم** الإعداد الناقص من دون طباعة قيمته:

```bash
./gradlew --no-daemon :app:verifyReleaseConfiguration
./gradlew --no-daemon :app:assembleDirectRelease :app:bundleDirectRelease
./gradlew --no-daemon :app:assemblePlayRelease :app:bundlePlayRelease
```

## GitHub Actions للإصدار

ينفذ CI في كل طلب سحب اختباراتcore ووحدةAndroid للنسختين وlint وبناءdebug واختباراتالإدارة وPostgreSQL دونأسرار. وظيفةالمحاكي تفتحالشاشات الأصلية وتحفظPNG/شجرةالإتاحة/سجلcrashes؛ نجاحها اختبارفتح ومعاينة، وليس اعتمادًا لـOTP/OAuth أوكلتدفقاتالأذونات. فحصالاعتمادات اختياري للمستودع الخاص؛ فعّل `ENABLE_DEPENDENCY_REVIEW=true` عندتوفر GitHub Code Security فقط.

لا يعمل بناء APK/AAB الموقّع إلا من `main` أو `master` عندما يكون الفرع محمياً، وعبر بيئة GitHub المسماة `production`. أنشئ هذه البيئة واضبط لها المراجعين المطلوبين قبل تفعيل النشر. أضف أسرار المستودع التالية في `Settings → Secrets and variables → Actions` من دون تدوير أي مفتاح قائم ضمن هذه العملية:

| سر GitHub | المتغير الذي يستهلكه البناء |
|---|---|
| `SUPABASE_URL` | `SUPABASE_URL` |
| `SUPABASE_ANON_KEY` | `SUPABASE_ANON_KEY` |
| `GOOGLE_WEB_CLIENT_ID` | `GOOGLE_WEB_CLIENT_ID`، قيمةتهيئة لا سرOAuth. |
| `KEYSTORE_PASSWORD` | `SABBAQ_KEYSTORE_PASSWORD` |
| `KEY_ALIAS` | `SABBAQ_KEY_ALIAS` |
| `KEY_PASSWORD` | `SABBAQ_KEY_PASSWORD` |
| `KEYSTORE_BASE64` | يفك إلى keystore مؤقت بصلاحية `0600` داخل runner فقط. |

لإنشاء قيمة `KEYSTORE_BASE64` من ملف جديد، استخدم `base64 -w0 upload.jks` في Linux أو `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks"))` في PowerShell. لا تسجل الناتج في terminal history ولا تضفه إلى ملف.

## سلامة أدوات البناء والاعتمادات

يحوي `gradle-wrapper.properties` بصمة SHA-256 الرسمية لتوزيع Gradle 8.13، ويتحقق CI من ملف Wrapper قبل التنفيذ. المستودعات المسموحة للمكتبات محصورة في Google وMaven Central، ومراجع GitHub Actions في CI مثبتة إلى SHA غير قابل للتغيير.

لا توجد بعدُ `gradle/verification-metadata.xml` مُولدة ومراجعة؛ لذلك لا تدّعِ أن كل ملفات Maven العابرة محققة بالـ checksum. عند اعتماد أو ترقية مكتبات، ولّد metadata في بيئة موثوقة بعد مراجعة تغيّر الاعتمادات ثم راجعها وأضفها للمستودع:

```bash
./gradlew --write-verification-metadata sha256 :app:assembleDirectDebug :app:assemblePlayDebug
git add gradle/verification-metadata.xml
```

بعد إضافة الملف، يبقى وضع Gradle الافتراضي الصارم مناسباً، ويجب أن يفشل CI عند وصول artifact غير موجود في metadata بدلاً من قبول بصمة جديدة تلقائياً.

## سياسة الاشتراك والإتاحة

القيمةالافتراضية18.75ريال/30يومًا مرتبطةمرجعيًا بـ5دولارات عندربط3.75، وليستسعرصرفحيًا أو تصريحًابشمولالضريبة. تُحفظبالهللات integer1875 وتُعدلإداريًا معمدةالاشتراك وإعدادالتجربة ذريًا. التجربةالافتراضية3أيام تبدأبعدفعلواضح؛ تعطيلها يوقفمنحهاوترخيصها عندإعادةالفحصدونوقف المدفوع. تعديلالأيام يطبّقعلىالتجاربالجديدة، ولايحذفledgerمطالباتقديمة.

يجب أن تكون الروابطالعلنية للخصوصية وطلبحذفالحساب والجهةالمسؤولةعنهما صحيحةقبلنشرPlay؛ لا تستخدممثالًا أو رابطًاوهميًا. أتمِم نموذجالإتاحة والإفصاح المستقل والخدمةالأمامية وDataSafety ومراجعةSDKs/الفيديو/اختباراتالجهاز. لايُسجلالتطبيق أداةإعاقة بالادعاء ولايُحوَّل إلىوكيلذاتيالقرارات؛ سبّاقيتبعقواعدحتميةيحددها المستخدم.

## ملفات ممنوعة من Git

يتجاهل `.gitignore` مفاتيح التوقيع الشائعة وملفات خصائص التوقيع و`local.properties` وملفات `.env` و`admin/config.js`. راجع أيضاً `git status --ignored` قبل أي commit وتأكد من أن قيمة حقيقية لم تُدرج في ملف متعقب.

راجع [قائمة الاعتماد الإنتاجي واختبارات القبول](PRODUCTION_READINESS.md) قبل أي إطلاق. بناء release لا يثبت موافقة Google Play أو نجاح التشغيل على الأجهزة والتكامل الحي.

## ربط الإنتاج الفعلي — 19 أكتوبر 2026

المشروع: `rxioktlmecdpuyokyatl`، والعنوان `https://rxioktlmecdpuyokyatl.supabase.co`. إعدادات العميل العامة في `config/public-backend.properties` ومفاتيح الإدارة غير مضمنة. وجد المخطط الأول موجودًا بلا سجل migrations؛ لذلك طُبقت 0002 و0003 و0004 معًا كترحيل `sabbaq_production_hardening_and_saudi_identity_20261019`، ولم تُعد0001 أوتُحذفبيانات. تحققنا من `public_offer` HTTP200 بسياسة3 أيام/1875هللة/30يومًا ومنحجبdrivers وcommercial_policyعنanonHTTP401. بقيusers2 وadmins1 وdrivers0 وcodes0 قبلوبعد.

**تحديث 20 أكتوبر 2026:** Google منشور للإنتاج (عميل Android لـ `app.sabbaq.driver` بشهادة الإصدار)، وPhone مفعّل عبر Twilio Verify، وترحيل `0005` مطبّق. حساب Twilio تجريبي حتى يرقّيه المالك. حالة 19 أكتوبر للسجل: Google وPhone كانا معطّلين فيAuth. يلزم مالك المشروعإعداد OAuth (WebclientID/secretوشهادةAndroid) ومزودSMSحقيقيثم الاختبار. لايكفيpublickeyلتهيئةهذهالمفاتيح، ولايوجدنجاحدخولحيأوإرسالSMSفيهذهالنتيجة.
