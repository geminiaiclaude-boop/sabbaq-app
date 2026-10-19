# إعداد مشروع Qannas

يوثق هذا الدليل إعداد قاعدة البيانات ولوحة التحكم وبناء Android المحلي وCI للإصدار. لا تنسخ قيماً حقيقية للمفاتيح أو كلمات المرور إلى المستودع أو إلى سجل طرفية مشترك.

## المتطلبات والبناء المحلي

يتطلب المشروع **JDK 17** وAndroid SDK الموافق لـ `compileSdk = 36`. بعد استنساخ المستودع، امنح Gradle Wrapper صلاحية التنفيذ ثم نفّذ الفحوصات التي لا تحتاج أسراراً:

```bash
chmod +x gradlew
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lint
./gradlew --no-daemon :app:assembleDebug
```

ملف `local.properties` محلي فقط. أنشئه بصلاحيات مقيدة عند الحاجة إلى تشغيل التطبيق على خدمة حقيقية، أو مرر القيم نفسها كمتغيرات بيئة؛ لمتغيرات البيئة أسبقية على الملف:

```properties
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-KEY
```

مفتاح `anon` قيمة عميل مضمنة في التطبيق وليست بديلاً عن سياسات RLS السليمة في Supabase. لا تضع مطلقاً `service_role` أو أي مفتاح إداري في تطبيق Android أو ملف إعداد عميل.

## إعداد قاعدة بيانات Supabase

1. أنشئ مشروعاً في [Supabase](https://supabase.com).
2. افتح **SQL Editor** ونفذ ملفات `supabase/migrations/` بالترتيب المعجمي، بدءاً من `0001_init.sql`.
3. تحقق من أن سياسات RLS والأدوار موجودة قبل ربط بيئة إنتاج؛ لا يعتمد التطبيق على إخفاء مفتاح `anon` كحاجز أمني.

الترحيل `0003_registration_admin_contract.sql` مطلوب لعقد الإدارة الصريح وإنشاء ملف السائق من metadata التسجيل عند تأكيد البريد. لا يشمل الحسابات السابقة بأثر رجعي. لا تنفذ `supabase/tests/bootstrap.sql` أو fixtures على الإنتاج.

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
| `QANNAS_KEYSTORE` | المسار المطلق إلى ملف `.jks` أو `.keystore`. |
| `QANNAS_KEYSTORE_PASSWORD` | كلمة مرور ملف التوقيع. |
| `QANNAS_KEY_ALIAS` | اسم المفتاح داخل الملف. |
| `QANNAS_KEY_PASSWORD` | كلمة مرور الـ alias. |
| `QANNAS_VERSION_CODE` | اختياري؛ عدد صحيح موجب. |
| `QANNAS_VERSION_NAME` | اختياري؛ اسم إصدار غير فارغ. |

نفّذ فحصاً غير منتج قبل إنتاج أي ملف. يفشل الفحص برسائل تحدد **اسم** الإعداد الناقص من دون طباعة قيمته:

```bash
./gradlew --no-daemon :app:verifyReleaseConfiguration
./gradlew --no-daemon :app:assembleRelease :app:bundleRelease
```

## GitHub Actions للإصدار

ينفذ CI في كل طلب سحب اختبارات core ووحدة Android وlint وبناء debug واختبارات لوحة الإدارة وPostgreSQL من دون حقن أسرار. فحص مراجعة الاعتمادات اختياري لهذا المستودع الخاص؛ فعّل `ENABLE_DEPENDENCY_REVIEW=true` فقط عند توفر GitHub Code Security. عند تفعيله يرفض تبعية runtime جديدة ذات ثغرة بدرجة `high` أو أعلى.

لا يعمل بناء APK/AAB الموقّع إلا من `main` أو `master` عندما يكون الفرع محمياً، وعبر بيئة GitHub المسماة `production`. أنشئ هذه البيئة واضبط لها المراجعين المطلوبين قبل تفعيل النشر. أضف أسرار المستودع التالية في `Settings → Secrets and variables → Actions` من دون تدوير أي مفتاح قائم ضمن هذه العملية:

| سر GitHub | المتغير الذي يستهلكه البناء |
|---|---|
| `SUPABASE_URL` | `SUPABASE_URL` |
| `SUPABASE_ANON_KEY` | `SUPABASE_ANON_KEY` |
| `KEYSTORE_PASSWORD` | `QANNAS_KEYSTORE_PASSWORD` |
| `KEY_ALIAS` | `QANNAS_KEY_ALIAS` |
| `KEY_PASSWORD` | `QANNAS_KEY_PASSWORD` |
| `KEYSTORE_BASE64` | يفك إلى keystore مؤقت بصلاحية `0600` داخل runner فقط. |

لإنشاء قيمة `KEYSTORE_BASE64` من ملف جديد، استخدم `base64 -w0 upload.jks` في Linux أو `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks"))` في PowerShell. لا تسجل الناتج في terminal history ولا تضفه إلى ملف.

## سلامة أدوات البناء والاعتمادات

يحوي `gradle-wrapper.properties` بصمة SHA-256 الرسمية لتوزيع Gradle 8.13، ويتحقق CI من ملف Wrapper قبل التنفيذ. المستودعات المسموحة للمكتبات محصورة في Google وMaven Central، ومراجع GitHub Actions في CI مثبتة إلى SHA غير قابل للتغيير.

لا توجد بعدُ `gradle/verification-metadata.xml` مُولدة ومراجعة؛ لذلك لا تدّعِ أن كل ملفات Maven العابرة محققة بالـ checksum. عند اعتماد أو ترقية مكتبات، ولّد metadata في بيئة موثوقة بعد مراجعة تغيّر الاعتمادات ثم راجعها وأضفها للمستودع:

```bash
./gradlew --write-verification-metadata sha256 :app:assembleDebug
git add gradle/verification-metadata.xml
```

بعد إضافة الملف، يبقى وضع Gradle الافتراضي الصارم مناسباً، ويجب أن يفشل CI عند وصول artifact غير موجود في metadata بدلاً من قبول بصمة جديدة تلقائياً.

## ملفات ممنوعة من Git

يتجاهل `.gitignore` مفاتيح التوقيع الشائعة وملفات خصائص التوقيع و`local.properties` وملفات `.env` و`admin/config.js`. راجع أيضاً `git status --ignored` قبل أي commit وتأكد من أن قيمة حقيقية لم تُدرج في ملف متعقب.

راجع [قائمة الاعتماد الإنتاجي واختبارات القبول](PRODUCTION_READINESS.md) قبل أي إطلاق. بناء release لا يثبت موافقة Google Play أو نجاح التشغيل على الأجهزة والتكامل الحي.
