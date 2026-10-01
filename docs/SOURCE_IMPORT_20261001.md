# 최신 KNOK ZIP 소스 등록

- 기준일: 2026-10-01
- 전달 ZIP SHA-256: `b4c9809d381eb57a9643d92dd2abb9bea53de2f94511d5a9de46a5460b54c86e`
- ZIP 내부 프로젝트 폴더: `knok (4)/`
- 이전 전달본과 비교: 추출 대상 중 22개 파일 추가, 13개 파일 수정

## 등록 범위

Android 앱·테스트·리소스와 실행용 TFLite 모델, ESP32-S3/INMP441 펌웨어,
Firebase 규칙과 Functions 소리 분석 서버, Node 의존성 lockfile,
Python/JavaScript 학습·검증 도구, Gradle wrapper 및 설정 문서를 등록합니다.

원본 음원과 학습 산출물, APK, 빌드·IDE 캐시, node_modules,
`.secrets/`, 실제 Firebase Android 설정, 개발자 PC의 SDK 경로는 제외했습니다.
팀원은 Firebase 설정과 필요한 학습 데이터를 비공개 경로로 전달받아야 합니다.
추가 학습 재현에는 ZIP에 보관된 dataset/artifacts 등 별도 자료가 필요합니다.

## 공개용 정리

학교 저장소의 기존 팀 소개·협업 규칙을 유지하고 최신 폴더 구조와 실행 안내를 갱신했습니다.
펌웨어의 Wi-Fi 이름·비밀번호는 `YOUR_WIFI_SSID`, `YOUR_WIFI_PASSWORD`로 바꿨습니다.
`.gitignore`를 보완해 비공개 설정과 생성물을 제외합니다.
그 외 앱·서버 기능 코드는 전달본을 유지했습니다. 원본 ZIP은 변경하지 않았습니다.

## 검증 범위

추출한 소스와 ZIP의 바이트·해시 일치, 소스 구조와 민감정보 제외를 확인합니다.
이번 등록에서 Python 8개, JSON 4개, XML 10개, JavaScript 6개 파일의 구문을 확인했습니다.
`node --test firebase/functions/test/audio.test.js`의 오디오 처리 테스트 3개가 통과했습니다.
인증·실제 ffmpeg 디코딩 테스트는 의존성이 설치되지 않아 재실행하지 않았습니다.
주요 API 키·인증 토큰·개인키 패턴 검사에서 발견 0건이며, 비공개 설정 파일 제외를 확인했습니다.
Android·Arduino 전체 빌드, 실제 장치 동작, Firebase 서버 배포·유료 API 호출은 이 등록 작업에 포함되지 않습니다.
`firebase/SOUND_ANALYSIS_VERIFICATION.md`의 빌드·서버 기록은 전달본에 포함된 이전 검증 기록입니다.
이번 소스 등록에서 해당 작업을 재실행했다고 의미하지 않습니다.
