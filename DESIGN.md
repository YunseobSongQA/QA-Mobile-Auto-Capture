# QA 증적 자동 캡처 — 설계 문서

## 궁극적 목표 (바꾸지 않는다)

**QA 증적에 드는 시간을 줄인다.** QA 테스터는 목표 화면을 확인하면서 바뀐 점을 확인하고, 그 경로(루트)를 증적으로 남겨 보고해야 한다.
이 앱은 테스터가 평소처럼 앱을 쓰는 동안 그 경로를 **보고서에 바로 붙일 수 있는 깔끔한 사진**으로 남긴다.

| 결과물 원칙 | 내용 |
|---|---|
| 사진 순서 = 경로 | 방문한 화면마다 시간순 번호가 붙은 사진. 같은 화면을 다시 가면 새 번호 |
| 스크롤은 화면당 파일 하나 | 한 화면에서 스크롤한 부분은 그 화면의 파일 하나로 병합. **스크롤했을 때 겹치는 부분끼리만** 합친다. 다른 화면·상태의 사진은 합치지 않고, 겹침이 없으면 다음 번호 파일로 나눈다 |
| 깔끔한 사진만 | 화면에 들어간 사진은 전환이 끝난 뒤, 토스트가 떠 있거나 로딩 표시가 돌면 사라진 뒤에 찍는다 |
| 단순하게, 첫 버전 방식 | 화면에 들어가면 1장, 스크롤을 멈추면 1장(길게 스크롤하면 화면 절반마다). 찍는 때를 판단하는 복잡한 규칙을 다시 넣지 않는다(0-3) |
| 앱 흔적 없음 | 플로팅 버튼 등이 사진에 찍히지 않는다 |

모든 기능 변경은 이 원칙을 지킨다(같은 내용이 저장소의 CLAUDE.md 에 있다).
판단이 애매하면 "보고서에 그대로 붙일 수 있는 사진인가, 테스터의 시간을 줄이는가"로 정한다.

- 대상 기기: Galaxy S21 / Android 15 (One UI 7) / 1080x2400
- Kotlin, minSdk 26, targetSdk 35 (compileSdk 35). API 30 이상이 기준이고 API 26~29 는 0-5 의 방식으로 찍는다.
- 런타임 외부 라이브러리 **0개** (AndroidX도 쓰지 않음). 테스트 전용으로 JUnit 4만 사용.
- 빌드: Gradle Wrapper(9.7.1) + AGP 9.4.1(내장 Kotlin), JDK 21. Android Studio 불필요.

---

## 0. 사양과 달라진 부분

### 0-1. 저장 경로 (`Pictures/` 에는 manifest.json 을 둘 수 없음)

저장소 권한 없이 MediaStore로 쓸 때 Android 11+ 의 MediaProvider 규칙은 다음과 같다.

| 컬렉션 | 허용 최상위 폴더 | 허용 MIME |
|---|---|---|
| `MediaStore.Images` | `DCIM/`, `Pictures/` | `image/*` 만 |
| `MediaStore.Files` | `Download/`, `Documents/` | 제한 없음 |

그래서 모든 산출물(PNG, manifest.json, session_log.txt)을 `Documents/QAEvidence/{세션시작시각}/` 한 폴더에 저장한다(A안, 확정).
PNG도 이미지로 색인되므로 갤러리 앱에 앨범으로 보인다. 경로와 파일 이름 규칙은 `storage/StoragePaths.kt` 한 곳에서 관리한다.

### 0-2. 캡처 방식 변경 이력

| 차수 | 방식 | 바꾼 이유 |
|---|---|---|
| 1차 | 진입 대기·스크롤 디바운스 후 캡처, 화면마다 스티칭 1장 | — |
| 2차 | 화면 안정화 대기(로딩 표시·토스트 판단) | 깨끗한 화면 |
| 3차 | 판단 규칙 없이 변화마다 캡처 + `_final`, 스크롤은 `_scroll` 로 이어붙임 | 놓치는 화면 없게 |
| 4차 | 보고용: 중요한 때만, 정적으로 완성된 화면, 화면 상태마다 1장(스크롤은 합침) | 3차는 이미지가 너무 많고 깜빡임·작은 상태까지 찍힘 |
| 5차 | 4차 + 이벤트가 쉬지 않는 앱 대응, 스크롤 중간 프레임, 토스트·로딩 걸러내기 | 실제 앱에서 확보 숫자가 잘 안 오르고, 스크롤이 `_2` 로 나뉨 |
| **6차(현재)** | **1차 방식으로 되돌림 + 진입 사진의 토스트·로딩만 미룸** | 2~5차의 판단 규칙이 실제 앱에서 제대로 동작하지 않았고, 사용자가 "기능은 맨 처음 버전이 가장 좋았다"고 함 |

### 0-3. 캡처 규칙 (6차 = 1차 방식)

찍는 방식은 1차(커밋 `c380ba0`)를 기준으로 단순하게 유지한다. 판단 규칙을 덧붙이지 않는다.

| 규칙 | 내용 |
|---|---|
| 화면 진입 | `TYPE_WINDOW_STATE_CHANGED` 뒤 창 이벤트가 `ENTRY_SETTLE_MS`(0.5초, 설정 0.3~3초) 동안 잠잠하면 1장. 같은 화면의 창 변경(패널·제목)도 같은 방식으로 1장 더 |
| 스크롤 | 스크롤 이벤트가 `SCROLL_DEBOUNCE_MS`(0.3초) 동안 없으면 1장. 멈추지 않고 길게 스크롤하면 이동량이 화면 높이의 50%(`SCROLL_STEP_RATIO`)를 넘을 때마다 1장(이동량을 모르면 0.45초마다) → 프레임끼리 겹친다 |
| 화면당 1장 | 같은 화면 방문에서 찍은 사진은 그 화면을 떠날 때(다른 화면·정지) **겹치는 부분끼리** 이어붙여 1장. 같은 위치 사진은 버리고, 겹침이 없거나(새 패널, 너무 빠른 스크롤) 크기가 바뀌면 `_2`, `_3` 으로 나눈다 |
| 진입 사진의 토스트·로딩 | 진입 사진을 찍을 때 토스트가 떠 있으면(뜬 뒤 4초) 사라질 때까지, 앞 창에 돌아가는 로딩 표시(진행률 없는 `ProgressBar`, Compose 포함)가 보이면 0.3초마다 다시 보며 미룬다. 진입 후 `ENTRY_MAX_WAIT_MS`(5초)가 지나면 그대로 찍는다. 찍는 순간 토스트가 떠 있었으면 그 사진은 버리고 토스트가 사라진 뒤 다시 |
| 스크롤 사진 | 겹침을 위해 거르지 않는다(이어붙일 때 다른 사진의 같은 행이 우선 쓰인다) |
| 이동량 모름 | `scrollDeltaY` 가 -1 이면 이동량을 모르는 것으로 본다(1차와 같음. 2~5차는 1px 이동으로 잘못 셌다) |
| 무시 | 가로로만 스크롤(배너·탭 넘기기), 화면 높이 25% 미만 영역의 스크롤(티커 등), 이 앱·런처·시스템 UI·키보드 |
| 플로팅 버튼 | 찍을 때마다 숨겼다가 다시 보인다. 스크롤하는 동안은 계속 숨겨 둔다(깜빡임 없음). 사진을 찍을 때마다 잠깐 `✓ 수` 로 바뀐다(숫자 = 사진을 찍은 화면 수) |
| 결과 | 사진은 임시 PNG 로만 두고 화면을 떠날 때 이미지로 저장. 파일 이름 `{순번}_{앱}_{화면}_{진입시각}.png`, 나뉜 경우 `_2`, `_3` |

2~5차의 정지 확인(비교 화면 반복), 미리 확보, 작은 변화 교체·새 상태 판단, 터치·입력·내용 변경 이벤트는 모두 뺐다.

### 0-4. 함께 정한 것

| 항목 | 내용 | 이유 |
|---|---|---|
| 토스트 | 진입 사진은 토스트가 사라진 뒤 찍고, 문구는 시각과 함께 세션 로그·manifest 에 기록 | 결과 확인 문구도 증적 |
| 화면을 떠남 | 홈(런처)·이 앱이 앞으로 오면 기다리던 촬영을 버린다 | 홈 화면이 증적에 찍히지 않게 |
| 저장 밀림 보호 | 처리 대기 사진이 3장 이상이면 다음 촬영을 미룬다 | 메모리 보호 |
| 마스터 스위치 | 접근성 서비스는 켜 둔 채 캡처만 일시정지/재개. 메인 화면 상단 + 빠른 설정 타일 | |
| 네트워크 권한 제거 고정 | Manifest `tools:node="remove"`, CI 에서 APK 권한 확인 | 외부 전송 없음 보장 |
| 창 ID 매핑 | `windowId → 화면 식별자`. 같은 창의 다른 className 은 같은 화면, 다이얼로그가 닫힌 뒤 원래 화면 스크롤로 복귀 인식 | 1차부터 |
| 최대 높이 | 이어붙인 이미지 1장 `MAX_STITCH_HEIGHT` 20,000px | 뷰어 호환 |
| PNG 직접 인코딩 | 이어붙인 이미지는 순수 Kotlin 스트리밍 인코더로 행 단위 기록 | 메모리 |
| 공유 | zip 을 MediaStore 에 저장하고 그 URI 로 공유(FileProvider 없이) | |

### 0-5. API 26~29 지원 (Android 8.0~10)

API 30 이상의 동작은 그대로 두고, 26~29 에서만 다른 길로 간다. 26~29 코드는 새 파일에 두고 기존 파일에는 `SDK_INT` 분기만 넣었다.

| 다른 점 | API 26~29 에서 | 파일 |
|---|---|---|
| 화면 캡처 | 접근성 `takeScreenshot`(30) 대신 화면 녹화(MediaProjection). 기록을 시작할 때 1회 동의 → 포그라운드 서비스(알림 "QA 증적 기록 중")가 화면을 비추는 가상 디스플레이를 ImageReader 로 받고, 찍을 때 가장 최근 화면 1장을 Bitmap 으로. 기록을 멈추면 녹화·알림도 끝 | `capture/projection/ProjectionCapture`·`ProjectionService`·`ProjectionConsentActivity`, 분기: `ScreenshotTaker.takeShot`, `CaptureService.startSession/stopSession` |
| 저장 (26~28) | MediaStore `RELATIVE_PATH`(29)가 없어 저장공간 쓰기 권한으로 `Documents/QAEvidence/` 에 직접 쓰고 MediaStore 에 경로로 등록(같은 content URI 로 목록·갤러리·공유). 권한은 기록 시작 때 동의 화면이 먼저 묻는다 | `storage/LegacyEvidenceStore`, 분기: `EvidenceStore` 각 메서드 첫 줄 |
| 스크롤 이동량 (26~27) | `scrollDeltaY`(28)가 없어 -1(모름)로 넘긴다 → 길게 스크롤하면 0.45초마다 1장(1차와 같음) | `CaptureService`, `DebugLog` |
| 화면 | 굵은 글꼴·시스템바 여백·타일 부제목·썸네일·갤러리 URI 를 이 버전 API 로 | `Ui`, `CaptureTileService`, `SessionActivity`, `ImageViewerActivity`, `SessionStore`(versionCode) |
| 안내 | 설정 안내에 저장공간 권한(26~28), 화면 녹화 허용 안내 단계 추가. 개인정보 카드 권한 문구 | `SetupGuideCard`, `PrivacyCard` |

동의 화면과 녹화 서비스는 `@bool/projection_capture`(values-v30 에서 false)로 API 30 이상에서 꺼 두고, 권한은 `maxSdkVersion` 으로 API 30 이상에 없다.

---

## 1. 코드 구조

### 1-1. 규칙

GitHub 에서 많이 참고하는 안드로이드 저장소(Now in Android, android/architecture-samples)와 Kotlin 공식 코딩 규칙을 따른다.

| 규칙 | 내용 |
|---|---|
| 패키지 = 기능 | `capture`(언제 찍을지) · `session`(찍은 것을 기록) · `stitch`(이어붙이기) · `storage`(파일) · `overlay`(플로팅 버튼) · `ui`(화면) |
| 파일 1개 = 클래스 1개 | 파일 이름 = 주 클래스 이름. 그 클래스만 쓰는 작은 모델(예: `FrameStore.kt` 의 `FrameFile`)만 같은 파일에 둔다 |
| 순수 로직 분리 | `stitch` 는 Android 를 import 하지 않는다 → JVM 단위테스트. 테스트는 같은 패키지 경로의 `{클래스}Test.kt` |
| 한 곳에 모으기 | 캡처 상수는 `CaptureConfig`, 저장 위치·파일 이름 규칙은 `StoragePaths` |
| 의존 방향 | `ui` → `capture`·`storage` / `capture` → `session` → `stitch`·`storage`. 거꾸로 부르지 않는다 |
| 하지 않는 것 | 인터페이스·DI 프레임워크(구체 클래스 직접 호출), 레이아웃 XML(화면은 코드로), 외부 라이브러리 |
| 파일 머리말 | 파일 맨 위 KDoc 에 "이 파일이 하는 일"을 쓰고, 긴 클래스는 `// ── 구역 ──` 주석으로 나눈다 |
| 이름 고정 | `capture/CaptureService`(바꾸면 덮어쓰기 설치 후 접근성 권한이 풀림), `capture/CaptureTileService`(빠른 설정 타일), `MainActivity`(런처·접근성 설정의 "설정" 버튼이 여는 화면, 앱의 진입점이라 최상위 패키지) |

### 1-2. 폴더와 파일

```
app/src/main/java/com/qa/autocapture/
├── MainActivity.kt                 앱 진입점(메인 화면): 카드 4개(ui/main)를 쌓는다
├── capture/                        접근성 서비스 — 언제 무엇을 찍을지
│   ├── CaptureService.kt               진입점: 이벤트 분배, 기록 시작·정지, 마스터 스위치
│   ├── CaptureController.kt            찍을 때 판단: 화면 진입 0.5초 뒤, 스크롤 멈춤·길게 스크롤 중(토스트·로딩이면 진입 사진 미룸)
│   ├── ScreenshotTaker.kt              촬영 큐: 최소 간격, 버튼 숨김, takeScreenshot, 축소 이미지 계산
│   ├── ScreenTracker.kt                화면 식별: 무시할 패키지, 창 ID → 화면(Screen)
│   ├── CaptureStatus.kt                메인 화면·타일이 보는 서비스 상태
│   ├── DebugLog.kt                     디버그 로그(받은 이벤트·판단 기록)
│   ├── CaptureTileService.kt           빠른 설정 타일(일시정지/재개)
│   ├── CaptureConfig.kt                캡처 상수 전부
│   └── projection/                     API 26~29 전용: 화면 녹화로 찍기(0-5)
│       ├── ProjectionCapture.kt            기록 시작 때 동의 요청, 찍기, 녹화 종료
│       ├── ProjectionService.kt            포그라운드 서비스 + 알림, 가상 디스플레이 → 가장 최근 화면
│       └── ProjectionConsentActivity.kt    투명 화면: 저장공간 권한(26~28), 화면 녹화 동의
├── session/                        기록 — 찍은 화면을 세션·이미지로
│   ├── SessionRecorder.kt              세션 1개: 화면 방문마다 사진 묶음, manifest·세션 로그
│   ├── GroupSaver.kt                   화면 상태 1개의 프레임 → 이어붙인 이미지 저장
│   ├── SessionRecovery.kt              비정상 종료 세션 정리
│   └── SessionThreads.kt               저장·이어붙이기 스레드
├── stitch/                         순수 로직 (JVM 테스트) — 이어붙이기
│   ├── Stitcher.kt                     겹침 계산, 고정 영역(헤더·탭바), 합성 계획
│   ├── PartRenderer.kt                 합성 계획 → PNG
│   └── PngWriter.kt                    행 단위 스트리밍 PNG 인코더
├── storage/                        파일 — 어디에 어떻게 쓰는지
│   ├── StoragePaths.kt                 저장 위치·파일 이름 규칙
│   ├── EvidenceStore.kt                결과물 파일(MediaStore): 저장·목록·읽기·zip
│   ├── LegacyEvidenceStore.kt          API 26~28 결과물 파일: 직접 쓰고 MediaStore 에 경로로 등록
│   ├── SessionStore.kt                 session.json·manifest.json 읽기·쓰기
│   ├── SessionData.kt                  기록 모델(세션·화면·이미지·토스트)
│   ├── FrameStore.kt                   임시 프레임 폴더
│   ├── FrameReader.kt                  임시 프레임 PNG → 픽셀
│   ├── TextLog.kt                      세션 로그·디버그 로그 파일
│   ├── Options.kt                      설정값(마스터 스위치, 타이밍, 디버그 로그)
│   └── Times.kt                        시각 형식
├── overlay/
│   └── FloatingButton.kt               플로팅 버튼(TYPE_ACCESSIBILITY_OVERLAY)
└── ui/                             화면
    ├── common/Ui.kt                    공통 뷰 도우미(Material 3 색·글자·카드·버튼)
    ├── main/                           메인 화면의 카드 4개
    │   ├── StatusCard.kt                   상태 + 마스터 스위치
    │   ├── SetupGuideCard.kt               설정 안내 4단계, 접근성 목록 미등록 경고
    │   ├── SessionListCard.kt              저장된 세션 목록
    │   └── PrivacyCard.kt                  개인정보 및 권한
    ├── session/SessionActivity.kt      세션 열기: 이미지 목록, zip 공유, 세션 로그
    ├── viewer/                         사진 보기
    │   ├── ImageViewerActivity.kt          세션 사진을 옆으로 넘기며 보기(긴 사진은 세로 스크롤), 갤러리로 열기
    │   └── PagerView.kt                    옆으로 넘기는 페이지 뷰(HorizontalScrollView + 장 단위 맞춤)
    └── settings/SettingsActivity.kt    타이밍·디버그 로그 설정

app/src/test/java/com/qa/autocapture/   main 과 같은 경로
└── stitch/                         StitcherTest, PngWriterTest

app/src/main/res/                   mipmap-anydpi(앱 아이콘), drawable(아이콘), values·values-night(색·테마·문구), xml(접근성 서비스 설정)
.github/workflows/release.yml       테스트 → release APK → 네트워크 권한 확인 → GitHub Release "latest"
```

## 2. 파일이 이어지는 순서

한 장이 저장되기까지 어떤 파일을 거치는지(메인 스레드 → 저장 스레드 → 이어붙이기 스레드).

```
CaptureService.onAccessibilityEvent
  → ScreenTracker            어느 화면의 이벤트인가, 무시할 것인가
  → CaptureController        찍을 때인가 (화면 진입 0.5초 뒤 / 스크롤 멈춤 0.3초 뒤 / 길게 스크롤 중 화면 절반마다)
      → ScreenshotTaker      찍기(버튼 숨김) → Bitmap
  → SessionRecorder.addFrame → FrameStore (그 화면 방문의 임시 PNG)
  → 화면을 떠나면 GroupSaver → Stitcher(계획) → PartRenderer·PngWriter → EvidenceStore (Documents/QAEvidence)
                          SessionStore (manifest.json) · TextLog (session_log.txt)
메인 화면: MainActivity → StatusCard(CaptureStatus) · SetupGuideCard · SessionListCard(SessionStore) · PrivacyCard
```

| 계층 | 파일 | Android 의존 |
|---|---|---|
| 판단(순수) | `stitch/Stitcher`, `stitch/PngWriter`, `stitch/PartRenderer` | 없음 → JVM 테스트 |
| 서비스 | `capture/*` | AccessibilityService, takeScreenshot (API 26~29 는 MediaProjection) |
| 기록 | `session/*` | Bitmap, 스레드 |
| 저장 | `storage/*` | MediaStore, 파일, SharedPreferences |
| 화면 | `ui/*`, `overlay/*` | View |

## 3. 이벤트 흐름

### 3-1. 화면 식별

```
이벤트 수신(마스터 스위치가 꺼져 있으면 아예 받지 않음, 3-5)
  └─ packageName ∈ 무시 목록 → 버림
       무시 목록 = 이 앱, 홈 런처(HOME 인텐트로 조회), com.android.systemui,
                  활성 IME 패키지(InputMethodManager), 삼성 엣지패널·스마트캡처
       이 중 이 앱·런처의 WINDOW_STATE_CHANGED = "화면을 떠남" → 기다리던 촬영을 모두 버림
  └─ WINDOW_STATE_CHANGED(pkg, cls, windowId)
       cls 가 android.widget.Toast… 이면 화면 전환이 아니라 내용 변경으로만 처리
       key = windowScreens[windowId] 가 있으면 그것, 없으면 (pkg, cls) 로 새로 등록
       key ≠ 현재 화면 → 화면 진입. 같으면(창 제목·패널 변경) 터치처럼 처리
  └─ VIEW_SCROLLED(windowId)
       key = windowScreens[windowId] (모르는 창이면 현재 화면 패키지와 같을 때만 현재 화면)
화면 식별자 = packageName + className. 세션 중이 아니어도 마지막 화면은 기억해 둔다(세션 시작 직후 사용).
```

### 3-2. 진입 → 스크롤 → 이탈 (1차 방식)

```
[세션 시작] 플로팅 버튼 탭 (마스터 스위치 켜짐일 때만 버튼이 보임)
   SessionRecorder 생성(세션ID = yyyyMMdd_HHmmss), 세션 로그 열기, 내부 session.json 기록
   앞에 있는 창이 아는 화면이면 그 화면으로 진입

[창 전환] WINDOW_STATE_CHANGED (3-1 로 화면 식별)
   다른 화면이면: 지금 방문을 닫고(사진을 이어붙여 저장 예약) 새 방문을 연다, 기다리던 촬영은 버림
   창 이벤트가 ENTRY_SETTLE_MS(0.5초) 잠잠해지면 진입 사진 1장 (같은 화면의 창 변경도 1장 더)
     · 토스트가 떠 있으면(뜬 뒤 4초) 사라질 때까지, 로딩 표시(진행률 없는 ProgressBar)가 보이면 0.3초마다 다시 보며 미룸
     · 진입 후 ENTRY_MAX_WAIT_MS(5초)가 지나면 그대로 찍음
     · 찍는 순간 토스트가 떠 있었으면 그 사진은 버리고 토스트가 사라진 뒤 다시

[세로 스크롤] VIEW_SCROLLED (가로로만 스크롤, 화면 높이 25% 미만 영역은 무시. scrollDeltaY = -1 이면 이동량 모름)
   다른 창이면 그 화면으로 전환(다이얼로그가 닫힌 뒤 원래 화면 스크롤)
   시작: 플로팅 버튼 숨김(스크롤이 끝날 때까지)
   이벤트마다: 이동량 누적. 화면 높이 × 0.5 를 넘으면(모르면 0.45초마다) 1장 → 프레임끼리 겹침
   0.3초 동안 스크롤 이벤트가 없으면 멈춤 → 1장 → 버튼 다시 표시

[토스트] NOTIFICATION_STATE_CHANGED(Toast) → 문구·시각·그때 화면을 manifest toasts 와 세션 로그에 기록, 4초 동안 진입 사진 미룸

[촬영 큐] (메인 스레드, 1장씩, 대기 요청은 1건으로 합침)
   처리 대기 ≥ 3장이면 100ms 씩 미룸
   촬영 시각 = max(지금, 마지막촬영 + 350ms − (버튼을 숨겨야 하면 80ms)), 버튼을 숨기고 80ms 뒤 takeScreenshot
   → 버튼 복원 → 별도 스레드: HardwareBuffer → ARGB_8888 복사 → 메인 스레드로 결과
   화면이 바뀐 뒤 도착한 결과는 버림. INTERVAL_TIME_SHORT 이면 다시 요청

[사진 저장] (저장 스레드)
   그 방문의 첫 사진이면 화면 순번 부여, 임시 폴더 groups/{방문}_01/ + info.txt(순번·이름)
   사진 덧붙임 {번호}_{scrollDy|n}.png (직전 사진 이후 이동량 = 이어붙일 때 힌트)
   플로팅 버튼 숫자 = 사진을 찍은 화면 수, 찍을 때마다 잠깐 ✓

[화면 이탈] 다른 화면 / 세션 정지
   스티칭 스레드: 겹치는 사진끼리 이어붙이기(4장) → {base}.png, (나뉘면) {base}_2.png … → manifest·세션 로그 → 임시 폴더 삭제

[세션 정지] 플로팅 버튼 탭 / 일시정지 / 서비스 꺼짐
   기다리던 촬영 취소, 디버그 로그 닫기 → 현재 방문 닫기 → 이미지 저장이 끝난 뒤 endedAt, manifest 최종,
   세션 로그 "세션 종료"·닫기, "QA 기록 저장 완료" 토스트

[복구] 서비스 연결 시
   남은 임시 폴더(이전 버전 scroll/ 포함)를 이미지로 저장(`…_recovered.png`)하고 지운다(한 번만 시도).
   ended=false 인 세션을 닫는다(이탈·종료시각 = 마지막 이미지 시각).
```

같은 화면을 다시 방문하면 새 순번이다(A→B→A = 화면 순번 3개, 시간순). 사진을 하나도 못 찍고 지나간 화면은 저장하지 않는다.
홈 화면/알림창/키보드는 무시 대상이라 화면을 바꾸지 않는다(A→홈→A 는 같은 방문이 이어짐).

### 3-3. 상수 (`CaptureConfig`)

| 상수 | 기본값 | 의미 |
|---|---|---|
| `ENTRY_SETTLE_MS` | 500ms (설정 300~3000) | 창 이벤트가 이만큼 잠잠하면 진입 사진 |
| `ENTRY_MAX_WAIT_MS` | 5000ms | 창 이벤트·토스트·로딩이 계속돼도 진입 후 이만큼 지나면 진입 사진 |
| `SCROLL_DEBOUNCE_MS` | 300ms | 스크롤 이벤트가 이만큼 없으면 멈춤 → 1장 |
| `SCROLL_STEP_RATIO` | 0.5 | 길게 스크롤하면 이동량이 화면 높이 × 이 비율마다 1장 |
| `SCROLL_STEP_MS` | 450ms | 이동량을 모르는 스크롤은 이 간격마다 1장 |
| `SCROLL_MIN_HEIGHT_RATIO` | 0.25 | 이보다 낮은 영역의 스크롤은 무시 |
| `TOAST_VISIBLE_MS` | 4000ms | 토스트가 뜬 뒤 이만큼 진입 사진을 미룸 |
| `LOADING_RECHECK_MS` | 300ms | 로딩 표시가 있으면 이 간격으로 다시 확인 |
| `MIN_SHOT_INTERVAL_MS`, `HIDE_SETTLE_MS` | 350ms, 80ms | 촬영 최소 간격, 버튼을 숨긴 뒤 반영 대기 |

### 3-5. 마스터 스위치(일시정지/재개)

- 값: `Options.captureEnabled`(SharedPreferences, 기본 켜짐). 재부팅·앱 재시작·덮어쓰기 설치 후에도 유지된다.
- 바꾸는 곳: 메인 화면 상태 카드의 스위치, 알림창 빠른 설정 타일 "QA 캡처". 서비스가 SharedPreferences 변경 리스너로 바로 반영한다.
- 끔(일시정지): 기록 중이면 세션을 정지·저장 → 플로팅 버튼 제거 → 서비스 이벤트 종류를 0 으로 바꿔 **이벤트를 아예 받지 않음**.
- 켬(재개): 무시 목록 갱신 → 플로팅 버튼 표시 → 이벤트 종류 복원. 기록은 다시 버튼으로 시작한다.

### 3-6. 디버그 로그

- 설정 화면 스위치(기본 끔). 세션을 시작할 때 적용된다.
- 켜면 서비스가 받는 이벤트 종류를 `TYPES_ALL_MASK` 로 바꾸고, 이 앱 것을 뺀 모든 이벤트를 한 줄씩 기록한다. 정지하면 원래대로 돌린다.
- 형식(탭 구분): `시각(HH:mm:ss.SSS)  이벤트타입  패키지  클래스  텍스트  기타(win=창ID, change=contentChangeTypes, dx/dy)`
- 서비스 내부 판단도 남긴다: `#SHOT`(entry/scroll_step/scroll_stop), `#FRAME`(저장할 사진, 이동량), `#SCROLL`(start/step/stop), `#WAIT`(toast/loading), `#DROP`(토스트가 찍힘), `#SHOT_FAIL`
- 저장: `Documents/QAEvidence/debug/{세션}.txt`. 세션 zip 에 `debug_log.txt` 로 들어간다.

## 4. 스티칭 알고리즘 (화면 상태 1개 → 이미지 1장)

화면 상태가 끝날 때(새 상태·화면 전환·세션 정지) 그 상태의 임시 프레임(확정 화면 + 스크롤이 멈출 때마다 1장)을 순서대로 입력으로 쓴다.
프레임이 1장이면 임시 PNG 를 그대로 복사한다.
프레임마다 직전 프레임부터의 `scrollDeltaY` 합(모르면 없음)이 함께 온다. 모든 판단은 **행 해시(LongArray)** 로 하고, 픽셀은 마지막 렌더링 때만 다시 읽는다.

### 4-1. 행 해시

```
rowHash(frame, y):
  x 범위 = [0, width - width*3%)        # 오른쪽 3%(스크롤바 자리)는 제외
  x 를 2px 간격으로 샘플링하며 64bit FNV-1a 해시
```

### 4-2. 연속 프레임 비교 `match(A, B)` — A는 직전에 채택된 프레임

```
if A의 모든 행 해시 == B의 모든 행 해시:  return SAME          # 동일 프레임(오프셋 0) → 버림

# scrollDeltaY 힌트 dy0 (A 이후 버린 프레임의 이동량까지 더한 값)가 있고 0 < |dy0| < 높이 이면
#   겹치는 구간에서 양쪽 모두 정적이 아닌 행 중 B[y]==A[y+dy0] 비율 ≥ 80% (비교 행 ≥ 10) → return OFFSET(dy0)
#   (반복 무늬처럼 행 비교로는 위치를 못 찾는 화면도 이어진다. 안 맞으면 아래 행 비교로)

# 오프셋 투표: B에서 '정적이 아니고(A와 같은 위치 행과 다름) 프레임 내 유일한' 행이
#             A에서 유일하게 나타나는 위치와의 차이에 투표
uniqA = {hash → y | A에서 1번만 나오는 해시}
for y in B:
  if B[y] == A[y] or countB[B[y]] != 1: continue
  ya = uniqA[B[y]] ?: continue
  votes[ya - y] += 1                      # dy>0 : 아래로 스크롤(내용이 위로 이동)

dy = argmax(votes)
if votes[dy] >= 10 and votes[dy] >= 2 * (2등 표):
   # 검증: 겹치는 구간에서 정적이 아닌 행 중 B[y]==A[y+dy] 비율 ≥ 50%
   if 검증 통과: return OFFSET(dy)

if A와 B가 다른 행 수 ≤ 높이×15%:  return SAME                  # 시계·커서 등 소량 변화 → 버림
return NO_MATCH                                                  # 오프셋 못 찾음 → 분리
```

정적인 행(헤더·탭바·상태바)은 투표하지 않으므로 고정 영역이 오프셋 계산을 방해하지 않는다.
빈 줄·구분선처럼 반복되는 행도 투표하지 않는다.

### 4-3. 프레임 묶기(run)와 장 나누기(part)

```
runs = [[(f0, pos=0)]]
last = f0
for f in frames[1..]:
  m = 크기 다름(회전 등) ? NO_MATCH : match(last, f)
  SAME:      버림 (continue)
  NO_MATCH:
     다음 프레임 f' 이 last 와 이어지면(OFFSET) → f 만 버림(오버스크롤로 늘어난 순간 등), continue
     아니면 새 run 시작(f, pos=0). 크기가 달랐으면 "새 장" 표시
  OFFSET(dy):
     pos = pos(last) + dy
     run 높이(max(pos)+H - min(pos)) > MAX_STITCH_HEIGHT(20000) 이면 새 run 시작(f, pos=0, 새 장)
     아니면 현재 run 에 (f, pos) 추가
  last = f

parts: run 하나 = 결과 이미지 1장. 겹치는 프레임끼리만 합치므로 run 이 나뉜 곳은 다음 번호 파일(…_2.png)이 된다.
```

### 4-4. run 하나를 1장으로 합성 `compose(run)`

```
프레임 1장뿐이면: 그 프레임 전체 → 결과 single

# (1) 고정 영역 판별 fixedArea(): run 안의 연속 프레임 쌍마다 위/아래에서부터 같은 위치 행이 동일한 길이
T = max(상태바높이, min_pairs(상단 동일 행 수))
B = max(내비바높이, min_pairs(하단 동일 행 수))
(T + B ≥ H 이면 T=상태바높이, B=내비바높이)

# (2) 행 분류 (각 프레임 k, 각 행 y) — 이웃 프레임(k-1, k+1) 기준
CONTENT : 스크롤 위치를 맞춘 이웃 행과 해시가 같음 (정상 콘텐츠)
FIXED   : 위가 아니면서 이웃의 '같은 화면 위치' 행과 같음 (헤더·탭바처럼 안 움직임)
UNKNOWN : 둘 다 아님 (새로 나타난 행, 또는 FAB·팝업처럼 떠 있는 요소)

# (3) 긴 스트립 좌표 s = pos_k + y 로 행마다 출처 프레임 선택
top    = 가장 위 프레임(min pos),  bottom = 가장 아래 프레임(max pos)
for s in [min(pos)+T, max(pos)+H-B):
   후보 = s 를 화면 안에 담는 프레임들
   우선순위: (y 가 [T, H-B) 안) > 분류(CONTENT < UNKNOWN < FIXED) > 먼저 찍힌 프레임
   → 선택 (같은 프레임의 연속 행은 하나의 Slice 로 합침)

# (4) 결과 = top 프레임 [0,T)  +  스트립  +  bottom 프레임 [H-B, H)
#     아래로만 스크롤하면 top=첫 프레임, bottom=마지막 프레임
#     → "첫 프레임 상단 고정영역 + 누적 스크롤 영역 + 마지막 프레임 하단 고정영역"
```

- 아래로 스크롤 → 새로 보인 행만 뒤에 붙는다. 위로 스크롤 → 이미 있는 위치라 아무것도 안 붙는다.
  처음부터 중간 위치에서 진입해 위로 스크롤하면 위쪽에 붙는다.
- 헤더·탭바는 스트립 중간 후보에서 FIXED 라 밀려나고, 맨 위/맨 아래에서 한 번씩만 나온다.
- 접히는 툴바, 스크롤 시 숨는 하단바, FAB 도 (2)~(3) 에서 다른 프레임의 깨끗한 행이 우선 선택된다.

### 4-5. 렌더링

```
plan.parts[i] = Slice(frame, top, bottom) 목록
PngWriter(out, width, height):  IHDR(RGB 8bit) + sRGB + IDAT(Deflater 스트리밍) + IEND
for slice in part: frame 픽셀 로드(최근 2장 캐시) → 행마다 필터(Sub/Up 중 작은 쪽) → 압축 기록
```

저장: part 마다 화면 방문의 다음 번호로 저장한다(`{base}.png`, `{base}_2.png` …). part 가 프레임 1장 전체면 임시 PNG 를 그대로 복사한다.
manifest 의 `frames` = 그 이미지에 쓰인 프레임 수(2 이상이면 스크롤을 이어붙인 이미지).



## 5. 저장

- 폴더: `Documents/QAEvidence/{yyyyMMdd_HHmmss}/` (0-1)
- 이미지: 화면 방문마다 `{화면순번3자리}_{패키지 끝부분}_{화면클래스 짧은이름}_{진입 HHmmss}.png`, 같은 방문의 다음 상태는 `_2`, `_3` …
  - 예: `003_settings_SubSettings_103512.png`(스크롤한 부분까지 한 장), `003_settings_SubSettings_103512_2.png`(오류 문구가 뜬 상태)
  - 이어붙이지 못한 지점(겹침 없음)과 20,000px 를 넘는 지점에서도 다음 번호로 나뉜다
  - 클래스 짧은이름 = 마지막 `.` 뒤, 영문/숫자 외 문자는 `_`, 최대 40자. 이름순 정렬 = 시간순
- 세션 로그: `session_log.txt` (탭 구분, `시각  종류  내용`)

```
# QA 증적 캡처 세션 로그 / 세션 20261015_103000
# 시각	종류	내용
10:30:00.120	세션 시작
10:30:00.310	화면	com.android.settings / com.android.settings.Settings
10:30:21.015	토스트	[com.android.settings] 저장되었습니다
10:30:40.880	저장	001_settings_Settings_103000.png (화면 6장 이어붙임)
10:42:10.004	세션 종료	플로팅 버튼으로 정지 · 이미지 18장
```

- 디버그 로그: `Documents/QAEvidence/debug/{세션}.txt` (3-6)
- `manifest.json` (이미지 저장 때마다 덮어씀). 시각은 밀리초까지.

```json
{
  "session": "20261015_103000",
  "startedAt": "2026-10-15T10:30:00.120+09:00",
  "endedAt": "2026-10-15T10:42:10.004+09:00",
  "device": { "manufacturer": "samsung", "model": "SM-G991N", "android": "15", "sdk": 35 },
  "app": { "versionName": "1.0.12", "versionCode": 12 },
  "screens": [
    {
      "seq": 1,
      "packageName": "com.android.settings",
      "className": "com.android.settings.Settings",
      "enteredAt": "2026-10-15T10:30:00.310+09:00",
      "exitedAt": "2026-10-15T10:30:40.512+09:00",
      "captures": [
        { "file": "001_settings_Settings_103000.png", "at": "2026-10-15T10:30:00.702+09:00", "frames": 6 }
      ]
    }
  ],
  "toasts": [
    {
      "at": "2026-10-15T10:30:21.015+09:00",
      "packageName": "com.android.settings",
      "text": "저장되었습니다",
      "screen": "com.android.settings.Settings"
    }
  ]
}
```

- `frames` = 그 이미지에 이어붙인 화면 수(1 = 한 화면).
- 내부 `noBackupFiles/sessions/{세션}/session.json`: 위 manifest + `ended` 플래그. 메인 화면 목록·복구에 사용.
  이전 버전 기록도 읽는다(`files` → captures, `final`·`scroll`·`full`).
- 임시 프레임: `noBackupFiles/sessions/{세션}/groups/{방문4자리}_{상태2자리}/` — `info.txt`(화면 순번, 파일 이름 앞부분),
  `{번호4자리}_{scrollDy 또는 n}.png`. 이미지로 저장하면 지운다.
- 공유: 세션 화면 [zip으로 공유] → `Documents/QAEvidence/{세션}.zip` 생성(기존 것은 교체) → `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`.

## 6. 화면 구성

### 6-1. 디자인 원칙 (신뢰감 있는 사내 QA 도구)

- Material 3 색 역할·글자 단계·모양을 따르되, 외부 라이브러리 금지 조건 때문에 Material Components 대신 프레임워크 뷰로 구현한다(`ui/common/Ui.kt`).
- 색: 남색 `primary`(#24457A, 다크 #AEC6FF) 1색 + 회색 톤. **상태 색만** 초록(동작 중)·회색(일시정지)·빨강(서비스 꺼짐).
- 다크모드: `values-night` 의 같은 이름 색 역할 + 어두운 기본 테마.
- 글자: 22sp 앱바 제목, 16sp 카드 제목/본문, 14sp 본문·버튼, 12sp 보조 설명. 여백: 화면 가장자리 16dp, 카드 안 16dp, 카드 사이 12dp.
- 앱 아이콘: adaptive icon(남색 배경 + 흰색 굵은 "QA" 글자. 폰트 대신 원·다각형으로 직접 그린 벡터), 같은 모양의 단색 테마 아이콘과 빠른 설정 타일 아이콘.

### 6-2. 화면

- **메인**(위에서부터)
  1. 상태 카드: 상태 점·제목(동작 중 / 동작 중 · 기록 중 / 일시정지 / 서비스 꺼짐) + 설명 + 마스터 스위치
  2. 설정 안내 카드: 맨 위 "앱을 업데이트하면 이 과정을 다시 해야 합니다". 단계마다 번호/체크, 구체적인 메뉴 경로, 바로 가는 버튼.
     ① **접근성에서 앱 한 번 누르기(가장 먼저)**: [접근성 설정 열기] → 설치된 앱 → 앱 → "제한된 설정" 안내 창 → 확인.
        Android 13+ 에서는 이걸 거쳐야 앱 정보 ⋮ 에 "제한된 설정 허용" 메뉴가 생긴다.
     ② **제한된 설정 허용**: [앱 정보 열기](또는 아이콘 길게 누르기 → ⓘ, 설정 → 애플리케이션 → 앱) → 오른쪽 위 ⋮ → 제한된 설정 허용 → PIN
     ③ **접근성 서비스 켜기**: [접근성 설정 열기] → 설치된 앱 → 앱 → 스위치 켜기 → 허용
     ④ **배터리 "제한 없음", "사용하지 않는 앱 관리" 끄기**: [앱 정보 열기]
     ①~③은 접근성 서비스가 켜지면 자동 완료(제한된 설정을 직접 확인하는 공개 API 가 없음), ④는 `PowerManager.isIgnoringBatteryOptimizations`.
     미완료(서비스 꺼짐 포함)면 펼친 상태, 모두 완료면 접힌 상태. 제목 줄을 눌러 직접 펼치고 접는다(완료 여부가 바뀔 때만 기본 상태로 돌아감).
  3. 저장된 세션: 날짜, 이미지 수·화면 수·기록 시간(또는 기록 중), [열기]
  4. 개인정보 및 권한: 읽는 정보, 저장 위치, 외부 전송 없음(네트워크 권한 없음), 사용자 권한은 접근성 1개
  - 앱바 오른쪽 설정 아이콘 → 설정 화면
- **설정**: 캡처 타이밍(SETTLE_MS, MAX_WAIT_MS 슬라이더 + 기본값으로 되돌리기, 바로 적용), 디버그 로그 스위치
- **세션**: 요약, [zip으로 공유], [세션 로그], 이미지 목록(썸네일·파일명·시각·"스크롤 이어붙임 · 사진 N장"). 누르면 사진 보기로 연다.
- **사진 보기**: 세션 사진을 파일 이름 순(= 경로 순)으로 옆으로 넘기며 본다. 손가락을 따라 움직이고 놓으면 가까운 장으로 맞춘다.
  긴 사진은 세로로 스크롤한다. 지금 장과 양옆 장만 읽어 두고(메모리), 긴 사진은 2048px 높이 조각으로 읽고 24MB 를 넘으면 절반 크기로 읽는다.
  [갤러리로 열기] = 지금 사진을 기본 갤러리 앱으로(확대용). 외부 라이브러리 금지라 ViewPager 대신 HorizontalScrollView 로 만들었다.
- **플로팅 버튼**: 대기 `● 시작`(남색), 기록 중 `■ {확보 이미지 수}`(초록). 탭=시작/정지, 길게=메인 화면, 드래그=이동. 일시정지 중에는 숨김.
- **빠른 설정 타일** "QA 캡처": 누르면 일시정지/재개.

## 7. 권한과 개인정보

- 사용자가 켜는 권한: **접근성 서비스 1개**뿐(API 30 이상). API 26~29 는 기록 시작마다 화면 녹화 동의, 26~28 은 저장공간 권한도(0-5).
- API 30 이상은 Manifest 권한 선언 없음(`FOREGROUND_SERVICE` maxSdkVersion 29, `WRITE_EXTERNAL_STORAGE` maxSdkVersion 28 만 선언). 네트워크 권한(INTERNET, ACCESS_NETWORK_STATE)은 `tools:node="remove"` 로 명시 제거하고, CI 에서 APK 권한에 네트워크 권한이 있으면 배포를 멈춘다.
- `<queries>` 로 HOME 인텐트와 IME 인텐트만 조회(권한 아님). 타일 서비스의 `BIND_QUICK_SETTINGS_TILE` 은 시스템이 바인딩할 때 쓰는 보호 권한이다.
- 쓰지 않음: 포그라운드 서비스, 알림, `SYSTEM_ALERT_WINDOW`, 저장소 권한, `QUERY_ALL_PACKAGES`, 네트워크(포그라운드 서비스·알림·저장소 권한은 API 26~29 만 예외).
- 접근성으로 읽는 것: 화면 전환·스크롤 이벤트(패키지, 클래스, 창 ID, 시각), 토스트 문구, 진입 사진 직전 로딩 표시가 있는지, 기록 중 화면 캡처.
  화면 글자 내용은 디버그 로그를 켠 경우에만 기록한다.
  같은 내용을 메인 화면 "개인정보 및 권한"과 접근성 서비스 설명(`service_description`)에 적었다.
- 서비스 설정: `canTakeScreenshot=true`, `canRetrieveWindowContent=true`(창 ID, 기록 시작 시 앞 창 확인, 로딩 표시 확인),
  이벤트 `typeWindowStateChanged|typeViewScrolled|typeNotificationStateChanged`
  (일시정지 중 0, 디버그 로그 중 전체), `notificationTimeout=0`.
- 덮어쓰기 설치 시 권한 유지 조건: 같은 서명 + 서비스 클래스 이름(`com.qa.autocapture.capture.CaptureService`) 고정.
  단, 브라우저로 받은 APK 는 업데이트 후 "제한된 설정"이 다시 걸릴 수 있어 설정 안내를 다시 해야 할 수 있다.

## 8. 빌드/배포

- `app/build.gradle.kts`: `versionCode = GITHUB_RUN_NUMBER`(없으면 1), `versionName = "1.0.{run}"`.
  서명은 환경변수 `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` 가 있을 때만 적용(로컬은 미서명 빌드 가능).
- `.github/workflows/release.yml` (main push, 수동 실행):
  1. JDK 21, Gradle 캐시
  2. Secrets 4개 확인(없으면 한국어 오류로 실패)
  3. `./gradlew testDebugUnitTest`
  4. `KEYSTORE_BASE64` → `$RUNNER_TEMP/release.jks` 복원 → `./gradlew assembleRelease` → `qa-capture.apk`
  5. `aapt2 dump permissions` 로 네트워크 권한 없음 확인
  6. `latest` 태그를 현재 커밋으로 이동, 릴리스가 없으면 prerelease 로 생성, `gh release upload --clobber` 로 덮어쓰기
- 다운로드 고정 주소: `https://github.com/<owner>/<repo>/releases/download/latest/qa-capture.apk`
- 키 파일(`*.jks`, `*.b64`, `*.keystore`, `*.p12`)은 `.gitignore` 로 커밋 제외.

## 9. 테스트 계획 (JVM 단위 테스트)

찍는 때 판단(`CaptureController`)은 타이머 몇 개뿐이라 단위테스트 대신 실기기 체크리스트(README)로 확인한다.

`StitcherTest`, `PngWriterTest` — 합성 원본(고정 헤더 + 긴 콘텐츠 + 고정 푸터)을 겹치게 잘라 만든 프레임으로
`plan → render → PNG → ImageIO 디코드` 결과가 기대 이미지와 **픽셀 단위로 동일**한지 본다(아래로 끝까지, 중복 프레임, 왕복,
중간 진입 후 위로, 시계 변화, 겹침 없는 점프 → 다음 장으로 분리, 어긋난 프레임 1장 버림, 1장, 회전, 접히는 툴바, 높이 제한 20000px, scrollDeltaY 힌트 맞음/틀림,
반복 무늬를 힌트로 이어붙이기, 버린 프레임이 끼어도 힌트 누적, 힌트가 있어도 겹침 없으면 분리, 고정 영역 판별, 헤더·탭바 한 번씩, PNG 왕복).

## 10. 알려진 한계

- API 26~29: 기록할 때마다 화면 녹화 동의 창이 뜨고, 녹화하는 동안 상태바에 시스템의 화면 녹화(전송) 아이콘이 보여 사진 상단에 찍힐 수 있다.
  API 26 은 내비게이션바 밝은 아이콘(API 27)이 없어 밝은 테마에서 내비게이션바 버튼이 잘 안 보일 수 있다.
- `FLAG_SECURE` 화면, 동영상/애니메이션 최적화는 범위 밖이다. 기본 디스플레이만 캡처한다(DeX/멀티 디스플레이 제외).
- **찍는 때는 화면 진입과 스크롤뿐이다**(1차 방식): 같은 화면 안에서 누른 뒤 바뀐 결과(오류 문구, 펼친 내용)는 창이 바뀌거나
  스크롤할 때 함께 찍힌다. 창이 바뀌지 않고 스크롤도 하지 않으면 남지 않는다.
- 0.5초도 머물지 않고 지나간 화면은 남지 않는다.
- 진입 사진: 0.5초 안에 다 뜨지 않는 화면은 덜 뜬 모습이 찍힐 수 있다. 돌아가는 `ProgressBar` 는 기다리지만, 자체 로딩 표시·빈 화면·스켈레톤은
  알아보지 못한다(느린 앱이면 설정에서 진입 후 대기를 늘린다). 로딩·토스트가 5초 넘게 계속되면 그 화면을 찍는다.
- 토스트: 시스템 토스트만 알아본다. 앱이 직접 그린 토스트 모양·스낵바(Snackbar)는 거르지 못한다.
  스크롤 사진은 토스트를 거르지 않는다(이어붙일 때 다른 사진의 같은 행이 우선 쓰여 대부분 가려진다).
- 스크롤: takeScreenshot 은 0.33초에 1장이 한계라 아주 빠르게 튕기면 프레임끼리 겹치지 않는다.
  겹치지 않는 부분은 합치지 않으므로 다음 번호 파일(`_2`)로 나뉘고 그 사이 내용은 남지 않는다.
  오버스크롤로 늘어난 프레임은 버리고, 스크롤하며 접히는 툴바는 행 비교로 넘어가고 그래도 못 찾으면 나뉜다.
  화면에 들어오자마자(0.5초 안) 스크롤하면 맨 위가 빠지고 첫 사진 위치부터 이어진다.
- 플로팅 버튼은 찍을 때마다 약 0.1초, 스크롤하는 동안은 계속 사라진다.
- **단일 Activity 앱**(Fragment/Compose 내비게이션)은 화면이 바뀌어도 className 이 같아 화면 순번이 늘지 않는다.
  창 이벤트가 오면 같은 방문에 사진이 더해지고, 내용이 겹치지 않으면 같은 순번의 `_2`, `_3` 으로 저장된다.
- 화면을 떠난 뒤 이미 촬영 중이던 1장(수십 ms)은 홈 화면이 찍힐 수 있다.
- 제한된 설정 허용 여부는 공개 API 로 확인할 수 없어, 접근성 서비스가 켜졌는지로 대신 판단한다.
- 앱을 **삭제 후 재설치**하면 이전 파일의 MediaStore 소유권이 사라져 목록/공유에서 빠진다(파일은 남음).
- 배터리 최적화로 접근성 서비스가 강제 종료될 수 있으므로 "제한 없음" 설정이 필요하다(README).
