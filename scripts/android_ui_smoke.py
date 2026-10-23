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

def adb(*params, binary=False, timeout=45):
    return subprocess.check_output(['adb', *params], timeout=timeout, text=not binary, stderr=subprocess.STDOUT)


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

assert adb('shell', 'getprop', 'sys.boot_completed').strip() == '1', 'Emulator has not completed boot'
print(adb('install', '-r', args.apk), flush=True)
adb('shell', 'settings', 'put', 'global', 'window_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'transition_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
adb('shell', 'input', 'keyevent', '82')
results = []

def apply_environment(env):
    """Device conditions for a case: tablet geometry or no network; None restores the phone defaults."""
    adb('shell', 'wm', 'size', '1600x2560' if env == 'tablet' else 'reset')
    adb('shell', 'wm', 'density', '320' if env == 'tablet' else 'reset')
    state = 'disable' if env == 'offline' else 'enable'
    adb('shell', 'svc', 'wifi', state)
    adb('shell', 'svc', 'data', state)
    if env is not None:
        time.sleep(4)


def reachable_by_scrolling(text, visible):
    """True when [text] is on screen now or appears after scrolling the page down."""
    if text in visible:
        return True
    # Use the actual accessibility ScrollView bounds and keep the number of probes
    # small: repeated full accessibility dumps can stall a loaded emulator.
    last_xml = ''
    for attempt in range(8):
        adb('shell', 'uiautomator', 'dump', '/sdcard/sabbaq-ui.xml', timeout=60)
        last_xml = adb('shell', 'cat', '/sdcard/sabbaq-ui.xml')
        scrolled = ET.fromstring(last_xml).findall('.//node')
        if any(
            n.get('package') == args.package
            and text in (n.get('text', '') + n.get('content-desc', ''))
            for n in scrolled
        ):
            return True
        visible_text = ' '.join(n.get('text', '') + ' ' + n.get('content-desc', '') for n in scrolled)
        if "isn't responding" in visible_text.lower() or 'لا يستجيب' in visible_text:
            print(f'Android ANR dialog detected while looking for {text!r}', flush=True)
            break
        scroll_views = [
            n for n in scrolled
            if n.get('package') == args.package and n.get('scrollable') == 'true'
        ]
        bounds = None
        if scroll_views:
            def scroll_area(node):
                coordinates = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                return (coordinates[2] - coordinates[0]) * (coordinates[3] - coordinates[1]) if len(coordinates) == 4 else 0

            scroll_view = max(scroll_views, key=scroll_area)
            values = list(map(int, re.findall(r'\d+', scroll_view.get('bounds', ''))))
            if len(values) == 4:
                bounds = values
        if bounds is None:
            print(f'No app scroll container while looking for {text!r}', flush=True)
            break
        left, top, right, bottom = bounds
        inset = max(80, int((bottom - top) * 0.16))
        x = (left + right) // 2
        start_y = bottom - inset
        end_y = top + inset
        if start_y <= end_y:
            break
        if attempt % 6 == 0:
            print(f'Scrolling toward {text!r} using app viewport {bounds} (attempt {attempt + 1}/8)', flush=True)
        adb('shell', 'input', 'swipe', str(x), str(start_y), str(x), str(end_y), '700')
        time.sleep(1)
    # Preserve the last viewport when a section is genuinely absent or unreachable.
    if last_xml:
        slug = hashlib.sha1(text.encode('utf-8')).hexdigest()[:10]
        (out / f'scroll-failure-{slug}.xml').write_text(last_xml)
        try:
            (out / f'scroll-failure-{slug}.png').write_bytes(screencap())
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        try:
            logcat = adb('logcat', '-d', '-b', 'main', '-b', 'system', '-b', 'events', '-v', 'threadtime', '-t', '2000')
            (out / f'scroll-failure-{slug}-logcat.txt').write_text(logcat)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
    return False


cases = [
    ('first-launch', None, False, 1.0, None),
    ('intro', 'intro', False, 1.0, None),
    ('welcome-example', 'welcome-example', False, 1.0, None),
    ('welcome-ready', 'welcome-ready', False, 1.0, None),
    ('login', 'login', False, 1.0, None),
    ('login-trial', 'login-trial', False, 1.0, None),
    ('dashboard', 'dashboard', False, 1.0, None),
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
    ('login-offline', 'login', False, 1.0, 'offline'),
]
current_env = 'unset'
for name, screen, dark, font_scale, env in cases:
    print(f'Capturing {args.package} {name}', flush=True)
    if env != current_env:
        apply_environment(env)
        current_env = env
    adb('shell', 'am', 'force-stop', args.package)
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
    window = adb('shell', 'dumpsys', 'window', 'windows')
    activity_state = adb('shell', 'dumpsys', 'activity', 'activities')
    assert args.package in window + activity_state, f'Package is not visible: {name}'
    # On a freshly booted emulator a system window (launcher, "not responding" prompt) can
    # still sit above the app on the first case; bring the app back to front and dump again.
    for attempt in range(6):
        adb('shell', 'uiautomator', 'dump', '/sdcard/sabbaq-ui.xml', timeout=60)
        xml = adb('shell', 'cat', '/sdcard/sabbaq-ui.xml')
        (out / f'{name}.xml').write_text(xml)
        nodes = ET.fromstring(xml).findall('.//node')
        if any(node.get('package') == args.package for node in nodes):
            break
        print(f'{name}: app tree not on top (attempt {attempt + 1}), relaunching', flush=True)
        # The emulator's own launcher often raises "isn't responding" right after boot.
        # Close that system dialog; it belongs to another package, never to the app under test.
        for node in nodes:
            if node.get('resource-id') in ('android:id/aerr_close', 'android:id/aerr_wait') and node.get('package') != args.package:
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
                print(f'{name}: dismissing system dialog {node.get("resource-id")}', flush=True)
                adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(2)
                break
        adb(*command, timeout=60)
        time.sleep(5)
    assert any(node.get('package') == args.package for node in nodes), f'No app accessibility tree: {name}'
    visible = ' '.join(node.get('text', '') + ' ' + node.get('content-desc', '') for node in nodes)
    for jargon in ('GOOGLE_WEB_CLIENT_ID', 'RPC', 'JSON', 'تعرض هذه المعلومات للقراءة فقط', 'تجربة محلية للتعلّم'):
        assert jargon not in visible, f'Developer-facing copy in customer screen {name}: {jargon}'
    if screen == 'welcome-example':
        # The pipeline scene leads the page; the worked example sits below it.
        assert reachable_by_scrolling('٢٫٥ ر.س قبل التكاليف', visible), f'Concrete fare/distance example missing: {name}'
    if env == 'offline':
        # The app-wide offline banner must tell the driver what needs a connection.
        assert 'لا يوجد اتصال بالإنترنت' in visible, f'Offline banner missing: {name}'
    if screen == 'login':
        # Play requires the privacy policy to be reachable inside the app before sign-in.
        # It sits at the end of the sign-in page, below the first screen.
        assert reachable_by_scrolling('سياسة الخصوصية', visible), f'Privacy policy link missing: {name}'
    if screen == 'account':
        # Google Play requires an in-app path to delete the account in every distribution.
        # It sits at the end of the page, below the first screen, so scroll before reading.
        assert reachable_by_scrolling('حذف حسابي', visible), f'Account deletion entry point missing: {name}'
    if screen == 'dashboard':
        # The stats card is intentionally below voice alerts; capture the real phone UI
        # at each section instead of relying only on the dashboard's first viewport.
        current_visible = visible
        checkpoints = (
            ('التنبيهات الصوتية', None),
            ('إحصاءات سبّاق', 'stats'),
            ('هذا الأسبوع', 'stats-week'),
            ('هذا الشهر', 'stats-month'),
            ('أكثر أسباب الرفض اليوم', 'stats-reasons'),
            ('يعمل مع تطبيقات الرحلات', None),
        )
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
            if capture_name:
                capture_name = f'{name}-{capture_name}'
                screenshot = screencap()
                assert screenshot.startswith(b'\x89PNG\r\n\x1a\n'), f'Invalid stats screenshot: {capture_name}'
                (out / f'{capture_name}.xml').write_text(checkpoint_xml)
                (out / f'{capture_name}.png').write_bytes(screenshot)
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
    adb('shell', 'am', 'force-stop', args.package)
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
capture_motion('welcome', ['--es', 'screen', 'welcome', '--ez', 'dark', 'false'], [1.5, 0.5, 0.5, 0.5])
capture_motion('dashboard', ['--es', 'screen', 'dashboard', '--ez', 'dark', 'false'], [1.0, 0.5, 0.5, 0.5])
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
(out / 'motion.json').write_text(json.dumps(motion, indent=2))
adb('shell', 'am', 'force-stop', args.package)
(out / 'results.json').write_text(json.dumps(results, indent=2))
print(json.dumps(results, indent=2), flush=True)
