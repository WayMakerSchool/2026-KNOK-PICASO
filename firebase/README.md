# KNOK Firebase 설정

녹음의 소리 종류를 Gemini로 분석하는 기능은 [소리 분석 설정](SOUND_ANALYSIS.md)을 참고한다.

## 1. Android 앱 등록

Firebase Console에서 프로젝트를 만든 뒤 Android 앱을 추가한다.

- Android package name: `com.aistudio.quietneighbors.gspqzy`
- 앱 닉네임은 자유롭게 입력
- `./gradlew signingReport`의 debug SHA-1을 등록

다운로드한 파일 이름이 정확히 `google-services.json`인지 확인하고 아래 위치에 둔다.

```text
app/google-services.json
```

파일을 넣은 뒤에는 앱을 다시 빌드해야 한다.

## 2. Google 로그인 활성화

Firebase Console의 `Authentication > Sign-in method`에서 Google을 활성화한다.
활성화 후 최신 `google-services.json`을 다시 다운로드해 기존 파일을 교체한다.

## 3. Firestore 만들기

`Firestore Database > Create database`에서 Native mode 데이터베이스를 만든다.
테스트 모드를 계속 사용하지 말고 `firestore.rules`의 내용을 Rules 탭에 붙여 넣어 게시한다.

데이터 구조:

```text
users/{uid}
users/{uid}/noise_records/{syncId}
```

## 4. Storage 만들기

`Storage > Get started`에서 버킷을 만든다. `storage.rules`의 내용을 Storage Rules 탭에 붙여 넣어 게시한다.

파일 구조:

```text
noise-recordings/{uid}/{syncId}.{확장자}
```

## 5. 동작 방식

- Firebase Auth의 Google 계정 UID를 앱의 계정 키로 사용한다.
- 로그인 전 Room 기록은 이 기기에서 처음 로그인한 계정에 귀속한다.
- 측정 기록은 Room에 먼저 저장되고 Firestore에 비동기로 동기화된다.
- 녹음 파일은 Firebase Storage에 올라가고 다른 기기에서 필요할 때 내려받는다.
- Firestore와 Storage 규칙 모두 요청 UID와 경로 UID가 같을 때만 접근을 허용한다.
