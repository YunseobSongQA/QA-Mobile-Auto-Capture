# QA 증적 자동 캡처 — 설계 문서

안드로이드 수동 QA 중 **화면이 바뀔 때마다 자동으로 캡처**하고, 화면이 멈추면 **최종 화면(`_final`)** 을 1장 더 남긴다.
스크롤한 화면은 저장한 캡처를 이어붙여 **위에서 아래까지 긴 이미지(`_full`) 1장**도 만든다.
테스터는 평소처럼 앱을 쓰고 스크롤만 한다.

- 대상 기기: Galaxy S21 / Android 15 (One UI 7) / 1080x2400
- Kotlin, minSdk 30, targetSdk 35 (compileSdk 35)
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
PNG도 이미지로 색인되므로 갤러리 앱에 앨범으로 보인다. 경로는 `storage/EvidenceStore.kt` 의 상수 하나로 관리한다.

### 0-2. 캡처 방식 (2차 변경: 판단 규칙 없이 단순화)

1차 MVP(진입 대기·스크롤 디바운스 후 캡처)와 2차(화면 안정화 대기: 로딩 표시(ProgressBar) 대기·토스트 보류·토스트 순간 별도 저장)의 판단 규칙을 모두 없애고 아래 규칙만 둔다.

| 규칙 | 내용 |
|---|---|
| 변화마다 캡처 | 창 전환·스크롤·내용 변경·토스트를 감지할 때마다 캡처. 로딩·토스트 화면도 제외하지 않는다. 변화가 이어지면 takeScreenshot 최소 간격(350ms)마다 계속 찍는다 |
| 최종 캡처 | 변화가 `SETTLE_MS`(기본 1500ms) 동안 없으면 1장 더(`_final`). 변화가 계속돼도 `MAX_WAIT_MS`(기본 8000ms)가 지나면 그 시점에 `_final` |
| 중복 생략 | 직전 저장 이미지와 축소 이미지(칸 평균색)를 비교해 거의 같으면 저장하지 않는다 |
| 토스트 기록 | 문구를 시각과 함께 세션 로그(`session_log.txt`)와 manifest 의 `toasts` 에 남긴다 |
| 상수 | `capture/CapturePolicy.kt` 의 `CaptureConfig` 한 곳. SETTLE/MAX_WAIT 은 설정 화면에서 바꿀 수 있다 |

추가로 정한 것:

| 항목 | 내용 | 이유 |
|---|---|---|
| 최종 캡처의 중복 처리 | 최종 화면이 같은 화면의 직전 저장 이미지와 **완전히 같으면**(바뀐 칸 0) 새로 저장하지 않고 그 파일 이름에 `_final` 을 붙인다. 조금이라도 다르면 새로 저장 | "거의 같음" 기준(1%)으로 이름만 바꾸면 체크박스 같은 작은 변화가 빠진 이미지가 최종으로 표시될 수 있다 |
| 긴 이미지(`_full`) | 세로 스크롤이 있었던 화면을 떠날 때 그 방문의 캡처를 스티칭. 2장 이상 이어붙은 부분만 저장 | 1차 목표(전체 길이 1장) 유지. 변화마다 찍으므로 스크롤 중 겹침이 충분하다 |
| 비교 범위 | 상태바·내비게이션바 높이는 비교에서 뺀다 | 시계·알림 아이콘 변화로 저장되지 않게 |
| 저장 밀림 보호 | 저장 대기 캡처가 3장 이상이면 다음 캡처를 100ms 씩 미룬다(버리지 않음) | 스크린샷 버퍼(장당 약 10MB)가 쌓이지 않게 |
| 화면을 떠남 | 홈(런처)·이 앱이 앞으로 오면 대기 중인 캡처·최종 판단을 버린다 | 홈 화면이 증적에 찍히지 않게 |
| 마스터 스위치 | 접근성 서비스는 켜 둔 채 캡처만 일시정지/재개. 메인 화면 상단 + 빠른 설정 타일 | 서비스를 끄고 켤 때마다 접근성 설정에 들어가지 않게 |
| 네트워크 권한 제거 고정 | Manifest 에 `tools:node="remove"` 로 INTERNET·ACCESS_NETWORK_STATE 제거, CI 에서 APK 권한 확인 | 외부 전송 없음 보장 |

### 0-3. 1차부터 유지한 보완 (승인됨)

| 항목 | 내용 |
|---|---|
| 창 ID 매핑 | `windowId → 화면 식별자`. 같은 창에서 온 다른 className(Drawer, DecorView 등)은 같은 화면. 스크롤 이벤트의 창이 현재 화면과 다르면 그 화면으로 전환(다이얼로그가 닫힌 뒤 복귀 인식). `canRetrieveWindowContent=true` 필요 |
| 최대 높이 | 긴 이미지 1장이 **30,000px** 를 넘으면 프레임 경계에서 `_full2` 로 분리 |
| PNG 직접 인코딩 | 긴 이미지는 순수 Kotlin 스트리밍 인코더로 행 단위 기록(메모리는 프레임 1~2장분) |
| 공유 | zip을 MediaStore에 저장하고 그 URI로 공유(FileProvider 없이) |

---

## 1. 폴더 구조

```
.
├── DESIGN.md
├── README.md
├── .gitignore                      # *.jks, *.b64, *.keystore, local.properties, build/ 등
├── .github/workflows/release.yml   # 테스트 → release APK → 네트워크 권한 확인 → GitHub Release "latest"
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradlew, gradlew.bat, gradle/wrapper/*
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/qa/autocapture/
        │   │   ├── MainActivity.kt
        │   │   ├── capture/CaptureService.kt
        │   │   ├── capture/CapturePolicy.kt
        │   │   ├── capture/CaptureTileService.kt
        │   │   ├── session/SessionRecorder.kt
        │   │   ├── stitch/Stitcher.kt
        │   │   ├── stitch/PngWriter.kt
        │   │   ├── storage/EvidenceStore.kt
        │   │   ├── overlay/FloatingButton.kt
        │   │   ├── ui/Ui.kt
        │   │   ├── ui/SettingsActivity.kt
        │   │   └── ui/SessionActivity.kt
        │   └── res/
        │       ├── mipmap-anydpi/ic_launcher.xml   # adaptive icon (배경·전경·단색)
        │       ├── drawable/                       # 아이콘 전경·단색·타일, UI 아이콘(벡터)
        │       ├── values/, values-night/          # 색 역할(colors), 테마(themes) — 다크모드
        │       ├── values/strings.xml
        │       └── xml/accessibility_service_config.xml
        └── test/java/com/qa/autocapture/
            ├── capture/CapturePolicyTest.kt
            ├── stitch/StitcherTest.kt
            └── stitch/PngWriterTest.kt
```

## 2. 파일 목록과 역할

| 파일 | 계층 | 역할 |
|---|---|---|
| `capture/CaptureService.kt` | Android | 접근성 서비스. 이벤트 수신·무시 대상 필터·화면 식별, 변화마다 캡처 요청, 최종 캡처 타이머 구동, 350ms 간격 캡처 큐, 캡처 전후 오버레이 숨김/복원, 세션 시작/정지, 마스터 스위치 반영, 디버그 로그, 화면·타일용 상태 |
| `capture/CapturePolicy.kt` | **순수 Kotlin** | `CaptureConfig`(상수 전부), `FinalTimer`(최종 캡처 시점), `FrameDiff`(축소 이미지·비교) |
| `capture/CaptureTileService.kt` | Android | 빠른 설정 타일. 마스터 스위치 토글, 상태 표시(동작 중/기록 중/일시정지/서비스 꺼짐) |
| `session/SessionRecorder.kt` | Android | 세션 1개. 캡처 저장(중복 생략, `_final` 이름 변경), 화면 순번, manifest·세션 로그 갱신, 스크롤한 화면의 긴 이미지 생성, 비정상 종료 정리 |
| `stitch/Stitcher.kt` | **순수 Kotlin** | 행 해시, 오프셋 투표, 중복 제거, 분리 판단, 고정 영역 검출, 합성 계획(Plan) |
| `stitch/PngWriter.kt` | **순수 Kotlin** | 스트리밍 PNG 인코더 + Plan 렌더링 |
| `storage/EvidenceStore.kt` | Android | MediaStore 저장·이름 변경·조회(PNG, manifest.json, zip), 세션 기록(session.json), `Options`(설정값), `TextLog`(세션 로그·디버그 로그) |
| `overlay/FloatingButton.kt` | Android UI | `TYPE_ACCESSIBILITY_OVERLAY` 플로팅 버튼. 탭=시작/정지, 길게=앱 열기, 드래그 이동, 저장 이미지 수, 캡처용 숨김/복원 |
| `MainActivity.kt` | Android UI | 상태 카드 + 마스터 스위치, 처음 설정 안내, 세션 목록, 개인정보 및 권한 |
| `ui/Ui.kt` | Android UI | 공통 뷰 도우미: Material 3 색 역할·글자 단계·카드·버튼·스위치·앱바·여백 |
| `ui/SettingsActivity.kt` | Android UI | SETTLE_MS, MAX_WAIT_MS 슬라이더, 디버그 로그 |
| `ui/SessionActivity.kt` | Android UI | 세션 열기: 요약, zip 공유, 세션 로그 보기, 이미지 목록(썸네일, 누르면 보기) |
| `CapturePolicyTest.kt` | JVM 테스트 | 최종 캡처 시점, 축소 이미지 비교 |
| `StitcherTest.kt`, `PngWriterTest.kt` | JVM 테스트 | 합성 이미지로 원본 복원, PNG 왕복 |

인터페이스·DI 없음. 계층 간 호출은 구체 클래스 직접 호출. 화면은 레이아웃 XML 없이 코드로 만든다.

## 3. 이벤트 흐름

### 3-1. 화면 식별

```
이벤트 수신(마스터 스위치가 꺼져 있으면 아예 받지 않음, 3-4)
  └─ packageName ∈ 무시 목록 → 버림
       무시 목록 = 이 앱, 홈 런처(HOME 인텐트로 조회), com.android.systemui,
                  활성 IME 패키지(InputMethodManager), 삼성 엣지패널·스마트캡처
       이 중 이 앱·런처의 WINDOW_STATE_CHANGED = "화면을 떠남" → 대기 중인 캡처·최종 판단 버림
  └─ WINDOW_STATE_CHANGED(pkg, cls, windowId)
       cls 가 android.widget.Toast… 이면 화면 전환이 아니라 화면 변화로만 처리
       key = windowScreens[windowId] 가 있으면 그것, 없으면 (pkg, cls) 로 새로 등록
  └─ VIEW_SCROLLED(windowId)
       key = windowScreens[windowId] (모르는 창이면 현재 화면 패키지와 같을 때만 현재 화면)
화면 식별자 = packageName + className. 세션 중이 아니어도 마지막 화면은 기억해 둔다(세션 시작 직후 사용).
```

### 3-2. 진입 → 변화 → 최종 → 이탈

```
[세션 시작] 플로팅 버튼 탭 (마스터 스위치 켜짐일 때만 버튼이 보임)
   SessionRecorder 생성(세션ID = yyyyMMdd_HHmmss), 세션 로그 열기, 내부 session.json 기록
   디버그 로그 켜짐 → Documents/QAEvidence/debug/{세션}.txt 열고 모든 이벤트 받기(3-5)
   앞에 있는 창이 아는 화면이면 그 화면으로 진입 + 화면 변화 1번

[진입] key ≠ 현재 화면
   이전 화면 방문 닫기(아래 [이탈]), 최종 타이머 초기화(최종 캡처는 화면마다 따로)
   세션 로그에 "화면 pkg / cls"

[화면 변화] 진입·스크롤·WINDOW_CONTENT_CHANGED(현재 화면 패키지)·토스트
   변화 캡처 요청 + FinalTimer.onChange(지금)
   세로 스크롤이면 이 방문에 "스크롤함" 표시(긴 이미지 대상)

[토스트] NOTIFICATION_STATE_CHANGED && className 에 "Toast" (이 앱 제외)
   문구·시각·그때 화면을 manifest toasts 와 세션 로그에 기록 → 화면 변화로 처리(캡처)

[최종 판단] FinalTimer (3-3). 대기 시각에 Handler 로 다시 판단(이벤트마다 타이머를 다시 걸지 않음)
   최종 → 최종 캡처 요청(판단한 화면 구간 기억)

[캡처 큐] (메인 스레드, 요청은 1장으로 합쳐짐)
   요청 있음 && 진행 중 아님 && 저장 대기 < 3장 →
     숨김 시각 = max(지금, 마지막촬영 + 350ms - 80ms) 에 오버레이 숨김 → 80ms 뒤 촬영
     촬영 시점에 요청을 소진(그 사이 들어온 변화도 이 1장에 담김)
     최종 요청이 있고 판단한 화면이 아직 현재 화면이면 최종, 아니면 변화 캡처
     takeScreenshot(DEFAULT_DISPLAY)
       성공: 오버레이 복원 → SessionRecorder.addCapture(버퍼, 최종 여부)
       실패: 오버레이 복원 → INTERVAL_TIME_SHORT 이면 재요청, 그 외는 이번 1장 포기
   저장 대기 ≥ 3장 → 100ms 뒤 다시 시도(버리지 않음)

[캡처 저장] (저장 스레드, 순서대로)
   HardwareBuffer → ARGB_8888 복사 → 버퍼 close
   축소 이미지(3-3) 를 만들어 직전 저장 이미지와 비교
     변화 캡처: 거의 같음(바뀐 칸 ≤ 1%) → 저장 생략
     최종 캡처: 같은 화면의 직전 저장 이미지와 같음(바뀐 칸 0)
                 → 직전 파일이 이미 _final 이면 생략, 아니면 이름에 _final 붙이기(실패하면 새로 저장)
   저장: 이 방문의 첫 저장이면 화면 순번 부여 → {순번}_{앱}_{화면}_{진입HHmmss}_{캡처번호}[_final].png
         MediaStore(IS_PENDING) 에 PNG → manifest·session.json 갱신 → 세션 로그 → 플로팅 버튼 숫자

[이탈] 다른 화면 진입 / 세션 정지 / 일시정지 / 서비스 종료
   저장 스레드에서 그 방문의 exitedAt 기록
   스크롤함 && 저장한 캡처 ≥ 2장 → 스티칭 스레드에서 긴 이미지(4장) → …_full.png 저장 → manifest·세션 로그

[세션 정지] 플로팅 버튼 탭 / 일시정지 / 서비스 꺼짐
   대기 중인 캡처·최종 판단 취소, 디버그 로그 닫기
   현재 방문 이탈 → 긴 이미지까지 끝난 뒤 endedAt 기록, manifest 최종 저장, 세션 로그 "세션 종료"·닫기,
   "QA 기록 저장 완료" 토스트

[복구] 서비스 연결 시
   ended=false 인 세션을 닫는다(이탈시각·종료시각 = 마지막 캡처 시각). 캡처는 찍을 때마다 저장되므로 잃는 이미지는 없다.
```

같은 화면을 다시 방문하면 새 순번이다(A→B→A = 화면 순번 3개, 시간순).
홈 화면/알림창/키보드는 무시 대상이라 화면을 바꾸지 않는다(A→홈→A 는 같은 방문이 이어짐).

### 3-3. 캡처 규칙 (`capture/CapturePolicy.kt`)

상수는 `CaptureConfig` 한 곳에 모았다. SETTLE/MAX_WAIT 은 설정 화면 값(`Options`)이 우선한다.

| 상수 | 기본값 | 의미 |
|---|---|---|
| `SETTLE_MS` | 1500ms (설정 500~3000) | 화면 변화가 이만큼 없으면 최종 캡처 |
| `MAX_WAIT_MS` | 8000ms (설정 3000~30000) | 변화가 계속돼도 이만큼 지나면 그 시점에 최종 캡처 |
| `MIN_SHOT_INTERVAL_MS`, `HIDE_SETTLE_MS` | 350ms, 80ms | takeScreenshot 최소 간격(시스템 333ms + 여유), 오버레이 숨김 반영 |
| `MAX_PENDING_SAVES`, `BACKLOG_RETRY_MS` | 3장, 100ms | 저장 밀림 보호 |
| `THUMB_COLUMNS`, `CELL_DIFF` | 72칸, 24 | 축소 이미지 가로 칸 수(1080px → 15px 칸), 바뀐 칸 기준(칸 평균색 채널 차이) |
| `SIMILAR_MAX_CHANGED_RATIO` | 1% | 변화 캡처의 "거의 같음" 기준 |

```
FinalTimer
onChange(t):  변화묶음시작이 없으면 = t.  마지막변화 = t
evaluate(now, SETTLE, MAX_WAIT):
  묶음 없음                         → 대기 없음
  now ≥ 마지막변화 + SETTLE          → 최종(멈춤),  묶음 끝
  now ≥ 묶음시작 + MAX_WAIT          → 최종(강제),  묶음시작 = now   # 계속 변하면 MAX_WAIT 마다, 멈추면 SETTLE 뒤 한 번 더
  그 외                              → 대기(min(마지막변화 + SETTLE, 묶음시작 + MAX_WAIT))
cancel(): 묶음 버림 (화면 전환·화면 이탈·세션 정지)

FrameDiff
thumbnail: 상태바 아래 ~ 내비게이션바 위를 정사각형 칸(폭/72)으로 나눠 칸마다 평균 RGB
changedCells(a, b): 칸 평균색이 R/G/B 중 하나라도 24 보다 크게 다른 칸 수 (크기가 다르면 전체보다 큼)
similar = 바뀐 칸 ≤ 전체 × 1%     same = 바뀐 칸 0
```

크기 감각(1080x2400, 칸 약 1만 개): 상태바 시계는 비교 범위 밖, 깜빡이는 커서·작은 스피너(0.5% 안팎)는 similar,
토스트(약 3%)·스낵바·목록 스크롤은 저장된다.

### 3-4. 마스터 스위치(일시정지/재개)

- 값: `Options.captureEnabled`(SharedPreferences, 기본 켜짐). 재부팅·앱 재시작·덮어쓰기 설치 후에도 유지된다.
- 바꾸는 곳: 메인 화면 상태 카드의 스위치, 알림창 빠른 설정 타일 "QA 캡처". 서비스가 SharedPreferences 변경 리스너로 바로 반영한다.
- 끔(일시정지): 기록 중이면 세션을 정지·저장 → 플로팅 버튼 제거 → 서비스 이벤트 종류를 0 으로 바꿔 **이벤트를 아예 받지 않음**(캡처도 없음).
- 켬(재개): 무시 목록 갱신 → 플로팅 버튼 표시 → 이벤트 종류 복원. 기록은 다시 버튼으로 시작한다.
- 타일: 서비스 꺼짐이면 비활성 표시 + 누르면 앱 메인 화면. 부제목 = 동작 중/기록 중/일시정지/서비스 꺼짐.

### 3-5. 디버그 로그

- 설정 화면 스위치(기본 끔). 세션을 시작할 때 적용된다.
- 켜면 서비스가 받는 이벤트 종류를 `TYPES_ALL_MASK` 로 바꾸고, 이 앱 것을 뺀 모든 이벤트를 한 줄씩 기록한다. 정지하면 원래대로 돌린다.
- 형식(탭 구분): `시각(HH:mm:ss.SSS)  이벤트타입  패키지  클래스  텍스트  기타(win=창ID, change=contentChangeTypes, dx/dy)`
- 서비스 내부 판단도 같이 남긴다: `#SHOT`(change/final), `#FINAL`(settled/forced), `#SHOT_FAIL`
- 저장: `Documents/QAEvidence/debug/{세션}.txt` (MediaStore, 전용 스레드, 마지막 쓰기 1초 뒤 flush). 세션 zip 에 `debug_log.txt` 로 들어간다.

## 4. 스티칭 알고리즘 (긴 이미지 `_full`)

**세로 스크롤이 있었던 화면 방문**을 떠날 때, 그 방문에서 저장한 캡처(2장 이상, 최종 캡처 포함)를 촬영 순서대로 입력으로 쓴다.
저장한 PNG 를 MediaStore 에서 다시 읽으므로 임시 프레임 파일은 없다. 모든 판단은 **행 해시(LongArray)** 로 하고, 픽셀은 마지막 렌더링 때만 다시 읽는다.

### 4-1. 행 해시

```
rowHash(frame, y):
  x 범위 = [0, width - width*3%)        # 오른쪽 3%(스크롤바 자리)는 제외
  x 를 2px 간격으로 샘플링하며 64bit FNV-1a 해시
```

### 4-2. 연속 프레임 비교 `match(A, B)` — A는 직전에 채택된 프레임

```
if A의 모든 행 해시 == B의 모든 행 해시:  return SAME          # 동일 프레임(오프셋 0) → 버림

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

### 4-3. 프레임 묶기(part 분리)

```
groups = [[(f0, pos=0)]]
last = f0
for f in frames[1..]:
  if 크기 다름(회전 등):                 새 group 시작(f, pos=0)
  m = match(last, f)
  SAME:      버림 (continue)
  NO_MATCH:  새 group 시작(f, pos=0)                   → 결과 파일 …_part2.png
  OFFSET(dy):
     pos = pos(last) + dy
     group 높이(max(pos)+H - min(pos)) > 30000 이면 새 group 시작(f, pos=0)
     아니면 현재 group 에 (f, pos) 추가
  last = f
```

### 4-4. group 하나를 1장으로 합성 `compose(group)`

```
프레임 1장뿐이면: 그 프레임 전체 → 결과 single

# (1) 고정 영역 검출: 연속 프레임 쌍마다 위/아래에서부터 같은 위치 행이 동일한 길이
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

저장: 이어붙은 part(높이 > 프레임 높이)만 `…_full.png`, `…_full2.png` 로 저장한다.
1장짜리 part(로딩 화면, 전환 애니메이션 등 이어지지 않는 프레임)는 이미 개별 캡처로 남아 있으므로 만들지 않는다.


## 5. 저장

- 폴더: `Documents/QAEvidence/{yyyyMMdd_HHmmss}/` (0-1)
- 이미지: `{화면순번3자리}_{패키지 끝부분}_{화면클래스 짧은이름}_{진입 HHmmss}_{캡처번호2자리}[_final].png`
  - 예: `003_settings_SubSettings_103512_01.png`, `003_settings_SubSettings_103512_02_final.png`
  - 긴 이미지: `003_settings_SubSettings_103512_full.png` (30,000px 초과·이어지지 않는 구간은 `_full2`, `_full3`)
  - 클래스 짧은이름 = 마지막 `.` 뒤, 영문/숫자 외 문자는 `_`, 최대 40자. 이름순 정렬 = 시간순
- 세션 로그: `session_log.txt` (탭 구분, `시각  종류  내용`)

```
# QA 증적 캡처 세션 로그 / 세션 20261015_103000
# 시각	종류	내용
10:30:00.120	세션 시작
10:30:00.310	화면	com.android.settings / com.android.settings.Settings
10:30:00.702	캡처	001_settings_Settings_103000_01.png
10:30:02.230	최종 캡처	001_settings_Settings_103000_02_final.png
10:30:21.015	토스트	[com.android.settings] 저장되었습니다
10:30:40.880	긴 이미지	001_settings_Settings_103000_full.png
10:42:10.004	세션 종료	플로팅 버튼으로 정지 · 이미지 57장
```

- 디버그 로그: `Documents/QAEvidence/debug/{세션}.txt` (3-5)
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
        { "file": "001_settings_Settings_103000_01.png", "at": "2026-10-15T10:30:00.702+09:00", "final": false },
        { "file": "001_settings_Settings_103000_02_final.png", "at": "2026-10-15T10:30:02.230+09:00", "final": true }
      ],
      "full": ["001_settings_Settings_103000_full.png"]
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

- 내부 `noBackupFiles/sessions/{세션}/session.json`: 위 manifest + `ended` 플래그. 메인 화면 목록·복구에 사용.
  이전 버전 기록(`files`·`frames`·`stitch`)도 읽는다(`files` → captures).
- 공유: 세션 화면 [zip으로 공유] → `Documents/QAEvidence/{세션}.zip` 생성(기존 것은 교체) → `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`.
  zip 내용은 MediaStore에서 그 세션 폴더(RELATIVE_PATH)의 내 파일 + 디버그 로그.

## 6. 화면 구성

### 6-1. 디자인 원칙 (신뢰감 있는 사내 QA 도구)

- Material 3 색 역할·글자 단계·모양을 따르되, 외부 라이브러리 금지 조건 때문에 Material Components 대신 프레임워크 뷰로 구현한다(`ui/Ui.kt`).
- 색: 남색 `primary`(#24457A, 다크 #AEC6FF) 1색 + 회색 톤(surface, outline 등). **상태 색만** 초록(동작 중)·회색(일시정지)·빨강(서비스 꺼짐).
- 다크모드: `values-night` 의 같은 이름 색 역할 + 어두운 기본 테마. 상태바·내비게이션바 아이콘 밝기도 테마를 따른다.
- 글자: 22sp 앱바 제목, 16sp 카드 제목/본문, 14sp 본문·버튼, 12sp 보조 설명. 여백: 화면 가장자리 16dp, 카드 안 16dp, 카드 사이 12dp.
- 모양: 테두리 카드(12dp 모서리, 1dp 테두리), 버튼 40dp 높이·20dp 모서리(Filled/Tonal/Text), 스위치 52x32dp.
- 앱 아이콘: adaptive icon. 남색 배경 + 흰 문서(오른쪽 위 접힘)·글줄 2개·체크. 단색 테마 아이콘(monochrome, 글줄·체크는 뚫림), 같은 모양으로 빠른 설정 타일 아이콘.

### 6-2. 화면

- **메인**(위에서부터)
  1. 상태 카드: 상태 점·제목(동작 중 / 동작 중 · 기록 중 / 일시정지 / 서비스 꺼짐) + 설명 + 마스터 스위치
  2. 처음 설정 카드(모두 끝나면 숨김): ① 접근성 서비스 켜기 ② 제한된 설정 허용 ③ 배터리 "제한 없음". 완료 단계는 체크, 아니면 번호 + 이동 버튼.
     ①②는 서비스가 켜져 있으면 완료(제한된 설정을 직접 확인하는 공개 API 가 없고, 서비스가 켜졌다면 허용됐거나 필요 없던 것). ③은 `PowerManager.isIgnoringBatteryOptimizations`.
  3. 저장된 세션: 날짜, 이미지 수·화면 수·기록 시간(또는 기록 중), [열기]
  4. 개인정보 및 권한: 읽는 정보, 저장 위치, 외부 전송 없음(네트워크 권한 없음), 사용자 권한은 접근성 1개
  - 앱바 오른쪽 설정 아이콘 → 설정 화면
- **설정**: 최종 캡처 타이밍(SETTLE_MS, MAX_WAIT_MS 슬라이더 + 기본값으로 되돌리기, 바로 적용), 디버그 로그 스위치
- **세션**: 요약(이미지·화면·토스트 수, 시간, 저장 위치), [zip으로 공유], [세션 로그](대화상자로 보기), 이미지 목록(썸네일·파일명·시각·종류). 누르면 기본 이미지 앱으로 연다.
- **플로팅 버튼**: 대기 `● 시작`(남색), 기록 중 `■ {저장 이미지 수}`(초록). 탭=시작/정지, 길게=메인 화면, 드래그=이동. 일시정지 중에는 숨김.
- **빠른 설정 타일** "QA 캡처": 누르면 일시정지/재개(3-4).
- 접근성 바로가기: 서비스가 `FLAG_REQUEST_ACCESSIBILITY_BUTTON` 을 요청하지 않으므로 바로가기는 서비스 자체를 켜고 끈다. 끄면 진행 중 세션은 정지·저장된다.

## 7. 권한과 개인정보

- 사용자가 켜는 권한: **접근성 서비스 1개**뿐.
- Manifest 권한 선언 없음. 네트워크 권한(INTERNET, ACCESS_NETWORK_STATE)은 `tools:node="remove"` 로 명시 제거하고, CI 에서 APK 권한에 네트워크 권한이 있으면 배포를 멈춘다.
- `<queries>` 로 HOME 인텐트와 IME 인텐트만 조회(권한 아님). 타일 서비스의 `BIND_QUICK_SETTINGS_TILE` 은 시스템이 바인딩할 때 쓰는 보호 권한이다(앱이 요청하는 권한 아님).
- 쓰지 않음: 포그라운드 서비스, 알림, `SYSTEM_ALERT_WINDOW`, 저장소 권한, `QUERY_ALL_PACKAGES`, 네트워크.
- 접근성으로 읽는 것: 화면 전환·스크롤·내용 변경 이벤트(패키지, 클래스, 창 ID), 토스트 문구, 기록 중 화면 캡처. 화면 글자 내용은 디버그 로그를 켠 경우에만 기록.
  같은 내용을 메인 화면 "개인정보 및 권한"과 접근성 서비스 설명(`service_description`)에 적었다.
- 서비스 설정: `canTakeScreenshot=true`, `canRetrieveWindowContent=true`(창 ID, 기록 시작 시 앞 창 확인),
  이벤트 `typeWindowStateChanged|typeViewScrolled|typeWindowContentChanged|typeNotificationStateChanged`(일시정지 중 0, 디버그 로그 중 전체), `notificationTimeout=0`.
- 덮어쓰기 설치 시 권한 유지 조건: 같은 서명 + 서비스 클래스 이름(`com.qa.autocapture.capture.CaptureService`) 고정.

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

`CapturePolicyTest` — 변화 이벤트를 시간순으로 넣고 서비스처럼 대기 시각마다 다시 판단하는 시뮬레이터 + 합성 화면 비교.

| 테스트 | 기대 |
|---|---|
| 변화 1번 | SETTLE_MS 뒤 최종 1장 |
| 변화가 이어짐 | 마지막 변화 + SETTLE_MS 에 최종 |
| 계속 변함(롤링 배너) 후 멈춤 | MAX_WAIT_MS 마다 강제 최종, 멈추면 SETTLE_MS 뒤 최종 |
| 변화 없음 / 취소 | 최종 없음, 취소 후 새 변화는 처음부터 |
| 설정값 전달 | 넘긴 SETTLE/MAX_WAIT 로 판단 |
| 같은 화면 / 상태바 시계 변화 | same |
| 스피너 크기 변화 | similar 이지만 same 아님(최종은 새로 저장) |
| 토스트 크기 변화 / 크기 다름 | similar 아님 |
| 칸 평균색 | 칸 안 픽셀 평균 |

`StitcherTest`, `PngWriterTest` — 합성 원본(고정 헤더 + 긴 콘텐츠 + 고정 푸터)을 겹치게 잘라 만든 프레임으로
`plan → render → PNG → ImageIO 디코드` 결과가 기대 이미지와 **픽셀 단위로 동일**한지 본다(아래로 끝까지, 중복 프레임, 왕복,
중간 진입 후 위로, 시계 변화, 겹침 없는 점프 → 분리, 1장, 회전, 접히는 툴바, 높이 제한, PNG 왕복).

## 10. 알려진 한계

- `FLAG_SECURE` 화면, 동영상/애니메이션 최적화는 범위 밖이다. 기본 디스플레이만 캡처한다(DeX/멀티 디스플레이 제외).
- **이미지가 많다**: 변화마다 찍으므로 스크롤·애니메이션 중에는 초당 약 3장까지 저장된다(거의 같은 화면은 생략).
  계속 바뀌는 큰 영역(자동 넘김 배너, 동영상)이 있으면 바뀔 때마다 저장된다.
- 캡처는 변화를 감지한 뒤 80~350ms 뒤에 찍히므로, 나타나는 애니메이션 도중이나 아주 짧게 떴다 사라진 상태는 찍히지 않을 수 있다.
  토스트는 나타난 직후 1장, 그 뒤 최종 캡처(1.5초 뒤)에 보통 함께 찍힌다. 시스템 UI 가 그리는 토스트가 **사라지는 순간**은 대상 앱 이벤트가 아니어서 따로 찍지 않는다.
- 저장이 밀리면(저장 대기 3장) 다음 캡처가 늦어진다. 그 사이 지나간 중간 상태는 남지 않을 수 있다(최종 캡처는 남음).
- "거의 같음"(바뀐 칸 ≤ 1%)에 걸리는 작은 변화(체크박스, 한 글자 입력)는 변화 캡처로는 생략되고, 최종 캡처에서 남는다.
- 일시정지 중 바뀐 화면은 모른다. 재개 후 기록을 시작하면 앞 창이 이미 아는 화면일 때만 바로 찍고, 아니면 다음 이벤트부터 찍는다.
- 플로팅 버튼은 촬영 직전 80ms 동안 숨으므로, 변화가 이어지는 동안 깜빡인다.
- **단일 Activity 앱**(Fragment/Compose 내비게이션)은 화면이 바뀌어도 className 이 같아 화면 순번이 늘지 않는다(캡처 번호만 늘어남).
- 긴 이미지: 세로 스크롤 이벤트가 있었던 방문만 만든다. 오버스크롤 효과·로딩 프레임 등 이어지지 않는 지점에서는 `_full2` 로 나뉜다.
  스크롤바 영역(오른쪽 3%)은 비교에서 빠지므로 사라지는 중인 스크롤바 조각이 남을 수 있다. 상태바는 가장 위 프레임 것이 쓰인다.
- 화면을 떠난 뒤 이미 촬영 중이던 1장(수십 ms)은 홈 화면이 찍힐 수 있다.
- 제한된 설정 허용 여부는 공개 API 로 확인할 수 없어, 접근성 서비스가 켜졌는지로 대신 판단한다.
- 앱을 **삭제 후 재설치**하면 이전 파일의 MediaStore 소유권이 사라져 목록/공유에서 빠진다(파일은 남음). 덮어쓰기 설치는 문제없다.
- 배터리 최적화로 접근성 서비스가 강제 종료될 수 있으므로 "제한 없음" 설정이 필요하다(README).
