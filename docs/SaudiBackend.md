# عقد الخلفية السعودي

الترحيل المضاف هو `supabase/migrations/0004_saudi_commercial_identity.sql`. هذا ملف **ترحيل فقط**؛ لا ينفذ تلقائياً على أي مشروع Supabase حي، ولا تتضمن المستودعات أو الاختبارات بيانات إنتاج أو SMS أو Google sign-in حقيقي.

## الهوية وملف السائق

`ensure_driver_profile(p_display_name text default null) -> jsonb` هو المسار الجديد لإنشاء ملف السائق. يتطلب جلسة `authenticated` ويعيد:

```json
{"ok": true, "created": true}
```

`created=false` يعني أن الملف كان موجوداً؛ لا يغير ذلك `status` ولا مدد الترخيص. ينشئ السجل الجديد بالحالة `pending` فقط. لا يقوم أي RPC إداري بمنح تجربة تلقائياً عند تغيير الحالة.

تُقبل الهوية عندما يتحقق أحد الشرطين داخل `auth.users`:

- `raw_app_meta_data.provider = "google"` أو تحتوي `raw_app_meta_data.providers` على `"google"`.
- `phone_confirmed_at` غير فارغ و`phone` يطابق `+9665XXXXXXXX` حرفياً (`^\+9665[0-9]{8}$`).

هذا يتعمد **عدم** قراءة `raw_user_meta_data` للمزود أو الدور أو السعر أو التجربة. يمكن استخدام `full_name` أو `name` أو `given_name` منه كاسم عرض شخصي فقط، بعد حد 2–80 حرفاً. تقرر وثائق Supabase أن `app_metadata` يحمل `provider/providers`، وأن `user_metadata` قابل لتعديل المستخدم ولا يصلح لمنطق التفويض [1].

عمودا `public.drivers.full_name` و`public.drivers.phone` أصبحا nullable. لذلك يبقى ملف Google بلا هاتف (`null`) ولا يُنشأ رقم وهمي. ملف الهاتف ينسخ فقط الرقم السعودي المؤكد من `auth.users.phone`. بيانات السائق القديمة لا يعاد تطبيعها أو حذفها.

يحافظ `register_driver(p_full_name, p_phone)` على توقيعه للتوافق، لكنه يستدعي `ensure_driver_profile(p_full_name)` ويتجاهل `p_phone`. لذلك لا يستطيع عميل قديم كتابة هاتف غير مؤكد. أُزيل trigger `0003` الذي كان ينشئ ملفاً من `raw_user_meta_data`.

يوجد trigger على `auth.users` يرفض hash كلمة مرور جديداً برسالة `password_signup_disabled` وSQLSTATE `22023`، سواء كتب عند `INSERT` أو في انتقال hash فارغ إلى غير فارغ. لا يحذف الحسابات القديمة ذات كلمات المرور ولا يغير `pro_until` أو اشتراكات activation codes. يظل تعطيل مزود البريد/كلمة المرور في Auth إعداداً تشغيلياً مطلوباً؛ البريد وكلمة المرور مفعّلان افتراضياً في Supabase [7]. لا يجب تعطيل خيار عام يمنع كل المستخدمين الجدد إذا كان المطلوب استمرار Google/phone؛ يجب ضبط مزودي Auth وتدفقات العميل معاً.

## سياسة العرض التجارية

جدول `public.commercial_policy` هو singleton محمي بـRLS ولا تمنح له أي صلاحية جدول إلى `anon` أو `authenticated`. القيم الافتراضية هي:

| الحقل | القيمة |
|---|---:|
| `trial_enabled` | `true` |
| `trial_days` | `3` |
| `price_minor` | `1875` هللة |
| `currency` | `SAR` |
| `subscription_days` | `30` |
| `purchase_contact_url` | `null` |

`public_offer() -> jsonb` متاح بلا جلسة إلى `anon` و`authenticated` فقط، ويعيد دائماً المفاتيح التالية:

```json
{
  "trial_enabled": true,
  "trial_days": 3,
  "price_minor": 1875,
  "currency": "SAR",
  "subscription_days": 30,
  "purchase_contact_url": null
}
```

هذه قيمة عرض فقط؛ `price_minor` لا يمنح entitlement ولا يمثل سعراً من Play Billing ولا يشمل VAT.

`admin_commercial_policy() -> jsonb` يتطلب `require_admin()` ويعيد نفس الحقول مع `updated_at`. و`admin_set_commercial_policy(p_trial_enabled boolean, p_trial_days integer, p_price_minor integer, p_subscription_days integer, p_purchase_contact_url text default null) -> jsonb` يتطلب المشرف ويغير كل الحقول في UPSERT واحد مع سجل audit باسم `set_commercial_policy`.

الحدود ثابتة: أيام التجربة والاشتراك 1–365، السعر 1–100000000 هللة، العملة `SAR` فقط، والرابط إما `null` أو HTTPS بلا مسافات وبحد 2048 بايت. يعيد الإدخال غير الصالح SQLSTATE `22023`. مفتاح `app_config.trial_days` متقاعد: `admin_set_config('trial_days', ...)` يفشل صراحة بـ`commercial_policy_required` ولا يترك لوحة الإدارة القديمة تحفظ قيمة يتجاهلها الترخيص.

## التجربة والترخيص

`start_trial(p_device_id text, p_version_code integer) -> jsonb` يتطلب جلسة وهوية مؤهلة، `p_device_id` بعد trim بطول 16–256 بايت، و`p_version_code >= 1`.

- لا ينشئ ملفاً؛ يجب استدعاء `ensure_driver_profile` أولاً.
- يرفض الحساب `rejected` أو `banned`، جهازاً مختلفاً، أو فترة `pro_until` المدفوعة النشطة.
- يقفل صف الملف والسياسة داخل transaction، ثم يسجل claim واحداً في `trial_claims(account_id primary key, ...)` ويكتب `trial_until = now() + trial_days` من ساعة الخادم.
- يسجل `trial_device_claims(reported_device_id primary key, ...)` لمنع أفضل-جهد فقط من أن يطلب حساب ثانٍ تجربة على device ID المبلغ عنه. هذا **ليس attestation** ولا ادعاء منع حتمي لانتحال الجهاز.
- إعادة نفس الطلب على نفس الحساب تعيد `{"ok":true,"created":false,"trial_until":"..."}` ولا تمدد التاريخ. `ok` إقرار idempotent وليس lease؛ يجب بعدها دائماً استدعاء `license_status`.
- إذا كانت السياسة معطلة، يعيد `{"ok":false,"created":false,"error":"trial_disabled"}` ولا ينشئ claim.

يسجل الترحيل claim تاريخي لكل `drivers.trial_until` غير null قبل 0004، كي لا تصبح تجربة سابقة مؤهلة من جديد. يحفظ القيمة والتراخيص القديمة كما هي.

`license_status(p_device_id text, p_version_code integer default null) -> jsonb` يبقي الحقول القديمة `state`, `valid_until`, `server_time`, `grace_hours`, `min_version_code`, `full_name`, `announcement` ويضيف:

| الحقل | النوع | المعنى الآمن |
|---|---|---|
| `trial_until` | RFC3339 أو `null` | التاريخ المخزن؛ قد يكون مستقبلياً حتى عندما تكون التجربة معطلة |
| `pro_until` | RFC3339 أو `null` | نهاية الاشتراك/الكود المدفوع المخزنة |
| `trial_eligible` | boolean | هوية Google/هاتف سعودي مؤكد، ليست banned/rejected، ولم تسجل claim حساب بعد |
| `trial_enabled` | boolean | السياسة العالمية الحالية |
| `trial_days` | integer | أيام claims الجديدة الحالية |

`state` واحد من `unregistered`, `pending`, `rejected`, `banned`, `device_mismatch`, `expired`, `trial`, `active`. لا تكون `trial` إلا عند `trial_until > server_time` والسياسة مفعلة. لا تكون `active` إلا لفترة `pro_until` مدفوعة نشطة. عند تعطيل التجربة، لا يمسح RPC أي تاريخ، لكنه يعيد الحساب التجريبي إلى `expired` و`valid_until: null`. لا يتأثر `pro_until` المدفوع: يبقى `state: active` و`valid_until = pro_until`.

لهذا لا يجوز للعميل تحويل `trial_until` إلى lease محلي. يعتمد lease فقط على `state` الصحيح و`valid_until` و`server_time` من RPC ناجح. سياسة RLS لإدخال `calibration_captures` تتطلب entitlement مدفوعاً نشطاً أو تجربة نشطة عالمياً، وتمنع pending وحدها أو تجربة معطلة.

## الحماية والتدقيق

كل الدوال الجديدة ذات صلاحية عالية هي `SECURITY DEFINER` مع `search_path = pg_catalog, public` ثابت وعلاقات مؤهلة schema. توصي Supabase بتحديد `search_path` لكل SECURITY DEFINER وبسحب EXECUTE الافتراضي ثم منح الدوال المحددة فقط [2]. جرى ذلك هنا:

- `public_offer` فقط: `anon`, `authenticated`.
- `ensure_driver_profile`, `register_driver`, `start_trial`, `license_status` ودوال الإدارة: `authenticated` فقط؛ دوال الإدارة تستدعي `require_admin()` داخلها.
- جداول policy وclaim لا صلاحية مباشرة لها إلى أدوار العميل، وكلها RLS مفعّل.
- helper الخاص بالهوية وtrigger كلمة المرور لا EXECUTE لهما إلى العميل. `current_trial_enabled()` هو helper غير حساس متاح لـ`authenticated` كي تعمل سياسة RLS؛ نفس العلم ظاهر أصلاً في `public_offer` و`license_status`.

تسجل العمليات الناجحة `ensure_driver_profile` و`device_bound` و`start_trial` و`set_commercial_policy` في `audit_log`. لا يسجل audit رموز OTP أو access tokens أو كلمة مرور أو Google token. ينسجم الجمع بين RLS والمنح في نفس الترحيل مع إرشاد Supabase بأن المنح تسبق فحص policy وأن كل جدول مكشوف يحتاج RLS ومنحاً محددين واختبارات allow/deny [3].

## متطلبات التشغيل قبل الإنتاج

1. ينفذ مالك Supabase الترحيلات بالترتيب في بيئة مراجعة أولاً. يتطلب trigger على `auth.users` دور ترحيل يملك صلاحية إنشاء trigger هناك؛ لم يُطبق هذا الترحيل على مشروع حي ضمن هذا العمل.
2. يفعّل Google provider ويضبط Google Cloud Client ID/secret وredirect callback من Dashboard. توثق Supabase إعداد provider والـredirects، وتدعم تبادل Google ID token مع nonce [4]. لا يكفي وضع Client ID في Android وحده.
3. يفعّل Phone Auth ويضبط مزود SMS وحدود الإرسال وCAPTCHA وسياسة امتثال السعودية. توثق Supabase أن phone OTP يحتاج مزود SMS وأن OTP ستة أرقام، مع مراعاة rate limits والتنظيم المحلي [5]. لا توجد رسالة SMS حقيقية في الاختبارات.
4. يعطل/يحجب مزود وتدفقات email/password للعميل الجديد، ويحذف أو يعزل مسار `signUp` القديم في Android بواسطة الوكيل المالك له. قاعدة البيانات تمنع hash جديداً كدفاع إضافي، لكنها ليست بديلاً عن ضبط GoTrue ومزود Auth.
5. يبقى `auth.users` مصدر الحقيقة لهوية Google/phone. لا يمنح `raw_user_meta_data` role أو provider أو trial أو entitlement.

## الاختبارات المحلية

تشغّل ملفات SQL على PostgreSQL محلي معزول فقط، لا على Supabase:

- `supabase/tests/license_test.sql` حدّث fixtures إلى هاتف سعودي مؤكد واستبدل التجربة التلقائية بـ`start_trial` الصريح.
- `supabase/tests/registration_admin_test.sql` استبدل assertions الخاصة بـmetadata trigger بـ`ensure_driver_profile` وهوية Google من app metadata.
- `supabase/tests/saudi_commercial_trial_test.sql` يغطي defaults، العرض العام، Google/phone، رفض provider spoof وكلمة المرور، سياسة admin الذرية، تقاعد `app_config.trial_days`، claim الواحد، best-effort device claim، تعطيل التجربة، اشتراك مدفوع وRLS.

**References**

[1]: https://supabase.com/docs/guides/auth/users "Supabase Auth user fields and metadata security"
[2]: https://supabase.com/docs/guides/database/functions "Supabase database function security and privileges"
[3]: https://supabase.com/docs/guides/database/postgres/row-level-security "Supabase row level security grants and policies"
[4]: https://supabase.com/docs/guides/auth/social-login/auth-google "Supabase Google sign-in provider setup"
[5]: https://supabase.com/docs/guides/auth/phone-login "Supabase phone OTP sign-in setup"
[6]: https://supabase.com/docs/guides/auth/general-configuration "Supabase Auth general configuration"
[7]: https://supabase.com/docs/guides/auth/passwords "Supabase password-based authentication configuration"

## تدقيق التكامل اللاحق

يقبل الهاتف الخادمي المؤكد بصيغة+9665… أو9665… ويُحفظ فيdrivers بصيغةE.164مع+؛ لا تُقبل قيمةNULL حتى لوphone_confirmed_atموجود. يُحجبtrial_eligibleعنpaidactive أوالجهازغيرالمطابقأوالجهازالمطالببهلغيرالحساب. بدءالتجربة الصريحيفعّلالملفالجديد؛ تعليقpendingإدارييبطلleaseوإدخالcapturesولووجدpaiddate، ولايُحذفالتاريخالمدفوع. روابطقناةالإدارةHTTPSذاتمضيفوبلادخولcredentials؛ defaultnull. أعيدتSQLsuitesمتتابعةفيقاعدةمحليةواحدةبنفسCIبعدهذهالتعديلاتونجحت.
