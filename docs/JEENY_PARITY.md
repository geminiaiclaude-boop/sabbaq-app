# Jeeny parity matrix

هذه المصفوفة تسجل أدلة المستودع فقط، ولا تدعي نجاحاً على جهاز حقيقي أو تكافؤاً ميدانياً. «منفذ» يعني عقداً deterministic مع اختبار JVM؛ «جزئي» يعني أن مسار Android موصول لكن سلوك APK/شجرة الإتاحة يحتاج جهازاً؛ «غير مثبت» يعني عدم وجود دليل كافٍ في المستودع.

| البعد | الحالة | القياس والدليل | المتبقي |
|---|---|---|---|
| package/window scoping | منفذ | `SabbaqA11yService` يقيد Jeeny بالحزمة والنافذة، و`JeenyOfferPipeline` يلغي generation عند التغيير؛ اختبارات state | تحقق device من تبديل النوافذ أثناء إعادة الرسم |
| adapter contract | منفذ | `JeenyRideAppAdapter` يعرّف package/accept-confirm families/signal config/node-confirmation strategy؛ contract tests لـ Jeeny/Bolt/Petra | تحقق device من labels/IDs الفعلية لكل APK |
| independent bounded preflight | منفذ | classifier قبل full parse؛ 128 node و4096 حرفاً كحد أقصى، وminimum 3 features؛ `ProductionGuardsTest` و`JeenyOfferPipelineTest` | corpus حي لقياس false positive/negative |
| structural change fingerprint | منفذ | SHA-256 حتمي يضم package/window/path/class/view-id وhash/length للإشارات النصية فقط؛ 96 node و2048 حرفاً؛ لا يعيد النص | قياس latency على أجهزة منخفضة الموارد |
| progressive frames | منفذ | `INCOMPLETE` لا ينتج signal ولا click، وdiagnostic `frame_incomplete` | توقيت إعادة الرسم الحقيقي |
| confirmation/active exclusion | منفذ | confirm labels وactive cues hard exclusion؛ `AcceptTracker` bounded | شاشة تأكيد Jeeny الحقيقية وتنوع اللغات |
| full fare/route parsing | منفذ | parser corpus يغطي Arabic/Indic digits/currency/meters/minutes/addresses/identity | توافق شجرة APK الحالي |
| decision explainability | منفذ | `DecisionReport` 0–100، available signals فقط، وreasons بدون fake missing values؛ لا يغير `Verdict.accept`؛ tests | مراجعة UX لعرض snapshot في UI |
| diagnostics/privacy | منفذ | ring buffer capacity 64، stages محددة، reason allow-list/redaction؛ لا نص عرض/عنوان/صورة/network | مراجعة release logcat |
| watchdog/liveness | منفذ جزئي | `LivenessState` يسجل accessibility/target/offer/decision/click ويحسب stale/paused؛ snapshot privacy-safe؛ لا background loop | ربط شاشة UI فعلية وقياس thresholds ميدانياً |
| safe click/confirmation | جزئي | node → ancestor → gesture مع ClickGate/AcceptTracker وlifecycle الحالي | dispatch/action/timeout على جهاز حقيقي |

## Counts and tests

- ملفات `core` المضافة/الموصولة: `ProductionGuards.kt`، وتعديلات `OfferEvaluator.kt` و`SabbaqA11yService.kt`.
- `JeenyOfferPipelineTest`: 7 سيناريوهات pipeline/classifier قائمة (positive, incomplete, active, hint-only, scan budget, same-offer, window reset).
- `ProductionGuardsTest`: 7 اختبارات (fingerprint determinism/context/redaction، adapter platform contract، diagnostics capacity، liveness، decision report، preflight budgets).
- تشغيل التحقق: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 && git diff --check && ./gradlew --no-daemon :core:test` — **نجح**.
- حدود preflight: 128 عقدة/4096 حرفاً للمصنف، fingerprint مستقل 96 عقدة/2048 حرفاً، late captures بحد أقصى 2، diagnostics بحد أقصى 64 entry.
- لا توجد أرقام accuracy أو parity ميدانية مدعاة.

## Real-device-only criteria

قبل أي ادعاء إنتاجي يجب تشغيل APK Jeeny مفوض على Android 8+، خصوصاً 13/14/16، مع RTL وIndic digits، العرض التدريجي، تغيّر view IDs، زر غير قابل للنقر، confirmation timeout، إعادة الرسم، window/app switch، license expiry، disclosure revoke، شجرة كبيرة، وفشل ACTION_CLICK. يجب حفظ package/API/app version والنتيجة وقياس false positives/negatives وpreflight latency، دون حفظ نصوص/عناوين/صور.
