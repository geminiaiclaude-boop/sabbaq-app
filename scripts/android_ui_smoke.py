#!/usr/bin/env python3
"""Native Android launch/screenshot smoke test; never signs in or starts KeeperService."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--apk', required=True)
parser.add_argument('--package', required=True)
parser.add_argument('--out', required=True)
parser.add_argument('--distribution', choices=['direct', 'play'], required=True)
args = parser.parse_args()
out = pathlib.Path(args.out)
out.mkdir(parents=True, exist_ok=True)

def adb(*params, binary=False, timeout=45, transport_retries=0):
    for attempt in range(transport_retries + 1):
        try:
            return subprocess.check_output(
                ['adb', *params], timeout=timeout, text=not binary, stderr=subprocess.STDOUT
            )
        except subprocess.CalledProcessError as error:
            output = error.output.decode(errors='replace') if isinstance(error.output, bytes) else (error.output or '')
            transient = any(marker in output.lower() for marker in ('device offline', 'device not found', 'no devices/emulators found'))
            if not transient or attempt >= transport_retries:
                raise
            print(f'adb transport unavailable; waiting and retrying safe command ({attempt + 1}/{transport_retries})', flush=True)
            subprocess.run(['adb', 'reconnect', 'offline'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
            subprocess.run(['adb', 'wait-for-device'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60, check=True)
            time.sleep(1)


def screencap(png=True):
    """Screen capture with retries: on a loaded CI emulator adb occasionally drops the
    connection (exit 255) for one call; a single dropped capture is not an app failure."""
    for attempt in range(4):
        try:
            return adb('exec-out', 'screencap', *(['-p'] if png else []), binary=True)
        except subprocess.CalledProcessError:
            if attempt == 3:
                raise
            time.sleep(2)



def force_stop():
    """Stopping the app is idempotent, so any failure (not only a lost transport) is retried: the
    emulator's activity manager can refuse one call while it is busy (seen 2026-10-31)."""
    for attempt in range(3):
        try:
            return adb('shell', 'am', 'force-stop', args.package, transport_retries=2)
        except subprocess.CalledProcessError:
            if attempt == 2:
                raise
            time.sleep(2)


def ui_xml(timeout=60):
    """Dump and read the UI tree as one retriable operation.

    On the CI emulator, uiautomator can finish its dump while the file is not
    readable for a short moment. Keep the final error visible, but do not turn
    that transient hand-off into an app failure.
    """
    last_error = None
    for attempt in range(4):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/sabbaq-ui.xml',
                timeout=timeout, transport_retries=2)
            return adb('shell', 'cat', '/sdcard/sabbaq-ui.xml',
                       transport_retries=2)
        except subprocess.CalledProcessError as error:
            last_error = error
            if attempt == 3:
                raise
            time.sleep(2)
    raise last_error

assert adb('shell', 'getprop', 'sys.boot_completed', transport_retries=2).strip() == '1', 'Emulator has not completed boot'
print(adb('install', '-r', args.apk), flush=True)
# These screens are authored against the app's Arabic design language. The emulator's own
# language is English, so pin the app (not the device) to Arabic via the framework per-app
# locale (API 33+); the app's localeConfig lists ar, and this persists across force-stops.
adb('shell', 'cmd', 'locale', 'set-app-locales', args.package, '--user', '0', '--locales', 'ar')
adb('shell', 'settings', 'put', 'global', 'window_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'transition_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
adb('shell', 'input', 'keyevent', '82')
results = []

def apply_environment(env):
    """Device conditions for a case: tablet or 360 dp phone geometry, or no network; None restores the phone defaults."""
    adb('shell', 'wm', 'size', {'tablet': '1600x2560', 'narrow': '720x1600'}.get(env, 'reset'))
    adb('shell', 'wm', 'density', '320' if env in ('tablet', 'narrow') else 'reset')
    state = 'disable' if env == 'offline' else 'enable'
    adb('shell', 'svc', 'wifi', state)
    adb('shell', 'svc', 'data', state)
    if env is not None:
        time.sleep(4)


def assert_decision_history(visible, where):
    """The history page in reading order: heading, live status, statistics, then the seeded row."""
    assert 'النشاط والتشخيص' in visible, f'Decision history page did not render: {where}'
    for label in ('تشخيص الرصد', 'حالة التشغيل الفعلية', 'مسار الطلبات منذ التشغيل', 'إحصاءات سبّاق', 'هذا الأسبوع', 'هذا الشهر', 'أكثر أسباب الرفض اليوم',
                  'آخر القرارات', 'تم قبول الرحلة', 'ci-preview ·'):
        assert reachable_by_scrolling(label, visible), f'Decision history section missing: {label}: {where}'


def assert_brand_not_squeezed(nodes, where):
    """The onboarding bar on a 360 dp phone with large text: «سبّاق» keeps its full width beside the other controls."""
    def box(node):
        return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    brand = [box(n) for n in nodes if n.get('text') == 'سبّاق']
    assert brand, f'Brand name missing from the onboarding bar: {where}'
    x1, y1, x2, y2 = brand[0]
    # The five-letter word is well over twice as wide as its line; a squeezed slot clips it below that.
    assert (x2 - x1) >= 2 * (y2 - y1), f'Brand name squeezed to {x2 - x1}x{y2 - y1}px: {where}'
    for label in ('EN', 'ع', 'تخطي الجولة'):
        for n in nodes:
            if n.get('text') == label:
                a1, b1, a2, b2 = box(n)
                assert a2 <= x1 or a1 >= x2 or b2 <= y1 or b1 >= y2, f'«{label}» overlaps the brand name: {where}'


def png_colors(data, points):
    """RGB at (x, y) points of a PNG screenshot, decoded with the standard library only."""
    import struct
    import zlib
    pos, width, height, color_type, idat = 8, 0, 0, 2, b''
    while pos < len(data):
        length, kind = struct.unpack('>I4s', data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + length]
        if kind == b'IHDR':
            width, height, _depth, color_type = struct.unpack('>IIBB', chunk[:10])
        elif kind == b'IDAT':
            idat += chunk
        pos += 12 + length
    bpp = 4 if color_type == 6 else 3
    raw = zlib.decompress(idat)
    stride = width * bpp
    rows, prev = [], bytearray(stride)
    for y in range(height):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    return [tuple(rows[y][x * bpp:x * bpp + 3]) for x, y in points]


def reachable_by_scrolling(text, visible):
    """True when [text] is on screen now or appears after scrolling the page down."""
    if text in visible:
        return True
    # Derive a gesture from the actual scroll viewport. Fixed phone coordinates
    # land inside the tablet's rail or a card and can fail to scroll the content.
    # Keep each drag long, slow and inside the viewport to avoid accidental taps.
    for attempt in range(40):
        current_xml = ui_xml()
        current_root = ET.fromstring(current_xml)
        scroll_nodes = [n for n in current_root.iter('node') if n.get('scrollable') == 'true']
        if scroll_nodes:
            # The outermost scroll container is the page. Use its center, but
            # clamp to a generous inset so a system bar or navigation rail is avoided.
            bounds = re.findall(r'\d+', scroll_nodes[0].get('bounds', ''))
            if len(bounds) == 4:
                left, top, right, bottom = map(int, bounds)
                x = (left + right) // 2
                y_start = top + (bottom - top) * 4 // 5
                y_end = top + (bottom - top) // 5
            else:
                x, y_start, y_end = 540, 1500, 1000
        else:
            x, y_start, y_end = 540, 1500, 1000
        adb('shell', 'input', 'swipe', str(x), str(y_start), str(x), str(y_end), '700')
        time.sleep(1)
        scrolled_xml = ui_xml()
        scrolled = ET.fromstring(scrolled_xml).findall('.//node')
        if any(text in (n.get('text', '') + n.get('content-desc', '')) for n in scrolled):
            return True
    # Preserve enough evidence to distinguish layout regression from a test gesture issue.
    safe_name = re.sub(r'[^A-Za-z0-9_-]+', '-', text).strip('-')[:60] or 'missing-section'
    (out / f'failed-scroll-{safe_name}.xml').write_text(scrolled_xml)
    try:
        (out / f'failed-scroll-{safe_name}.png').write_bytes(screencap())
    except Exception as error:
        (out / f'failed-scroll-{safe_name}-screencap-error.txt').write_text(str(error))
    return False


cases = [
    ('first-launch', None, False, 1.0, None),
    ('cinematic-opening', 'cinematic-opening', False, 1.0, None),
    ('intro', 'intro', False, 1.0, None),
    ('intro-narrow-large-font', 'intro', False, 1.3, 'narrow'),
    ('welcome-features', 'welcome-features', False, 1.0, None),
    ('welcome-example', 'welcome-example', False, 1.0, None),
    ('login', 'login', False, 1.0, None),
    ('login-trial', 'login-trial', False, 1.0, None),
    ('dashboard', 'dashboard', False, 1.0, None),
    ('decision-log-screen', 'history', False, 1.0, None),
    ('decision-log', 'decision-log', False, 1.0, None),
    ('filters', 'filters', False, 1.0, None),
    ('simulator', 'simulator', False, 1.0, None),
    ('account', 'account', False, 1.0, None),
    ('setup', 'setup', False, 1.0, None),
    ('setup-dark', 'setup', True, 1.0, None),
    ('login-dark', 'login', True, 1.0, None),
    ('disclosure', 'disclosure', False, 1.0, None),
    ('dashboard-dark', 'dashboard', True, 1.0, None),
    ('login-large-font', 'login', False, 1.6, None),
    ('dashboard-tablet', 'dashboard', False, 1.0, 'tablet'),
    ('filters-tablet', 'filters', False, 1.0, 'tablet'),
    ('zones', 'zones', False, 1.0, None),
    ('zones-dark', 'zones', True, 1.0, None),
    ('zone-editor', 'zone-editor', False, 1.0, None),
    ('login-offline', 'login', False, 1.0, 'offline'),
]
current_env = 'unset'
for name, screen, dark, font_scale, env in cases:
    print(f'Capturing {args.package} {name}', flush=True)
    if env != current_env:
        apply_environment(env)
        current_env = env
    force_stop()
    adb('shell', 'settings', 'put', 'system', 'font_scale', str(font_scale))
    adb('logcat', '-c')
    activity = 'MainActivity' if screen is None else 'DesignPreviewActivity'
    command = ['shell', 'am', 'start', '-W', '-n', f'{args.package}/app.sabbaq.ui.{activity}']
    if screen is not None:
        command += ['--es', 'screen', screen, '--ez', 'dark', str(dark).lower()]
    launch = adb(*command, timeout=60)
    (out / f'{name}-launch.txt').write_text(launch)
    assert 'Error:' not in launch, launch
    total = re.search(r'TotalTime: (\d+)', launch)
    startup_ms = int(total.group(1)) if total else None
    print(f'{name} startup TotalTime={startup_ms}ms', flush=True)
    time.sleep(5)
    logs = adb('logcat', '-d', '-s', 'AndroidRuntime:E')
    (out / f'{name}-runtime.txt').write_text(logs)
    assert 'FATAL EXCEPTION' not in logs, f'Activity crashed: {name}\n{logs}'
    window = adb('shell', 'dumpsys', 'window', 'windows', transport_retries=2)
    activity_state = adb('shell', 'dumpsys', 'activity', 'activities', transport_retries=2)
    assert args.package in window + activity_state, f'Package is not visible: {name}'
    # On a freshly booted emulator a system window (launcher, "not responding" prompt) can
    # still sit above the app on the first case; bring the app back to front and dump again.
    for attempt in range(6):
        xml = ui_xml()
        (out / f'{name}.xml').write_text(xml)
        nodes = ET.fromstring(xml).findall('.//node')
        if any(node.get('package') == args.package for node in nodes):
            break
        # Android may show a one-time "Viewing full screen" confirmation when an app first
        # enters immersive mode. Dismiss it in place instead of relaunching over the app.
        dismissed_system_prompt = False
        for node in nodes:
            if node.get('resource-id') == 'android:id/ok' and node.get('package') == 'android':
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
                print(f'{name}: dismissing Android immersive confirmation', flush=True)
                adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(2)
                dismissed_system_prompt = True
                break
            # The emulator's own launcher can also raise "isn't responding" after boot.
            if node.get('resource-id') in ('android:id/aerr_close', 'android:id/aerr_wait') and node.get('package') != args.package:
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
                print(f'{name}: dismissing system dialog {node.get("resource-id")}', flush=True)
                adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(2)
                break
        if dismissed_system_prompt:
            continue
        print(f'{name}: app tree not on top (attempt {attempt + 1}), relaunching', flush=True)
        adb(*command, timeout=60)
        time.sleep(5)
    assert any(node.get('package') == args.package for node in nodes), f'No app accessibility tree: {name}'
    visible = ' '.join(node.get('text', '') + ' ' + node.get('content-desc', '') for node in nodes)
    for jargon in ('GOOGLE_WEB_CLIENT_ID', 'RPC', 'JSON', 'تعرض هذه المعلومات للقراءة فقط', 'تجربة محلية للتعلّم'):
        assert jargon not in visible, f'Developer-facing copy in customer screen {name}: {jargon}'
    if name in ('first-launch', 'cinematic-opening'):
        assert 'سبّاق' in visible, 'Brand-led cinematic opening did not expose the Sabbaq identity'
        for player_control in ('تخطي الفيديو', 'تشغيل صوت الافتتاحية', 'كتم صوت الافتتاحية'):
            assert player_control not in visible, f'Player/ad control leaked into launch opening: {player_control}'
    if name == 'intro-narrow-large-font':
        assert_brand_not_squeezed(nodes, name)
    if screen == 'history':
        assert_decision_history(visible, f'direct open: {name}')
    if screen == 'zones':
        # Plain-language page: the two lists by what they mean to the driver, each with its own add button.
        for label in ('مناطق أذهب إليها', 'العليا', 'مناطق أتجنبها', 'المطار', 'أضف منطقة أتجنبها', 'خيارات متقدمة'):
            assert reachable_by_scrolling(label, visible), f'Zones page section missing: {label}: {name}'
        for word in ('OpenStreetMap', 'Google', 'Photon', 'Nominatim', 'تكييش', 'الترميز'):
            assert word not in visible, f'Provider or jargon shown on the zones page: {word}: {name}'
    if screen == 'zone-editor':
        # The search waits 450 ms, then asks the live service; give it time, then the suggestions
        # (or the explicit «nothing found») must be on screen.
        time.sleep(8)
        editor_xml = ui_xml()
        (out / f'{name}-search.xml').write_text(editor_xml)
        editor_shot = screencap()
        if editor_shot.startswith(b'\x89PNG\r\n\x1a\n'):
            (out / f'{name}-search.png').write_bytes(editor_shot)
        editor_visible = ' '.join(n.get('text', '') + ' ' + n.get('content-desc', '') for n in ET.fromstring(editor_xml).findall('.//node'))
        assert 'العليا،' in editor_visible or 'لم يُعثر على مكان' in editor_visible, f'Zone search showed nothing: {editor_visible[:600]!r}'
        # The map must have drawn: a map that never loads stays one flat colour (2026-10-31: the style
        # JSON was passed as a URL and nothing ever rendered).
        map_nodes = [n for n in ET.fromstring(editor_xml).findall('.//node') if 'zone-map' in (n.get('resource-id', '') + n.get('content-desc', ''))]
        if map_nodes:
            mx1, my1, mx2, my2 = map(int, re.findall(r'\d+', map_nodes[0].get('bounds', '')))
        else:
            mx1, my1, mx2, my2 = 0, 1100, 1080, 2100
        grid = [(mx1 + (mx2 - mx1) * i // 6, my1 + (my2 - my1) * j // 6) for i in range(1, 6) for j in range(1, 6)]
        colors = set(png_colors(editor_shot, grid)) if editor_shot.startswith(b'\x89PNG\r\n\x1a\n') else set()
        assert len(colors) >= 4, f'Zone editor map did not render (only {len(colors)} colours in the map area): {sorted(colors)[:6]}'
        # Choosing the district names the zone after it, and «استخدم حدود …» draws its boundary: search
        # knows Al Olaya only as a point, so the boundary comes from the district around it (owner, 2026-10-31).
        def tap_node(pred):
            for n in ET.fromstring(ui_xml()).findall('.//node'):
                if n.get('package') == args.package and pred(n):
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds', '')))
                    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                    return True
            return False
        if 'العليا،' in editor_visible and (tap_node(lambda n: n.get('text', '').startswith('العليا، الرياض')) or tap_node(lambda n: n.get('text', '').startswith('العليا،'))):
            time.sleep(2)
            picked_nodes = ET.fromstring(ui_xml()).findall('.//node')
            # Compose test tags are not exposed as ids here: the zone name is the editor's first text field.
            name_fields = [n.get('text', '') for n in picked_nodes
                           if n.get('package') == args.package and n.get('class') == 'android.widget.EditText'][:1]
            assert any('العليا' in t for t in name_fields), f'Picking a place left the zone name empty: {name_fields}'
            # Picking the place makes it the zone, ready to save: its boundary, or a square around it
            # when none is mapped or the map servers are busy (owner, 2026-10-31). Corners are counted in Arabic digits.
            outlined = ''
            for _ in range(16):
                time.sleep(2.5)
                outlined = ' '.join(n.get('text', '') for n in ET.fromstring(ui_xml()).findall('.//node'))
                if not re.search(r'\((0|٠) حتى الآن\)', outlined):
                    break
            shot = screencap()
            if shot.startswith(b'\x89PNG\r\n\x1a\n'):
                (out / f'{name}-outline.png').write_bytes(shot)
            assert not re.search(r'\((0|٠) حتى الآن\)', outlined), f'Picking «العليا» drew no zone: {outlined[:400]!r}'
            if 'رُسمت منطقة مربعة' in outlined:
                print(f'{name}: no district boundary reached from CI; a square was drawn instead', flush=True)
            assert tap_node(lambda n: n.get('text', '') == 'حفظ المنطقة'), 'Save button missing'
            time.sleep(1.5)
            saved = ' '.join(n.get('text', '') for n in ET.fromstring(ui_xml()).findall('.//node'))
            for refusal in ('الرجاء إدخال اسم المنطقة', 'المنطقة تحتاج 3 نقاط'):
                assert refusal not in saved, f'Saving the picked place was refused: {refusal}'
    if screen == 'welcome-example':
        # The pipeline scene leads the page; the worked example sits below it.
        assert reachable_by_scrolling('2.5 ر.س قبل التكاليف', visible), f'Concrete fare/distance example missing: {name}'
    if screen == 'welcome-features':
        assert 'أنت تحدد، وسبّاق يتابع' in visible, f'Feature card missing: {name}'
        assert reachable_by_scrolling('جرّبه قبل الاشتراك', visible) or reachable_by_scrolling('اكتشفه أولًا', visible), f'Subscription card missing: {name}'
    if env == 'offline':
        # The app-wide offline banner must tell the driver what needs a connection.
        assert 'لا يوجد اتصال بالإنترنت' in visible, f'Offline banner missing: {name}'
    if screen == 'login':
        # Play requires the privacy policy to be reachable inside the app before sign-in.
        # It sits at the end of the sign-in page, below the first screen.
        assert 'فتح القائمة الجانبية' in visible, f'Global navigation menu missing on unauthenticated page: {name}'
        assert reachable_by_scrolling('سياسة الخصوصية', visible), f'Privacy policy link missing: {name}'
    if screen == 'account':
        # Google Play requires an in-app path to delete the account in every distribution.
        # It sits at the end of the page, below the first screen, so scroll before reading.
        assert reachable_by_scrolling('حذف حسابي', visible), f'Account deletion entry point missing: {name}'
    if screen == 'decision-log':
        # History is intentionally not a crowded bottom-bar item. It remains available
        # from the production hamburger menu; the debug activity seeds one synthetic row
        # so both the empty and populated rendering paths are exercised.
        menu_button = next((node for node in nodes
                            if node.get('package') == args.package and
                            node.get('content-desc') == 'فتح القائمة الجانبية'), None)
        assert menu_button is not None, f'Hamburger menu missing: {name}'
        menu_bounds = re.findall(r'\d+', menu_button.get('bounds', ''))
        assert len(menu_bounds) == 4, f'Hamburger menu bounds missing: {name}'
        menu_left, menu_top, menu_right, menu_bottom = map(int, menu_bounds)
        adb('shell', 'input', 'tap', str((menu_left + menu_right) // 2), str((menu_top + menu_bottom) // 2))
        time.sleep(0.8)
        nodes = ET.fromstring(ui_xml()).findall('.//node')
        history_tab = next((node for node in nodes
                            if node.get('package') == args.package and
                            'النشاط والتشخيص' in (node.get('text', '') + node.get('content-desc', ''))), None)
        assert history_tab is not None, f'Decision history hamburger item missing: {name}'
        bounds = re.findall(r'\d+', history_tab.get('bounds', ''))
        assert len(bounds) == 4, f'Decision history navigation bounds missing: {name}'
        left, top, right, bottom = map(int, bounds)
        adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(1.5)
        history_xml = ui_xml()
        (out / 'decision-log-after-navigation.xml').write_text(history_xml)
        history_nodes = ET.fromstring(history_xml).findall('.//node')
        history_visible = ' '.join(
            node.get('text', '') + ' ' + node.get('content-desc', '')
            for node in history_nodes if node.get('package') == args.package
        )
        history_runtime = adb('logcat', '-d', '-s', 'AndroidRuntime:E')
        (out / 'decision-log-after-navigation-runtime.txt').write_text(history_runtime)
        history_screenshot = screencap()
        if history_screenshot.startswith(b'\x89PNG\r\n\x1a\n'):
            (out / 'decision-log-after-navigation.png').write_bytes(history_screenshot)
        print(f'decision-log post-tap package-visible={args.package in history_visible}; text={history_visible[:1000]!r}', flush=True)
        assert_decision_history(history_visible, f'after navigation: {name}')
        assert 'FATAL EXCEPTION' not in history_runtime, f'App crashed after opening decision history:\n{history_runtime}'
        assert history_screenshot.startswith(b'\x89PNG\r\n\x1a\n'), f'Invalid decision history screenshot: {name}'
    if screen == 'dashboard':
        # «ابدأ» always answers on screen: the missing-permissions list, the start consent, or a
        # message right under the button. Pressing it once did nothing visible (owner, 2026-10-30).
        start_nodes = [n for n in ET.fromstring(ui_xml()).findall('.//node')
                       if n.get('package') == args.package and 'ابدأ متابعة الطلبات' in n.get('content-desc', '')]
        assert start_nodes, f'Start button missing: {name}'
        sx1, sy1, sx2, sy2 = map(int, re.findall(r'\d+', start_nodes[0].get('bounds', '')))
        adb('shell', 'input', 'tap', str((sx1 + sx2) // 2), str((sy1 + sy2) // 2))
        time.sleep(2.5)
        start_xml = ui_xml()
        start_visible = ' '.join(n.get('text', '') + ' ' + n.get('content-desc', '')
                                 for n in ET.fromstring(start_xml).findall('.//node'))
        start_shot = screencap()
        if start_shot.startswith(b'\x89PNG\r\n\x1a\n'):
            (out / f'{name}-start-pressed.png').write_bytes(start_shot)
        answers = ('اضبط شرطًا واحدًا على الأقل', 'قبل البدء: أذونات مطلوبة', 'بدء متابعة الطلبات', 'لم نتمكن من التحقق من اشتراكك',
                   'جارٍ استعادة جلستك', 'تعذر تحديث الجلسة', 'تُحفظ التعديلات تلقائيًا', 'صحح شروط الطلبات')
        assert any(answer in start_visible for answer in answers), f'«ابدأ» gave no visible answer: {name}: {start_visible[:600]!r}'
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(1.0)
        # Voice alerts moved to «الإعدادات» and the statistics and the monitoring check to «النشاط والتشخيص»; the work page
        # keeps the editable driving conditions and the supported apps. Capture each section.
        current_visible = visible
        # The floating control is always on the page; the places and zones sit inside «شروط القيادة»
        # under «قنص الطلبات بدون وجهة»; the offers' path lives only in «النشاط والتشخيص» (owner, 2026-10-31).
        checkpoints = (
            ('الزر العائم', 'floating'),
            ('شروط القيادة', 'conditions'),
            ('نطاق السعر', None),
            ('قنص الطلبات بدون وجهة', None),
            ('إدارة الأماكن والمناطق', 'zones'),
            ('يعمل مع تطبيقات الرحلات', None),
        )
        seen_on_dashboard = visible
        for label, capture_name in checkpoints:
            assert reachable_by_scrolling(label, current_visible), f'Dashboard stats section missing: {label}: {name}'
            time.sleep(0.5)
            adb('shell', 'uiautomator', 'dump', '/sdcard/sabbaq-ui.xml', timeout=60)
            checkpoint_xml = adb('shell', 'cat', '/sdcard/sabbaq-ui.xml')
            checkpoint_nodes = ET.fromstring(checkpoint_xml).findall('.//node')
            current_visible = ' '.join(
                node.get('text', '') + ' ' + node.get('content-desc', '')
                for node in checkpoint_nodes if node.get('package') == args.package
            )
            assert label in current_visible, f'Dashboard stats label is not visible after scrolling: {label}: {name}'
            seen_on_dashboard += ' ' + current_visible
            if capture_name:
                capture_name = f'{name}-{capture_name}'
                screenshot = screencap()
                assert screenshot.startswith(b'\x89PNG\r\n\x1a\n'), f'Invalid stats screenshot: {capture_name}'
                (out / f'{capture_name}.xml').write_text(checkpoint_xml)
                (out / f'{capture_name}.png').write_bytes(screenshot)
        if screen == 'dashboard':
            for moved in ('مسار الطلبات منذ التشغيل', 'تشخيص الرصد'):
                assert moved not in seen_on_dashboard, f'«{moved}» is still on the work page: {name}'
    if args.distribution == 'direct' and screen == 'account':
        # No dead ends: a purchase step may only appear together with the button it tells
        # the driver to press, whatever channel the live offer configures. Labels are
        # collected as a set of exact node texts so overlapping scroll dumps cannot
        # double-count a step and fake a button.
        def node_labels(found):
            # Text and content description both count: Compose merges a button's label
            # into the button node, which may expose it through either attribute. Only the
            # app's own nodes count: another app on top must never supply or hide a label.
            return {value.strip() for n in found if n.get('package') == args.package
                    for value in (n.get('text', ''), n.get('content-desc', '')) if value.strip()}

        def dump_app_tree(index):
            # A drag on a slow emulator can land as a tap on a link (privacy page, WhatsApp)
            # and bring a browser to the front. Go back to the app and dump again.
            for _ in range(3):
                adb('shell', 'uiautomator', 'dump', '/sdcard/sabbaq-ui.xml', timeout=60)
                dumped = adb('shell', 'cat', '/sdcard/sabbaq-ui.xml')
                found = ET.fromstring(dumped).findall('.//node')
                if found and found[0].get('package') == args.package:
                    return dumped, found
                print(f'{name}: scroll {index} left the app, returning', flush=True)
                adb('shell', 'input', 'keyevent', '4')
                time.sleep(2)
            raise AssertionError(f'{name}: app not in front after scroll {index}')
        labels = node_labels(nodes)
        # The deletion check above left the page at its end: go back to the top first,
        # then walk down with short, slow drags so the middle (purchase steps and their
        # button) is not flung past.
        for _ in range(10):
            adb('shell', 'input', 'swipe', '540', '600', '540', '1900', '150')
        time.sleep(1)
        for index in range(14):
            adb('shell', 'input', 'swipe', '540', '1500', '540', '1000', '700')
            time.sleep(1)
            scrolled_xml, scrolled_nodes = dump_app_tree(index)
            (out / f'{name}-scroll-{index}.xml').write_text(scrolled_xml)
            (out / f'{name}-scroll-{index}.png').write_bytes(screencap())
            labels |= node_labels(scrolled_nodes)
        whatsapp_step = any(label.startswith('اضغط «اشترك عبر واتساب»') for label in labels)
        page_step = any(label.startswith('اضغط «اشترك الآن»') for label in labels)
        def has_button(label_text):
            return any(label == label_text or (label_text in label and not label.startswith('اضغط')) for label in labels)
        if whatsapp_step:
            assert has_button('اشترك عبر واتساب'), f'WhatsApp purchase step shown without its button: {name}: {sorted(labels)}'
        if page_step:
            assert has_button('اشترك الآن'), f'Purchase step shown without its button: {name}: {sorted(labels)}'
        (out / f'{name}-purchase.txt').write_text('whatsapp' if whatsapp_step else 'page' if page_step else 'none')
    if args.distribution == 'play' and screen in ('login', 'account'):
        visible = ' '.join(node.get('text', '') + ' ' + node.get('content-desc', '') for node in nodes)
        for forbidden in ('كود التفعيل', 'شراء كود', 'شراء الاشتراك من الإدارة', 'واتساب', '18.75'):
            assert forbidden not in visible, f'External code-selling surface in Play screen {name}: {forbidden}'
    image = screencap()
    assert image.startswith(b'\x89PNG\r\n\x1a\n'), f'Invalid screenshot: {name}'
    (out / f'{name}.png').write_bytes(image)
    results.append({'screen': name, 'package': args.package, 'distribution': args.distribution, 'fontScale': font_scale,
                    'dark': dark, 'accessibilityNodes': len(nodes), 'nativeLaunch': True,
                    'startupMs': startup_ms, 'environment': env or 'phone-online'})

adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
apply_environment(None)

# Motion evidence: with system animations on, the logo intro and the live 3D scenes must
# actually move. Frames are kept for review; identical frames mean the motion is broken.
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1')
motion = {}

def content_digest(raw):
    """Digest of the app's content rows only: status/navigation bars (clock, network icons)
    change on their own and must not count as app motion. raw = `screencap` without -p."""
    width = int.from_bytes(raw[0:4], 'little')
    height = int.from_bytes(raw[4:8], 'little')
    header = len(raw) - width * height * 4
    top, bottom = int(height * 0.08), int(height * 0.92)
    return hashlib.sha256(raw[header + top * width * 4: header + bottom * width * 4]).hexdigest()


def capture_motion(tag, extras, delays):
    activity = 'MainActivity' if extras is None else 'DesignPreviewActivity'
    force_stop()
    # -W returns once the app has drawn its first frame, so the system splash is not measured.
    adb('shell', 'am', 'start', '-W', '-n', f'{args.package}/app.sabbaq.ui.{activity}', *(extras or []), timeout=60)
    digests = []
    for index, delay in enumerate(delays):
        time.sleep(delay)
        (out / f'motion-{tag}-{index}.png').write_bytes(screencap())
        digests.append(content_digest(screencap(png=False)))
    motion[tag] = {'frames': len(digests), 'distinct': len(set(digests))}
    print(f'motion {tag}: {motion[tag]}', flush=True)
    assert len(set(digests)) > 1, f'No motion captured: {tag}'

capture_motion('intro', None, [0.1, 0.3, 0.3, 0.3])
capture_motion('cinematic-opening', ['--es', 'screen', 'cinematic-opening', '--ez', 'dark', 'false'], [1.2, 0.5, 0.5, 0.5])
capture_motion('welcome', ['--es', 'screen', 'welcome', '--ez', 'dark', 'false'], [1.5, 0.5, 0.5, 0.5])
capture_motion('dashboard', ['--es', 'screen', 'dashboard', '--ez', 'dark', 'false'], [1.0, 0.5, 0.5, 0.5])
(out / 'motion.json').write_text(json.dumps(motion, indent=2))
(out / 'results.json').write_text(json.dumps(results, indent=2))
print(json.dumps(results, indent=2), flush=True)

# These are teardown-only operations on an ephemeral CI emulator. Preserve successful
# assertions and their artifacts if the emulator drops adb after the test has completed.
for cleanup_command in (
    ('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0'),
    ('shell', 'am', 'force-stop', args.package),
):
    try:
        adb(*cleanup_command, timeout=5)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f'Non-blocking emulator teardown warning: {error}', flush=True)
