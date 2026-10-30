# KNOK 소리·층 방향 AI 실행 안내

이 폴더는 KNOK의 **소리 분류 모델**과 **층 방향 연구 모델**을 맥에서 실행하기 위한 코드와 가중치를 제공합니다. 두 모델의 실측 정확도나 실제 건물에서의 층 판별을 보장하지 않습니다.

## 구성

| 구분 | 구성 | 저장 위치 |
|---|---|---|
| 소리 분류 | AST 사전학습 본체 + KNOK `knok-sound-desk-repair-20261028` 분류부 | 분류부 `sound/models/sound.pt`, 본체는 설치 시 다운로드 |
| 층 방향 연구 | 226개 음향 특징 + `knok-research-floor-clean-r20-20261027` SVM | `floor/current/floor-svm.joblib` |
| 앱 내부 모델 | 이전 시연에서 사용한 TFLite 모델들 | `app/src/main/assets/` |

앱 내부 TFLite와 맥 서비스의 AST·SVM은 서로 다른 모델입니다. 서비스는 16 kHz, 모노, PCM16 WAV를 받아 소리 종류와 조건부 층 방향을 반환합니다. 소리 점수 기준은 0.65이며, **모델 점수는 실측 정답률이나 정답일 확률을 보장하지 않습니다.** 층 방향은 연구 결과이므로 실제 건물의 소음 발생 층을 확정하는 데 사용할 수 없습니다.

## 설치와 실행

macOS에서 Python **3.12**와 Git이 필요합니다. 최초 설치는 인터넷과 충분한 저장 공간이 필요하고, AI 본체는 약 330 MiB입니다. 다음 명령을 터미널에 입력하세요.

```sh
git clone --branch develop https://github.com/WayMakerSchool/2026-KNOK-PICASO.git
cd 2026-KNOK-PICASO
sh research/portable/setup.sh
research/.venv-sound/bin/python research/portable/run_service.py
```

`python3.12`가 기본 경로에 없다면 설치한 Python 3.12 실행 파일을 지정합니다.

```sh
PYTHON=/path/to/python3.12 sh research/portable/setup.sh
```

스크립트는 소리와 층 모델에 필요한 서로 다른 라이브러리 버전을 각각 별도 환경에 설치합니다. [AI 모델 릴리스](https://github.com/WayMakerSchool/2026-KNOK-PICASO/releases/tag/v3.2.2-ai-runtime-20261030)에서 AST 본체를 내려받아 SHA-256을 확인합니다. 처음 구동 시 모델 로딩에 시간이 걸릴 수 있습니다. 서비스는 **이 컴퓨터의 127.0.0.1:8765**에서만 응답하며, 종료는 `Ctrl+C`입니다. 포트가 이미 사용 중이면 `--port 18765`처럼 지정할 수 있으나 기존 Android 앱의 분석 주소는 기본 8765입니다.

별도 터미널에서 소리 재생 없이 확인할 수 있습니다.

```sh
python3 research/portable/smoke_test.py
```

기본 시험에는 프로그램이 만든 무음 WAV를 사용하므로 `normal_or_unknown` 또는 낮은 신호 품질이 정상입니다. 직접 준비한 WAV를 확인하려면 `python3 research/portable/smoke_test.py /path/to/audio.wav`를 사용하세요. 오디오는 로컬 서비스로만 전달하며, 이 실행 경로는 녹음을 별도 캡처 폴더에 저장하지 않습니다.

또는 `GET /health`, `POST /v1/analyze`를 직접 사용할 수 있습니다. POST의 `Content-Type`은 `audio/wav`이며, 최대 크기는 2 MiB입니다. 미니어처 연구에서 등록한 수신 위치를 제공할 경우 헤더 `X-KNOK-Receiver-Position: lower` 또는 `upper`를 넣습니다. 지원하지 않는 소리, 품질이 낮은 녹음, 미등록 위치 등은 방향을 판단 보류합니다.

## Android 및 기기

`./gradlew -PknokV3=true assembleDebug`로 V3 디버그 앱을 빌드할 수 있습니다. Android 에뮬레이터에서 앱은 맥 분석 서비스에 `10.0.2.2:8765`로 접속합니다. 실제 ESP32를 사용하려면 펌웨어에 각 제품의 배선과 Wi-Fi를 설정하고, 앱의 KNOK 주소를 해당 기기의 현재 주소로 지정해야 합니다. 앱과 기기 연결·네트워크·Google 로그인은 환경마다 따로 확인해야 하며 이 안내가 실물 작동이나 로그인을 보증하지 않습니다. 실물 Android 휴대전화의 맥 서비스 연결은 이 설치 경로에서 검증하지 않았습니다.

## 출처와 제한

AST 본체는 [MIT/ast-finetuned-audioset-10-10-0.4593](https://huggingface.co/MIT/ast-finetuned-audioset-10-10-0.4593)의 리비전 `f826b80d28226b62986cc218e5cec390b1096902`입니다. 원본 라이선스는 [BSD 3-Clause](portable/AST_LICENSE.txt)입니다. 본체 SHA-256은 `ae0c1e2ad4e1381d851fa9bf298ba13ebc9c5a914cdee2dbe427a6583869924d`입니다.

이 설치 경로의 검증 범위는 macOS Apple Silicon입니다. 다른 OS나 기기에서의 설치는 별도 확인이 필요합니다. 실제 사용자 녹음, 학습 원본, Wi-Fi 비밀번호, 서명키 및 Firebase 설정은 공개 저장소에 포함하지 않습니다. 연구 자료의 증강 파일은 독립적인 실제 녹음이 아니며, 과거의 개발 자료 결과를 새로운 환경의 정확도로 표현할 수 없습니다.
