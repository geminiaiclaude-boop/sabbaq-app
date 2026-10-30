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
        idleTimer: null,
        signingOut: false,
        loginFailures: 0,
        loginBlockedUntil: 0,
        notificationDriver: null,
        notificationsLoaded: false,
        supportSelected: null,
        supportSelectedStatus: 'open',
        supportCorrelation: null,
        supportPendingReply: null,
        supportTimer: null,
        zonePolicy: null
    };
    const el = {};

    document.addEventListener('DOMContentLoaded', initialize);

    const WHATSAPP_PATTERN = /^[1-9]\d{7,14}$/;
    const NOTIFICATION_KINDS = { info: 'معلومة', warning: 'تنبيه', success: 'خبر سار', account: 'الاشتراك', update: 'تحديث التطبيق' };
    const NOTIFICATION_ACTIONS = new Set(['', 'open_account', 'open_setup', 'open_filters', 'update_app', 'open_support']);
    const SUPPORT_POLL_MS = 20 * 1000;
    const SUPPORT_FILTERS = new Set(['open', 'closed', 'all']);
    const NOTIFICATION_EXPIRY_DAYS = new Set(['', '1', '3', '7', '30']);

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
            retryCommercialPolicy: document.getElementById('retry-commercial-policy'),
            notificationsTab: document.getElementById('notifications-tab'),
            notificationsPanel: document.getElementById('tab-notifications'),
            notificationForm: document.getElementById('notification-form'),
            notificationTarget: document.getElementById('notification-target'),
            notificationTargetClear: document.getElementById('notification-target-clear'),
            notificationTitle: document.getElementById('notification-title'),
            notificationBody: document.getElementById('notification-body'),
            notificationBodyCount: document.getElementById('notification-body-count'),
            notificationKind: document.getElementById('notification-kind'),
            notificationAction: document.getElementById('notification-action'),
            notificationExpiry: document.getElementById('notification-expiry'),
            notificationError: document.getElementById('notification-error'),
            notificationSend: document.getElementById('notification-send'),
            notificationFeedback: document.getElementById('notification-feedback'),
            refreshNotifications: document.getElementById('refresh-notifications'),
            notificationsList: document.getElementById('notifications-list'),
            supportTab: document.getElementById('support-tab'),
            supportTabBadge: document.getElementById('support-tab-badge'),
            supportPanel: document.getElementById('tab-support'),
            supportFilter: document.getElementById('support-filter'),
            refreshSupport: document.getElementById('refresh-support'),
            supportFeedback: document.getElementById('support-feedback'),
            supportThreads: document.getElementById('support-threads'),
            supportConversation: document.getElementById('support-conversation'),
            supportDriverName: document.getElementById('support-driver-name'),
            supportDriverMeta: document.getElementById('support-driver-meta'),
            supportDiagnose: document.getElementById('support-diagnose'),
            supportToggleStatus: document.getElementById('support-toggle-status'),
            supportMessages: document.getElementById('support-messages'),
            supportReplyForm: document.getElementById('support-reply-form'),
            supportReply: document.getElementById('support-reply'),
            supportSend: document.getElementById('support-send'),
            zonesTab: document.getElementById('zones-tab'),
            zonesPanel: document.getElementById('tab-zones'),
            zoneLimitsForm: document.getElementById('zone-limits-form'),
            zoneDefaultMax: document.getElementById('zone-default-max'),
            zoneProximity: document.getElementById('zone-proximity'),
            zoneLimitsSave: document.getElementById('zone-limits-save'),
            zonesReload: document.getElementById('zones-reload'),
            zonePolicyMeta: document.getElementById('zone-policy-meta'),
            zoneCountryForm: document.getElementById('zone-country-form'),
            zoneCountry: document.getElementById('zone-country'),
            zoneCountryEnabled: document.getElementById('zone-country-enabled'),
            zoneCountrySave: document.getElementById('zone-country-save'),
            zoneCountryList: document.getElementById('zone-country-list'),
            zoneCountryJson: document.getElementById('zone-country-json'),
            zoneDriverForm: document.getElementById('zone-driver-form'),
            zoneDriverId: document.getElementById('zone-driver-id'),
            zoneDriverLimit: document.getElementById('zone-driver-limit'),
            zoneDriverRead: document.getElementById('zone-driver-read'),
            zoneDriverSave: document.getElementById('zone-driver-save'),
            placesEnabled: document.getElementById('places-enabled'),
            placesMeta: document.getElementById('places-meta'),
            zonesFeedback: document.getElementById('zones-feedback')
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

        bindEvents();
        renderPagination('drivers');
        renderPagination('codes');
        void restoreInMemorySession();
    }

    function createAdminClient() {
        return window.supabase.createClient(
            window.SABBAQ_CONFIG.supabaseUrl,
            window.SABBAQ_CONFIG.supabaseAnonKey,
            {
                auth: {
                    persistSession: false,
                    autoRefreshToken: true,
                    detectSessionInUrl: false
                }
            }
        );
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
        el.notificationsTab.addEventListener('click', () => { void showTab('notifications'); });
        el.zonesTab.addEventListener('click', () => { void showTab('zones'); });
        el.zonesReload.addEventListener('click', () => { void runWithButton(el.zonesReload, loadZones); });
        el.zoneLimitsForm.addEventListener('submit', handleZoneLimitsSave);
        el.zoneCountry.addEventListener('change', showZoneCountry);
        el.zoneCountryJson.addEventListener('input', () => renderZoneList(el.zoneCountryJson.value));
        el.zoneCountryForm.addEventListener('submit', handleZoneCountrySave);
        el.zoneDriverRead.addEventListener('click', () => { void runWithButton(el.zoneDriverRead, readDriverZoneLimit); });
        el.zoneDriverForm.addEventListener('submit', handleDriverZoneLimitSave);
        el.placesEnabled.addEventListener('change', () => { void handlePlacesToggle(); });
        el.supportTab.addEventListener('click', () => { void showTab('support'); });
        el.supportFilter.addEventListener('change', () => { void loadSupportThreads(); });
        el.refreshSupport.addEventListener('click', () => { void runWithButton(el.refreshSupport, refreshSupport); });
        el.supportReplyForm.addEventListener('submit', handleSupportReply);
        el.supportReply.addEventListener('input', () => { state.supportPendingReply = null; });
        el.supportToggleStatus.addEventListener('click', () => { void runWithButton(el.supportToggleStatus, toggleSupportStatus); });
        el.supportDiagnose.addEventListener('click', () => { void diagnoseSupportThread(); });
        el.notificationForm.addEventListener('submit', handleNotificationSend);
        el.notificationTargetClear.addEventListener('click', () => setNotificationTarget(null));
        el.refreshNotifications.addEventListener('click', () => { void runWithButton(el.refreshNotifications, loadNotifications); });
        el.notificationBody.addEventListener('input', () => {
            el.notificationBodyCount.textContent = `${el.notificationBody.value.length} / 500`;
            el.notificationError.hidden = true;
        });
        el.notificationTitle.addEventListener('input', () => { el.notificationError.hidden = true; });
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
            userResult = await db.auth.getUser();
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
        startSupportPolling();
    }

    async function signOut(message) {
        if (state.signingOut) return;
        state.signingOut = true;
        clearTimeout(state.idleTimer);
        stopSupportPolling();
        state.adminAuthorized = false;
        el.driversList.replaceChildren();
        el.codesList.replaceChildren();
        resetCommercialPolicyUi();
        state.zonePolicy = null;
        el.zoneCountryJson.value = '';
        el.zoneCountryList.replaceChildren();
        el.zoneDriverId.value = '';
        el.zonesFeedback.hidden = true;
        el.dashboard.hidden = true;
        el.loginSection.hidden = false;
        state.offsets.drivers = 0;
        state.offsets.codes = 0;
        state.hasMore.drivers = false;
        state.hasMore.codes = false;
        renderPagination('drivers');
        renderPagination('codes');

        try {
            const { error } = await db.auth.signOut();
            if (error) await db.auth.signOut({ scope: 'local' });
        } catch (_) {
            try { await db.auth.signOut({ scope: 'local' }); } catch (_) { /* The in-memory UI is already cleared. */ }
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
        // verifyAdminSession checks the user and the admin role together; one round per operation.
        const access = await verifyAdminSession();
        if (access !== 'authorized') {
            await signOut(access === 'forbidden'
                ? 'انتهت صلاحية المشرف لهذا الحساب.'
                : access === 'unauthenticated'
                    ? 'انتهت الجلسة أو لم يعد الحساب مخوّلًا.'
                    : 'تعذر التحقق من صلاحية الإدارة؛ لم تُنفذ العملية.');
            return false;
        }
        refreshIdleDeadline();
        return true;
    }

    async function showTab(tabName) {
        if (!['drivers', 'codes', 'commercial-policy', 'telemetry', 'profiles', 'notifications', 'support', 'zones'].includes(tabName)) return;
        state.activeTab = tabName;
        const showingDrivers = tabName === 'drivers';
        const showingCodes = tabName === 'codes';
        el.driversPanel.hidden = !showingDrivers;
        el.codesPanel.hidden = !showingCodes;
        el.commercialPolicyPanel.hidden = tabName !== 'commercial-policy';
        el.telemetryPanel.hidden = tabName !== 'telemetry';
        el.profilesPanel.hidden = tabName !== 'profiles';
        el.notificationsPanel.hidden = tabName !== 'notifications';
        el.supportPanel.hidden = tabName !== 'support';
        el.zonesPanel.hidden = tabName !== 'zones';
        el.zonesTab.classList.toggle('active', tabName === 'zones');
        el.zonesTab.setAttribute('aria-selected', String(tabName === 'zones'));
        el.driversTab.classList.toggle('active', showingDrivers);
        el.codesTab.classList.toggle('active', showingCodes);
        el.commercialPolicyTab.classList.toggle('active', tabName === 'commercial-policy');
        el.telemetryTab.classList.toggle('active', tabName === 'telemetry');
        el.profilesTab.classList.toggle('active', tabName === 'profiles');
        el.notificationsTab.classList.toggle('active', tabName === 'notifications');
        el.supportTab.classList.toggle('active', tabName === 'support');
        el.driversTab.setAttribute('aria-selected', String(showingDrivers));
        el.codesTab.setAttribute('aria-selected', String(showingCodes));
        el.commercialPolicyTab.setAttribute('aria-selected', String(tabName === 'commercial-policy'));
        el.telemetryTab.setAttribute('aria-selected', String(tabName === 'telemetry'));
        el.profilesTab.setAttribute('aria-selected', String(tabName === 'profiles'));
        el.notificationsTab.setAttribute('aria-selected', String(tabName === 'notifications'));
        el.supportTab.setAttribute('aria-selected', String(tabName === 'support'));
        if (showingDrivers) await loadDrivers();
        else if (showingCodes) await loadCodes();
        else if (tabName === 'commercial-policy' && !state.commercialPolicyLoaded) await loadCommercialPolicy({ announce: true });
        else if (tabName === 'profiles' && !state.profiles) await loadProfiles();
        else if (tabName === 'notifications' && !state.notificationsLoaded) await loadNotifications();
        else if (tabName === 'support') await refreshSupport();
        else if (tabName === 'zones' && !state.zonePolicy) await loadZones();
    }

    // ---- Zones (0017 zone policy, 0018 Places)
    const ZONE_COUNTRIES = new Set(['SA', 'JO']);

    // Mirrors private.zones_ok so a bad edit is refused before it reaches the server.
    function validateZonesDraft(text) {
        let zones;
        try { zones = JSON.parse(text.trim() || '[]'); } catch (_) { return { error: 'المحتوى ليس JSON صالحًا.' }; }
        if (!Array.isArray(zones)) return { error: 'المناطق يجب أن تكون قائمة JSON.' };
        if (zones.length > 50) return { error: '50 منطقة على الأكثر لكل دولة.' };
        for (let i = 0; i < zones.length; i++) {
            const z = zones[i];
            const label = `المنطقة ${i + 1}`;
            if (!z || typeof z !== 'object' || Array.isArray(z)) return { error: `${label}: يجب أن تكون كائنًا.` };
            if (typeof z.name !== 'string' || z.name.trim().length < 1 || z.name.trim().length > 60) return { error: `${label}: الاسم من 1 إلى 60 حرفًا.` };
            if ('active' in z && typeof z.active !== 'boolean') return { error: `${label}: active يجب أن تكون true أو false.` };
            if (!Array.isArray(z.points) || z.points.length < 3 || z.points.length > 100) return { error: `${label}: من 3 إلى 100 نقطة.` };
            for (const p of z.points) {
                if (!Array.isArray(p) || p.length !== 2 || !p.every(v => typeof v === 'number' && Number.isFinite(v))
                    || Math.abs(p[0]) > 90 || Math.abs(p[1]) > 180) {
                    return { error: `${label}: كل نقطة [خط العرض, خط الطول] بأرقام صحيحة المدى.` };
                }
            }
        }
        return { zones };
    }

    function setZonesFeedback(message, type) {
        el.zonesFeedback.textContent = message;
        el.zonesFeedback.className = `policy-feedback policy-feedback--${type}`;
        el.zonesFeedback.hidden = false;
    }

    async function zonesRpc(name, args, failure) {
        if (!await ensureActiveSession()) return null;
        const { data, error } = await db.rpc(name, args);
        if (error) {
            if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
            else setZonesFeedback(failure, 'error');
            return null;
        }
        return data;
    }

    async function loadZones() {
        setZonesFeedback('يجري تحميل سياسة المناطق…', 'info');
        const data = await zonesRpc('admin_zone_policy', {}, 'تعذر تحميل سياسة المناطق. هل طُبّق الترحيل 0017 على الخادم؟');
        if (!data) return;
        showZonePolicy(data);
        const places = await zonesRpc('admin_places_settings', {}, 'تعذر قراءة إعداد Google Places. هل طُبّق الترحيل 0018؟');
        if (places) showPlaces(places);
        if (places) el.zonesFeedback.hidden = true;
    }

    function showZonePolicy(data) {
        state.zonePolicy = data;
        el.zoneDefaultMax.value = String(data.default_max_zones ?? 0);
        el.zoneProximity.value = String(data.proximity_km ?? 0);
        el.zonePolicyMeta.textContent = `إصدار السياسة ${data.version ?? 0}. يصل كل تعديل للسائقين عند التحديث التالي مع إشعار «مناطق استثناء جديدة».`;
        showZoneCountry();
    }

    function showZoneCountry() {
        const country = ((state.zonePolicy || {}).countries || {})[el.zoneCountry.value] || {};
        el.zoneCountryEnabled.checked = country.enabled === true;
        el.zoneCountryJson.value = JSON.stringify(Array.isArray(country.zones) ? country.zones : [], null, 2);
        renderZoneList(el.zoneCountryJson.value);
    }

    function renderZoneList(text) {
        el.zoneCountryList.replaceChildren();
        const draft = validateZonesDraft(text);
        const item = (message) => { const li = document.createElement('li'); li.textContent = message; el.zoneCountryList.append(li); };
        if (draft.error) { item(draft.error); return; }
        if (draft.zones.length === 0) { item('لا مناطق لهذه الدولة.'); return; }
        draft.zones.forEach((z) => item(`${z.name.trim()} · ${z.points.length} نقطة${z.active === false ? ' · متوقفة' : ''}`));
    }

    async function handleZoneLimitsSave(event) {
        event.preventDefault();
        const max = Number(el.zoneDefaultMax.value);
        const km = Number(el.zoneProximity.value);
        if (!Number.isInteger(max) || max < 0 || max > 100 || !Number.isFinite(km) || km < 0 || km > 1000) {
            setZonesFeedback('الحد من 0 إلى 100، والمسافة من 0 إلى 1000 كم.', 'error');
            return;
        }
        await runWithButton(el.zoneLimitsSave, async () => {
            const data = await zonesRpc('admin_set_zone_limits', { p_default_max: max, p_proximity_km: km }, 'رفض الخادم الحدود.');
            if (!data) return;
            showZonePolicy(data);
            setZonesFeedback('حُفظت الحدود.', 'success');
        });
    }

    async function handleZoneCountrySave(event) {
        event.preventDefault();
        const country = el.zoneCountry.value;
        const draft = validateZonesDraft(el.zoneCountryJson.value);
        if (!ZONE_COUNTRIES.has(country) || draft.error) {
            setZonesFeedback(draft.error || 'الدولة غير معروفة.', 'error');
            return;
        }
        await runWithButton(el.zoneCountrySave, async () => {
            const data = await zonesRpc('admin_set_global_zones', { p_country: country, p_enabled: el.zoneCountryEnabled.checked, p_zones: draft.zones },
                'رفض الخادم المناطق. تحقق من الأسماء والنقاط.');
            if (!data) return;
            showZonePolicy(data);
            setZonesFeedback(`نُشرت مناطق ${country === 'SA' ? 'السعودية' : 'الأردن'} (${draft.zones.length}).`, 'success');
        });
    }

    function driverZoneId() {
        const id = el.zoneDriverId.value.trim();
        if (!UUID_PATTERN.test(id)) { setZonesFeedback('أدخل معرّف سائق صالحًا (UUID)، أو اضغط «حدّ المناطق» بجانبه في تبويب السائقين.', 'error'); return null; }
        return id;
    }

    function showDriverZoneLimit(data) {
        el.zoneDriverLimit.value = data.own === null || data.own === undefined ? '' : String(data.own);
        setZonesFeedback(`حدّ السائق: ${data.own ?? `الافتراضي (${data.default ?? 0})`}${data.country ? ` · الدولة ${data.country}` : ''}. 0 = بلا حد.`, 'info');
    }

    async function readDriverZoneLimit() {
        const id = driverZoneId();
        if (!id) return;
        const data = await zonesRpc('admin_driver_zone_limit', { p_driver: id }, 'تعذر قراءة حدّ السائق.');
        if (data) showDriverZoneLimit(data);
    }

    async function handleDriverZoneLimitSave(event) {
        event.preventDefault();
        const id = driverZoneId();
        if (!id) return;
        const raw = el.zoneDriverLimit.value.trim();
        const limit = raw === '' ? null : Number(raw);
        if (limit !== null && (!Number.isInteger(limit) || limit < 0 || limit > 100)) {
            setZonesFeedback('الحد من 0 إلى 100، أو فارغ للعودة إلى الافتراضي.', 'error');
            return;
        }
        await runWithButton(el.zoneDriverSave, async () => {
            const data = await zonesRpc('admin_set_driver_zone_limit', { p_driver: id, p_limit: limit }, 'رفض الخادم حدّ السائق.');
            if (data) showDriverZoneLimit(data);
        });
    }

    async function zoneLimitFor(driver) {
        await showTab('zones');
        el.zoneDriverId.value = driver.id;
        await readDriverZoneLimit();
    }

    function showPlaces(data) {
        el.placesEnabled.checked = data.places_enabled === true;
        el.placesMeta.textContent = `${data.cells ?? 0} خلية محفوظة`;
    }

    async function handlePlacesToggle() {
        const wanted = el.placesEnabled.checked;
        el.placesEnabled.disabled = true;
        try {
            const data = await zonesRpc('admin_places_settings', { p_enabled: wanted }, 'لم نتأكد من الحفظ؛ أعد التحميل.');
            if (data) {
                showPlaces(data);
                setZonesFeedback(wanted ? 'شُغّلت أسماء Google Places. تعمل فقط إذا كان Google مشغّلًا ومفتاحه محفوظًا.' : 'أُوقفت أسماء Google Places.', 'success');
            } else {
                el.placesEnabled.checked = !wanted;
            }
        } finally {
            el.placesEnabled.disabled = false;
        }
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
            : 'لا توجد ملفات منشورة بعد؛ التطبيق يستعمل الملفات المضمّنة (الإصدار 3). الصق ملفًا برقم إصدار 4 أو أكبر لنشر إصلاح.', 'info');
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
            target_other_profile: ['تطبيق جيني مثبت في ملف آخر (نسخة مزدوجة أو ملف عمل أو مجلد آمن) لا يستطيع سبّاق قراءته', 'وجّهه: ثبّت جيني في المساحة الأساسية للجوال وسجّل الدخول منه، واترك النسخة المزدوجة.'],
            target_unknown_package: ['جيني مثبت بحزمة لا يعرفها ملف التطبيقات', 'أضف الحزمة الظاهرة في حدث PACKAGE_SEEN إلى ملف جيني في تبويب «ملفات التطبيقات»؛ يصل للسائقين دون إصدار جديد.'],
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
                price_too_low: ['أجرة الطلبات أقل من الحد الأدنى الذي حدده', 'راجع معه الحد الأدنى للأجرة.'],
                price_too_high: ['أجرة الطلبات أعلى من الحد الأعلى الذي حدده', 'راجع معه الحد الأعلى للأجرة.'],
                price_missing: ['الأجرة لا تُقرأ من الطلب وشرط الأجرة مفعّل', 'سبب تقني في قراءة الأجرة؛ يلزم تحديث المحلل، ومؤقتًا يمكنه تعطيل شرط الأجرة.'],
                trip_km_missing: ['جيني لا يعرض مسافة الرحلة في بعض الطلبات وشرطها مفعّل', 'إن أراد قبول هذه الطلبات فليوقف شرط مسافة الرحلة لطلبات جيني.'],
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
            void loadDriverVersions(rows.slice(0, PAGE_SIZE).map((driver) => driver.id));
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
            appendEmptyRow(el.driversList, 7, 'لا توجد سجلات في هذه الصفحة.');
            return;
        }
        rows.forEach((driver) => {
            const row = document.createElement('tr');
            appendCell(row, displayValue(driver.full_name));
            appendCell(row, displayValue(driver.phone), 'ltr-cell');
            appendCell(row, displayValue(driver.status));
            const versionCell = document.createElement('td');
            versionCell.className = 'small-cell';
            versionCell.dataset.versionFor = driver.id;
            versionCell.textContent = '…';
            row.append(versionCell);
            appendCell(row, formatDate(driver.pro_until), 'ltr-cell');
            appendCell(row, displayValue(driver.device_id, 'غير مرتبط'), 'small-cell');
            const actionCell = document.createElement('td');
            const diagnose = document.createElement('button');
            diagnose.type = 'button';
            diagnose.className = 'btn-secondary';
            diagnose.textContent = 'تشخيص الشكوى';
            diagnose.addEventListener('click', () => { void diagnoseDriver(driver, diagnose); });
            const notify = document.createElement('button');
            notify.type = 'button';
            notify.className = 'btn-secondary';
            notify.textContent = 'إرسال إشعار';
            notify.addEventListener('click', () => { void notifyDriver(driver); });
            const message = document.createElement('button');
            message.type = 'button';
            message.className = 'btn-secondary';
            message.textContent = 'مراسلة';
            message.addEventListener('click', () => { void messageDriver(driver); });
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'btn-danger';
            button.textContent = 'فك ربط الجهاز';
            button.addEventListener('click', () => { void resetDevice(driver.id, button); });
            const zones = document.createElement('button');
            zones.type = 'button';
            zones.className = 'btn-secondary';
            zones.textContent = 'حدّ المناطق';
            zones.addEventListener('click', () => { void zoneLimitFor(driver); });
            actionCell.append(diagnose, notify, message, zones, button);
            row.append(actionCell);
            el.driversList.append(row);
        });
        labelCells(el.driversList);
    }

    // ---- Support inbox
    function startSupportPolling() {
        stopSupportPolling();
        void refreshSupportBadge();
        state.supportTimer = setInterval(() => {
            if (document.hidden || el.dashboard.hidden) return;
            if (state.activeTab === 'support') void refreshSupport({ quiet: true });
            else void refreshSupportBadge();
        }, SUPPORT_POLL_MS);
    }

    function stopSupportPolling() {
        if (state.supportTimer) clearInterval(state.supportTimer);
        state.supportTimer = null;
        state.supportSelected = null;
        state.supportPendingReply = null;
        el.supportThreads.replaceChildren();
        el.supportMessages.replaceChildren();
        el.supportConversation.hidden = true;
        el.supportTabBadge.hidden = true;
    }

    async function refreshSupport({ quiet = false } = {}) {
        await loadSupportThreads({ quiet });
        if (state.supportSelected) await openSupportThread(state.supportSelected, { quiet: true, reloadList: false });
    }

    async function refreshSupportBadge() {
        try {
            const { data, error } = await db.rpc('admin_support_threads', { p_status: 'open', p_limit: 200 });
            if (error) throw error;
            renderSupportBadge(Array.isArray(data) ? data : []);
        } catch (error) {
            if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
        }
    }

    function renderSupportBadge(rows) {
        const unread = rows.reduce((sum, row) => sum + (Number.isFinite(Number(row.admin_unread)) ? Number(row.admin_unread) : 0), 0);
        el.supportTabBadge.textContent = unread > 99 ? '99+' : String(unread);
        el.supportTabBadge.hidden = unread === 0;
        el.supportTab.setAttribute('aria-label', unread > 0 ? `الدعم، ${unread} رسالة غير مقروءة` : 'الدعم');
    }

    async function loadSupportThreads({ quiet = false } = {}) {
        if (!await ensureActiveSession()) return;
        const filter = SUPPORT_FILTERS.has(el.supportFilter.value) ? el.supportFilter.value : 'open';
        try {
            const { data, error } = await db.rpc('admin_support_threads', { p_status: filter === 'all' ? null : filter, p_limit: 200 });
            if (error) throw error;
            const rows = Array.isArray(data) ? data : [];
            renderSupportThreads(rows);
            if (filter === 'open') renderSupportBadge(rows);
            else void refreshSupportBadge();
        } catch (error) {
            if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
            else if (!quiet) setSupportFeedback('تعذر تحميل المحادثات. تحقق من الاتصال.', 'error');
        }
    }

    function renderSupportThreads(rows) {
        el.supportThreads.replaceChildren();
        if (rows.length === 0) {
            const empty = document.createElement('li');
            empty.className = 'field-hint';
            empty.textContent = 'لا توجد محادثات في هذا العرض.';
            el.supportThreads.append(empty);
            return;
        }
        rows.forEach((row) => {
            if (typeof row?.driver_id !== 'string' || !UUID_PATTERN.test(row.driver_id)) return;
            const item = document.createElement('li');
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'support-thread';
            button.setAttribute('aria-current', String(row.driver_id === state.supportSelected));
            const name = document.createElement('span');
            name.className = 'support-thread__name';
            name.textContent = displayValue(row.driver_name, 'سائق');
            const preview = document.createElement('span');
            preview.className = 'support-thread__preview';
            const last = row.last_message && typeof row.last_message.body === 'string' ? row.last_message : null;
            preview.textContent = last ? `${last.sender === 'admin' ? 'أنت: ' : ''}${last.body}` : 'لا رسائل';
            const meta = document.createElement('span');
            meta.className = 'support-thread__unread';
            const unread = Number(row.admin_unread) || 0;
            meta.textContent = `${formatDateTime(row.last_message_at)}${unread > 0 ? ` · ${unread} جديدة` : ''}${row.status === 'closed' ? ' · مغلقة' : ''}`;
            button.append(name, preview, meta);
            button.addEventListener('click', () => { void openSupportThread(row.driver_id); });
            item.append(button);
            el.supportThreads.append(item);
        });
    }

    async function messageDriver(driver) {
        if (typeof driver?.id !== 'string' || !UUID_PATTERN.test(driver.id)) {
            setNotice('تعذر التحقق من معرّف السائق المحدد.', 'error');
            return;
        }
        state.supportSelected = driver.id;
        await showTab('support');
        el.supportReply.focus();
    }

    async function openSupportThread(driverId, { quiet = false, reloadList = true } = {}) {
        if (typeof driverId !== 'string' || !UUID_PATTERN.test(driverId)) return;
        if (!await ensureActiveSession()) return;
        if (state.supportSelected !== driverId) state.supportPendingReply = null;
        state.supportSelected = driverId;
        try {
            const { data, error } = await db.rpc('admin_support_thread', { p_driver: driverId, p_limit: 200 });
            if (error) throw error;
            if (state.supportSelected !== driverId) return;
            renderSupportThread(data);
            if (reloadList) await loadSupportThreads({ quiet: true });
        } catch (error) {
            if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
            else if (!quiet) setSupportFeedback('تعذر فتح المحادثة.', 'error');
        }
    }

    function renderSupportThread(data) {
        const messages = Array.isArray(data?.messages) ? data.messages : [];
        state.supportSelectedStatus = data?.status === 'closed' ? 'closed' : 'open';
        state.supportCorrelation = typeof data?.last_correlation_id === 'string' && /^[a-f0-9]{16,64}$/.test(data.last_correlation_id)
            ? data.last_correlation_id : null;
        el.supportConversation.hidden = false;
        el.supportDriverName.textContent = displayValue(data?.driver_name, 'سائق');
        const meta = [displayValue(data?.driver_phone, ''), data?.last_app_version ? `الإصدار ${displayValue(data.last_app_version)}` : '']
            .filter(Boolean).join(' · ');
        el.supportDriverMeta.textContent = meta || 'لا توجد بيانات إضافية';
        el.supportDiagnose.disabled = !state.supportCorrelation;
        el.supportToggleStatus.textContent = state.supportSelectedStatus === 'closed' ? 'إعادة فتح المحادثة' : 'إغلاق المحادثة';
        const wasAtBottom = el.supportMessages.scrollHeight - el.supportMessages.scrollTop - el.supportMessages.clientHeight < 40;
        el.supportMessages.replaceChildren();
        if (messages.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'field-hint';
            empty.textContent = 'لا رسائل بعد. يمكنك بدء المحادثة مع السائق.';
            el.supportMessages.append(empty);
        }
        messages.forEach((message) => {
            if (typeof message?.body !== 'string') return;
            const bubble = document.createElement('div');
            bubble.className = `support-bubble support-bubble--${message.sender === 'admin' ? 'admin' : 'driver'}`;
            bubble.textContent = message.body;
            const time = document.createElement('span');
            time.className = 'support-bubble__time';
            time.textContent = `${message.sender === 'admin' ? 'الإدارة' : 'السائق'} · ${formatDateTime(message.created_at)}`;
            bubble.append(time);
            el.supportMessages.append(bubble);
        });
        if (wasAtBottom || messages.length < 3) el.supportMessages.scrollTop = el.supportMessages.scrollHeight;
    }

    async function handleSupportReply(event) {
        event.preventDefault();
        const driverId = state.supportSelected;
        const body = el.supportReply.value.trim();
        if (!driverId) return;
        if (body.length < 1 || body.length > 1000) {
            setSupportFeedback('اكتب ردًا بين حرف و1000 حرف.', 'error');
            el.supportReply.focus();
            return;
        }
        // The same id is reused until the server confirms, so a retried click never duplicates.
        if (!state.supportPendingReply) state.supportPendingReply = newClientId();
        await runWithButton(el.supportSend, async () => {
            if (!await ensureActiveSession()) return;
            try {
                const { error } = await db.rpc('admin_support_reply', { p_driver: driverId, p_client_id: state.supportPendingReply, p_body: body });
                if (error) throw error;
                state.supportPendingReply = null;
                el.supportReply.value = '';
                setSupportFeedback('أُرسل الرد وسيصل السائق كإشعار.', 'success');
                await openSupportThread(driverId, { quiet: true });
            } catch (error) {
                if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
                else setSupportFeedback('تعذر إرسال الرد. أعد المحاولة؛ لن يتكرر الرد.', 'error');
            }
        });
    }

    async function toggleSupportStatus() {
        const driverId = state.supportSelected;
        if (!driverId || !await ensureActiveSession()) return;
        const next = state.supportSelectedStatus === 'closed' ? 'open' : 'closed';
        try {
            const { error } = await db.rpc('admin_support_set_status', { p_driver: driverId, p_status: next });
            if (error) throw error;
            setSupportFeedback(next === 'closed' ? 'أُغلقت المحادثة. ستُفتح تلقائيًا إذا كتب السائق.' : 'أُعيد فتح المحادثة.', 'success');
            await refreshSupport({ quiet: true });
        } catch (error) {
            await handleOperationError(error, 'تعذر تغيير حالة المحادثة.');
        }
    }

    async function diagnoseSupportThread() {
        if (!state.supportCorrelation) return;
        await showTab('telemetry');
        el.telemetryCorrelation.value = state.supportCorrelation;
        el.telemetryForm.requestSubmit();
    }

    function newClientId() {
        if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }

    function setSupportFeedback(message, type) {
        el.supportFeedback.textContent = message;
        el.supportFeedback.className = `policy-feedback policy-feedback--${type}`;
        el.supportFeedback.hidden = false;
    }

    async function notifyDriver(driver) {
        if (typeof driver?.id !== 'string' || !UUID_PATTERN.test(driver.id)) {
            setNotice('تعذر التحقق من معرّف السائق المحدد.', 'error');
            return;
        }
        setNotificationTarget({ id: driver.id, name: displayValue(driver.full_name, 'سائق') });
        await showTab('notifications');
        el.notificationTitle.focus();
    }

    function setNotificationTarget(driver) {
        state.notificationDriver = driver;
        el.notificationTarget.textContent = driver ? driver.name : 'كل السائقين';
        el.notificationTargetClear.hidden = !driver;
        el.notificationSend.textContent = driver ? `إرسال إلى ${driver.name}` : 'إرسال لكل السائقين';
    }

    function readNotificationValues() {
        const title = el.notificationTitle.value.trim();
        const body = el.notificationBody.value.trim();
        const kind = el.notificationKind.value;
        const action = el.notificationAction.value;
        const expiry = el.notificationExpiry.value;
        if (title.length < 2 || title.length > 80) return { error: 'العنوان بين حرفين و80 حرفًا.', field: el.notificationTitle };
        if (body.length < 2 || body.length > 500) return { error: 'النص بين حرفين و500 حرف.', field: el.notificationBody };
        if (!Object.hasOwn(NOTIFICATION_KINDS, kind) || !NOTIFICATION_ACTIONS.has(action) || !NOTIFICATION_EXPIRY_DAYS.has(expiry)) {
            return { error: 'اختيار غير صالح؛ أعد تحميل الصفحة.', field: el.notificationKind };
        }
        const expiresAt = expiry ? new Date(Date.now() + Number(expiry) * 24 * 60 * 60 * 1000).toISOString() : null;
        return { title, body, kind, action: action || null, expiresAt };
    }

    async function handleNotificationSend(event) {
        event.preventDefault();
        const values = readNotificationValues();
        if (values.error) {
            el.notificationError.textContent = values.error;
            el.notificationError.hidden = false;
            values.field.focus();
            return;
        }
        const target = state.notificationDriver;
        if (!target && !window.confirm('سيصل هذا الإشعار إلى كل السائقين. هل تريد الإرسال؟')) return;
        await runWithButton(el.notificationSend, async () => {
            if (!await ensureActiveSession()) return;
            try {
                const { error } = await db.rpc('admin_send_notification', {
                    p_driver: target ? target.id : null,
                    p_title: values.title,
                    p_body: values.body,
                    p_kind: values.kind,
                    p_action: values.action,
                    p_expires_at: values.expiresAt
                });
                if (error) throw error;
                setNotificationFeedback(target ? `أُرسل الإشعار إلى ${target.name}.` : 'أُرسل الإشعار لكل السائقين.', 'success');
                el.notificationForm.reset();
                el.notificationBodyCount.textContent = '0 / 500';
                setNotificationTarget(null);
                await loadNotifications();
            } catch (error) {
                if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
                else setNotificationFeedback(error?.code === '22023'
                    ? 'رفض الخادم الإشعار: تحقق من العنوان والنص والمستلم.'
                    : 'تعذر إرسال الإشعار. تحقق من الاتصال ثم أعد المحاولة.', 'error');
            }
        });
    }

    async function loadNotifications() {
        if (!await ensureActiveSession()) return;
        el.notificationsList.replaceChildren();
        appendEmptyRow(el.notificationsList, 6, 'جارٍ التحميل…');
        try {
            const { data, error } = await db.rpc('admin_list_notifications', { p_limit: 100 });
            if (error) throw error;
            state.notificationsLoaded = true;
            renderNotifications(Array.isArray(data) ? data : []);
        } catch (error) {
            if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
            else {
                el.notificationsList.replaceChildren();
                appendEmptyRow(el.notificationsList, 6, 'تعذر تحميل الإشعارات المرسلة.');
            }
        }
    }

    function renderNotifications(rows) {
        el.notificationsList.replaceChildren();
        if (rows.length === 0) {
            appendEmptyRow(el.notificationsList, 6, 'لم يُرسل أي إشعار بعد.');
            return;
        }
        rows.forEach((item) => {
            const row = document.createElement('tr');
            appendCell(row, formatDateTime(item.created_at), 'ltr-cell');
            appendCell(row, item.driver_id ? displayValue(item.driver_name, 'سائق') : 'كل السائقين');
            appendCell(row, displayValue(item.title));
            appendCell(row, NOTIFICATION_KINDS[item.kind] || 'معلومة');
            appendCell(row, String(Number.isFinite(Number(item.read_count)) ? Number(item.read_count) : 0));
            const actionCell = document.createElement('td');
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'btn-danger';
            remove.textContent = 'حذف';
            remove.addEventListener('click', () => { void deleteNotification(item.id, remove); });
            actionCell.append(remove);
            row.append(actionCell);
            el.notificationsList.append(row);
        });
        labelCells(el.notificationsList);
    }

    async function deleteNotification(id, button) {
        if (typeof id !== 'string' || !UUID_PATTERN.test(id)) return;
        if (!window.confirm('سيختفي هذا الإشعار من تطبيقات السائقين. هل تريد الحذف؟')) return;
        await runWithButton(button, async () => {
            if (!await ensureActiveSession()) return;
            try {
                const { error } = await db.rpc('admin_delete_notification', { p_id: id });
                if (error) throw error;
                setNotificationFeedback('حُذف الإشعار.', 'success');
                await loadNotifications();
            } catch (error) {
                await handleOperationError(error, 'تعذر حذف الإشعار.');
            }
        });
    }

    function setNotificationFeedback(message, type) {
        el.notificationFeedback.textContent = message;
        el.notificationFeedback.className = `policy-feedback policy-feedback--${type}`;
        el.notificationFeedback.hidden = false;
    }

    // The Sabbaq build each driver runs: version name and Android from the latest telemetry,
    // the version code from the latest licence check, and when the driver was last seen.
    async function loadDriverVersions(ids) {
        const valid = ids.filter((id) => typeof id === 'string' && UUID_PATTERN.test(id));
        if (valid.length === 0) return;
        try {
            const { data, error } = await db.rpc('admin_driver_versions', { p_drivers: valid });
            if (error) throw error;
            const byId = new Map((Array.isArray(data) ? data : []).map((row) => [row.driver_id, row]));
            el.driversList.querySelectorAll('td[data-version-for]').forEach((cell) => {
                cell.textContent = describeDriverVersion(byId.get(cell.dataset.versionFor));
            });
        } catch (error) {
            if (isAuthorizationError(error)) await signOut('انتهت صلاحية جلسة المشرف.');
            else el.driversList.querySelectorAll('td[data-version-for]').forEach((cell) => { cell.textContent = 'تعذر التحميل'; });
        }
    }

    function describeDriverVersion(row) {
        if (!row) return 'غير معروف';
        const name = typeof row.version_name === 'string' && /^[A-Za-z0-9._-]{1,32}$/.test(row.version_name) ? row.version_name : null;
        const code = Number.isInteger(Number(row.version_code)) && Number(row.version_code) > 0 ? Number(row.version_code) : null;
        const android = androidRelease(Number(row.android_api));
        const parts = [];
        if (name) parts.push(code ? `${name} (${code})` : name);
        else if (code) parts.push(`رمز ${code}`);
        else parts.push('لم يتصل بعد');
        if (android) parts.push(`أندرويد ${android}`);
        if (row.last_seen_at) parts.push(`آخر ظهور ${formatDateTime(row.last_seen_at)}`);
        return parts.join(' · ');
    }

    function androidRelease(api) {
        const releases = { 26: '8', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 32: '12L', 33: '13', 34: '14', 35: '15', 36: '16' };
        return Number.isInteger(api) ? releases[api] || `API ${api}` : null;
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
