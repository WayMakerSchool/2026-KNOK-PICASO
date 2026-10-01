# 상담 참고보고서 기능을 포함한 최종 소스 등록

- 기준일: 2026-10-02
- 전달 ZIP SHA-256: `4db236507feaf8299239b0e4c88a708f5405cf98367e63abdb8382ea098d94f1`
- ZIP 내부 프로젝트 폴더: `knok (4)/`
- 이전 전달본과 비교: 소스 대상 16개 파일 추가, 5개 파일 수정, 삭제 없음

## 반영 내용

녹음 선택 → 작성·관찰 → 미리보기 → 내용 검토 → PDF 저장/공유 흐름을 포함합니다.
보고서 구성·PDF 렌더링·공유 코드, 보고서 화면, FileProvider 경로 설정,
한글 나눔고딕 글꼴과 라이선스, 보고서 자동 테스트 및 기기 테스트를 등록합니다.
기존 Android 앱, ESP32-S3/INMP441 펌웨어, Firebase 서버 및 AI 도구 소스도 유지합니다.

보고서는 저장된 AI 결과를 사용하며, 보고서 작성을 위해 새 Gemini 분석을 호출하지 않습니다.
사용 방법과 구현 범위는 [보고서 출력 안내](../REPORT_EXPORT.md)를 참고하세요.

## 공개용 정리

학교 저장소의 팀 소개와 기존 소스 등록 기록을 유지하고 README의 기능·사용 안내를 갱신했습니다.
펌웨어의 실제 Wi-Fi 정보는 입력용 예시 값으로 바꿨습니다.
그 외 기능 코드는 전달 ZIP과 동일하며, 한글 글꼴과 배포에 필요한 라이선스를 포함합니다.

비밀키·인증 설정, `google-services.json`, 개발자 PC 설정, 빌드·IDE 캐시,
`node_modules`, APK, 원본 음원·학습 산출물, 생성 PDF와 임시 미리보기 파일은 제외합니다.
`REPORT_EXPORT.md`에서 언급하는 `artifacts/`와 `output/`의 검증 산출물은 원본 ZIP에 보관하며 Git에는 등록하지 않습니다.
비공개 Firebase 설정과 학습 데이터를 사용하는 팀원은 해당 자료를 별도로 전달받아야 합니다.

## 이번 등록의 확인 범위

- 오디오 처리 테스트 `node --test firebase/functions/test/audio.test.js`: 3개 통과
- Python 8개, JSON 4개, XML 11개, JavaScript 6개 파일 구문 확인
- 공개용 정리 대상 3개를 제외한 ZIP 소스 109개 파일의 바이트 일치 확인
- 보고서 글꼴·라이선스·PDF 의존성, 화면 연결 및 좁은 FileProvider 공유 경로 확인
- 주요 API 키·GitHub 토큰·개인키·JWT 패턴 검사와 비공개 파일 제외 확인

이 PC에는 실행 가능한 JDK와 Android SDK가 없어 Android 빌드와 보고서 Kotlin/Android 테스트를 재실행하지 않았습니다.
실기기 저장·공유, Arduino 빌드, Firebase 배포 및 유료 API 호출도 수행하지 않았습니다.
`REPORT_EXPORT.md`와 `firebase/SOUND_ANALYSIS_VERIFICATION.md`의 테스트·APK 빌드·PDF 확인 기록은
전달본에 포함된 이전 검증 결과입니다. 이번 소스 등록에서 재실행한 결과와 구분해야 합니다.
