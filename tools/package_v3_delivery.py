"""Package the verified V3 application and source, refusing a mismatched tested APK."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET,zipfile

ROOT=Path(__file__).resolve().parents[1]
DELIVERY=ROOT/'delivery'
VERIFY=DELIVERY/'verification'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    subprocess.run(['python3',str(ROOT/'tools/verify_v3_preservation.py')],check=True,cwd=ROOT)
    native=json.loads((ROOT/'evidence/native-result-final.json').read_text())
    apk=DELIVERY/'KNOK_V3_3.0-ui-preview.apk'
    assert native['status']=='passed' and native['installed_apk_sha256']==sha(apk),'Final APK differs from the native-tested APK'
    rows=[ET.parse(p).getroot() for p in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
    tests={k:sum(int(r.attrib.get(k,'0')) for r in rows) for k in ['tests','failures','errors','skipped']}
    tests['passed']=tests['tests']-tests['failures']-tests['errors']-tests['skipped']
    assert tests['tests']>=88 and tests['failures']==0 and tests['errors']==0
    (ROOT/'evidence/final-jvm-summary.json').write_text(json.dumps(tests,indent=2)+'\n')
    sdk=Path.home()/'Library/Android/sdk'
    buildtools=sorted((sdk/'build-tools').iterdir())[-1]
    env=dict(os.environ,JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home')
    subprocess.run([str(buildtools/'apksigner'),'verify',str(apk)],env=env,check=True,capture_output=True)
    badging=subprocess.check_output([str(buildtools/'aapt'),'dump','badging',str(apk)],text=True)
    package=next(line for line in badging.splitlines() if line.startswith('package:'))
    assert "name='com.aistudio.quietneighbors.gspqzy.v3'" in package and "versionName='3.0-ui-preview'" in package
    assert "application-label:'KNOK V3'" in badging
    metadata={'package':package,'apk_bytes':apk.stat().st_size,'apk_sha256':sha(apk),'signature_verified':True,
        'jvm':tests,'native':native,'physical_knok_tested':False,'sound_or_floor_accuracy_evaluated':False}
    (ROOT/'evidence/apk-verification.json').write_text(json.dumps({'package_line':package,'application_label':'KNOK V3','apk_signature_verified':True,'apk_bytes':apk.stat().st_size,'apk_sha256':sha(apk),'signed_for':'debug verification; not a store release'},ensure_ascii=False,indent=2)+'\n')
    (ROOT/'evidence/final-verification.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
    report=f'''KNOK V3 구현·검증 결과 — 2026-10-27 (Asia/Seoul)\n\n결과\n확정 포스터 UI를 Native Kotlin/Jetpack Compose에 적용한 별도 Android 앱을 구현했다.\n앱: KNOK V3 / 버전: 3.0-ui-preview / 패키지: com.aistudio.quietneighbors.gspqzy.v3\nAPK: KNOK_V3_3.0-ui-preview.apk / {apk.stat().st_size:,} bytes\nSHA256: {sha(apk)}\ndebug 서명 검증 통과. 실제 가상폰에 설치한 APK와 전달 APK의 해시가 같다.\n\n화면·조작\n- 단일 웜화이트 제품 이미지, 연한 KNOK 배경 글자, 크림 F7F2EB / 블루 006FBA, 일관된 아이콘을 적용했다.\n- 홈·기록·리포트·기기 네 탭과 프로필의 기존 추가 기능을 연결했다.\n- 감지 대기 / 실제 녹음 / 저장 / 오류 상태를 구분한다. 수동 녹음 중에도 홈에서 중지할 수 있다.\n- 감지 기준 조절은 기존 setter를 사용하며 감지·녹음 중에는 편집을 막는다.\n- 연결이 끊기거나 값이 유효하지 않으면 이전 음량을 현재 값처럼 표시하지 않는다. 수신·CRC·저장 재시도 알림을 숨기지 않는다.\n- 저장 녹음을 날짜로 묶고 원본 재생·메모·삭제·분석 결과 및 상담 참고보고서 경로를 유지했다.\n- 이름 변경은 로컬 표시용 별칭이며 기기 주소·원본 녹음·분석 모델은 바꾸지 않는다.\n\n실제로 실행한 검증\nJVM {tests['tests']}개 중 {tests['passed']}개 통과, 실패 0, 기존 시험 {tests['skipped']}개 스킵.\n스킵: AudioFeatureExtractorTest.matchesPythonFeatureRecipeForPreparedClip — 기존 준비음원 특징 비교 항목.\n360dp / 412dp, 1.5배 글꼴과 긴 기기 이름의 줄 간격·버튼 접근, 감지/녹음/저장/실패/분석 대기·실패 상태를 렌더링했다.\n독립 코드 검토에서 찾은 감지 기준 경로·수동 녹음 상태·수신 실패 알림 문제에 회귀 시험을 추가하고 실패→통과를 확인했다.\n\nAndroid 가상폰 실제 실행 시험 {native['tests']}개 통과.\n별도 설치 / 네 탭 이동 / 기기 별칭 저장과 Activity 재생성 / 감지 기준 85 변경과 원래 값 복원을 실행했다.\n이 시험은 자동 감지 시작·소리 재생·새 KNOK 녹음·모델 추론을 실행하지 않았다.\n기존 앱 {native['original_apps_checked']}개에서 검사한 WAV·DB·DB-WAL {native['original_data_files_checked']}개 해시 변경 0건.\n가상폰은 테스트 후 종료했고 Android Studio 편집기는 실행하지 않았다.\n\n보존\n기준 연구 앱 원본 193개 파일의 해시 변경 0건.\nV3의 통신·데이터·ML·인증·ViewModel 등 보호 파일 34개와 모델 관련 자산 11개는 복사 당시와 같다.\nGET /health, POST /v1/analyze, 저장 후 ACK, 기존 저장 형식과 분석 방식은 바꾸지 않았다.\n포스터 숫자·연결 상태·예시 녹음을 실제 데이터로 삽입하지 않았다.\n기존 SDK의 res/raw/hello.wav 리소스는 원래 APK와 같은 해시로 유지된다. 이를 녹음 목록에 등록하거나 이번 시험에서 재생하지 않았다.\n\n자료 구분\nscreenshots/native-emulator/: 실제 별도 설치한 Android 앱의 빈 목록·연결 전 화면 및 설정 조작 화면.\nscreenshots/ui-fixtures/: 테스트 코드에서만 만든 예시 자료로 렌더링한 화면. 이미지 상단에도 실물 시험이 아님을 표시했다.\n이 예시들은 실제 사용자 동작 횟수·AI 성능·새 녹음 결과가 아니다.\n\n남아 있는 검증 범위\n실제 KNOK 감지→녹음→수신→AI 분류→저장 통합 재시험은 미실행이다. 실제 소리·층 정확도를 새로 측정하지 않았다.\nV3는 기존 후보 앱과 같은 KNOK_CANDIDATE=true / KNOK_V2=false 설정을 유지한다.\n실행 DB의 PRAGMA synchronous는 {native['sqlite_probe']['synchronous']} (NORMAL)이다. 기존 순서와 코드 보존을 새 전원 손실 내구성 시험으로 표현하지 않는다.\n서버 연결과 실제 기기 동작은 실제 주소·동일 네트워크·맥 분석 서버 실행 후 별도로 확인해야 한다.\n기존 Gradle/JDK의 향후 버전 호환 경고가 출력되지만 이번 빌드와 시험은 성공했다.\n\n실행\n프로젝트 루트의 'KNOK V3 켜기.command'를 실행한다. GPU host / CPU 2코어 / 메모리 2GB로 가상폰을 독립 실행한다.\n실제 분석에는 README_V3.txt에 적힌 기존 맥 분석 서버가 별도로 필요하다.\n'''
    VERIFY.mkdir(exist_ok=True)
    (VERIFY/'KNOK_V3_검증결과.txt').write_text(report)
    for name in ['final-verification.json','final-jvm-summary.json','native-result-final.json','preservation-result.json','baseline-source-manifest.json']:
        shutil.copy2(ROOT/'evidence'/name,VERIFY/name)
    for f in (ROOT/'app/build/outputs/roborazzi/v3').glob('*.png'):
        shutil.copy2(f,DELIVERY/'screenshots/ui-fixtures'/f.name)
    files=[apk,ROOT/'README_V3.txt',ROOT/'KNOK V3 켜기.command',VERIFY/'KNOK_V3_검증결과.txt']
    (DELIVERY/'manifest.json').write_text(json.dumps({'version':'3.0-ui-preview','artifacts':[{'path':str(p.relative_to(ROOT)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in files]},ensure_ascii=False,indent=2)+'\n')
    archive=DELIVERY/'KNOK_V3_소스와실행자료.zip'
    skip_dirs={'.git','.gradle','.kotlin','build','runtime','__pycache__'}
    source_files=[]
    for p in ROOT.rglob('*'):
        if not p.is_file() or p==archive or any(part in skip_dirs for part in p.relative_to(ROOT).parts):continue
        if p.name in {'.DS_Store','.env','local.properties','archive-integrity.json'} or p.suffix in {'.jks','.keystore'}:continue
        source_files.append(p)
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(source_files):z.write(p,Path(ROOT.name)/p.relative_to(ROOT))
    with zipfile.ZipFile(archive) as z:assert z.testzip() is None
    integrity={'archive':archive.name,'files':len(source_files),'bytes':archive.stat().st_size,'sha256':sha(archive),'zip_crc_verified':True}
    (DELIVERY/'archive-integrity.json').write_text(json.dumps(integrity,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'apk_sha256':sha(apk),'jvm':tests,'native_test_passed':True,'zip':integrity},ensure_ascii=False))

if __name__=='__main__':main()
