#!/usr/bin/env python3
"""Runs the Compose instrumentation flow tests on the booted CI emulator.

`am instrument` exits 0 even when tests fail, so the result is decided from its
output: the run must end with "OK (N tests)" and contain no FAILURES section.
"""
import argparse
import pathlib
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--apk', required=True)
parser.add_argument('--test-apk', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args()
out = pathlib.Path(args.out)
out.mkdir(parents=True, exist_ok=True)


def adb(*params, timeout=600):
    return subprocess.check_output(['adb', *params], timeout=timeout, text=True, stderr=subprocess.STDOUT)


print(adb('install', '-r', '-t', args.apk), flush=True)
print(adb('install', '-r', '-t', args.test_apk), flush=True)
result = adb('shell', 'am', 'instrument', '-w', '-r',
             'app.qannas.driver.debug.test/androidx.test.runner.AndroidJUnitRunner')
(out / 'instrumentation.txt').write_text(result)
print(result[-4000:], flush=True)

passed = re.search(r'OK \((\d+) tests?\)', result)
assert passed and 'FAILURES!!!' not in result and 'INSTRUMENTATION_FAILED' not in result, \
    'Compose flow tests failed; see ui-screenshots/flow-tests/instrumentation.txt'
assert int(passed.group(1)) > 0, 'No flow tests ran'
print(f'Flow tests passed: {passed.group(1)}', flush=True)
