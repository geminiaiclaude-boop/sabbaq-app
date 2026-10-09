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
   - `KEYSTORE_BASE64`: محتوى مفتاح `upload.jks` الخاص بك بعد تشفيره كـ Base64. (يمكنك استخدام السكربت المرفق `Get-Secrets.ps1` لتوليده).
