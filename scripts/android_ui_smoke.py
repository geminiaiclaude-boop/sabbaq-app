#!/usr/bin/env python3
"""Native Android launch/screenshot smoke test; never signs in or starts KeeperService."""
import argparse
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

assert adb('shell', 'getprop', 'sys.boot_completed').strip() == '1', 'Emulator has not completed boot'
print(adb('install', '-r', args.apk), flush=True)
adb('shell', 'settings', 'put', 'global', 'window_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'transition_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
adb('shell', 'input', 'keyevent', '82')
results = []

cases = [
    ('first-launch', None, False, 1.0),
    ('welcome-example', 'welcome-example', False, 1.0),
    ('welcome-ready', 'welcome-ready', False, 1.0),
    ('login', 'login', False, 1.0),
    ('dashboard', 'dashboard', False, 1.0),
    ('filters', 'filters', False, 1.0),
    ('simulator', 'simulator', False, 1.0),
    ('account', 'account', False, 1.0),
    ('disclosure', 'disclosure', False, 1.0),
    ('dashboard-dark', 'dashboard', True, 1.0),
    ('login-large-font', 'login', False, 1.6),
]
for name, screen, dark, font_scale in cases:
    print(f'Capturing {args.package} {name}', flush=True)
    adb('shell', 'am', 'force-stop', args.package)
    adb('shell', 'settings', 'put', 'system', 'font_scale', str(font_scale))
    adb('logcat', '-c')
    activity = 'MainActivity' if screen is None else 'DesignPreviewActivity'
    command = ['shell', 'am', 'start', '-W', '-n', f'{args.package}/app.qannas.ui.{activity}']
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
    adb('shell', 'uiautomator', 'dump', '/sdcard/qannas-ui.xml', timeout=60)
    xml = adb('shell', 'cat', '/sdcard/qannas-ui.xml')
    (out / f'{name}.xml').write_text(xml)
    nodes = ET.fromstring(xml).findall('.//node')
    assert any(node.get('package') == args.package for node in nodes), f'No app accessibility tree: {name}'
    visible = ' '.join(node.get('text', '') + ' ' + node.get('content-desc', '') for node in nodes)
    for jargon in ('GOOGLE_WEB_CLIENT_ID', 'RPC', 'JSON', 'تعرض هذه المعلومات للقراءة فقط', 'تجربة محلية للتعلّم'):
        assert jargon not in visible, f'Developer-facing copy in customer screen {name}: {jargon}'
    if screen == 'welcome-example':
        assert '٢٫٥ ر.س قبل التكاليف' in visible, f'Concrete fare/distance example missing: {name}'
    if screen == 'login':
        # Play requires the privacy policy to be reachable inside the app before sign-in.
        assert 'سياسة الخصوصية' in visible, f'Privacy policy link missing: {name}'
    if screen == 'account':
        # Google Play requires an in-app path to delete the account in every distribution.
        # It sits at the end of the page, below the first screen, so scroll before reading.
        deletion_visible = 'حذف حسابي' in visible
        for _ in range(6):
            if deletion_visible:
                break
            adb('shell', 'input', 'swipe', '540', '1700', '540', '500', '250')
            time.sleep(1)
            adb('shell', 'uiautomator', 'dump', '/sdcard/qannas-ui.xml', timeout=60)
            scrolled = ET.fromstring(adb('shell', 'cat', '/sdcard/qannas-ui.xml')).findall('.//node')
            deletion_visible = any('حذف حسابي' in (n.get('text', '') + n.get('content-desc', '')) for n in scrolled)
        assert deletion_visible, f'Account deletion entry point missing: {name}'
    if args.distribution == 'play' and screen in ('login', 'account'):
        visible = ' '.join(node.get('text', '') + ' ' + node.get('content-desc', '') for node in nodes)
        for forbidden in ('كود التفعيل', 'شراء كود', 'شراء الاشتراك من الإدارة', 'واتساب', '18.75'):
            assert forbidden not in visible, f'External code-selling surface in Play screen {name}: {forbidden}'
    image = adb('exec-out', 'screencap', '-p', binary=True)
    assert image.startswith(b'\x89PNG\r\n\x1a\n'), f'Invalid screenshot: {name}'
    (out / f'{name}.png').write_bytes(image)
    results.append({'screen': name, 'package': args.package, 'distribution': args.distribution, 'fontScale': font_scale,
                    'dark': dark, 'accessibilityNodes': len(nodes), 'nativeLaunch': True,
                    'startupMs': startup_ms})

adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
adb('shell', 'am', 'force-stop', args.package)
(out / 'results.json').write_text(json.dumps(results, indent=2))
print(json.dumps(results, indent=2), flush=True)
