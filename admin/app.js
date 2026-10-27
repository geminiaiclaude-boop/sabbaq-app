(() => {
    'use strict';

    const PAGE_SIZE = 100;
    const MAX_EXPORT_ROWS = 1000;
    const MAX_BATCH_LENGTH = 80;
    const SESSION_IDLE_MS = 15 * 60 * 1000;
    const LOGIN_FAILURE_LIMIT = 5;
    const LOGIN_COOLDOWN_MS = 30 * 1000;
    const ALLOWED_CODE_DAYS = new Set([7, 30, 90, 365]);
    const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
    const POLICY_MIN_DAYS = 1;
    const POLICY_MAX_DAYS = 365;
    const POLICY_MIN_MINOR = 1;
    const POLICY_MAX_MINOR = 100000000;
    const HALALAS_PER_SAR = 100;
    const MAX_CONTACT_URL_BYTES = 2048;
    const ARABIC_DIGITS = '٠١٢٣٤٥٦٧٨٩';
    const PERSIAN_DIGITS = '۰۱۲۳۴۵۶۷۸۹';

    let db;
    const state = {
        activeTab: 'drivers',
        offsets: { drivers: 0, codes: 0 },
        hasMore: { drivers: false, codes: false },
        loading: { drivers: false, codes: false },
        adminAuthorized: false,
        commercialPolicy: null,
        commercialPolicyLoaded: false,
        commercialPolicyLoading: false,
        profiles: null,
        nativeAccessToken: null,
        nativeSessionAccepting: false,
        idleTimer: null,
        signingOut: false,
        loginFailures: 0,
        loginBlockedUntil: 0
    };
    const el = {};

    document.addEventListener('DOMContentLoaded', initialize);

    const WHATSAPP_PATTERN = /^[1-9]\d{7,14}$/;

    function initialize() {
        Object.assign(el, {
            status: document.getElementById('app-status'),
            loginSection: document.getElementById('login-section'),
            loginForm: document.getElementById('login-form'),
            email: document.getElementById('email'),
            password: document.getElementById('password'),
            loginButton: document.getElementById('login-button'),
            dashboard: document.getElementById('dashboard-section'),
            logoutButton: document.getElementById('logout-button'),
            driversTab: document.getElementById('drivers-tab'),
            codesTab: document.getElementById('codes-tab'),
            commercialPolicyTab: document.getElementById('commercial-policy-tab'),
            driversPanel: document.getElementById('tab-drivers'),
            codesPanel: document.getElementById('tab-codes'),
            commercialPolicyPanel: document.getElementById('tab-commercial-policy'),
            telemetryTab: document.getElementById('telemetry-tab'),
            telemetryPanel: document.getElementById('tab-telemetry'),
            telemetryForm: document.getElementById('telemetry-form'),
            telemetryCorrelation: document.getElementById('telemetry-correlation'),
            telemetrySearch: document.getElementById('telemetry-search'),
            telemetryFeedback: document.getElementById('telemetry-feedback'),
            telemetryResults: document.getElementById('telemetry-results'),
            telemetryOverview: document.getElementById('telemetry-overview'),
            profilesTab: document.getElementById('profiles-tab'),
            profilesPanel: document.getElementById('tab-profiles'),
            profileForm: document.getElementById('profile-form'),
            profileSelect: document.getElementById('profile-select'),
            profileEnabled: document.getElementById('profile-enabled'),
            profileReload: document.getElementById('profile-reload'),
            profileSave: document.getElementById('profile-save'),
            profileBody: document.getElementById('profile-body'),
            profileFeedback: document.getElementById('profile-feedback'),
            refreshDrivers: document.getElementById('refresh-drivers'),
            refreshCodes: document.getElementById('refresh-codes'),
            driversPrevious: document.getElementById('drivers-previous'),
            driversNext: document.getElementById('drivers-next'),
            codesPrevious: document.getElementById('codes-previous'),
            codesNext: document.getElementById('codes-next'),
            driversPage: document.getElementById('drivers-page'),
            codesPage: document.getElementById('codes-page'),
            driversList: document.getElementById('drivers-list'),
            codesList: document.getElementById('codes-list'),
            codeForm: document.getElementById('code-generation-form'),
            codeCount: document.getElementById('code-count'),
            codeDays: document.getElementById('code-days'),
            codeBatch: document.getElementById('code-batch'),
            generateCodes: document.getElementById('generate-codes'),
            exportCodes: document.getElementById('export-codes'),
            commercialPolicyForm: document.getElementById('commercial-policy-form'),
            trialEnabled: document.getElementById('trial-enabled'),
            trialEnabledState: document.getElementById('trial-enabled-state'),
            trialDays: document.getElementById('trial-days'),
            subscriptionPriceSar: document.getElementById('subscription-price-sar'),
            subscriptionDays: document.getElementById('subscription-days'),
            purchaseContactUrl: document.getElementById('purchase-contact-url'),
            adminWhatsapp: document.getElementById('admin-whatsapp'),
            adminWhatsappError: document.getElementById('admin-whatsapp-error'),
            trialDaysError: document.getElementById('trial-days-error'),
            subscriptionPriceError: document.getElementById('subscription-price-error'),
            subscriptionDaysError: document.getElementById('subscription-days-error'),
            purchaseContactError: document.getElementById('purchase-contact-error'),
            commercialPolicyFeedback: document.getElementById('commercial-policy-feedback'),
            policyUpdatedAt: document.getElementById('policy-updated-at'),
            saveCommercialPolicy: document.getElementById('save-commercial-policy'),
            retryCommercialPolicy: document.getElementById('retry-commercial-policy')
        });

        if (!isValidConfiguration(window.SABBAQ_CONFIG)) {
            renderFatalError('الإعداد مفقود أو غير صالح. انسخ config.example.js إلى config.js وأدخل رابط مشروع Supabase ومفتاح anon الصحيحين.');
            return;
        }
        if (!window.supabase || typeof window.supabase.createClient !== 'function') {
            renderFatalError('تعذر تحميل مكتبة الاتصال. تحقق من الشبكة وسلامة الملف الخارجي ثم أعد المحاولة.');
            return;
        }

        try {
            db = createAdminClient();
        } catch (_) {
            renderFatalError('تعذر تهيئة اتصال لوحة الإدارة. راجع إعدادات النشر دون كشف بيانات الاعتماد.');
            return;
        }

        installNativeSessionAcceptor();
        bindEvents();
        renderPagination('drivers');
        renderPagination('codes');
        void restoreInMemorySession();
    }

    function createAdminClient(nativeAccessToken = null) {
        const options = {
            auth: {
                persistSession: false,
                autoRefreshToken: nativeAccessToken === null,
                detectSessionInUrl: false
            }
        };
        if (nativeAccessToken !== null) {
            options.global = { headers: { Authorization: `Bearer ${nativeAccessToken}` } };
        }
        return window.supabase.createClient(
            window.SABBAQ_CONFIG.supabaseUrl,
            window.SABBAQ_CONFIG.supabaseAnonKey,
            options
        );
    }

    function installNativeSessionAcceptor() {
        if (isTrustedNativeBridgeOrigin()) window.SABBAQ_ACCEPT_NATIVE_SESSION = acceptNativeSession;
    }

    async function acceptNativeSession(nativeAccessToken) {
        if (state.nativeSessionAccepting || state.nativeAccessToken !== null) return false;
        state.nativeSessionAccepting = true;

        try {
            if (!isTrustedNativeBridgeOrigin() || !isValidNativeAccessToken(nativeAccessToken)) {
                throw new Error('invalid_native_token');
            }
            db = createAdminClient(nativeAccessToken);
            state.nativeAccessToken = nativeAccessToken;

            const access = await verifyAdminSession();
            if (access !== 'authorized') throw new Error('native_admin_verification_failed');

            state.adminAuthorized = true;
            state.loginFailures = 0;
            el.email.value = '';
            el.password.value = '';
            await enterDashboard();
            delete window.SABBAQ_ACCEPT_NATIVE_SESSION;
            return true;
        } catch (_) {
            clearNativeAccessState();
            el.dashboard.hidden = true;
            el.loginSection.hidden = false;
            resetCommercialPolicyUi();
            setNotice('تعذر التحقق من جلسة الإدارة الأصلية؛ استخدم دخول المشرف المصرّح به.', 'error');
            return false;
        } finally {
            state.nativeSessionAccepting = false;
        }
    }

    function isTrustedNativeBridgeOrigin() {
        return window.location.origin === 'https://sabbaq.local';
    }

    function isValidNativeAccessToken(value) {
        return typeof value === 'string'
            && value.length >= 20
            && value.length <= 8192
            && !/[\u0000-\u001F\u007F\s]/.test(value);
    }

    function clearNativeAccessState() {
        state.nativeAccessToken = null;
        state.adminAuthorized = false;
        db = createAdminClient();
    }

    function isValidConfiguration(config) {
        if (!config || typeof config.supabaseUrl !== 'string' || typeof config.supabaseAnonKey !== 'string') return false;
        if (config.supabaseUrl.includes('YOUR-PROJECT') || config.supabaseAnonKey.includes('YOUR-ANON-KEY')) return false;
        if (config.supabaseAnonKey.trim().length < 20) return false;
        try {
            const endpoint = new URL(config.supabaseUrl);
            return endpoint.protocol === 'https:'
                && endpoint.username === ''
                && endpoint.password === ''
                && endpoint.port === ''
                && endpoint.search === ''
                && endpoint.hash === ''
                && endpoint.hostname.endsWith('.supabase.co')
                && endpoint.hostname.length > '.supabase.co'.length;
        } catch (_) {
            return false;
        }
    }

    function bindEvents() {
        el.loginForm.addEventListener('submit', handleLogin);
        el.logoutButton.addEventListener('click', () => { void signOut('تم تسجيل الخروج بأمان.'); });
        el.driversTab.addEventListener('click', () => { void showTab('drivers'); });
        el.codesTab.addEventListener('click', () => { void showTab('codes'); });
        el.commercialPolicyTab.addEventListener('click', () => { void showTab('commercial-policy'); });
        el.telemetryTab.addEventListener('click', () => { void showTab('telemetry'); });
        el.telemetryForm.addEventListener('submit', handleTelemetrySearch);
        el.telemetryOverview.addEventListener('click', () => { void handleTelemetryOverview(); });
        el.profilesTab.addEventListener('click', () => { void showTab('profiles'); });
        el.profileSelect.addEventListener('change', showSelectedProfile);
        el.profileReload.addEventListener('click', () => { void runWithButton(el.profileReload, loadProfiles); });
        el.profileForm.addEventListener('submit', handleProfileSave);
        el.refreshDrivers.addEventListener('click', () => { void loadDrivers({ reset: true }); });
        el.refreshCodes.addEventListener('click', () => { void loadCodes({ reset: true }); });
        el.driversPrevious.addEventListener('click', () => { void movePage('drivers', -1); });
        el.driversNext.addEventListener('click', () => { void movePage('drivers', 1); });
        el.codesPrevious.addEventListener('click', () => { void movePage('codes', -1); });
        el.codesNext.addEventListener('click', () => { void movePage('codes', 1); });
        el.codeForm.addEventListener('submit', handleGenerateCodes);
        el.exportCodes.addEventListener('click', () => { void runWithButton(el.exportCodes, exportCodes); });
        el.commercialPolicyForm.addEventListener('submit', handleCommercialPolicySave);
        el.retryCommercialPolicy.addEventListener('click', () => { void loadCommercialPolicy({ announce: true }); });
        el.trialEnabled.addEventListener('change', updateTrialEnabledLabel);
        [el.trialDays, el.subscriptionPriceSar, el.subscriptionDays, el.purchaseContactUrl, el.adminWhatsapp].forEach((field) => {
            field.addEventListener('input', () => clearPolicyFieldError(field));
        });

        ['pointerdown', 'keydown', 'touchstart'].forEach((eventName) => {
            document.addEventListener(eventName, refreshIdleDeadline, { passive: true });
        });
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) void enforceIdleDeadline();
        });
        window.addEventListener('offline', () => setNotice('لا يوجد اتصال بالشبكة؛ لم تُرسل أي عملية.', 'error'));
        window.addEventListener('online', () => setNotice('عاد الاتصال بالشبكة. تحقق من البيانات قبل تنفيذ أي عملية.', 'info'));

        db.auth.onAuthStateChange((event) => {
            if (event === 'SIGNED_OUT' && !state.signingOut && !el.dashboard.hidden) {
                void signOut('انتهت جلسة المشرف؛ سجّل الدخول مجددًا.');
            }
        });
    }

    async function restoreInMemorySession() {
        const { data, error } = await db.auth.getSession();
        if (error || !data.session) return;
        const access = await verifyAdminSession();
        if (access === 'authorized') {
            state.adminAuthorized = true;
            await enterDashboard();
        } else {
            await signOut(access === 'forbidden'
                ? 'هذا الحساب غير مخوّل للوصول إلى لوحة الإدارة.'
                : 'تعذر التحقق من الجلسة الحالية؛ سجّل الدخول مجددًا.');
        }
    }

    async function handleLogin(event) {
        event.preventDefault();
        const now = Date.now();
        if (now < state.loginBlockedUntil) {
            const remaining = Math.ceil((state.loginBlockedUntil - now) / 1000);
            setNotice(`تأخر في المحاولات. أعد المحاولة بعد ${remaining} ثانية.`, 'error');
            return;
        }

        const email = el.email.value.trim();
        const password = el.password.value;
        if (!isValidEmail(email) || password.length === 0) {
            setNotice('أدخل بريدًا إلكترونيًا صالحًا وكلمة المرور.', 'error');
            return;
        }

        await runWithButton(el.loginButton, async () => {
            setNotice('يجري التحقق من بيانات الدخول…', 'info');
            let result;
            try {
                result = await db.auth.signInWithPassword({ email, password });
            } catch (_) {
                registerLoginFailure();
                setNotice('تعذر إتمام تسجيل الدخول. تحقق من الاتصال ثم أعد المحاولة.', 'error');
                return;
            } finally {
                el.password.value = '';
            }

            if (result.error || !result.data || !result.data.session) {
                registerLoginFailure();
                setNotice('تعذر تسجيل الدخول بهذه البيانات.', 'error');
                return;
            }

            const access = await verifyAdminSession();
            if (access !== 'authorized') {
                await signOut(access === 'forbidden'
                    ? 'هذا الحساب غير مخوّل للوصول إلى لوحة الإدارة.'
                    : 'تعذر التحقق من صلاحية الإدارة؛ لم تُفتح اللوحة.');
                return;
            }

            state.loginFailures = 0;
            state.adminAuthorized = true;
            el.email.value = '';
            await enterDashboard();
        });
    }

    function registerLoginFailure() {
        state.loginFailures += 1;
        if (state.loginFailures >= LOGIN_FAILURE_LIMIT) {
            state.loginFailures = 0;
            state.loginBlockedUntil = Date.now() + LOGIN_COOLDOWN_MS;
        }
    }

    function isValidEmail(email) {
        return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
    }

    async function verifyAdminSession() {
        if (!navigator.onLine) return 'failure';
        let userResult;
        try {
            userResult = state.nativeAccessToken === null
                ? await db.auth.getUser()
                : await db.auth.getUser(state.nativeAccessToken);
        } catch (_) {
            return 'failure';
        }
        const { data, error } = userResult;
        if (error || !data.user) return 'unauthenticated';

        try {
            const { data: status, error: statusError } = await db.rpc('admin_session_status');
            if (!statusError && status && status.is_admin === true) return 'authorized';
            if (statusError && (statusError.code === '42501' || statusError.status === 401 || statusError.status === 403)) return 'forbidden';
            if (!statusError) return 'forbidden';
            return 'failure';
        } catch (_) {
            return 'failure';
        }
    }

    async function enterDashboard() {
        if (!state.adminAuthorized) {
            await signOut('تعذر التحقق من صلاحية الإدارة؛ لم تُفتح اللوحة.');
            return;
        }
        el.loginSection.hidden = true;
        el.dashboard.hidden = false;
        refreshIdleDeadline();
        setNotice('تم التحقق من جلسة المشرف. ستنتهي الجلسة بعد 15 دقيقة من عدم النشاط.', 'success');
        await loadCommercialPolicy({ announce: false });
        await showTab('drivers');
    }

    async function signOut(message) {
        if (state.signingOut) return;
        state.signingOut = true;
        clearTimeout(state.idleTimer);
        state.adminAuthorized = false;
        el.driversList.replaceChildren();
        el.codesList.replaceChildren();
        resetCommercialPolicyUi();
        el.dashboard.hidden = true;
        el.loginSection.hidden = false;
        state.offsets.drivers = 0;
        state.offsets.codes = 0;
        state.hasMore.drivers = false;
        state.hasMore.codes = false;
        renderPagination('drivers');
        renderPagination('codes');

        try {
            if (state.nativeAccessToken !== null) {
                clearNativeAccessState();
            } else {
                const { error } = await db.auth.signOut();
                if (error) await db.auth.signOut({ scope: 'local' });
            }
        } catch (_) {
            if (state.nativeAccessToken === null) {
                try { await db.auth.signOut({ scope: 'local' }); } catch (_) { /* The in-memory UI is already cleared. */ }
            }
        } finally {
            state.signingOut = false;
        }
        setNotice(message, 'info');
        el.password.focus();
    }

    function refreshIdleDeadline() {
        if (el.dashboard.hidden) return;
        clearTimeout(state.idleTimer);
        state.idleTimer = window.setTimeout(() => {
            void signOut('انتهت الجلسة بسبب عدم النشاط.');
        }, SESSION_IDLE_MS);
    }

    async function enforceIdleDeadline() {
        if (el.dashboard.hidden) return;
        if (!await ensureActiveSession()) return;
        refreshIdleDeadline();
    }

    async function ensureActiveSession() {
        if (!state.adminAuthorized) {
            await signOut('لا يمكن تنفيذ العملية قبل التحقق من صلاحية المشرف.');
            return false;
        }
        if (!navigator.onLine) {
            setNotice('لا يوجد اتصال بالشبكة؛ لا يمكن التحقق من الجلسة أو تنفيذ العملية.', 'error');
            return false;
        }
        let userResult;
        try {
            userResult = state.nativeAccessToken === null
                ? await db.auth.getUser()
                : await db.auth.getUser(state.nativeAccessToken);
        } catch (_) {
            await signOut('تعذر التحقق من جلسة المشرف.');
            return false;
        }
        const { data, error } = userResult;
        if (error || !data.user) {
            await signOut('انتهت الجلسة أو لم يعد الحساب مخوّلًا.');
            return false;
        }
        const access = await verifyAdminSession();
        if (access !== 'authorized') {
            await signOut(access === 'forbidden'
                ? 'انتهت صلاحية المشرف لهذا الحساب.'
                : 'تعذر التحقق من صلاحية الإدارة؛ لم تُنفذ العملية.');
            return false;
        }
        refreshIdleDeadline();
        return true;
    }

    async function showTab(tabName) {
        if (!['drivers', 'codes', 'commercial-policy', 'telemetry', 'profiles'].includes(tabName)) return;
        state.activeTab = tabName;
        const showingDrivers = tabName === 'drivers';
        const showingCodes = tabName === 'codes';
        el.driversPanel.hidden = !showingDrivers;
        el.codesPanel.hidden = !showingCodes;
        el.commercialPolicyPanel.hidden = tabName !== 'commercial-policy';
        el.telemetryPanel.hidden = tabName !== 'telemetry';
        el.profilesPanel.hidden = tabName !== 'profiles';
        el.driversTab.classList.toggle('active', showingDrivers);
        el.codesTab.classList.toggle('active', showingCodes);
        el.commercialPolicyTab.classList.toggle('active', tabName === 'commercial-policy');
        el.telemetryTab.classList.toggle('active', tabName === 'telemetry');
        el.profilesTab.classList.toggle('active', tabName === 'profiles');
        el.driversTab.setAttribute('aria-selected', String(showingDrivers));
        el.codesTab.setAttribute('aria-selected', String(showingCodes));
        el.commercialPolicyTab.setAttribute('aria-selected', String(tabName === 'commercial-policy'));
        el.telemetryTab.setAttribute('aria-selected', String(tabName === 'telemetry'));
        el.profilesTab.setAttribute('aria-selected', String(tabName === 'profiles'));
        if (showingDrivers) await loadDrivers();
        else if (showingCodes) await loadCodes();
        else if (tabName === 'commercial-policy' && !state.commercialPolicyLoaded) await loadCommercialPolicy({ announce: true });
        else if (tabName === 'profiles' && !state.profiles) await loadProfiles();
    }

    async function loadProfiles() {
        if (!await ensureActiveSession()) return;
        setProfileFeedback('يجري تحميل ملفات التطبيقات…', 'info');
        const { data, error } = await db.from('ride_profiles').select('id,version,enabled,updated_at,body').order('id');
        if (error) {
            if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
            else setProfileFeedback('تعذر تحميل ملفات التطبيقات. تحقق من الاتصال.', 'error');
            return;
        }
        state.profiles = Array.isArray(data) ? data : [];
        el.profileSelect.replaceChildren();
        state.profiles.forEach((profile) => {
            const option = document.createElement('option');
            option.value = profile.id;
            option.textContent = `${profile.id} · الإصدار ${profile.version}${profile.enabled ? '' : ' · معطّل'}`;
            el.profileSelect.append(option);
        });
        const empty = document.createElement('option');
        empty.value = '';
        empty.textContent = 'ملف جديد…';
        el.profileSelect.append(empty);
        showSelectedProfile();
        setProfileFeedback(state.profiles.length
            ? 'الملفات المنشورة تتقدّم على الملفات المضمّنة في التطبيق فقط إذا كان رقم إصدارها أكبر.'
            : 'لا توجد ملفات منشورة بعد؛ التطبيق يستعمل الملفات المضمّنة (الإصدار 2). الصق ملفًا برقم إصدار 3 أو أكبر لنشر إصلاح.', 'info');
    }

    function showSelectedProfile() {
        const selected = (state.profiles || []).find(profile => profile.id === el.profileSelect.value);
        el.profileBody.value = selected ? JSON.stringify(selected.body, null, 2) : '';
        el.profileEnabled.checked = selected ? selected.enabled === true : true;
    }

    // Mirrors admin_upsert_profile and core Profiles.isUsable so a bad edit is refused before
    // it reaches the server, and a publish never silently loses to an equal or higher version.
    function validateProfileDraft(text, published) {
        let body;
        try { body = JSON.parse(text); } catch (_) { return { error: 'المحتوى ليس JSON صالحًا.' }; }
        if (!body || typeof body !== 'object' || Array.isArray(body)) return { error: 'الملف يجب أن يكون كائن JSON.' };
        if (typeof body.id !== 'string' || !/^[a-z0-9_]{2,32}$/.test(body.id)) return { error: 'المعرّف id غير صالح.' };
        if (!Number.isSafeInteger(body.version) || body.version < 1) return { error: 'رقم الإصدار version يجب أن يكون عددًا صحيحًا موجبًا.' };
        for (const field of ['packages', 'acceptLabels', 'currencyTokens']) {
            if (!Array.isArray(body[field]) || !body[field].some(value => typeof value === 'string' && value.trim())) {
                return { error: `الحقل ${field} يجب أن يكون قائمة غير فارغة.` };
            }
        }
        const current = (published || []).find(profile => profile.id === body.id);
        if (current && body.version <= Number(current.version)) {
            return { error: `ارفع رقم الإصدار فوق ${current.version}؛ لن يطبّق التطبيق إصدارًا مساويًا أو أقدم.` };
        }
        return { body };
    }

    async function handleProfileSave(event) {
        event.preventDefault();
        const draft = validateProfileDraft(el.profileBody.value, state.profiles);
        if (draft.error) {
            setProfileFeedback(draft.error, 'error');
            return;
        }
        await runWithButton(el.profileSave, async () => {
            if (!await ensureActiveSession()) return;
            const { error } = await db.rpc('admin_upsert_profile', { p_body: draft.body, p_enabled: el.profileEnabled.checked });
            if (error) {
                if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
                else setProfileFeedback('رفض الخادم الملف. تحقق من الحقول ورقم الإصدار ثم أعد المحاولة.', 'error');
                return;
            }
            await loadProfiles();
            el.profileSelect.value = draft.body.id;
            showSelectedProfile();
            setProfileFeedback(`نُشر ${draft.body.id} الإصدار ${draft.body.version}. يصل للسائقين خلال 6 ساعات أو عند تشغيل المتابعة.`, 'success');
        });
    }

    function setProfileFeedback(message, type) {
        el.profileFeedback.textContent = message;
        el.profileFeedback.className = `policy-feedback policy-feedback--${type}`;
        el.profileFeedback.hidden = false;
    }

    async function loadCommercialPolicy({ announce = true } = {}) {
        if (state.commercialPolicyLoading) return false;
        if (!await ensureActiveSession()) {
            setPolicyFeedback('لا يمكن تحميل السياسة قبل التحقق من جلسة المشرف.', 'error');
            return false;
        }

        state.commercialPolicyLoading = true;
        setPolicyControlsDisabled(true);
        el.retryCommercialPolicy.disabled = true;
        setPolicyFeedback('يجري تحميل سياسة العرض من الخادم…', 'info');

        try {
            const { data, error } = await db.rpc('admin_commercial_policy');
            if (error) {
                await handleCommercialPolicyFailure(error, 'تعذر تحميل سياسة العرض. لم تُعرض أي قيم غير مؤكدة.');
                return false;
            }

            const policy = parseCommercialPolicy(data);
            if (!policy) {
                invalidateCommercialPolicy('تعذر التحقق من استجابة سياسة العرض. لم تُعرض أي قيم غير مؤكدة.');
                return false;
            }

            state.commercialPolicy = policy;
            state.commercialPolicyLoaded = true;
            populateCommercialPolicyForm(policy);
            setPolicyControlsDisabled(false);
            if (announce) setPolicyFeedback('تم تحميل سياسة العرض المؤكدة من الخادم.', 'success');
            else clearPolicyFeedback();
            return true;
        } catch (_) {
            invalidateCommercialPolicy('تعذر تحميل سياسة العرض. تحقق من الاتصال ثم أعد المحاولة.');
            return false;
        } finally {
            state.commercialPolicyLoading = false;
            el.retryCommercialPolicy.disabled = false;
        }
    }

    async function handleCommercialPolicySave(event) {
        event.preventDefault();
        if (!state.commercialPolicyLoaded || !state.commercialPolicy) {
            invalidateCommercialPolicy('لا يمكن الحفظ قبل تحميل سياسة مؤكدة من الخادم. أعد تحميلها أولًا.');
            return;
        }

        const values = readCommercialPolicyValues();
        if (!values) return;

        await runWithButton(el.saveCommercialPolicy, async () => {
            if (!await ensureActiveSession()) return;
            setPolicyFeedback('يجري حفظ السياسة كاملة في عملية واحدة…', 'info');
            try {
                const { data, error } = await db.rpc('admin_set_commercial_policy', {
                    p_trial_enabled: values.trialEnabled,
                    p_trial_days: values.trialDays,
                    p_price_minor: values.priceMinor,
                    p_subscription_days: values.subscriptionDays,
                    p_purchase_contact_url: values.purchaseContactUrl
                });
                if (error) {
                    await handleCommercialPolicyFailure(error, 'لم نتأكد من حفظ السياسة؛ لم تُحدَّث الواجهة. أعد تحميل السياسة قبل المحاولة مجددًا.');
                    return;
                }
                if (!data || typeof data !== 'object') {
                    invalidateCommercialPolicy('لم نتأكد من استجابة الحفظ؛ لم تُحدَّث الواجهة. أعد تحميل السياسة قبل المحاولة مجددًا.');
                    return;
                }

                // The WhatsApp number has its own server operation; save it, then reload the
                // whole policy so the form only ever shows values the server confirmed.
                const whatsapp = await db.rpc('admin_set_whatsapp', { p_number: values.adminWhatsapp });
                if (whatsapp.error) {
                    await handleCommercialPolicyFailure(whatsapp.error, 'حُفظت السياسة، لكن لم نتأكد من حفظ رقم واتساب. أعد تحميل السياسة ثم حاول مجددًا.');
                    return;
                }
                if (await loadCommercialPolicy({ announce: false })) {
                    setPolicyFeedback('تم حفظ سياسة العرض ورقم واتساب وتأكيدهما من الخادم.', 'success');
                }
            } catch (_) {
                invalidateCommercialPolicy('تعذر تأكيد حفظ السياسة. أعد تحميلها قبل أي محاولة جديدة.');
            }
        });
    }

    async function handleTelemetrySearch(event) {
        event.preventDefault();
        const correlation = el.telemetryCorrelation.value.trim().toLowerCase();
        if (!/^[a-f0-9]{16,64}$/.test(correlation)) {
            setTelemetryFeedback('أدخل معرّف دعم سداسي عشري صحيحًا. لم تُرسل أي عملية.', 'error');
            return;
        }
        await runWithButton(el.telemetrySearch, async () => {
            if (!await ensureActiveSession()) return;
            setTelemetryFeedback('يجري تحميل خط سير العرض…', 'info');
            el.telemetryResults.hidden = false;
            el.telemetryResults.textContent = 'جارٍ التحميل…';
            try {
                const { data, error } = await db.rpc('admin_offer_telemetry', { p_correlation_id: correlation });
                if (error) throw error;
                renderTelemetry(data);
            } catch (error) {
                if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
                else {
                    setTelemetryFeedback('تعذر تحميل التشخيص. تحقق من الاتصال أو من صلاحية معرّف الدعم.', 'error');
                    el.telemetryResults.textContent = 'لا تتوفر نتائج مؤكدة.';
                }
            }
        });
    }

    async function diagnoseDriver(driver, button) {
        if (typeof driver?.id !== 'string' || !UUID_PATTERN.test(driver.id)) {
            setNotice('تعذر التحقق من معرّف السائق المحدد.', 'error');
            return;
        }
        await runWithButton(button, async () => {
            if (!await ensureActiveSession()) return;
            await showTab('telemetry');
            setTelemetryFeedback(`يجري تحميل تشخيص ${displayValue(driver.full_name, 'السائق')}…`, 'info');
            el.telemetryResults.hidden = false;
            el.telemetryResults.textContent = 'جارٍ التحميل…';
            try {
                const { data, error } = await db.rpc('admin_offer_telemetry_for_driver', { p_driver: driver.id });
                if (error) throw error;
                if (typeof data?.correlation_id === 'string') el.telemetryCorrelation.value = data.correlation_id;
                renderTelemetry(data);
            } catch (error) {
                if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
                else {
                    setTelemetryFeedback('تعذر تحميل تشخيص هذا السائق. تحقق من الاتصال.', 'error');
                    el.telemetryResults.textContent = 'لا تتوفر نتائج مؤكدة.';
                }
            }
        });
    }

    async function handleTelemetryOverview() {
        await runWithButton(el.telemetryOverview, async () => {
            if (!await ensureActiveSession()) return;
            el.telemetryResults.hidden = false;
            el.telemetryResults.textContent = 'جارٍ التحميل…';
            try {
                const { data, error } = await db.rpc('admin_offer_telemetry_overview');
                if (error) throw error;
                renderTelemetryOverview(data);
            } catch (error) {
                if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
                else {
                    setTelemetryFeedback('تعذر تحميل نظرة الأسطول.', 'error');
                    el.telemetryResults.textContent = 'لا تتوفر نتائج مؤكدة.';
                }
            }
        });
    }

    function renderTelemetry(data) {
        const events = Array.isArray(data?.events) ? data.events : [];
        const funnel = Array.isArray(data?.funnel) ? data.funnel : [];
        el.telemetryResults.replaceChildren();
        const diagnosis = diagnoseTelemetry(funnel, events, data?.driver_has_telemetry !== false);
        const verdict = document.createElement('div');
        verdict.className = `policy-feedback policy-feedback--${diagnosis.action === 'none' ? 'success' : 'error'}`;
        const cause = document.createElement('h4');
        cause.textContent = `السبب: ${diagnosis.cause}`;
        const action = document.createElement('p');
        action.textContent = `${diagnosis.action === 'update' ? 'يحتاج تحديث التطبيق' : diagnosis.action === 'user' ? 'وجّه المستخدم' : 'الإجراء'}: ${diagnosis.advice}`;
        verdict.append(cause, action);
        el.telemetryResults.append(verdict);
        if (!events.length) {
            setTelemetryFeedback('اكتمل البحث.', 'info');
            return;
        }
        const funnelHeading = document.createElement('h4');
        funnelHeading.textContent = 'مراحل التوقف (المجموع خلال 7 أيام)';
        const funnelList = document.createElement('ul');
        funnel.forEach((item) => {
            const row = document.createElement('li');
            row.textContent = `${stageLabel(item.stage)} · ${displayValue(item.reason_code, 'بدون سبب')} · ${Number(item.count) || 0}` +
                (typeof item.detail === 'string' ? ` · ${item.detail}` : '');
            funnelList.append(row);
        });
        const timelineHeading = document.createElement('h4');
        timelineHeading.textContent = `آخر الأحداث (${events.length})`;
        const list = document.createElement('ol');
        events.slice(0, 100).forEach((item) => {
            const row = document.createElement('li');
            row.textContent = `${formatDateTime(item.occurred_at)} · ${stageLabel(item.stage)} · ${displayValue(item.reason_code, 'بدون سبب')}` +
                ` ×${Number(item.event_count) || 1} · سبّاق ${displayValue(item.app_version)} · جيني ${displayValue(item.target_app_version, 'غير معروف')}` +
                (Number.isFinite(Number(item.latency_ms)) && item.latency_ms !== null ? ` · زمن الاستجابة ${Number(item.latency_ms)} ملّي ثانية` : '');
            list.append(row);
        });
        el.telemetryResults.append(funnelHeading, funnelList, timelineHeading, list);
        setTelemetryFeedback('تم تحميل بيانات metadata المسموح بها للمشرف.', 'success');
    }

    function renderTelemetryOverview(data) {
        const funnel = Array.isArray(data?.funnel) ? data.funnel : [];
        const devices = Array.isArray(data?.devices) ? data.devices : [];
        el.telemetryResults.replaceChildren();
        const heading = document.createElement('h4');
        heading.textContent = `الأسطول خلال 24 ساعة: ${devices.length} جهاز`;
        const response = data?.response_ms;
        const responseLine = document.createElement('p');
        responseLine.textContent = Number(response?.samples) > 0
            ? `زمن الاستجابة من ظهور الطلب إلى الضغط: الوسيط ${Math.round(Number(response.p50))} ملّي ثانية · 90% أسرع من ${Math.round(Number(response.p90))} ملّي ثانية (${Number(response.samples)} عينة)`
            : 'لا توجد عينات زمن استجابة بعد.';
        el.telemetryResults.append(responseLine);
        const funnelList = document.createElement('ul');
        funnel.slice(0, 30).forEach((item) => {
            const row = document.createElement('li');
            row.textContent = `${stageLabel(item.stage)} · ${displayValue(item.reason_code, 'بدون سبب')} · ${Number(item.count) || 0} حدث · ${Number(item.devices) || 0} جهاز`;
            funnelList.append(row);
        });
        const deviceList = document.createElement('ol');
        devices.forEach((item) => {
            const row = document.createElement('li');
            row.textContent = `${displayValue(item.correlation_id)} · ${formatDateTime(item.last_seen)} · سبّاق ${displayValue(item.app_version)} · ${displayValue(item.last_heartbeat, 'بلا نبضة')}`;
            deviceList.append(row);
        });
        el.telemetryResults.append(heading, funnelList, deviceList);
        setTelemetryFeedback('تم تحميل نظرة الأسطول.', 'success');
    }

    // Mirrors core TelemetryDiagnosis: the earliest pipeline stop wins, and each verdict says
    // whether support can fix it by guiding the driver or it needs an app release.
    function diagnoseTelemetry(funnel, events, hasTelemetry) {
        const rows = Array.isArray(funnel) ? funnel : [];
        const total = (stage, reason) => rows
            .filter(item => item.stage === stage && (reason === undefined || item.reason_code === reason))
            .reduce((sum, item) => sum + (Number(item.count) || 0), 0);
        const verdict = (cause, action, advice) => ({ cause, action, advice });
        if (!hasTelemetry || rows.length === 0) {
            return verdict('لا توجد بيانات تشخيص من جهاز هذا المستخدم', 'user',
                'اطلب منه تحديث سبّاق لآخر إصدار، وفتحه مع تسجيل الدخول ودقيقتين من الاتصال بالإنترنت، ثم أعد البحث.');
        }
        const list = Array.isArray(events) ? events : [];
        const crash = list.find(item => item.stage === 'APP_CRASH');
        const heartbeat = list.find(item => item.stage === 'HEARTBEAT');
        const healthVerdicts = {
            a11y_disabled: ['خدمة إمكانية الوصول لسبّاق غير مفعّلة في إعدادات الهاتف', 'وجّهه: الإعدادات ← إمكانية الوصول ← التطبيقات المثبتة ← سبّاق ← تفعيل. في أندرويد 13+ قد يلزم أولًا «السماح بالإعدادات المقيدة» من معلومات التطبيق.'],
            a11y_not_connected: ['الخدمة مفعّلة لكن النظام أوقفها', 'وجّهه: أطفئ خدمة سبّاق في إمكانية الوصول وأعد تشغيلها، ثم استثنِ سبّاق من تحسين البطارية وفعّل التشغيل التلقائي إن وُجد في الهاتف.'],
            target_not_installed: ['تطبيق جيني للسائقين غير مثبت على الجهاز', 'اطلب منه تثبيت تطبيق جيني للسائقين وتسجيل الدخول فيه.'],
            signed_out: ['المستخدم غير مسجل الدخول في سبّاق', 'اطلب منه فتح سبّاق وتسجيل الدخول.'],
            license_invalid: ['الاشتراك غير صالح على هذا الجهاز', 'تحقق من اشتراكه في تبويب السائقين، ثم اطلب منه فتح سبّاق لتحديث الحالة.'],
            disclosure_required: ['لم يوافق على إفصاح إمكانية الوصول', 'اطلب منه فتح سبّاق والموافقة على الإفصاح ثم تشغيل المتابعة.'],
            monitoring_off: ['المتابعة متوقفة في سبّاق', 'اطلب منه الضغط على «تشغيل المتابعة» في لوحة التحكم.'],
            background_restricted: ['النظام يقيّد عمل سبّاق في الخلفية', 'وجّهه: معلومات التطبيق ← البطارية ← «غير مقيد».'],
            battery_optimized: ['تحسين البطارية مفعّل لسبّاق وقد يوقفه النظام', 'وجّهه: معلومات التطبيق ← البطارية ← «غير مقيد/بدون تحسين».'],
            power_save: ['وضع توفير الطاقة مفعّل وقد يبطئ الرصد', 'اطلب منه إيقاف توفير الطاقة أثناء العمل.'],
            notifications_off: ['إشعارات سبّاق متوقفة', 'وجّهه لتفعيل إشعارات سبّاق حتى يبقى يعمل في الخلفية.'],
            offline: ['الجهاز بلا إنترنت', 'اطلب منه التأكد من الاتصال بالإنترنت.'],
        };
        const heartbeatIsLatest = heartbeat && !list.some(item => ['OFFER_DETECTED', 'CLICK_RESULT'].includes(item.stage)
            && String(item.occurred_at) > String(heartbeat.occurred_at));
        if (heartbeatIsLatest && healthVerdicts[heartbeat.reason_code]) {
            const [cause, advice] = healthVerdicts[heartbeat.reason_code];
            return verdict(cause, 'user', advice);
        }
        const keeperStop = list.find(item => item.stage === 'PAUSED' && typeof item.reason_code === 'string' && item.reason_code.startsWith('keeper_'));
        const keeperVerdicts = {
            keeper_restore_timeout: ['توقفت المتابعة بعد إعادة تشغيل الهاتف لعدم توفر الإنترنت لمدة طويلة', 'اطلب منه فتح سبّاق مرة واحدة مع اتصال بالإنترنت؛ تُستأنف المتابعة تلقائيًا.'],
            keeper_waiting_network: ['المتابعة متوقفة مؤقتًا بانتظار الإنترنت لتجديد الاشتراك', 'تُستأنف تلقائيًا عند عودة الاتصال؛ اطلب منه التأكد من بيانات الجوال.'],
            keeper_restore_refused: ['انتهت جلسة الدخول المحفوظة (خروج من جهاز آخر أو حذف الحساب)', 'اطلب منه تسجيل الدخول من جديد ثم تشغيل المتابعة.'],
            keeper_license_lost: ['توقفت المتابعة لأن الاشتراك لم يعد صالحًا', 'تحقق من اشتراكه في تبويب السائقين وجدده إن لزم.'],
            keeper_notifications_denied: ['توقفت المتابعة لأن إذن الإشعارات مسحوب', 'وجّهه لتفعيل إشعارات سبّاق ثم تشغيل المتابعة.'],
            keeper_disclosure_withdrawn: ['سحب موافقة إفصاح إمكانية الوصول', 'اطلب منه الموافقة على الإفصاح ثم تشغيل المتابعة.'],
        };
        const keeperIsLatest = keeperStop && !list.some(item => ['PACKAGE_SEEN', 'OFFER_DETECTED', 'CLICK_RESULT', 'SERVICE_CONNECTED'].includes(item.stage)
            && String(item.occurred_at) > String(keeperStop.occurred_at));
        if (keeperIsLatest && keeperVerdicts[keeperStop.reason_code]) {
            const [cause, advice] = keeperVerdicts[keeperStop.reason_code];
            return verdict(cause, 'user', advice);
        }
        if (crash && (!heartbeat || String(crash.occurred_at) >= String(heartbeat.occurred_at))) {
            return verdict(`انهيار في سبّاق (${displayValue(crash.reason_code)})`, 'update',
                `عطل برمجي يلزمه إصلاح وتحديث؛ موضع العطل: ${displayValue(crash.detail, 'غير معروف')}. وجّه المستخدم مؤقتًا لإعادة فتح سبّاق.`);
        }
        const latestGate = list.find(item => item.stage === 'GATE_BLOCKED');
        const latestProgress = list.find(item => ['PACKAGE_SEEN', 'SIGNAL_FOUND', 'SIGNAL_MISS', 'OFFER_DETECTED', 'CLICK_RESULT'].includes(item.stage));
        const gateIsLatest = latestGate && (!latestProgress || String(latestGate.occurred_at) >= String(latestProgress.occurred_at));
        if (gateIsLatest) {
            if (latestGate.reason_code === 'disclosure_required') return verdict('لم يوافق على إفصاح إمكانية الوصول', 'user', 'اطلب منه فتح سبّاق والموافقة على الإفصاح ثم تشغيل المتابعة.');
            if (latestGate.reason_code === 'service_paused') return verdict('المتابعة متوقفة في سبّاق', 'user', 'اطلب منه الضغط على «تشغيل المتابعة» في لوحة التحكم.');
            if (latestGate.reason_code === 'license_invalid') return verdict('الاشتراك غير صالح على هذا الجهاز', 'user', 'تحقق من الاشتراك في تبويب السائقين، ثم اطلب منه إعادة تسجيل الدخول.');
            if (['no_profiles', 'no_parser', 'no_adapter'].includes(latestGate.reason_code)) return verdict('ملفات التطبيقات المدعومة لم تُحمّل', 'update', 'عطل في الإصدار؛ يلزم إصلاح وتحديث التطبيق.');
        }
        if (total('OFFER_DETECTED') === 0) {
            const rootMissing = total('ROOT_UNAVAILABLE');
            const treeRejected = total('TREE_REJECTED');
            const signalMiss = total('SIGNAL_MISS');
            if (total('PACKAGE_SEEN') === 0) return verdict('لم يصل أي حدث من تطبيق جيني إلى سبّاق', 'user', 'تأكد أن جيني مفتوح ومتصل وأن خدمة إمكانية الوصول لسبّاق مفعّلة، ثم انتظر طلبًا.');
            if (rootMissing > 0 && rootMissing >= treeRejected && rootMissing >= signalMiss) return verdict('نافذة الطلب في جيني غير قابلة للقراءة', 'update', 'سبب تقني (نافذة طافية/نظام)؛ يلزم إصلاح في التطبيق. السبب الدقيق في reason_code لمرحلة ROOT_UNAVAILABLE.');
            if (treeRejected > 0 && treeRejected >= signalMiss) return verdict('شاشة جيني رُفضت أثناء القراءة', 'update', 'يلزم إصلاح في التطبيق؛ راجع reason_code لمرحلة TREE_REJECTED.');
            if (total('OFFER_NOT_PARSED') > 0) return verdict('الطلب ظهر وتعذر استخراج تفاصيله', 'update', 'تغيّرت واجهة جيني؛ يلزم تحديث المحلل. راجع detail للمعالم المفقودة.');
            if (signalMiss > 0) return verdict('الشاشة تُقرأ لكن الطلب لا يُتعرّف عليه', 'update', 'غالبًا تغيّرت تسميات جيني؛ detail يبيّن ما وُجد (a=زر قبول، f=أجرة، d=مسافة، t=وقت، p=عنوان، o=كلمة طلب، c=رحلة نشطة).');
            return verdict('يعمل؛ لم يظهر طلب خلال الفترة', 'none', 'لا يلزم إجراء.');
        }
        if (total('DECISION_ACCEPTED') === 0) {
            const topRejection = rows.filter(item => item.stage === 'REJECTED')
                .sort((a, b) => (Number(b.count) || 0) - (Number(a.count) || 0))[0];
            const rejectionAdvice = {
                no_filters: ['لم يحدد المستخدم أي شرط، والقبول دون شروط غير مفعّل', 'اطلب منه اختيار شروط من «الشروط» أو تفعيل «قبول الطلبات دون شروط».'],
                destination_missing: ['الطلبات لا تعرض الوجهة والمستخدم يشترط وجودها', 'جيني يخفي الوجهة غالبًا قبل القبول؛ اطلب منه تفعيل «قبول الطلبات بلا وجهة».'],
                fare_too_low: ['أجرة الطلبات أقل من الحد الأدنى الذي حدده', 'راجع معه الحد الأدنى للأجرة.'],
                fare_missing: ['الأجرة لا تُقرأ من الطلب وشرط الأجرة مفعّل', 'سبب تقني في قراءة الأجرة؛ يلزم تحديث المحلل، ومؤقتًا يمكنه تعطيل شرط الأجرة.'],
                pickup_too_far: ['مسافة الوصول للراكب أبعد من حده', 'راجع معه أقصى مسافة للوصول.'],
                pickup_too_long: ['وقت الوصول للراكب أطول من حده', 'راجع معه أقصى وقت للوصول.'],
                per_km_too_low: ['العائد لكل كم أقل من حده', 'راجع معه حد العائد لكل كم.'],
                outside_schedule: ['الطلبات تصل خارج أوقات العمل التي حددها', 'راجع معه جدول أوقات العمل.'],
            };
            const known = topRejection && rejectionAdvice[topRejection.reason_code];
            if (known) return verdict(known[0], known[1].startsWith('سبب تقني') ? 'update' : 'user', known[1]);
            return verdict('الطلبات تُرى وكلها ترفضها شروط المستخدم', 'user',
                `راجع معه الشروط؛ أكثر سبب رفض: ${displayValue(topRejection?.reason_code, 'غير محدد')}.`);
        }
        if (total('CLICK_RESULT', 'semantic') + total('CLICK_RESULT', 'gesture') === 0) return verdict('تعذر الضغط على زر القبول', 'update', 'يلزم إصلاح في التطبيق؛ راجع reason_code لمرحلة CLICK_RESULT.');
        if (total('CONFIRMATION_CONFIRMED') === 0 && total('CONFIRMATION_TIMEOUT') > 0) return verdict('أُرسل القبول ولم يتأكد في جيني', 'update', 'غالبًا ضغط في مكان غير صحيح أو طلب أخذه سائق آخر؛ راجع التسلسل الزمني.');
        return verdict('التطبيق يقبل الطلبات', 'none', 'لا يلزم إجراء.');
    }

    function stageLabel(stage) {
        const labels = {
            EVENT_RECEIVED: 'حدث وارد', PACKAGE_SEEN: 'تطبيق مدعوم', GATE_BLOCKED: 'موقوف', ROOT_UNAVAILABLE: 'نافذة غير مقروءة',
            TREE_REJECTED: 'شاشة مرفوضة', STALE: 'إطار مكرر', SIGNAL_MISS: 'لا طلب', SIGNAL_FOUND: 'إشارة طلب',
            FRAME_INCOMPLETE: 'إطار ناقص', OFFER_NOT_PARSED: 'تعذر الاستخراج', OFFER_DETECTED: 'طلب مكتشف',
            REJECTED: 'مرفوض بالشروط', DECISION_ACCEPTED: 'مطابق للشروط', ACCEPT_NODE: 'زر القبول', CLICK_RESULT: 'الضغط',
            CONFIRMATION_CONFIRMED: 'قبول مؤكد', CONFIRMATION_TIMEOUT: 'قبول غير مؤكد', SERVICE_CONNECTED: 'الخدمة متصلة',
            SERVICE_INTERRUPTED: 'الخدمة مقاطَعة', SERVICE_DESTROYED: 'الخدمة أُوقفت', HEARTBEAT: 'نبضة',
            UPLOAD_REJECTED: 'رفض الإرسال', QUEUE_DROPPED: 'أحداث محذوفة', PAUSED: 'إيقاف مؤقت', APP_CRASH: 'انهيار التطبيق',
        };
        return labels[stage] || displayValue(stage);
    }

    function formatDateTime(value) {
        if (!value) return 'غير محدد';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return 'غير محدد';
        return new Intl.DateTimeFormat('ar-EG', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date);
    }

    function setTelemetryFeedback(message, type) {
        el.telemetryFeedback.textContent = message;
        el.telemetryFeedback.className = `policy-feedback policy-feedback--${type}`;
        el.telemetryFeedback.hidden = false;
    }

    async function handleCommercialPolicyFailure(error, fallbackMessage) {
        if (isAuthorizationError(error)) {
            await signOut('انتهت الجلسة أو لم يعد الحساب مخوّلًا لإدارة سياسة العرض.');
            return;
        }
        if (error && error.code === '22023') {
            invalidateCommercialPolicy('رفض الخادم إحدى قيم السياسة. راجع الحقول ثم أعد تحميل السياسة قبل المحاولة مجددًا.');
            return;
        }
        invalidateCommercialPolicy(fallbackMessage);
    }

    function parseCommercialPolicy(data) {
        if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
        const contact = data.purchase_contact_url;
        const updatedAt = data.updated_at;
        if (typeof data.trial_enabled !== 'boolean'
            || !isBoundedInteger(data.trial_days, POLICY_MIN_DAYS, POLICY_MAX_DAYS)
            || !isBoundedInteger(data.price_minor, POLICY_MIN_MINOR, POLICY_MAX_MINOR)
            || data.currency !== 'SAR'
            || !isBoundedInteger(data.subscription_days, POLICY_MIN_DAYS, POLICY_MAX_DAYS)
            || !(contact === null || (typeof contact === 'string' && isValidHttpsContact(contact)))
            || typeof updatedAt !== 'string'
            || Number.isNaN(new Date(updatedAt).getTime())) return null;

        return {
            trialEnabled: data.trial_enabled,
            trialDays: data.trial_days,
            priceMinor: data.price_minor,
            subscriptionDays: data.subscription_days,
            purchaseContactUrl: contact,
            adminWhatsapp: typeof data.admin_whatsapp === 'string' && WHATSAPP_PATTERN.test(data.admin_whatsapp) ? data.admin_whatsapp : null,
            updatedAt
        };
    }

    function readCommercialPolicyValues() {
        clearAllPolicyFieldErrors();
        const trialDays = parseBoundedInteger(el.trialDays.value, POLICY_MIN_DAYS, POLICY_MAX_DAYS);
        const priceMinor = parseSarToMinor(el.subscriptionPriceSar.value);
        const subscriptionDays = parseBoundedInteger(el.subscriptionDays.value, POLICY_MIN_DAYS, POLICY_MAX_DAYS);
        const purchaseContactUrl = el.purchaseContactUrl.value.trim();
        const adminWhatsapp = normalizeWhatsapp(el.adminWhatsapp.value);
        let valid = true;

        if (trialDays === null) {
            setPolicyFieldError(el.trialDays, 'أدخل عددًا صحيحًا من 1 إلى 365 يومًا.');
            valid = false;
        }
        if (priceMinor === null) {
            setPolicyFieldError(el.subscriptionPriceSar, 'أدخل سعرًا موجبًا حتى 1,000,000.00 ريال، بدقة هللتين كحد أقصى.');
            valid = false;
        }
        if (subscriptionDays === null) {
            setPolicyFieldError(el.subscriptionDays, 'أدخل عددًا صحيحًا من 1 إلى 365 يومًا.');
            valid = false;
        }
        if (purchaseContactUrl && !isValidHttpsContact(purchaseContactUrl)) {
            setPolicyFieldError(el.purchaseContactUrl, 'استخدم رابط HTTPS بلا مسافات وبحد أقصى 2048 بايت، أو اترك الحقل فارغًا.');
            valid = false;
        }
        if (adminWhatsapp === undefined) {
            setPolicyFieldError(el.adminWhatsapp, 'أدخل رقمًا صحيحًا مثل 05XXXXXXXX أو ‎+9665XXXXXXXX، أو اترك الحقل فارغًا.');
            valid = false;
        }
        if (!valid) {
            setPolicyFeedback('صحح الحقول المعلَّمة قبل حفظ السياسة. لم تُرسل أي عملية إلى الخادم.', 'error');
            return null;
        }

        return {
            trialEnabled: el.trialEnabled.checked,
            trialDays,
            priceMinor,
            subscriptionDays,
            purchaseContactUrl: purchaseContactUrl || null,
            adminWhatsapp
        };
    }

    // Mirrors admin_set_whatsapp: wa.me digits, null when empty, undefined when invalid.
    function normalizeWhatsapp(rawValue) {
        let digits = normalizeArabicNumerals(String(rawValue || '')).replace(/[\s()+-]/g, '');
        if (!digits) return null;
        if (/^05\d{8}$/.test(digits)) digits = '966' + digits.slice(1);
        else if (/^00[1-9]/.test(digits)) digits = digits.slice(2);
        return WHATSAPP_PATTERN.test(digits) ? digits : undefined;
    }

    function populateCommercialPolicyForm(policy) {
        el.trialEnabled.checked = policy.trialEnabled;
        el.trialDays.value = String(policy.trialDays);
        el.subscriptionPriceSar.value = minorToSarInput(policy.priceMinor);
        el.subscriptionDays.value = String(policy.subscriptionDays);
        el.purchaseContactUrl.value = policy.purchaseContactUrl || '';
        el.adminWhatsapp.value = policy.adminWhatsapp ? '+' + policy.adminWhatsapp : '';
        el.policyUpdatedAt.textContent = `آخر تأكيد من الخادم: ${formatPolicyDate(policy.updatedAt)}`;
        clearAllPolicyFieldErrors();
        updateTrialEnabledLabel();
    }

    function resetCommercialPolicyUi() {
        state.commercialPolicy = null;
        state.commercialPolicyLoaded = false;
        state.commercialPolicyLoading = false;
        el.commercialPolicyForm.reset();
        el.trialEnabled.checked = false;
        el.trialDays.value = '';
        el.subscriptionPriceSar.value = '';
        el.subscriptionDays.value = '';
        el.purchaseContactUrl.value = '';
        el.adminWhatsapp.value = '';
        el.policyUpdatedAt.textContent = 'لم تُحمّل سياسة مؤكدة بعد.';
        clearAllPolicyFieldErrors();
        clearPolicyFeedback();
        setPolicyControlsDisabled(true);
        el.retryCommercialPolicy.disabled = false;
        updateTrialEnabledLabel();
    }

    function invalidateCommercialPolicy(message) {
        state.commercialPolicy = null;
        state.commercialPolicyLoaded = false;
        setPolicyControlsDisabled(true);
        el.policyUpdatedAt.textContent = 'لا توجد سياسة مؤكدة حاليًا.';
        setPolicyFeedback(message, 'error');
    }

    function setPolicyControlsDisabled(disabled) {
        [
            el.trialEnabled,
            el.trialDays,
            el.subscriptionPriceSar,
            el.subscriptionDays,
            el.purchaseContactUrl,
            el.adminWhatsapp,
            el.saveCommercialPolicy
        ].forEach((control) => { control.disabled = disabled; });
    }

    function setPolicyFeedback(message, type) {
        el.commercialPolicyFeedback.textContent = message;
        el.commercialPolicyFeedback.className = `policy-feedback policy-feedback--${type}`;
        el.commercialPolicyFeedback.hidden = false;
    }

    function clearPolicyFeedback() {
        el.commercialPolicyFeedback.textContent = '';
        el.commercialPolicyFeedback.className = 'policy-feedback';
        el.commercialPolicyFeedback.hidden = true;
    }

    function setPolicyFieldError(input, message) {
        const error = policyErrorFor(input);
        if (!error) return;
        input.setAttribute('aria-invalid', 'true');
        error.textContent = message;
        error.hidden = false;
    }

    function clearPolicyFieldError(input) {
        const error = policyErrorFor(input);
        if (!error) return;
        input.removeAttribute('aria-invalid');
        error.textContent = '';
        error.hidden = true;
    }

    function clearAllPolicyFieldErrors() {
        [el.trialDays, el.subscriptionPriceSar, el.subscriptionDays, el.purchaseContactUrl, el.adminWhatsapp]
            .forEach(clearPolicyFieldError);
    }

    function policyErrorFor(input) {
        if (input === el.trialDays) return el.trialDaysError;
        if (input === el.subscriptionPriceSar) return el.subscriptionPriceError;
        if (input === el.subscriptionDays) return el.subscriptionDaysError;
        if (input === el.purchaseContactUrl) return el.purchaseContactError;
        if (input === el.adminWhatsapp) return el.adminWhatsappError;
        return null;
    }

    function updateTrialEnabledLabel() {
        el.trialEnabledState.textContent = state.commercialPolicyLoaded
            ? (el.trialEnabled.checked ? 'مفعلة' : 'متوقفة')
            : 'غير مؤكد';
    }

    function parseBoundedInteger(rawValue, minimum, maximum) {
        const normalized = normalizeArabicNumerals(String(rawValue).trim());
        if (!/^\d+$/.test(normalized)) return null;
        const canonical = normalized.replace(/^0+(?=\d)/, '');
        if (canonical.length > String(maximum).length) return null;
        const value = Number(canonical);
        return isBoundedInteger(value, minimum, maximum) ? value : null;
    }

    function parseSarToMinor(rawValue) {
        const normalized = normalizeArabicNumerals(String(rawValue).trim())
            .replace(/٫/g, '.')
            .replace(/,/g, '.');
        const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(normalized);
        if (!match) return null;
        const whole = match[1].replace(/^0+(?=\d)/, '');
        if (whole.length > 7) return null;
        const fraction = (match[2] || '').padEnd(2, '0');
        const minor = (Number(whole) * HALALAS_PER_SAR) + Number(fraction);
        return isBoundedInteger(minor, POLICY_MIN_MINOR, POLICY_MAX_MINOR) ? minor : null;
    }

    function minorToSarInput(minor) {
        if (!isBoundedInteger(minor, POLICY_MIN_MINOR, POLICY_MAX_MINOR)) return '';
        const whole = Math.floor(minor / HALALAS_PER_SAR);
        const fraction = String(minor % HALALAS_PER_SAR).padStart(2, '0');
        return `${whole}.${fraction}`;
    }

    function normalizeArabicNumerals(value) {
        return value.replace(/[٠-٩۰-۹]/g, (digit) => {
            const arabicIndex = ARABIC_DIGITS.indexOf(digit);
            return arabicIndex >= 0 ? String(arabicIndex) : String(PERSIAN_DIGITS.indexOf(digit));
        });
    }

    function isBoundedInteger(value, minimum, maximum) {
        return Number.isSafeInteger(value) && value >= minimum && value <= maximum;
    }

    function isValidHttpsContact(value) {
        return /^https:\/\/[^\s]+$/.test(value)
            && new TextEncoder().encode(value).length <= MAX_CONTACT_URL_BYTES;
    }

    function formatPolicyDate(value) {
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return 'غير محدد';
        return new Intl.DateTimeFormat('ar-SA', {
            year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
        }).format(date);
    }

    async function movePage(kind, direction) {
        const nextOffset = state.offsets[kind] + (direction * PAGE_SIZE);
        if (nextOffset < 0 || (direction > 0 && !state.hasMore[kind])) return;
        state.offsets[kind] = nextOffset;
        if (kind === 'drivers') await loadDrivers();
        else await loadCodes();
    }

    async function loadDrivers({ reset = false } = {}) {
        if (reset) state.offsets.drivers = 0;
        if (state.loading.drivers || !await ensureActiveSession()) return;
        state.loading.drivers = true;
        renderPagination('drivers');
        el.refreshDrivers.disabled = true;

        try {
            const from = state.offsets.drivers;
            const { data, error } = await db
                .from('drivers')
                .select('id,full_name,phone,status,pro_until,device_id,created_at')
                .order('created_at', { ascending: false })
                .range(from, from + PAGE_SIZE);
            if (error) {
                await handleOperationError(error, 'تعذر تحميل بيانات السائقين.');
                return;
            }
            const rows = Array.isArray(data) ? data : [];
            state.hasMore.drivers = rows.length > PAGE_SIZE;
            renderDrivers(rows.slice(0, PAGE_SIZE));
            setNotice(`تم تحميل ${Math.min(rows.length, PAGE_SIZE)} سجل من السائقين.`, 'success');
        } catch (_) {
            setNotice('تعذر تحميل بيانات السائقين. تحقق من الاتصال ثم أعد المحاولة.', 'error');
        } finally {
            state.loading.drivers = false;
            el.refreshDrivers.disabled = false;
            renderPagination('drivers');
        }
    }

    function renderDrivers(rows) {
        el.driversList.replaceChildren();
        if (rows.length === 0) {
            appendEmptyRow(el.driversList, 6, 'لا توجد سجلات في هذه الصفحة.');
            return;
        }
        rows.forEach((driver) => {
            const row = document.createElement('tr');
            appendCell(row, displayValue(driver.full_name));
            appendCell(row, displayValue(driver.phone), 'ltr-cell');
            appendCell(row, displayValue(driver.status));
            appendCell(row, formatDate(driver.pro_until), 'ltr-cell');
            appendCell(row, displayValue(driver.device_id, 'غير مرتبط'), 'small-cell');
            const actionCell = document.createElement('td');
            const diagnose = document.createElement('button');
            diagnose.type = 'button';
            diagnose.className = 'btn-secondary';
            diagnose.textContent = 'تشخيص الشكوى';
            diagnose.addEventListener('click', () => { void diagnoseDriver(driver, diagnose); });
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'btn-danger';
            button.textContent = 'فك ربط الجهاز';
            button.addEventListener('click', () => { void resetDevice(driver.id, button); });
            actionCell.append(diagnose, button);
            row.append(actionCell);
            el.driversList.append(row);
        });
        labelCells(el.driversList);
    }

    async function resetDevice(id, button) {
        if (typeof id !== 'string' || !UUID_PATTERN.test(id)) {
            setNotice('تعذر التحقق من معرّف السائق المحدد.', 'error');
            return;
        }
        if (!window.confirm('هل أنت متأكد من فك ربط جهاز هذا السائق؟')) return;
        await runWithButton(button, async () => {
            if (!await ensureActiveSession()) return;
            const { error } = await db.rpc('admin_reset_device', { p_driver: id });
            if (error) {
                await handleOperationError(error, 'تعذر فك ربط الجهاز.');
                return;
            }
            setNotice('تم فك ربط الجهاز.', 'success');
            await loadDrivers();
        });
    }

    async function handleGenerateCodes(event) {
        event.preventDefault();
        const values = readGenerationValues();
        if (!values) return;
        await runWithButton(el.generateCodes, async () => {
            if (!await ensureActiveSession()) return;
            const { data, error } = await db.rpc('admin_generate_codes', {
                p_days: values.days,
                p_count: values.count,
                p_batch: values.batch
            });
            if (error) {
                await handleOperationError(error, 'تعذر توليد أكواد التفعيل.');
                return;
            }
            const generatedCount = Array.isArray(data) ? data.length : values.count;
            setNotice(`تم توليد ${generatedCount} كود تفعيل.`, 'success');
            el.codeBatch.value = '';
            state.offsets.codes = 0;
            await loadCodes();
        });
    }

    function readGenerationValues() {
        const count = Number(el.codeCount.value);
        const days = Number(el.codeDays.value);
        const batch = el.codeBatch.value.trim();
        if (!Number.isSafeInteger(count) || count < 1 || count > 500) {
            setNotice('يجب أن يكون عدد الأكواد رقمًا صحيحًا بين 1 و500.', 'error');
            el.codeCount.focus();
            return null;
        }
        if (!ALLOWED_CODE_DAYS.has(days)) {
            setNotice('مدة الاشتراك المحددة غير مسموح بها.', 'error');
            el.codeDays.focus();
            return null;
        }
        if (batch.length > MAX_BATCH_LENGTH || /[\u0000-\u001F\u007F]/.test(batch)) {
            setNotice('اسم الدفعة غير صالح. استخدم نصًا حتى 80 حرفًا دون محارف تحكم.', 'error');
            el.codeBatch.focus();
            return null;
        }
        return { count, days, batch: batch || null };
    }

    async function loadCodes({ reset = false } = {}) {
        if (reset) state.offsets.codes = 0;
        if (state.loading.codes || !await ensureActiveSession()) return;
        state.loading.codes = true;
        renderPagination('codes');
        el.refreshCodes.disabled = true;

        try {
            const from = state.offsets.codes;
            const { data, error } = await db
                .from('activation_codes')
                .select('code,days,batch,redeemed_by,revoked,created_at')
                .order('created_at', { ascending: false })
                .range(from, from + PAGE_SIZE);
            if (error) {
                await handleOperationError(error, 'تعذر تحميل أكواد التفعيل.');
                return;
            }
            const rows = Array.isArray(data) ? data : [];
            state.hasMore.codes = rows.length > PAGE_SIZE;
            renderCodes(rows.slice(0, PAGE_SIZE));
            setNotice(`تم تحميل ${Math.min(rows.length, PAGE_SIZE)} سجل من أكواد التفعيل.`, 'success');
        } catch (_) {
            setNotice('تعذر تحميل أكواد التفعيل. تحقق من الاتصال ثم أعد المحاولة.', 'error');
        } finally {
            state.loading.codes = false;
            el.refreshCodes.disabled = false;
            renderPagination('codes');
        }
    }

    function renderCodes(rows) {
        el.codesList.replaceChildren();
        if (rows.length === 0) {
            appendEmptyRow(el.codesList, 6, 'لا توجد أكواد في هذه الصفحة.');
            return;
        }
        rows.forEach((code) => {
            const row = document.createElement('tr');
            appendCell(row, displayValue(code.code), 'activation-code');
            appendCell(row, Number.isSafeInteger(code.days) ? String(code.days) : 'غير محدد');
            appendCell(row, displayValue(code.batch, '-'));
            const statusCell = document.createElement('td');
            const status = document.createElement('span');
            const unavailable = Boolean(code.redeemed_by || code.revoked);
            status.className = unavailable ? 'status-pill status-pill--unavailable' : 'status-pill status-pill--available';
            status.textContent = unavailable ? 'مستخدم/ملغى' : 'متاح';
            statusCell.append(status);
            row.append(statusCell);
            appendCell(row, formatDate(code.created_at), 'ltr-cell');
            appendCell(row, displayValue(code.redeemed_by, '-'), 'small-cell');
            el.codesList.append(row);
        });
        labelCells(el.codesList);
    }

    async function exportCodes() {
        if (!await ensureActiveSession()) return;
        try {
            const { data, error, count } = await db
                .from('activation_codes')
                .select('code,days,batch', { count: 'exact' })
                .is('redeemed_by', null)
                .eq('revoked', false)
                .order('created_at', { ascending: false })
                .limit(MAX_EXPORT_ROWS + 1);
            if (error) {
                await handleOperationError(error, 'تعذر تصدير أكواد التفعيل.');
                return;
            }
            if (!Number.isSafeInteger(count)) {
                setNotice('تعذر التأكد من اكتمال التصدير؛ لم يُنشأ أي ملف.', 'error');
                return;
            }
            if (count > MAX_EXPORT_ROWS) {
                setNotice(`التصدير محصور في ${MAX_EXPORT_ROWS} كود. استخدم واجهة تصدير خادمية مؤمنة للدفعات الأكبر؛ لم يُنشأ أي ملف.`, 'error');
                return;
            }
            const rows = Array.isArray(data) ? data : [];
            if (rows.length !== count) {
                setNotice('تعذر التأكد من اكتمال التصدير؛ لم يُنشأ أي ملف.', 'error');
                return;
            }
            if (rows.length === 0) {
                setNotice('لا توجد أكواد متاحة للتصدير.', 'info');
                return;
            }
            const csv = [
                ['الكود', 'المدة (أيام)', 'الدفعة'],
                ...rows.map((row) => [row.code, row.days, row.batch])
            ].map((row) => row.map(toCsvField).join(',')).join('\r\n');
            downloadCsv(csv);
            setNotice(`تم إنشاء ملف يضم ${rows.length} كودًا متاحًا. احفظه في موقع آمن.`, 'success');
        } catch (_) {
            setNotice('تعذر تصدير أكواد التفعيل. تحقق من الاتصال ثم أعد المحاولة.', 'error');
        }
    }

    function toCsvField(value) {
        const text = String(value ?? '').replace(/\u0000/g, '');
        const safeText = /^[\u0000-\u0020\uFEFF]*[=+\-@]/.test(text) ? `'${text}` : text;
        return `"${safeText.replace(/"/g, '""')}"`;
    }

    function downloadCsv(csv) {
        const blob = new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' });
        const objectUrl = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = objectUrl;
        link.download = 'sabbaq_available_codes.csv';
        link.hidden = true;
        document.body.append(link);
        link.click();
        link.remove();
        window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
    }

    async function handleOperationError(error, fallbackMessage) {
        if (isAuthorizationError(error)) {
            await signOut('انتهت الجلسة أو لم يعد الحساب مخوّلًا لتنفيذ هذه العملية.');
            return;
        }
        setNotice(fallbackMessage, 'error');
    }

    function isAuthorizationError(error) {
        return error && (error.code === '42501' || error.status === 401 || error.status === 403);
    }

    async function runWithButton(button, operation) {
        if (button.disabled) return;
        button.disabled = true;
        try {
            await operation();
        } finally {
            if (button.isConnected && !state.signingOut) button.disabled = false;
        }
    }

    function renderPagination(kind) {
        const isDrivers = kind === 'drivers';
        const offset = state.offsets[kind];
        const loading = state.loading[kind];
        const previous = isDrivers ? el.driversPrevious : el.codesPrevious;
        const next = isDrivers ? el.driversNext : el.codesNext;
        const label = isDrivers ? el.driversPage : el.codesPage;
        previous.disabled = loading || offset === 0;
        next.disabled = loading || !state.hasMore[kind];
        label.textContent = `الصفحة ${Math.floor(offset / PAGE_SIZE) + 1}`;
    }

    function appendCell(row, value, className = '') {
        const cell = document.createElement('td');
        if (className) cell.className = className;
        cell.textContent = value;
        row.append(cell);
    }

    // On phones each row is shown as a card: every cell carries its column name.
    function labelCells(tbody) {
        const headers = Array.from(tbody.closest('table').querySelectorAll('thead th'), (th) => th.textContent.trim());
        Array.from(tbody.rows).forEach((row) => {
            Array.from(row.cells).forEach((cell, index) => {
                if (headers[index]) cell.dataset.label = headers[index];
            });
        });
    }

    function appendEmptyRow(tbody, columns, message) {
        const row = document.createElement('tr');
        const cell = document.createElement('td');
        cell.colSpan = columns;
        cell.className = 'empty-cell';
        cell.textContent = message;
        row.append(cell);
        tbody.append(row);
    }

    function displayValue(value, fallback = 'غير محدد') {
        if (typeof value !== 'string') return fallback;
        const trimmed = value.trim();
        return trimmed ? trimmed : fallback;
    }

    function formatDate(value) {
        if (!value) return 'غير محدد';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return 'غير محدد';
        return new Intl.DateTimeFormat('ar-EG', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
    }

    function setNotice(message, type) {
        el.status.textContent = message;
        el.status.className = `notice notice--${type}`;
        el.status.hidden = false;
    }

    function renderFatalError(message) {
        const main = document.querySelector('main') || document.body;
        main.replaceChildren();
        const heading = document.createElement('h1');
        heading.textContent = 'تعذر فتح لوحة الإدارة';
        const paragraph = document.createElement('p');
        paragraph.className = 'notice notice--error';
        paragraph.textContent = message;
        main.append(heading, paragraph);
    }
})();
