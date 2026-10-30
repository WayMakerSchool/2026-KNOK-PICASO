"""Run only the V3 UI instrumentation test; preserve old KNOK app data and stop our emulator."""
from pathlib import Path
import hashlib
import json
import shlex
import subprocess

ROOT=Path(__file__).resolve().parents[1]
ADB=Path.home()/'Library/Android/sdk/platform-tools/adb'
PKG='com.aistudio.quietneighbors.gspqzy.v3'
STATE=json.loads((ROOT/'runtime/last_launch.json').read_text())
SERIAL=STATE['serial']

def adb(*args,binary=False,timeout=60):
    result=subprocess.run([str(ADB),'-s',SERIAL,*args],capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout if binary else result.stdout.decode(errors='replace').strip()

def old_snapshot():
    packages=[p.removeprefix('package:') for p in adb('shell','pm','list','packages').splitlines() if 'quietneighbors' in p]
    original={}
    for package in packages:
        if package.startswith(PKG):continue
        command='find files databases -type f \\( -name "*.wav" -o -name "*.db" -o -name "*.db-wal" \\) -exec sha256sum {} \\;'
        try:
            value=adb('shell','run-as '+shlex.quote(package)+' sh -c '+shlex.quote(command))
            original[package]={'readable':True,'sha256_lines':sorted(value.splitlines())}
        except RuntimeError:
            original[package]={'readable':False,'sha256_lines':[]}
    return {'packages':packages,'original_apps':original}

def main():
    before=old_snapshot()
    (ROOT/'evidence/emulator-before-final.json').write_text(json.dumps(before,ensure_ascii=False,indent=2)+'\n')
    test_apk=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    adb('install','-r',str(test_apk),timeout=120)
    output=adb('shell','am','instrument','-w','-e','class','com.example.V3AppDeviceTest',
        PKG+'.test/androidx.test.runner.AndroidJUnitRunner',timeout=180)
    (ROOT/'evidence/native-app-test-final.log').write_text(output+'\n')
    if 'OK (1 test)' not in output or 'FAILURES' in output:raise RuntimeError(output)
    shots=ROOT/'delivery/screenshots/native-emulator';shots.mkdir(parents=True,exist_ok=True)
    names=['home-empty','records-empty','report','device','home-renamed','threshold-control']
    for name in names:
        data=adb('exec-out','run-as',PKG,'cat',f'files/v3-evidence-{name}.png',binary=True)
        if not data.startswith(b'\x89PNG'):raise RuntimeError('Invalid screenshot: '+name)
        (shots/(name+'.png')).write_bytes(data)
    db=json.loads(adb('exec-out','run-as',PKG,'cat','files/v3-evidence-db-state.json'))
    after=old_snapshot()
    changed=[p for p,v in before['original_apps'].items() if v['readable'] and v!=after['original_apps'].get(p)]
    installed_path=adb('shell','pm','path',PKG).splitlines()[0].removeprefix('package:')
    installed_sha=adb('shell','sha256sum',installed_path).split()[0]
    expected_sha=hashlib.sha256((ROOT/'delivery/KNOK_V3_3.0-ui-preview.apk').read_bytes()).hexdigest()
    result={'status':'passed','tests':1,'serial':SERIAL,'installed_apk_sha256':installed_sha,
        'delivered_apk_sha256':expected_sha,'installed_matches_delivered':installed_sha==expected_sha,
        'original_app_data_changes':changed,'original_apps_checked':sum(v['readable'] for v in before['original_apps'].values()),
        'original_data_files_checked':sum(len(v['sha256_lines']) for v in before['original_apps'].values() if v['readable']),
        'sqlite_probe':db,'screenshots':[p+'.png' for p in names],
        'scope':'Separate app install, four-tab navigation, alias save/recreation, detection-threshold setter and restore only. No speaker, KNOK recording, or model inference.',
        'emulator_audio_disabled':STATE['audio_disabled'],'android_studio_started':False}
    if changed or installed_sha!=expected_sha:raise RuntimeError('Preservation/APK identity failed: '+json.dumps(result))
    adb('shell','run-as',PKG,'rm',*[f'files/v3-evidence-{n}.png' for n in names],'files/v3-evidence-db-state.json')
    adb('uninstall',PKG+'.test')
    (ROOT/'evidence/native-result-final.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False),flush=True)

if __name__=='__main__':
    try:
        main()
    finally:
        if STATE.get('started_emulator'):
            try:
                adb('emu','kill')
                print('Owned emulator stopped after UI verification.',flush=True)
            except Exception as e:
                print('Emulator cleanup needs checking: '+str(e),flush=True)
