# إعداد مشروع Qannas

هذا الدليل يوضح خطوات إعداد قاعدة البيانات ولوحة التحكم وبيئة البناء السحابية.

## 1. إعداد قاعدة بيانات Supabase
1. قم بإنشاء مشروع جديد في منصة [Supabase](https://supabase.com).
2. افتح **SQL Editor** وقم بتنفيذ الملفات التالية بالترتيب:
   - `supabase/migrations/0001_init.sql`

## 2. إنشاء حساب المشرف (Admin)
لوحة الإدارة تتطلب صلاحيات المشرف. لتعيين حسابك كمشرف:
1. اذهب إلى **Authentication** وقم بإنشاء مستخدم جديد (بإيميل وكلمة مرور).
2. افتح **SQL Editor** وقم بتنفيذ الأمر التالي لترقية حسابك إلى مشرف:
```sql
insert into public.admins (user_id) select id from auth.users where email = 'YOUR_ADMIN_EMAIL@example.com';
```

## 3. إعداد لوحة الإدارة (Admin Panel)
لوحة الإدارة تعتمد على ملف إعدادات محلي (غير مرفوع في جيت هاب لأسباب أمنية):
1. قم بنسخ ملف `admin/config.example.js` وأعد تسميته إلى `admin/config.js`.
2. ضع فيه رابط قاعدة البيانات ومفتاح `anon key` المتاحان في: `Project Settings -> API`.

## 4. إعداد جيت هاب (GitHub Secrets) لإنتاج التطبيق
لاستخراج ملف الـ `APK` الجاهز للإنتاج (Production-Ready)، يجب عليك إعداد بيئة جيت هاب:
1. اذهب إلى `Settings -> Secrets and variables -> Actions -> New repository secret`.
2. أضف المتغيرات التالية:
   - `SUPABASE_URL`: رابط المشروع (Project URL).
   - `SUPABASE_ANON_KEY`: مفتاح الواجهة (anon key).
   - `KEYSTORE_PASSWORD`: كلمة مرور مفتاح التوقيع.
   - `KEY_ALIAS`: اسم المفتاح (مثلاً `upload`).
   - `KEY_PASSWORD`: كلمة مرور الـ Alias.
   - `KEYSTORE_BASE64`: محتوى مفتاح التوقيع **الجديد** `upload.jks` بترميز Base64 (انظر القسم 5).

## 5. مفتاح التوقيع (إلزامي قبل أي إصدار)

المفتاح القديم `keystore.jks` كان مرفوعاً في تاريخ المستودع، فهو **مكشوف ولا يُستخدم أبداً**. ولّد مفتاحاً جديداً على جهاز فيه JDK 17:

```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

ثم:

1. احفظ `upload.jks` وكلمتي سره في مكانين آمنين **خارج المستودع**. ضياعه بعد النشر على Google Play يعني استحالة تحديث التطبيق.
2. حوّله إلى Base64 وضعه في السر `KEYSTORE_BASE64`:
   - Linux/macOS/Git Bash: `base64 -w0 upload.jks`
   - PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks"))`
3. ضع `KEY_ALIAS=upload` وكلمتي السر في `KEYSTORE_PASSWORD` و`KEY_PASSWORD`.
4. لا تضع `upload.jks` ولا `local.properties` ولا `admin/config.js` داخل المستودع؛ كلها مستبعدة في `.gitignore`.
