# 녹음 소리 분석 설정

녹음 보관함의 **소리 분석**을 누르면 로그인·App Check로 인증된 Firebase Callable 서버가
휴대폰 M4A 또는 ESP32 WAV의 소리 종류, 실제 들리는 특징, 다른 가능성을 분석한다.
원본 녹음과 기존 TFLite 결과는 유지하고 Gemini 결과를 Room/Firestore에 별도로 저장한다.
AI 결과는 오인식될 수 있으며 소음 발생 층·집·사람·dB를 판단하지 않는다.

## 연결 구조

- 앱 Firebase 프로젝트: `knok-641b9`, 서버 지역: `asia-northeast3`.
- 함수: `analyzeRecordingSound`, 소스: `functions/index.js`.
- 사용자가 충전한 Gemini 프로젝트: `gen-lang-client-0901461350`.
  해당 키를 서버 Secret Manager의 `GEMINI_SOUND_API_KEY`에 저장해야 이 크레딧을 사용할 수 있다.
- `.secrets/gemini-api-key`는 로컬 검증용 저장이다. 서버 등록 완료와 다르다.
  `.env`, APK, `google-services.json`, 브라우저 코드에 이 키를 넣지 않는다.
- 2026-10-01 사용자 승인 후 Secret Manager 버전1 등록과 서버 배포를 완료했다.
- Firebase AI Logic은 이번 기능의 호출 경로가 아니다.

## 서버 등록과 배포

공식 Firebase CLI 관리 권한 승인 후 저장한 키를 파일 입력으로 등록한다.
아래 명령은 저장소 루트에서 실행하며 키 값을 콘솔에 출력하지 않는다.

```powershell
npm.cmd --prefix firebase/functions ci
node firebase/functions/node_modules/firebase-tools/lib/bin/firebase.js login
node firebase/functions/node_modules/firebase-tools/lib/bin/firebase.js functions:secrets:set GEMINI_SOUND_API_KEY --data-file .secrets/gemini-api-key --project knok-641b9
node firebase/functions/node_modules/firebase-tools/lib/bin/firebase.js deploy --only functions:sound-analysis --project knok-641b9
```

프로젝트는 이미 Blaze 요금제다. Cloud Functions/Secret Manager/Firestore의 사용량 비용은
Gemini 선불 크레딧과 별도다. 배포 과정에서 필요한 Google API와 서비스 계정 권한 설정을 확인한다.
기존 Firestore/Storage 규칙은 이 배포에서 변경하지 않는다.

App Check에 기존 Android 앱을 등록한다. Debug 빌드는 Debug provider의 Logcat 토큰을
App Check > 앱 > Manage debug tokens에 등록한다. Release는 Play Integrity를 설정한다.
서버는 App Check 강제 적용 및 Firebase Auth 로그인을 요구하며 인증을 끄지 않는다.
토큰 발급에 필요한 `firebaseappcheck.googleapis.com` API도 활성화해야 한다.
이 저장소에서는 승인된 Firebase CLI 계정으로 `node tools/enable_sound_appcheck.cjs`를 실행한다.

실기기 테스트는 명시적인 `allowPaidSoundAnalysis=true` 인자에서만 활성화된다.
기존 로그인·녹음 하나로 UI 버튼 → 서버 분석 → Room/Firestore 저장을 검사하며,
테스트 중 휴대폰 잠금을 해제하고 화면을 켜 둬야 한다. 테스트 때문에 앱 데이터를 지우지 않는다.

## 입력·비용 제한

- 파일 10MB 이하, 실제 디코딩 길이 5분 이하. 긴 입력은 조용히 잘라서 분석하지 않는다.
- 서버에서 WAV/M4A를 16kHz 모노 PCM16으로 변환하고 분석 사본만 음량 보정한다.
  최대 이득 1000배, 목표 peak 0.8. 원본 파일은 변경하지 않는다.
- 디지털 무음은 Gemini 호출 없이 `판단 불가`로 처리한다. 미세한 배경 잡음의 무음을 보장하지는 않는다.
- 모델 `gemini-3.5-flash-lite`, thinking MINIMAL, 출력 최대 512토큰.
  상위 모델 자동 재호출이나 자동 재시도는 없다.
- 계정별 30초 간격/UTC 날짜별 하루 20회. 실패도 호출 시도 횟수에 포함된다.
  앱 저장 결과 및 서버의 동일 파일 해시 캐시를 재사용한다.
- 임시 오디오는 성공/실패 모두 서버 처리 종료 시 삭제한다. 서버에는 분석 결과·파일 해시·호출 횟수만 저장한다.

2026-10-01 공식 Standard 가격은 입력 $0.30/100만 토큰, 출력 $2.50/100만 토큰이다.
실측 4초 파일은 오디오 100토큰, 프롬프트 257토큰, 출력 약 55~68토큰:
Gemini API 비용 약 $0.000245~0.000277/회. Thinking·부대 비용과 요금 변경에 따라 달라진다.
가장 싼 2.5 Flash-Lite는 제공 키에서 호출 가능했지만 샘플별 오인식이 많아 제외했다.
3.1도 더 저렴한 출력 가격을 갖지만 이번 비교에서 청소기/무음 등을 오인식했다.
3.5의 정확도를 충분히 검증했다고 주장하지 않는다. 자세한 결과는 검증 기록을 참고한다.

공식 문서:
- https://ai.google.dev/gemini-api/docs/pricing
- https://firebase.google.com/docs/functions/callable
- https://firebase.google.com/docs/functions/config-env#secret_parameters
- https://firebase.google.com/docs/app-check/cloud-functions
