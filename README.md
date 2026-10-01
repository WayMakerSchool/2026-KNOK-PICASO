# 🚀 KNOK

> 생활 소음을 녹음하고 AI로 소리 종류를 분류하여, 소음 기록을 관리하는 Android·IoT 프로젝트입니다.

## 📖 프로젝트 소개

- **기간**: 진행 중
- **프로젝트**: KNOK — 생활 소음 기록 및 분류 시스템
- **팀 이름**: 피카소
- **소개**: 생활 소음으로 불편을 겪는 사용자가 소음 발생 기록과 음원을 함께 관리할 수 있도록 돕습니다. ESP32 외장 마이크로 소리를 녹음하고, Android 앱에서 AI로 소리 종류를 분류하여 기록합니다.
- **저장소**: [2026-KNOK-PICASO](https://github.com/WayMakerSchool/2026-KNOK-PICASO)

## ✨ 주요 기능

| **기능** | **설명** |
| :-- | :-- |
| 휴대폰 측정 | 휴대폰 마이크로 녹음하고, 휴대폰 가속도계로 진동을 측정합니다. |
| 외장 마이크 녹음 | Android 앱에서 ESP32 녹음을 시작·중지하고 WAV 파일을 저장합니다. |
| 자동 녹음 | 앱이 외장 마이크 음량값을 감시하고, 기준값 이상이면 녹음·분류·저장을 진행합니다. |
| AI 소음 분류 | 가구 끌기, 발걸음, 망치질, 순간 충격, 청소기, 정상·기타 소리를 분류합니다. |
| 추가 소리 분석 | 로그인·App Check로 인증된 Firebase Callable 서버를 통해 녹음의 소리 종류·특징·다른 가능성을 분석하고 결과를 저장합니다. |
| 상담 참고보고서 | 녹음 선택·작성·미리보기 후 한글 PDF로 저장하거나 공유합니다. 개인정보 가림과 선택한 로컬 파일의 SHA-256 생성을 지원합니다. |
| 기록 관리 | 측정 기록, 메모, AI 분류 결과를 확인하고 저장된 음원을 재생합니다. |
| 계정 및 동기화 | Google 로그인과 Firebase를 통해 사용자별 기록과 음원을 동기화합니다. |

위 기능은 전달된 개발 소스 기준이며, 실제 장치 동작과 앱 빌드 검증이 필요합니다. 기존 TFLite 분류는 Android 앱에서 실행하며, 추가 소리 분석은 Firebase 서버에서 Gemini API를 호출합니다. AI 결과는 참고용 추정이며 오인식될 수 있습니다. 자동 녹음은 앱의 감시에 의존하며, 소리의 실제 발생 층이나 이웃을 특정하는 기능은 구현되어 있지 않습니다.

## 🛠 기술 스택

- **언어**: Kotlin, C++(Arduino), Python, JavaScript
- **프레임워크 / 라이브러리**: Jetpack Compose, Room, Coroutines, OkHttp, TensorFlow Lite, Firebase Auth, Firestore, Firebase Storage, Cloud Functions, App Check, Gemini API, PDFBox-Android
- **도구**: Android Studio, Arduino IDE, Gradle, Git, GitHub
- **하드웨어**: ESP32-S3 SuperMini, INMP441 마이크

## 👥 팀원

| <img src="https://github.com/youngincho10.png" width="100" alt="조영인 프로필"> | <img src="https://github.com/Yesungcat.png" width="100" alt="이예성 프로필"> |
| :--: | :--: |
| [**조영인**](https://github.com/youngincho10) | [**이예성**](https://github.com/Yesungcat) |
| 소프트웨어·하드웨어 개발 | 전체 기획 · 제품 방향성 · 디자인 · 발표 · 사업 방향 |
## ▶️ 실행 방법

### 1. 저장소 받기

```bash
git clone --branch develop https://github.com/WayMakerSchool/2026-KNOK-PICASO.git
cd 2026-KNOK-PICASO
```

2026-10-02 전달된 최종 `KNOK.zip`의 상담 참고보고서 기능까지 반영했습니다. 추출 범위와 이번 확인 결과는 [최종 소스 등록 기록](docs/SOURCE_IMPORT_20261002.md)을 참고하세요. [이전 등록 기록](docs/SOURCE_IMPORT_20261001.md)도 보관합니다.

### 2. Android 앱 실행 준비

1. Android Studio에서 Android 프로젝트 폴더를 엽니다.
2. Android SDK와 Gradle 실행용 JDK를 설정합니다.
3. `local.properties`의 SDK 경로를 현재 PC 환경에 맞춥니다.
4. `app/google-services.json`은 Git에 포함하지 않습니다. 팀이 관리하는 Firebase Android 설정 파일을 별도로 전달받아 해당 위치에 넣습니다.
5. Gradle 동기화 결과를 확인하고 Android 기기에서 앱을 실행합니다.
6. 측정 기능에 필요한 마이크 권한을 허용합니다.

### 3. ESP32 외장 마이크 연결

1. INMP441 마이크를 아래 표에 맞춰 연결합니다.
2. 녹음기 스케치의 Wi-Fi 정보를 사용 환경에 맞게 설정합니다.
3. Arduino IDE에서 ESP32-S3 보드 설정을 확인하고 스케치를 업로드합니다.
4. 시리얼 모니터를 `115200 baud`로 열어 장치 IP를 확인합니다.
5. 휴대폰과 ESP32를 같은 Wi-Fi에 연결하고, 앱에 장치 주소를 입력합니다.

| **INMP441** | **ESP32-S3 SuperMini** |
| :-- | :-- |
| VDD | 3V3 |
| GND | GND |
| SCK / BCLK | GPIO12 |
| WS / LRCLK | GPIO11 |
| SD / DOUT | GPIO10 |
| L/R | GND |

Google 로그인과 클라우드 동기화를 사용하려면 Firebase의 Android 앱 등록, Google 로그인 활성화, Firestore 및 Storage 설정도 필요합니다.

### 4. 추가 소리 분석 설정

[소리 분석 연결·설정 안내](firebase/SOUND_ANALYSIS.md)를 확인하세요. 유료 Gemini 키와 인증 토큰은 `.secrets/` 또는 서버 Secret Manager 등 비공개 설정으로 관리하며 Git이나 APK에 넣지 않습니다. 서버 설정·배포와 유료 API 호출은 소스 등록 작업과 별도로 수행합니다.

### 5. 상담 참고보고서 만들기

녹음목록에서 **상담 참고보고서 만들기**를 선택한 뒤 녹음 선택 → 작성·관찰 → 미리보기 → 내용 검토 → PDF 저장/공유 순서로 진행합니다. 보고서는 저장된 AI 결과를 사용하며, 보고서 생성을 위해 새 Gemini 분석을 호출하지 않습니다. 자세한 사용 방법과 제약은 [보고서 출력 안내](REPORT_EXPORT.md)를 참고하세요.

## 📁 폴더 구조

최신 ZIP에서 등록한 소스 구성입니다.

```text
.
├── app/              # Android 앱·보고서 PDF·테스트·리소스·TFLite 모델
│   └── src/main/     # report 코드·보고서 화면·한글 글꼴·라이선스
├── esp32/            # ESP32-S3 + INMP441 펌웨어·배선 안내
├── firebase/         # 보안 규칙·소리 분석 서버·설정 및 검증 기록
│   └── functions/    # Node.js Callable 함수·package-lock·테스트
├── tools/            # AI 데이터 준비·학습·검증 도구
├── gradle/           # Android 빌드 wrapper
├── docs/             # 소스 등록 기록
├── firebase.json     # Firebase Functions 배포 설정
├── REPORT_EXPORT.md  # 상담 참고보고서 사용 방법·검증 기록
├── *.gradle.kts, gradle.properties, gradlew, gradlew.bat
└── README.md
```

## 🤝 협업 규칙

- **브랜치**
  - `develop`: 개발용 기본 브랜치. 모든 작업은 여기서 시작해요.
  - `feat/기능이름`, `fix/버그이름`: `develop`에서 만들어서 작업하고, PR로 `develop`에 합쳐요.
  - `main`: 발표나 배포할 때만 `develop`을 합쳐요.
- **커밋 메시지**: `feat: 녹음 목록 검색 추가`, `fix: WAV 해석 오류 수정`, `docs: README 수정`
- **이슈**: 작업 목적과 완료 조건을 적고, 버그는 재현 순서와 기대 동작을 함께 기록해요.
- **PR**: 관련 이슈, 변경 내용, 실제 확인 결과를 적고 팀원의 리뷰를 받은 뒤 합쳐요.
