import os

files = {
    'supabase/migrations/0002_activation_codes.sql': """-- جدول أكواد التفعيل
create table if not exists public.activation_codes (
    code text primary key,
    duration_days integer not null default 30,
    is_used boolean not null default false,
    used_by uuid references public.drivers(id),
    used_at timestamptz,
    created_at timestamptz not null default now()
);

-- دالة آمنة (RPC) يتم استدعاؤها من التطبيق لتفعيل الكود
create or replace function redeem_activation_code(activation_code text)
returns boolean language plpgsql security definer as $$
declare
    v_duration int;
    v_is_used boolean;
begin
    -- البحث عن الكود وإغلاقه لمنع الاستخدام المزدوج (Row Lock)
    select duration_days, is_used into v_duration, v_is_used
    from public.activation_codes where code = activation_code for update;

    -- إذا كان الكود غير موجود أو مستخدم
    if not found or v_is_used then
        return false;
    end if;

    -- تحديث الكود ليصبح مستخدماً
    update public.activation_codes
    set is_used = true, used_by = auth.uid(), used_at = now()
    where code = activation_code;

    -- تفعيل السائق وإضافة الأيام لاشتراكه
    update public.drivers
    set pro_until = greatest(coalesce(pro_until, now()), now()) + (v_duration || ' days')::interval,
        status = 'active'
    where id = auth.uid();

    return true;
end;
$$;
""",

    'admin/index.html': """<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
    <meta charset="UTF-8">
    <title>لوحة تحكم قناص</title>
    <script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2"></script>
    <style>
        body { font-family: Tahoma, Arial, sans-serif; background: #f4f7f6; padding: 20px; }
        .container { max-width: 1000px; margin: auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 0 10px rgba(0,0,0,0.1); }
        h1 { color: #2196F3; }
        .tabs { display: flex; border-bottom: 2px solid #ddd; margin-bottom: 20px; }
        .tab { padding: 10px 20px; cursor: pointer; background: #eee; border: 1px solid #ddd; border-bottom: none; margin-left: 5px; border-radius: 5px 5px 0 0; }
        .tab.active { background: #2196F3; color: white; border-color: #2196F3; }
        table { width: 100%; border-collapse: collapse; margin: 20px 0; }
        th, td { padding: 12px; border: 1px solid #ddd; text-align: right; }
        th { background: #2196F3; color: white; }
        button { background: #4CAF50; color: white; border: none; padding: 10px 15px; border-radius: 4px; cursor: pointer; font-weight: bold; }
        .btn-danger { background: #f44336; }
        .btn-secondary { background: #607d8b; }
        input, select { padding: 10px; margin: 5px 0; border: 1px solid #ccc; border-radius: 4px; }
        .card { background: #f9f9f9; padding: 15px; border: 1px solid #ddd; border-radius: 5px; margin-bottom: 20px; }
        .flex { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    </style>
</head>
<body>
    <div class="container">
        <h1>لوحة الإدارة القناص</h1>
        <div id="login-section">
            <input type="email" id="email" placeholder="البريد الإلكتروني (المشرف)">
            <input type="password" id="password" placeholder="كلمة المرور">
            <button onclick="login()">تسجيل الدخول</button>
        </div>
        
        <div id="dashboard-section" style="display: none;">
            <div class="tabs">
                <div class="tab active" onclick="showTab('drivers')">السائقين</div>
                <div class="tab" onclick="showTab('codes')">أكواد التفعيل</div>
            </div>

            <!-- قسم السائقين -->
            <div id="tab-drivers" class="tab-content">
                <button onclick="loadDrivers()">تحديث بيانات السائقين</button>
                <table>
                    <thead>
                        <tr><th>الاسم</th><th>الهاتف</th><th>الحالة</th><th>نهاية الاشتراك</th><th>معرف الجهاز</th><th>إجراءات</th></tr>
                    </thead>
                    <tbody id="drivers-list"></tbody>
                </table>
            </div>

            <!-- قسم الأكواد -->
            <div id="tab-codes" class="tab-content" style="display: none;">
                <div class="card">
                    <h3>توليد أكواد جديدة</h3>
                    <div class="flex">
                        <input type="number" id="code-count" placeholder="عدد الأكواد" value="10" min="1" max="500">
                        <select id="code-days">
                            <option value="7">7 أيام (أسبوع)</option>
                            <option value="30" selected>30 يوم (شهر)</option>
                            <option value="90">90 يوم (3 أشهر)</option>
                            <option value="365">365 يوم (سنة)</option>
                        </select>
                        <button onclick="generateCodes()">توليد الأكواد</button>
                        <button class="btn-secondary" onclick="exportCodes()">استخراج CSV (تصدير)</button>
                    </div>
                </div>
                <button onclick="loadCodes()">تحديث الأكواد</button>
                <table>
                    <thead>
                        <tr><th>الكود</th><th>المدة (أيام)</th><th>الحالة</th><th>تاريخ الإنشاء</th><th>المستخدم (ID)</th></tr>
                    </thead>
                    <tbody id="codes-list"></tbody>
                </table>
            </div>
        </div>
    </div>
    <script>
        const SUPABASE_URL = 'ضع_رابط_قاعدة_البيانات_هنا';
        const SUPABASE_KEY = 'ضع_مفتاح_API_هنا';
        const supabase = supabase.createClient(SUPABASE_URL, SUPABASE_KEY);

        function showTab(tabName) {
            document.querySelectorAll('.tab-content').forEach(el => el.style.display = 'none');
            document.querySelectorAll('.tab').forEach(el => el.classList.remove('active'));
            document.getElementById('tab-' + tabName).style.display = 'block';
            event.target.classList.add('active');
            if(tabName === 'codes') loadCodes();
            else loadDrivers();
        }

        async function login() {
            const { error } = await supabase.auth.signInWithPassword({
                email: document.getElementById('email').value,
                password: document.getElementById('password').value
            });
            if (!error) {
                document.getElementById('login-section').style.display = 'none';
                document.getElementById('dashboard-section').style.display = 'block';
                loadDrivers();
            } else alert("خطأ: " + error.message);
        }

        async function loadDrivers() {
            const { data, error } = await supabase.from('drivers').select('*').order('created_at', { ascending: false });
            if (data) {
                const tbody = document.getElementById('drivers-list');
                tbody.innerHTML = '';
                data.forEach(d => {
                    tbody.innerHTML += `<tr>
                        <td>${d.full_name}</td>
                        <td>${d.phone}</td>
                        <td>${d.status}</td>
                        <td dir="ltr" style="text-align:right">${d.pro_until ? new Date(d.pro_until).toLocaleDateString('ar-EG') : 'غير محدد'}</td>
                        <td style="font-size:11px">${d.device_id || 'غير مرتبط'}</td>
                        <td>
                            <button class="btn-danger" onclick="resetDevice('${d.id}')">فك ربط الجهاز</button>
                        </td>
                    </tr>`;
                });
            }
        }
        
        async function resetDevice(id) {
            if(confirm("هل أنت متأكد من فك ربط جهاز هذا السائق؟")) {
                await supabase.from('drivers').update({ device_id: null }).eq('id', id);
                loadDrivers();
            }
        }

        function generateRandomString(length) {
            const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // Excluded confusing chars like O,0,1,I
            let result = '';
            for (let i = 0; i < length; i++) result += chars.charAt(Math.floor(Math.random() * chars.length));
            return result;
        }

        async function generateCodes() {
            const count = parseInt(document.getElementById('code-count').value);
            const days = parseInt(document.getElementById('code-days').value);
            const newCodes = [];
            
            for(let i = 0; i < count; i++) {
                // Format: QAN-XXXX-XXXX
                const codeStr = 'QAN-' + generateRandomString(4) + '-' + generateRandomString(4);
                newCodes.push({ code: codeStr, duration_days: days, is_used: false });
            }

            const { error } = await supabase.from('activation_codes').insert(newCodes);
            if (error) {
                alert("حدث خطأ أثناء توليد الأكواد: " + error.message);
            } else {
                alert(`تم توليد ${count} كود بنجاح!`);
                loadCodes();
            }
        }

        async function loadCodes() {
            const { data, error } = await supabase.from('activation_codes').select('*').order('created_at', { ascending: false });
            if (data) {
                const tbody = document.getElementById('codes-list');
                tbody.innerHTML = '';
                data.forEach(c => {
                    const statusText = c.is_used ? '<span style="color:red">مستخدم</span>' : '<span style="color:green">متاح</span>';
                    tbody.innerHTML += `<tr>
                        <td style="font-weight:bold; letter-spacing:1px">${c.code}</td>
                        <td>${c.duration_days}</td>
                        <td>${statusText}</td>
                        <td dir="ltr" style="text-align:right">${new Date(c.created_at).toLocaleDateString('ar-EG')}</td>
                        <td style="font-size:10px">${c.used_by || '-'}</td>
                    </tr>`;
                });
            }
        }

        async function exportCodes() {
            const { data } = await supabase.from('activation_codes').select('*').eq('is_used', false);
            if (!data || data.length === 0) {
                alert('لا توجد أكواد متاحة للتصدير');
                return;
            }
            let csv = 'الكود,المدة (أيام)\n';
            data.forEach(row => { csv += `${row.code},${row.duration_days}\n`; });
            
            const blob = new Blob(["\ufeff" + csv], { type: 'text/csv;charset=utf-8;' });
            const link = document.createElement("a");
            const url = URL.createObjectURL(blob);
            link.setAttribute("href", url);
            link.setAttribute("download", "qannas_codes.csv");
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
        }
    </script>
</body>
</html>"""
}

for path, content in files.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"Created {path}")

