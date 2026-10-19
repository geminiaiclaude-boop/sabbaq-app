# تحقق إعادة التصميم — 15 أكتوبر 2026

نُفذت إعادة التصميم داخل تطبيق Android/Jetpack Compose نفسه، مع تحديث لوحة الإدارة المضمنة، وليس إنشاء صور بديلة أو تطبيق ويب يحاكيه. يرجع نظام الألوان والطباعة والمكونات إلى [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md).

## الواجهات والمنطق

أصبحت لوحة السائق أربع وجهات: لوحة القيادة، المرشحات، معاينة القرار، والحساب. شريط تنقل سفلي على الهاتف، وNavigationRail من عرض700dp. ضبطنا حد عرض المصادقة620dp ومحتوى السائق900dp، مع تباعد مرن وحواف آمنة وRTL وأيقونات وظيفية موصوفة.

المظهر يدعم الفاتح والداكن و«حسب النظام». التنقل يبقى عبر دوران Activity بواسطة ViewModel، ولا يعيد صلاحية دخول بعد موت العملية. لا تخزن كلمات المرور أو tokens أو كود التفعيل في حالة الاسترجاع. مسودة المرشحات فقط قابلة لاسترجاع حالة الدوران لأنها إعداد غير حساس.

يُتحقق من المسودة كاملة، ثم تُحفظ Filters دفعة واحدة على Dispatchers.IO ويُنتظر commit قبل إعلان النجاح. تعديل المرشحات يوقف الالتقاط الجاري، والتشغيل يتطلب مرشحات محفوظة وموافقة جديدة وإعادة تحقق الترخيص. المدخلات العربية والفارسية والحدود المتناقضة و24:00 والقيم خارج Int موثقة باختبارات. المعاينة المحلية تستخدم OfferEvaluator الحقيقي دون إيماءات أو شبكة أو إحصاءات رحلة مختلقة.

## النتائج المنفذة

`./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lint` انتهى بنجاح على آخر حالة للشيفرة. core: **39 اختبارًا**؛ app: **31 اختبارًا**؛ بلا فشل أو أخطاء أو تخطٍ. تشمل الإضافات8 اختبارات FilterDraft و3 اختبارات مدخلات/استرجاع و2 اختباري تباين للوضعين.

نجحت اختبارات الإدارة Node الخمسة. اختُبر عرض نموذج الدخول في Chromium عند375px و768px و1280px، مع الوضع الداكن للمقاس الأخير. لا تجاوز أفقي، زر الدخول48px، حقلا الدخول موجودان، ولا pageerror. هذه اختبارات عرض دخول فقط، وليست اختبارات العمليات الإدارية مع بيانات حية. إعداد config الوهمي أُزيل بعد الاختبار.

نجح `assembleRelease` و`bundleRelease` مع R8 وتقليص الموارد بمفتاح اختبار مؤقت وإعدادات Supabase وهمية فقط. نجح apksigner لتوقيع APK. فُحص البيان النهائي وتأكد غياب `DesignPreviewActivity` من release. أُزيل مفتاح الاختبار، ولم يُستخدم مفتاح الإنتاج ولم يحدث نشر.

Android Lint ناجح بلا أخطاء، مع **11 تحذيرًا معلنًا**:2 توصيات ترتيب Modifier، و1 HardwareIds لربط الترخيص، و3 شروط API زائدة، و5 توصيات KTX. توجد أيضًا تحذيرات deprecated لأيقونة Login واستدعاء recycle للأنظمة الأقدم. لا baseline لإخفاء النتائج ولا تعطيل lint. عولجت أخطاء NewApi الخاصة بـwindowLightNavigationBar على API26، مع fallback scrim في شريط النظام القديم.

## الفحص البصري الأصلي غير المكتمل

نُزّل وشُغّل محاكي Android35 x86_64 بتصيير برمجي دون KVM. لم يصل إلى sys.boot_completed خلال مهلة الجاهزية. رغم ظهور خدمة Package Manager، فشل تثبيت التطبيق داخل النظام غير المكتمل بـNullPointerException من StorageManagerService بسبب PackageManagerInternal غير جاهز. لا تثبت هذه النتيجة وجود عيب في APK، لكنها **لا تثبت نجاح تثبيته أو عرضه على Android أيضًا**. لم تُنتج لقطات Android وهمية ولم تُوصَف صور لوحة الويب بأنها لقطات للتطبيق الأصلي.

يلزم تشغيل APK على جهاز أو محاكي مكتمل قبل اعتماد المظهر: الهاتف الصغير، Landscape، التابلت، font scale1.3–2.0، IME، TalkBack، RTL، التنقل بعد الحفظ أو الإلغاء، تغير الإذن والترخيص، وموت العملية/الدوران. تباين أزواج الألوان الأساسية مختبر ≥4.5:1، لكنه ليس شهادة إتاحة لكل تركيب أو شاشة.

## كيفية معاينة الواجهات للمطور

نشاط المعاينة موجود في source set debug فقط، منفصل عن MainActivity ولا يمنح مصادقة أو ترخيصًا ولا يشغّل خدمة. يعرض نفس composables وحالات المتطلبات الفعلية؛ لا يسجّل حسابًا ولا يولّد بيانات تشغيل.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.qannas.driver.debug/app.qannas.ui.DesignPreviewActivity --es screen login
adb shell am start -n app.qannas.driver.debug/app.qannas.ui.DesignPreviewActivity --es screen dashboard
adb shell am start -n app.qannas.driver.debug/app.qannas.ui.DesignPreviewActivity --es screen filters
adb shell am start -n app.qannas.driver.debug/app.qannas.ui.DesignPreviewActivity --es screen simulator
adb shell am start -n app.qannas.driver.debug/app.qannas.ui.DesignPreviewActivity --es screen account --ez dark true
```

نسخة debug المرفقة بلا عنوان Supabase أو مفتاحها الحقيقي؛ الدخول الحي يتطلب بناء مهيأ. ما زالت بوابات [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) والتوقيع والخصوصية وشروط منصات الرحلات والمتجر مطلوبة. المظهر الاحترافي لا يثبت تفوقًا تجاريًا أو جاهزية إنتاجية شاملة.
