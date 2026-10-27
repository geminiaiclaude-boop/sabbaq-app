import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const sourceAdmin = join(repo, 'admin');
const bundledAdmin = join(repo, 'app', 'src', 'direct', 'assets', 'admin');
const read = (base, name) => readFileSync(join(base, name), 'utf8');

function extractFunction(source, name, nextName) {
    const start = source.indexOf(`function ${name}(`);
    const end = source.indexOf(`function ${nextName}(`, start);
    assert.ok(start >= 0 && end > start, `تعذر استخراج الدالة ${name}`);
    // The next function may be `async function …`; its `async` keyword must not be left behind.
    const body = source.slice(start, end).replace(/\basync\s*$/, '');
    return new Function(`${body}; return ${name};`)();
}

test('كل عنصر تطلبه اللوحة موجود في الصفحة، فلا ينهار فتحها بعد الدخول', () => {
    const html = read(sourceAdmin, 'index.html');
    const app = read(sourceAdmin, 'app.js');
    const ids = [...app.matchAll(/getElementById\('([^']+)'\)/g)].map((match) => match[1]);
    assert.ok(ids.length > 50, 'تعذر استخراج معرّفات عناصر اللوحة');
    const missing = ids.filter((id) => !new RegExp(`\\sid="${id}"`).test(html));
    assert.deepEqual(missing, [], `عناصر مفقودة من index.html: ${missing.join(', ')}`);
    // Every tab panel is its own section, so showing one tab never reaches a null panel.
    for (const panel of ['drivers', 'codes', 'commercial-policy', 'telemetry', 'profiles', 'notifications', 'support']) {
        assert.match(html, new RegExp(`<section id="tab-${panel}" class="tab-content" role="tabpanel" aria-labelledby="${panel}-tab"`));
    }
});

test('لوحة الويب للحاسوب فقط: لا نسخة مضمّنة في التطبيق ولا جسر جلسة أصلية', () => {
    const app = read(sourceAdmin, 'app.js');
    assert.equal(existsSync(bundledAdmin), false, 'التطبيق يستعمل الإدارة الأصلية؛ لا تُضمَّن لوحة الويب فيه');
    assert.doesNotMatch(app, /SABBAQ_ACCEPT_NATIVE_SESSION|nativeAccessToken|sabbaq\.local/);
    assert.match(app, /autoRefreshToken: true/);
    assert.doesNotMatch(app, /auth\.setSession\(|refresh_token/);
});

test('صفحة الإدارة تمنع التنفيذ المضمن وتعلن سياسة CSP مقيدة', () => {
    const html = read(sourceAdmin, 'index.html');
    assert.match(html, /Content-Security-Policy/);
    assert.match(html, /default-src 'self'/);
    assert.match(html, /script-src 'self' https:\/\/cdn\.jsdelivr\.net/);
    assert.match(html, /style-src 'self'/);
    assert.match(html, /connect-src https:\/\/\*\.supabase\.co/);
    assert.match(html, /base-uri 'none'/);
    assert.match(html, /object-src 'none'/);
    assert.match(html, /referrer" content="no-referrer/);
    assert.match(html, /integrity="sha384-/);
    assert.doesNotMatch(html, /<style\b/i);
    assert.doesNotMatch(html, /\sstyle\s*=/i);
    assert.doesNotMatch(html, /\son[a-z]+\s*=/i);
    assert.doesNotMatch(html, /<script(?![^>]*\bsrc=)/i);
});

test('منطق الإدارة يعرض بيانات الخدمة كنص ولا يسرب أخطاء أو يعتمد جلسة دائمة', () => {
    const app = read(sourceAdmin, 'app.js');
    assert.match(app, /persistSession:\s*false/);
    assert.match(app, /detectSessionInUrl:\s*false/);
    assert.match(app, /SESSION_IDLE_MS/);
    assert.match(app, /auth\.getUser\(\)/);
    assert.match(app, /verifyAdminSession/);
    assert.match(app, /db\.rpc\('admin_session_status'\)/);
    assert.match(app, /status\.is_admin === true/);
    assert.doesNotMatch(app, /admin_generate_codes', \{ p_days: 1, p_count: 0, p_batch: null \}/);
    assert.match(app, /textContent\s*=/);
    assert.match(app, /replaceChildren\(\)/);
    assert.doesNotMatch(app, /innerHTML/);
    assert.doesNotMatch(app, /error\.message/);
    assert.doesNotMatch(app, /localStorage|sessionStorage/);
    assert.doesNotMatch(app, /\.select\('\*'\)/);
});

test('التحقق والتصدير محميان من قيم غير صالحة وحقن CSV والتصدير الناقص', () => {
    const app = read(sourceAdmin, 'app.js');
    assert.match(app, /ALLOWED_CODE_DAYS/);
    assert.match(app, /count < 1 \|\| count > 500/);
    assert.match(app, /MAX_BATCH_LENGTH/);
    assert.match(app, /UUID_PATTERN/);
    assert.match(app, /MAX_EXPORT_ROWS/);
    assert.match(app, /count: 'exact'/);
    assert.match(app, /rows\.length !== count/);
    assert.match(app, /toCsvField/);
    assert.match(app, /\[=\+\\-@\]/);
    assert.match(app, /URL\.revokeObjectURL/);
});

test('سياسة العرض التجارية تستخدم RPC ذريًا وحقولها الفعلية ولا تعود إلى trial_days القديم', () => {
    const html = read(sourceAdmin, 'index.html');
    const app = read(sourceAdmin, 'app.js');

    for (const id of [
        'commercial-policy-tab', 'commercial-policy-form', 'trial-enabled', 'trial-days',
        'subscription-price-sar', 'subscription-days', 'purchase-contact-url',
        'save-commercial-policy', 'retry-commercial-policy'
    ]) {
        assert.match(html, new RegExp(`id="${id}"`), `الحقل ${id} مفقود من لوحة السياسة`);
    }

    assert.match(app, /db\.rpc\('admin_commercial_policy'\)/);
    assert.match(app, /db\.rpc\('admin_set_commercial_policy', \{\s*\n\s*p_trial_enabled: values\.trialEnabled,\s*\n\s*p_trial_days: values\.trialDays,\s*\n\s*p_price_minor: values\.priceMinor,\s*\n\s*p_subscription_days: values\.subscriptionDays,\s*\n\s*p_purchase_contact_url: values\.purchaseContactUrl/s);
    assert.doesNotMatch(app, /admin_set_config/);
    assert.match(app, /function parseSarToMinor\(/);
    assert.match(app, /HALALAS_PER_SAR/);
    assert.match(app, /Number\.isSafeInteger/);
    assert.doesNotMatch(app, /parseFloat\(|\.toFixed\(/);
    assert.match(app, /function isValidHttpsContact\(/);
});

test('سياسة العرض لا تُعرض أو تُحفظ قبل تحقق المشرف وتتعطل عند فشل التأكيد', () => {
    const app = read(sourceAdmin, 'app.js');

    assert.match(app, /adminAuthorized: false/);
    assert.match(app, /state\.adminAuthorized = true/);
    assert.match(app, /if \(!state\.adminAuthorized\)/);
    assert.match(app, /await loadCommercialPolicy\(\{ announce: false \}\)/);
    assert.match(app, /if \(!state\.commercialPolicyLoaded \|\| !state\.commercialPolicy\)/);
    assert.match(app, /handleCommercialPolicyFailure\(error, 'لم نتأكد من حفظ السياسة/);
    assert.match(app, /invalidateCommercialPolicy\(fallbackMessage\)/);
    assert.match(app, /setPolicyFieldError\(/);
    assert.match(app, /retryCommercialPolicy/);
});

test('التحقق من إعداد Supabase وتحييد CSV يعملان بالقيم الحدّية', () => {
    const app = read(sourceAdmin, 'app.js');
    const isValidConfiguration = extractFunction(app, 'isValidConfiguration', 'bindEvents');
    const toCsvField = extractFunction(app, 'toCsvField', 'downloadCsv');

    assert.equal(isValidConfiguration({
        supabaseUrl: 'https://example-project.supabase.co',
        supabaseAnonKey: 'a'.repeat(20)
    }), true);
    assert.equal(isValidConfiguration({
        supabaseUrl: 'http://example-project.supabase.co',
        supabaseAnonKey: 'a'.repeat(20)
    }), false);
    assert.equal(isValidConfiguration({
        supabaseUrl: 'https://example-project.supabase.co?redirect=https://attacker.test',
        supabaseAnonKey: 'a'.repeat(20)
    }), false);
    assert.equal(isValidConfiguration({
        supabaseUrl: 'https://attacker.test',
        supabaseAnonKey: 'a'.repeat(20)
    }), false);
    assert.equal(toCsvField('=1+1'), `"'=1+1"`);
    assert.equal(toCsvField(' \t@SUM(A1:A2)'), `"' \t@SUM(A1:A2)"`);
    assert.equal(toCsvField('a"b'), '"a""b"');
});

test('تشخيص الشكوى يحدد السبب والإجراء: توجيه المستخدم أو تحديث التطبيق', () => {
    const app = read(sourceAdmin, 'app.js');
    const html = read(sourceAdmin, 'index.html');
    assert.match(app, /db\.rpc\('admin_offer_telemetry_for_driver', \{ p_driver: driver\.id \}\)/);
    assert.match(app, /db\.rpc\('admin_offer_telemetry_overview'\)/);
    assert.match(html, /id="telemetry-overview"/);
    const diagnose = extractFunction(app, 'diagnoseTelemetry', 'stageLabel');
    const row = (stage, count, reason_code = null) => ({ stage, count, reason_code });
    const beat = (reason_code, occurred_at = '2026-10-26T10:00:00Z') => ({ stage: 'HEARTBEAT', reason_code, occurred_at });

    assert.equal(diagnose([], [], false).action, 'user');
    const disabled = diagnose([row('HEARTBEAT', 3, 'a11y_disabled')], [beat('a11y_disabled')], true);
    assert.equal(disabled.action, 'user');
    assert.match(disabled.cause, /إمكانية الوصول/);
    assert.equal(diagnose([row('HEARTBEAT', 1, 'battery_optimized')], [beat('battery_optimized')], true).action, 'user');
    const unreadable = diagnose(
        [row('HEARTBEAT', 1, 'healthy'), row('PACKAGE_SEEN', 50), row('ROOT_UNAVAILABLE', 40, 'active_other')],
        [beat('healthy')], true);
    assert.equal(unreadable.action, 'update');
    assert.match(unreadable.cause, /غير قابلة للقراءة/);
    const unrecognised = diagnose([row('HEARTBEAT', 1, 'healthy'), row('PACKAGE_SEEN', 50), row('SIGNAL_MISS', 50, 'cls_none')], [beat('healthy')], true);
    assert.equal(unrecognised.action, 'update');
    const rules = diagnose([row('HEARTBEAT', 1, 'healthy'), row('PACKAGE_SEEN', 9), row('OFFER_DETECTED', 3), row('REJECTED', 3, 'fare_too_low')], [beat('healthy')], true);
    assert.equal(rules.action, 'user');
    assert.match(rules.cause, /الحد الأدنى/);
    const noDestination = diagnose([row('HEARTBEAT', 1, 'healthy'), row('PACKAGE_SEEN', 9), row('OFFER_DETECTED', 3),
        row('REJECTED', 1, 'fare_too_low'), row('REJECTED', 5, 'destination_missing')], [beat('healthy')], true);
    assert.match(noDestination.advice, /بلا وجهة/);
    const keeper = diagnose([row('HEARTBEAT', 1, 'healthy'), row('PAUSED', 1, 'keeper_restore_timeout')],
        [{ stage: 'PAUSED', reason_code: 'keeper_restore_timeout', occurred_at: '2026-10-26T11:00:00Z' }, beat('healthy')], true);
    assert.equal(keeper.action, 'user');
    assert.match(keeper.cause, /إعادة تشغيل/);
    const working = diagnose([row('HEARTBEAT', 1, 'healthy'), row('PACKAGE_SEEN', 9), row('OFFER_DETECTED', 3),
        row('DECISION_ACCEPTED', 2), row('CLICK_RESULT', 2, 'semantic'), row('CONFIRMATION_CONFIRMED', 2)], [beat('healthy')], true);
    assert.equal(working.action, 'none');
});

test('نشر ملف تطبيق يرفض المحتوى غير الصالح أو الإصدار غير الأحدث', () => {
    const app = read(sourceAdmin, 'app.js');
    const html = read(sourceAdmin, 'index.html');
    for (const id of ['profiles-tab', 'tab-profiles', 'profile-select', 'profile-body', 'profile-save']) {
        assert.match(html, new RegExp(`id="${id}"`));
    }
    assert.match(app, /db\.rpc\('admin_upsert_profile', \{ p_body: draft\.body, p_enabled: el\.profileEnabled\.checked \}\)/);
    const validate = extractFunction(app, 'validateProfileDraft', 'handleProfileSave');
    const valid = { id: 'jeeny', version: 3, packages: ['me.com.easytaxista'], acceptLabels: ['قبول'], currencyTokens: ['sar'] };
    assert.ok(validate(JSON.stringify(valid), [{ id: 'jeeny', version: 2 }]).body);
    assert.match(validate('{bad', []).error, /JSON/);
    assert.match(validate(JSON.stringify({ ...valid, version: 2 }), [{ id: 'jeeny', version: 2 }]).error, /ارفع رقم الإصدار/);
    assert.match(validate(JSON.stringify({ ...valid, acceptLabels: [] }), []).error, /acceptLabels/);
    assert.match(validate(JSON.stringify({ ...valid, id: 'Bad Id' }), []).error, /id/);
});

test('على الجوال لا تتحرك اللوحة أفقيًا: صفوف الجداول بطاقات تحمل أسماء أعمدتها', () => {
    const css = read(sourceAdmin, 'styles.css');
    const app = read(sourceAdmin, 'app.js');
    // The page itself never scrolls sideways.
    assert.match(css, /html,\s*body\s*{[^}]*overflow-x:\s*hidden/);
    // Below phone width the tables stop being wide grids: no 780px minimum, rows as cards.
    const phone = css.slice(css.indexOf('@media (max-width: 700px)'));
    assert.ok(phone.length > 0, 'phone layout block missing');
    assert.match(phone, /table,\s*tbody,\s*tr,\s*td\s*{[^}]*display:\s*block[^}]*min-width:\s*0/);
    assert.match(phone, /content:\s*attr\(data-label\)/);
    // Every rendered table labels its cells for the card layout.
    for (const render of ['renderDrivers', 'renderCodes']) {
        const start = app.indexOf(`function ${render}(`);
        const body = app.slice(start, app.indexOf('\n    }\n', start));
        assert.match(body, /labelCells\(/, `${render} must label its cells`);
    }
    const labelCells = extractFunction(app, 'labelCells', 'appendEmptyRow');
    const th = (text) => ({ textContent: ` ${text} ` });
    const cells = [{ dataset: {} }, { dataset: {} }];
    labelCells({
        closest: () => ({ querySelectorAll: () => [th('الاسم'), th('الهاتف')] }),
        rows: [{ cells }]
    });
    assert.deepEqual(cells.map((cell) => cell.dataset.label), ['الاسم', 'الهاتف']);
    // Tabs wrap instead of scrolling sideways.
    assert.doesNotMatch(css.slice(css.indexOf('/* ── Phone layout')), /overflow-x:\s*auto/);
});

test('بث الإشعارات: إرسال لكل السائقين أو لسائق واحد بتأكيد صريح، وعرض وحذف المرسل', () => {
    const app = read(sourceAdmin, 'app.js');
    const html = read(sourceAdmin, 'index.html');
    assert.match(html, /id="notifications-tab"[^>]*aria-controls="tab-notifications"/);
    assert.match(html, /id="notification-title" maxlength="80"/);
    assert.match(html, /id="notification-body" maxlength="500"/);
    assert.match(app, /db\.rpc\('admin_send_notification', \{\s*p_driver: target \? target\.id : null/);
    assert.match(app, /db\.rpc\('admin_list_notifications', \{ p_limit: 100 \}\)/);
    assert.match(app, /db\.rpc\('admin_delete_notification', \{ p_id: id \}\)/);
    // A broadcast reaches every driver: it must never go out without a confirmation.
    assert.match(app, /if \(!target && !window\.confirm\(/);
    assert.match(app, /notify\.textContent = 'إرسال إشعار'/);
    for (const action of ['open_account', 'open_setup', 'open_filters', 'update_app']) {
        assert.match(html, new RegExp(`value="${action}"`));
    }
});

test('صندوق الدعم: قائمة المحادثات والرد بمعرّف ثابت لا يتكرر، وربط بالتشخيص والسائقين', () => {
    const app = read(sourceAdmin, 'app.js');
    const html = read(sourceAdmin, 'index.html');
    assert.match(html, /id="support-tab"[^>]*aria-controls="tab-support"/);
    assert.match(html, /id="support-messages"[^>]*role="log"/);
    assert.match(html, /id="support-reply" maxlength="1000"/);
    assert.match(html, /value="open_support"/);
    assert.match(app, /db\.rpc\('admin_support_threads', \{ p_status: filter === 'all' \? null : filter, p_limit: 200 \}\)/);
    assert.match(app, /db\.rpc\('admin_support_thread', \{ p_driver: driverId, p_limit: 200 \}\)/);
    assert.match(app, /db\.rpc\('admin_support_reply', \{ p_driver: driverId, p_client_id: state\.supportPendingReply, p_body: body \}\)/);
    assert.match(app, /db\.rpc\('admin_support_set_status', \{ p_driver: driverId, p_status: next \}\)/);
    // A retried reply reuses its client id until the server confirms it.
    assert.match(app, /if \(!state\.supportPendingReply\) state\.supportPendingReply = newClientId\(\);/);
    // Polling stops with the session and never runs while the page is hidden.
    assert.match(app, /stopSupportPolling\(\);\s*state\.adminAuthorized = false;/);
    assert.match(app, /if \(document\.hidden \|\| el\.dashboard\.hidden\) return;/);
    assert.match(app, /message\.textContent = 'مراسلة'/);
    const newClientId = extractFunction(app, 'newClientId', 'setSupportFeedback');
    assert.match(newClientId(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

test('جدول السائقين يعرض إصدار سبّاق المثبت لكل سائق', () => {
    const app = read(sourceAdmin, 'app.js');
    const html = read(sourceAdmin, 'index.html');
    assert.match(html, /<th scope="col">إصدار سبّاق<\/th>/);
    assert.match(app, /db\.rpc\('admin_driver_versions', \{ p_drivers: valid \}\)/);
    const describe = extractFunction(app, 'describeDriverVersion', 'androidRelease');
    const android = extractFunction(app, 'androidRelease', 'resetDevice');
    globalThis.androidRelease = android;
    globalThis.formatDateTime = () => 'الآن';
    assert.equal(describe({ version_name: '2.1.0', version_code: 9, android_api: 34 }), '2.1.0 (9) · أندرويد 14');
    assert.equal(describe({ version_code: 7 }), 'رمز 7');
    assert.equal(describe(undefined), 'غير معروف');
    assert.equal(describe({ version_name: '<b>x</b>' }), 'لم يتصل بعد');
});
