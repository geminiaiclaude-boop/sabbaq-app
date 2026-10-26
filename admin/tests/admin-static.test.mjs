import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const sourceAdmin = join(repo, 'admin');
const bundledAdmin = join(repo, 'app', 'src', 'direct', 'assets', 'admin');
const read = (base, name) => readFileSync(join(base, name), 'utf8');

const files = ['index.html', 'app.js', 'styles.css', 'config.example.js'];

function extractFunction(source, name, nextName) {
    const start = source.indexOf(`function ${name}(`);
    const end = source.indexOf(`function ${nextName}(`, start);
    assert.ok(start >= 0 && end > start, `تعذر استخراج الدالة ${name}`);
    return new Function(`${source.slice(start, end)}; return ${name};`)();
}

test('نسختا لوحة الإدارة متطابقتان', () => {
    for (const file of files) {
        assert.equal(read(sourceAdmin, file), read(bundledAdmin, file), `${file} يجب أن يتطابق بين المصدر والأصول المضمّنة`);
    }
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

test('جسر جلسة التطبيق الأصلية أحادي الاستخدام ومرتبط بـ sabbaq.local فقط', () => {
    const app = read(sourceAdmin, 'app.js');

    assert.match(app, /window\.SABBAQ_ACCEPT_NATIVE_SESSION = acceptNativeSession/);
    assert.match(app, /window\.location\.origin === 'https:\/\/sabbaq\.local'/);
    assert.match(app, /state\.nativeAccessToken = nativeAccessToken/);
    assert.match(app, /await verifyAdminSession\(\)/);
    assert.match(app, /delete window\.SABBAQ_ACCEPT_NATIVE_SESSION/);
    assert.match(app, /autoRefreshToken: nativeAccessToken === null/);
    assert.match(app, /db\.auth\.getUser\(state\.nativeAccessToken\)/);
    assert.match(app, /clearNativeAccessState\(\)/);
    assert.doesNotMatch(app, /auth\.setSession\(|refresh_token/);
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
