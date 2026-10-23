#!/usr/bin/env python3
"""Run destructive smoke checks only on a disposable emulator, never on a phone."""
import argparse, json, os, re, subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--fixture-info',default='/tmp/termora-device-fixture.json');args=p.parse_args()
assert args.serial.startswith('emulator-'), 'Use a disposable emulator for these checks'
root=Path(__file__).resolve().parents[1]
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Android/Sdk')))
adb=str(sdk/'platform-tools/adb')
fixture=json.loads(Path(args.fixture_info).read_text())
assert 0 < fixture['port'] <= 65535 and re.fullmatch(r'SHA256:[A-Za-z0-9+/=]+',fixture['pin'])
def run(*arguments):
    subprocess.run([adb,'-s',args.serial,*arguments],check=True)
run('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk'))
run('install','-r',str(root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
result=subprocess.run([adb,'-s',args.serial,'shell','am','instrument','-w','-e','fixturePort',str(fixture['port']),'-e','fixturePin',fixture['pin'],'app.termora.android.test/androidx.test.runner.AndroidJUnitRunner'],text=True,capture_output=True,check=True)
print(result.stdout)
assert re.search(r'OK \(4 tests\)',result.stdout),result.stdout+result.stderr
