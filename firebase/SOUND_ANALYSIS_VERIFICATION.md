# 소리 분석 검증 (2026-10-01)

## 충전 이후 API 확인

사용자가 충전한 뒤 제공 키로 Google 공식 Gemini API 오디오 요청이 HTTP 200으로 성공했다.
이전 HTTP 402 잔액 부족은 이번 요청에서 재현되지 않았다.
키는 `.secrets/gemini-api-key`에 저장되어 있으며 APK에 포함하지 않는다.

`artifacts/gemini_validation/`에 키를 제외한 실제 요청 결과를 기록했다.
2.5 Flash-Lite, 3.1 Flash-Lite, 3.5 Flash-Lite 및 비교용 2.5 Flash/3.8 Flash를 호출했다.
같은 프롬프트를 쓰고 파일 이름/정답 레이블은 모델에 보내지 않았다.

| 모델 | 실제 관찰 |
| --- | --- |
| 2.5 Flash-Lite | 원본 6개를 모두 발걸음으로 판단. 정규화 후에도 청소기를 물소리로 판단 |
| 3.1 Flash-Lite | 보정 후 첫 청소기는 감지, 다른 청소기는 물소리. 디지털 무음을 물소리로 판단 |
| 3.5 Flash-Lite | 보정 후 청소기 2개 모두 감지. 망치 1개 감지, 다른 망치는 문 두드림. 무음은 판단 불가 |
| 2.5 Flash / 3.8 Flash | 상위 가격 모델도 오인식 및 무음 환각 발생. 가격만으로 성능을 보장할 수 없음 |

기존 학습 데이터의 클래스별 `0`, `25` 샘플을 비교했다.
매우 작은 녹음(청소기0 RMS 약0.0002 등)은 분석 사본을 PCM peak 정규화해 재검증했다.
3.5도 발걸음/가구 끌기 등을 바람이나 심장박동으로 오인식했다.
원본 normal_or_unknown0에서는 3.1/3.5가 개 짖는 소리를 반환했다.
학습 레이블은 별도 청취로 확정한 평가 정답이 아니므로 정확도 퍼센트를 계산하지 않았다.
이 작은 표본은 실환경 일반화 성능을 증명하지 않는다.

이 결과로 3.5 Flash-Lite를 참고용 기능의 기본 모델로 선택했다.
AI가 무음에서 내용을 만들어 내는 사례를 막기 위해 서버의 PCM 무음 검사를 추가했다.
근거와 다른 가능성을 함께 표시하고 오인식 안내를 녹음 보관함에 표시한다.

## 앱·서버 검증

- Android 관련 자동 테스트 10개 통과: 응답 파싱, 저장/계정 소유권, 마이그레이션, 결과 UI.
- Debug APK 빌드와 Release Kotlin 컴파일 성공.
- 최종 APK의 압축 항목 전체를 검사해 제공한 유료 키의 원문/Base64 값이 없음을 확인했다.
  `artifacts/gemini_validation/apk-key-check.json`에 기록했다.
- 서버 자동 테스트 6개 통과: 입력 제한, 무음 차단/음량 보정, 응답 검증,
  로그인 누락/잘못된 입력 거절, 실제 ffmpeg를 사용한 WAV 및 AAC M4A 디코딩.
- Firebase Callable 함수 정의를 Node에서 로드하고 JS 구문 검사를 통과했다.

## 키 등록과 배포

- 사용자가 Firebase/Google Cloud 관리 권한 부여와 서버 연결을 승인했다.
  공식 Firebase CLI 로그인 완료 후 Secret Manager에 `GEMINI_SOUND_API_KEY` 버전 1을 등록했다.
  비밀 값은 활성 상태이며 함수의 secret environment binding에서 버전 1 참조를 확인했다.
- `analyzeRecordingSound`를 Seoul(`asia-northeast3`)에 배포했다. 최종 갱신은 `Deploy complete!`로 성공했다.
  함수 ACTIVE, 메모리512MiB, 최대2인스턴스, 동시성1, 제한시간120초를 확인했다.
  빌드 컨테이너 이미지는 7일 보관 정책을 설정해 누적을 제한했다.
- 실제 서버에 인증 누락 및 위조 토큰 요청을 보내 두 경우 모두 HTTP401 `UNAUTHENTICATED`를 확인했다.
  `artifacts/gemini_validation/server-auth-check.json`에 기록했다.
  이 검증은 유효한 Android 인증 요청의 성공을 증명하지 않는다.
- 앱 Firebase와 Gemini 키 프로젝트가 다르므로 직접 Firebase AI Logic 연결 대신
  제공한 키를 사용하는 인증된 Callable 서버를 배포했다.

## 아직 필요한 검증

- 사용자 제공 실패 화면과 서버 로그에서 로그인은 VALID, App Check는 INVALID임을 확인했다.
- 연결한 휴대폰의 전용 Debug App Check 토큰을 비공개로 등록했다.
  실기기 테스트에서 App Check API 비활성에 따른 HTTP403을 발견해 해당 API를 활성화했다.
  이후 실기기에서 실제 App Check JWT 발급 검사를 통과했다.
- 기존 녹음과 로그인 데이터를 유지한 채 Debug APK와 테스트 APK를 갱신했다.
  UI 버튼 → 분석 → 저장 검증은 휴대폰 잠금 상태로 인해 잠금 해제를 기다린다.

## 재검증

```powershell
python tools/verify_gemini_audio.py --model gemini-3.5-flash-lite --normalize --audio dataset/with_unknown/processed_16k/vacuum_cleaner0.wav --output artifacts/gemini_validation/recheck.json
npm.cmd --prefix firebase/functions test
```

검증 도구는 공식 Google API로만 요청하며 키를 헤더로 전달하고 오류 출력에서 가린다.
HTTP 402/인증/할당량 오류에서는 추가 요청을 중단한다.
