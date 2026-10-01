# KNOK ESP32-S3 SuperMini 녹음기

`KNOK_ESP32_Recorder/KNOK_ESP32_Recorder.ino`는 ESP32-S3 SuperMini에 연결한 I2S INMP441 마이크로 음량을 상시 감지하고, 같은 Wi-Fi의 Android 앱이 HTTP로 자동 녹음과 WAV 다운로드를 처리하도록 하는 펌웨어입니다.

## 1. 업로드 전 설정

스케치 상단의 다음 값을 공유기 정보로 바꿉니다.

```cpp
static const char *WIFI_SSID = "YOUR_WIFI_SSID";
static const char *WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
```

Arduino IDE에서 ESP32-S3 보드 패키지를 선택하고 스케치를 업로드합니다. 업로드 후 시리얼 모니터를 115200 baud로 열면 DHCP로 할당된 IP가 표시됩니다.

앱의 기본 주소는 `http://knok-esp32.local`입니다. mDNS가 공유기나 휴대폰에서 동작하지 않으면 시리얼 모니터에 표시된 IP를 앱에 입력합니다.

## 2. INMP441 배선

| INMP441 | ESP32-S3 SuperMini |
| --- | --- |
| VDD | 3V3 |
| GND | GND |
| SCK / BCLK | GPIO12 |
| WS / LRCLK | GPIO11 |
| SD / DOUT | GPIO10 |
| L/R | GND (left channel) |

## 3. 앱 사용 순서

1. 휴대폰과 ESP32를 같은 Wi-Fi에 연결합니다.
2. KNOK 홈 화면의 `ESP32 외장 마이크` 카드에 mDNS 주소 또는 ESP32 IP를 입력합니다.
3. `연결 확인`을 누릅니다.
4. 홈의 `마이크 녹음 시작`을 누르면 수동 녹음을 시작하고, `중지 및 저장`을 누르면 ESP32의 `latest.wav`가 휴대폰 앱 내부 저장소로 다운로드됩니다.
5. `상시 녹음` 탭에서 시작을 누르면 ESP 마이크 음량을 감시합니다. 90 dB 이상 감지 시 5초 녹음하고, 녹음 종료 후 3초 동안 재감지를 막습니다.
6. 저장된 WAV는 `녹음목록`에서 재생할 수 있습니다.

녹음은 최대 30초이며, 제한 시간에 도달하면 펌웨어가 자동 종료합니다. 앱이 실행 중이면 자동으로 WAV를 가져와 저장합니다.

## 4. HTTP API

| Method | Path | 용도 |
| --- | --- | --- |
| GET | `/api/status` | Wi-Fi, 녹음 상태, 경과 시간, 저장 크기, 현재 음량 확인 |
| POST | `/api/record/start` | 새 녹음 시작 (`?durationMs=5000`으로 제한 시간 지정 가능) |
| POST | `/api/record/stop` | 녹음 종료 및 WAV 헤더 확정 |
| GET | `/api/recording/latest` | 마지막 WAV 다운로드 |

현재 WAV는 16 kHz, mono, signed 16-bit PCM입니다. `levelDb`는 INMP441 원시 진폭에 보정 오프셋을 적용한 값이며, 실제 dB SPL 90 기준으로 사용하려면 기준 음압계로 `DB_CALIBRATION_OFFSET`을 보정해야 합니다. 이 값은 법적 소음 측정용으로 보장되지 않습니다.
