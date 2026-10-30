"""Launch the isolated V3 APK without Android Studio, wiping data, or changing AVD config."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
SDK = Path.home() / 'Library/Android/sdk'
ADB = SDK / 'platform-tools/adb'
AVD = 'KNOK_Pixel7_API36'
PACKAGE = 'com.aistudio.quietneighbors.gspqzy.v3'
APK = ROOT.parent / 'delivery/KNOK_V3_3.2.2-model-score.apk'
RUNTIME = ROOT.parent / 'runtime'


def run(args, timeout=30):
    return subprocess.run([str(v) for v in args], text=True, capture_output=True, check=True, timeout=timeout).stdout.strip()


def already_installed(serial):
    try:
        paths = run([ADB, '-s', serial, 'shell', 'pm', 'path', PACKAGE]).splitlines()
        if len(paths) != 1 or not paths[0].startswith('package:'):
            return False
        installed_hash = run([ADB, '-s', serial, 'shell', 'sha256sum', paths[0][8:]]).split()[0]
        digest = hashlib.sha256()
        with APK.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        return installed_hash == digest.hexdigest()
    except (subprocess.SubprocessError, OSError, IndexError):
        return False


def main():
    if not APK.is_file():
        raise SystemExit(f'APK not found: {APK}')
    aapt = sorted((SDK / 'build-tools').glob('*/aapt'))[-1]
    badging = run([aapt, 'dump', 'badging', APK])
    if not re.search(r"package: name='" + re.escape(PACKAGE) + "'", badging):
        raise SystemExit('This launcher only installs the separate KNOK V3 package.')
    RUNTIME.mkdir(exist_ok=True)
    lines = run([ADB, 'devices']).splitlines()[1:]
    entries = [line.split() for line in lines if len(line.split()) >= 2]
    serial = None
    started = False
    for name, state, *_ in entries:
        if name.startswith('emulator-') and state == 'device':
            if run([ADB, '-s', name, 'emu', 'avd', 'name']).splitlines()[0] == AVD:
                serial = name
                break
    if serial is None:
        occupied = {int(name.split('-')[1]) for name, *_ in entries if name.startswith('emulator-')}
        port = next(p for p in range(5554, 5682, 2) if p not in occupied)
        serial = f'emulator-{port}'
        log = (RUNTIME / 'emulator.log').open('a')
        subprocess.Popen([str(SDK / 'emulator/emulator'), '-avd', AVD, '-port', str(port),
            '-gpu', 'host', '-cores', '2', '-memory', '2048', '-no-audio', '-no-boot-anim',
            '-no-snapshot-load', '-no-snapshot-save', '-camera-back', 'none', '-camera-front', 'none'],
            stdout=log, stderr=log, start_new_session=True)
        log.close()
        started = True
        print('가상폰을 시작해요. Android Studio는 실행하지 않아요.', flush=True)
    for attempt in range(90):
        try:
            if run([ADB, '-s', serial, 'shell', 'getprop', 'sys.boot_completed'], timeout=5) == '1':
                break
        except (subprocess.SubprocessError, OSError):
            pass
        if attempt % 10 == 0:
            print('가상폰 준비를 기다리는 중…', flush=True)
        time.sleep(2)
    else:
        raise SystemExit('가상폰 준비 시간 초과. runtime/emulator.log를 확인해 주세요.')
    if already_installed(serial):
        print('점수 표시 버전이 이미 설치되어 있어요.', flush=True)
    else:
        print('기록을 유지하면서 KNOK V3를 업데이트해요.', flush=True)
        run([ADB, '-s', serial, 'install', '-r', APK], timeout=120)
    run([ADB, '-s', serial, 'shell', 'am', 'start', '-n', f'{PACKAGE}/com.example.MainActivity'])
    (RUNTIME / 'last_launch.json').write_text(json.dumps({'serial': serial, 'started_emulator': started,
        'package': PACKAGE, 'audio_disabled': True if started else None, 'studio_started': False}, indent=2) + '\n')
    print(f'KNOK V3 실행 완료: {serial}', flush=True)
    print('실제 감지·분석에는 기기 연결과 기존 맥 분석 서버가 별도로 필요해요.', flush=True)


if __name__ == '__main__':
    main()
