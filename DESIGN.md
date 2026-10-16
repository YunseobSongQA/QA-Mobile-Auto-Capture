# QA 증적 자동 캡처 — 설계 문서

안드로이드 수동 QA 중 **보고하기 좋은 화면만** 자동으로 캡처한다.
화면에 들어가거나 누른 뒤 **화면이 정적으로 완성되면 1장**, 스크롤한 부분은 **한 화면에 한 장으로 이어붙인다**.
깜빡이는 순간이나 작은 상태 변화는 하나하나 찍지 않는다. 테스터는 평소처럼 앱을 쓰고 스크롤만 한다.

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

### 0-2. 캡처 방식 변경 이력

| 차수 | 방식 | 바꾼 이유 |
|---|---|---|
| 1차 | 진입 대기·스크롤 디바운스 후 캡처, 화면마다 스티칭 1장 | — |
| 2차 | 화면 안정화 대기(로딩 표시·토스트 판단) | 깨끗한 화면 |
| 3차 | 판단 규칙 없이 변화마다 캡처 + `_final`, 스크롤은 `_scroll` 로 이어붙임 | 놓치는 화면 없게 |
| **4차(현재)** | **보고용: 중요한 때만, 정적으로 완성된 화면, 화면 상태마다 1장(스크롤은 합침)** | 3차는 이미지가 너무 많고 깜빡임·작은 상태까지 찍힘 |

### 0-3. 4차 규칙 (보고용 캡처)

| 규칙 | 내용 |
|---|---|
| 찍는 때 | 화면 진입, 터치·길게 누르기·입력(`TYPE_VIEW_CLICKED/LONG_CLICKED/TEXT_CHANGED`), 스크롤 멈춤. 앱이 스스로 바뀐 것(배너·시계·애니메이션)은 화면의 30% 이상이 바뀌었을 때만(터치 이벤트를 안 보내는 화면 전환 대비) |
| 정적 완성 확인 | 이벤트가 `SETTLE_MS`(1초) 동안 없으면, 버튼을 숨기지 않고 비교용 화면을 찍어 비교 → `CONFIRM_MS`(0.5초) 뒤 버튼을 숨기고 저장용 1장을 찍어 같으면 확정. 돌아가는 로딩 표시처럼 이벤트 없이 움직이는 화면도 정지할 때까지 기다린다 |
| 바뀐 게 없으면 안 찍음 | 첫 비교 화면이 이 화면의 마지막 확보 화면과 같으면 끝(버튼도 안 숨김) |
| 끝내 정지 안 함 | 화면 진입·터치 뒤 `MAX_WAIT_MS`(8초)가 지나면 그 시점 화면 1장. 앱이 스스로 바뀐 경우는 찍지 않음 |
| 화면 상태 = 이미지 1장 | 작은 변화(바뀐 칸 ≤ 1%: 체크박스, 글자 입력)는 새 파일 없이 그 상태 이미지를 최신 화면으로 바꾼다. 뚜렷한 변화(오류 문구, 펼친 내용)는 새 이미지 |
| 스크롤 | 스크롤 중에는 찍지 않고, 멈출 때(`SCROLL_SETTLE_MS` 0.4초)마다 1장을 같은 상태에 덧붙인다. 상태가 끝나면 이어붙여 긴 이미지 1장. 멈춘 뒤 이미지 로딩 등으로 바뀌면 그 프레임을 완성된 화면으로 바꾼다 |
| 진입 직후 미리 1장 | 화면에 들어와 0.4초 조용하면 1장 미리 확보(곧바로 스크롤해도 맨 위가 남게). 정지 확인 결과로 대신한다 |
| 깜빡임 최소화 | 플로팅 버튼은 저장용 1장을 찍을 때만 숨긴다. 비교용 화면에서는 버튼 영역을 비교에서 뺀다 |
| 결과 | 중간 화면은 임시 파일로만 두고, 화면 상태가 끝날 때 이미지로 저장. 파일 이름 `{순번}_{앱}_{화면}_{진입시각}.png`, 같은 방문의 다음 상태는 `_2`, `_3` |

3차의 `_final` 이름, 변화마다 캡처, 직전 저장 이미지와의 중복 생략은 위 규칙으로 대체했다.

### 0-4. 함께 정한 것

| 항목 | 내용 | 이유 |
|---|---|---|
| 토스트 | 문구를 시각과 함께 세션 로그·manifest 에 기록. 토스트가 떠 있는 동안 정지하면 토스트가 찍힌 화면이 된다 | 결과 확인 문구도 증적 |
| 화면을 떠남 | 홈(런처)·이 앱이 앞으로 오면 기다리던 촬영을 버린다 | 홈 화면이 증적에 찍히지 않게 |
| 저장 밀림 보호 | 처리 대기 화면이 3장 이상이면 다음 촬영을 미룬다 | 메모리 보호 |
| 마스터 스위치 | 접근성 서비스는 켜 둔 채 캡처만 일시정지/재개. 메인 화면 상단 + 빠른 설정 타일 | |
| 네트워크 권한 제거 고정 | Manifest `tools:node="remove"`, CI 에서 APK 권한 확인 | 외부 전송 없음 보장 |
| 창 ID 매핑 | `windowId → 화면 식별자`. 같은 창의 다른 className 은 같은 화면, 다이얼로그가 닫힌 뒤 원래 화면 스크롤로 복귀 인식 | 1차부터 |
| 최대 높이 | 이어붙인 이미지 1장 `MAX_STITCH_HEIGHT` 20,000px | 뷰어 호환 |
| PNG 직접 인코딩 | 이어붙인 이미지는 순수 Kotlin 스트리밍 인코더로 행 단위 기록 | 메모리 |
| 공유 | zip 을 MediaStore 에 저장하고 그 URI 로 공유(FileProvider 없이) | |

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
| `capture/CaptureService.kt` | Android | 접근성 서비스. 이벤트 수신·무시 대상 필터·화면 식별, 찍을 때 판단(진입·터치·입력·스크롤·큰 변화), 정지 확인 구동, 촬영 큐(350ms 간격, 저장용만 버튼 숨김), 별도 스레드에서 축소 이미지 계산, 화면 상태 판단(새 이미지/최신으로 바꿈/덧붙임), 세션 시작/정지, 마스터 스위치, 디버그 로그, 화면·타일용 상태 |
| `capture/CapturePolicy.kt` | **순수 Kotlin** | `CaptureConfig`(상수 전부), `SettleCycle`(정적 완성 확인), `ScrollTimer`(스크롤 멈춤), `FrameDiff`(축소 이미지·바뀐 칸 비율·버튼 영역 제외) |
| `capture/CaptureTileService.kt` | Android | 빠른 설정 타일. 마스터 스위치 토글, 상태 표시 |
| `session/SessionRecorder.kt` | Android | 세션 1개. 화면 상태별 임시 프레임(NEW/REPLACE/SCROLL), 상태가 끝나면 이어붙여 이미지 저장, 화면 순번·파일 번호, manifest·세션 로그, 비정상 종료 정리·남은 프레임 복구 |
| `stitch/Stitcher.kt` | **순수 Kotlin** | 행 해시, scrollDeltaY 힌트 검증, 오프셋 투표, 중복 제거, 분리 판단, 고정 영역 판별, 합성 계획(Plan) |
| `stitch/PngWriter.kt` | **순수 Kotlin** | 스트리밍 PNG 인코더 + Plan 렌더링 |
| `storage/EvidenceStore.kt` | Android | MediaStore 저장·조회(PNG, manifest.json, zip), 임시 프레임 파일, 세션 기록(session.json), `Options`(설정값), `TextLog`(세션 로그·디버그 로그) |
| `overlay/FloatingButton.kt` | Android UI | `TYPE_ACCESSIBILITY_OVERLAY` 플로팅 버튼. 탭=시작/정지, 길게=앱 열기, 드래그, 확보 이미지 수, 저장용 촬영 때 숨김, 화면 위치(비교에서 뺄 영역) |
| `MainActivity.kt` | Android UI | 상태 카드 + 마스터 스위치, 설정 안내(3단계, 펼침/접힘), 세션 목록, 개인정보 및 권한 |
| `ui/Ui.kt` | Android UI | 공통 뷰 도우미: Material 3 색 역할·글자 단계·카드·버튼·스위치·앱바·여백 |
| `ui/SettingsActivity.kt` | Android UI | SETTLE_MS, MAX_WAIT_MS 슬라이더, 디버그 로그 |
| `ui/SessionActivity.kt` | Android UI | 세션 열기: 요약, zip 공유, 세션 로그 보기, 이미지 목록 |
| `CapturePolicyTest.kt` | JVM 테스트 | 정적 완성 확인, 스크롤 멈춤, 축소 이미지 비교 |
| `StitcherTest.kt`, `PngWriterTest.kt` | JVM 테스트 | 합성 이미지로 원본 복원(겹침 계산·힌트·고정 영역 판별·분리), PNG 왕복 |

인터페이스·DI 없음. 계층 간 호출은 구체 클래스 직접 호출. 화면은 레이아웃 XML 없이 코드로 만든다.

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

### 3-2. 진입 → 확인 → 확보 → 이탈

```
[세션 시작] 플로팅 버튼 탭 (마스터 스위치 켜짐일 때만 버튼이 보임)
   SessionRecorder 생성(세션ID = yyyyMMdd_HHmmss), 세션 로그 열기, 내부 session.json 기록
   앞에 있는 창이 아는 화면이면 그 화면으로 진입

[화면 진입]  이전 방문 닫기(남은 상태를 이미지로 저장) → 정지 확인 시작(ACTION, 미리 1장)
[터치·입력]  VIEW_CLICKED / LONG_CLICKED / TEXT_CHANGED (현재 화면 패키지) → 정지 확인 시작(ACTION)
[내용 변경]  WINDOW_CONTENT_CHANGED·토스트·가로 스크롤
             스크롤 중 → 스크롤 멈춤 대기만 늘림 / 확인 중 → "아직 바뀌는 중"(비교 처음부터)
             둘 다 아님 → 정지 확인 시작(CHANGE: 화면의 30% 이상 바뀌었을 때만 찍음, 찍을 게 없었으면 3초 쉼)
[토스트]     문구·시각·그때 화면을 manifest toasts 와 세션 로그에 기록 → 내용 변경으로 처리

[정지 확인] SettleCycle (3-3). 찍으라고 하면 촬영 큐로:
   PREVIEW(버튼 숨김) → 이 방문의 첫 화면이면 새 상태로 미리 확보
   PROBE(버튼 그대로) → 비교만
   FINAL(버튼 숨김)   → 확정되면 화면 상태 판단(3-4)

[세로 스크롤] VIEW_SCROLLED (dy≠0, 또는 dx·dy 모두 0 = 이동량 모름)
   정지 확인 멈춤, scrollDy += dy, ScrollTimer.onScroll
   멈춤(0.4초 조용, 최대 1.5초) → 버튼 숨기고 1장 → 같은 상태에 덧붙임(SCROLL, scrollDy 힌트)
   → 정지 확인 시작(COMPLETION: 남은 로딩이 끝나 바뀌면 방금 프레임을 완성된 화면으로 바꿈)

[촬영 큐] (메인 스레드, 1장씩)
   스크롤 프레임이 먼저. 처리 대기 ≥ 3장이면 100ms 씩 미룸
   촬영 시각 = max(지금, 마지막촬영 + 350ms − (버튼 숨김이면 80ms)), 버튼 숨김이면 그 80ms 전에 숨김
   takeScreenshot → 버튼 복원 → 별도 스레드: HardwareBuffer → ARGB_8888 복사 → 축소 이미지
   (비교용은 축소 이미지만 남기고 Bitmap 해제) → 메인 스레드로 결과
   화면이 바뀐 뒤 도착한 결과는 버림. INTERVAL_TIME_SHORT 이면 다시 요청

[화면 상태 저장] (저장 스레드)
   NEW     → 이전 상태를 끝내고(이미지 저장 예약) 새 임시 폴더 groups/{방문}_{상태}/ + info.txt(순번·이름)
             이 방문의 첫 화면이면 화면 순번 부여
   REPLACE → 마지막 프레임 파일을 새 화면으로 덮어씀(이동량 힌트는 그대로)
   SCROLL  → 프레임 덧붙임 {번호}_{scrollDy|n}.png
   플로팅 버튼 숫자 = 저장한 이미지 + 저장 중 + 지금 만드는 상태

[상태 끝] 새 상태 / 화면 이탈 / 세션 정지
   스티칭 스레드: 프레임 이어붙이기(4장) → {base}.png, {base}_2.png … → manifest·세션 로그 → 임시 폴더 삭제

[세션 정지] 플로팅 버튼 탭 / 일시정지 / 서비스 꺼짐
   기다리던 촬영 취소, 디버그 로그 닫기 → 현재 방문 닫기 → 이미지 저장이 끝난 뒤 endedAt, manifest 최종,
   세션 로그 "세션 종료"·닫기, "QA 기록 저장 완료" 토스트

[복구] 서비스 연결 시
   남은 임시 폴더(이전 버전 scroll/ 포함)를 이미지로 저장(`…_recovered.png`)하고 지운다(한 번만 시도).
   ended=false 인 세션을 닫는다(이탈·종료시각 = 마지막 이미지 시각).
```

같은 화면을 다시 방문하면 새 순번이다(A→B→A = 화면 순번 3개, 시간순). 1초도 머물지 않고 지나간 화면은 저장하지 않는다.
홈 화면/알림창/키보드는 무시 대상이라 화면을 바꾸지 않는다(A→홈→A 는 같은 방문이 이어짐).

### 3-3. 정적 완성 확인 (`SettleCycle`)

| 상수(`CaptureConfig`) | 기본값 | 의미 |
|---|---|---|
| `SETTLE_MS` | 1000ms (설정 500~3000) | 이벤트가 이만큼 없으면 확인 시작 |
| `CONFIRM_MS` | 500ms | 두 화면을 이 간격으로 비교 |
| `MAX_WAIT_MS` | 8000ms (설정 3000~30000) | 진입·터치 뒤 끝내 정지하지 않으면 그 시점에 1장 |
| `ENTRY_PREVIEW_MS` | 400ms | 화면 진입 뒤 이만큼 조용하면 미리 1장 |
| `STATIC_MAX_CHANGED_RATIO` | 0.2% | 이하면 "정지"(깜빡이는 커서 허용, 돌아가는 로딩 표시는 정지 아님) |
| `SIMILAR_MAX_CHANGED_RATIO` | 1% | 이하면 "작은 변화" → 새 이미지 없이 최신 화면으로 바꿈 |
| `PAGE_CHANGE_RATIO` | 30% | 앱이 스스로 바뀐 경우 이 이상이어야 찍음 |
| `CHANGE_COOLDOWN_MS` | 3000ms | 앱의 작은 변화를 확인한 뒤 이만큼 다시 보지 않음 |
| `THUMB_COLUMNS`, `CELL_DIFF` | 72칸, 24 | 축소 이미지(폭 1080px → 15px 칸, 상태바·내비바 제외), 바뀐 칸 기준 |
| `OVERLAY_MASK_MARGIN_PX` | 12px | 비교용 화면에서 버튼 둘레로 더 빼는 여백 |

```
start(kind, now, preview)   진행 중이 아니면 시작. 진행 중이면 더 앞의 이유(ACTION > COMPLETION > CHANGE)로 바꿈. 활동으로 처리
onActivity(now)             마지막 활동 = now, 판단 번호++(찍던 결과는 버림), 비교 기준 버림
evaluate(now):
  now ≥ 시작 + MAX_WAIT      → ACTION 이면 FINAL(강제), 아니면 끝(찍지 않음)
  미리 1장 대기 중            → 마지막 활동 + PREVIEW 에 PREVIEW
  now < max(마지막 활동 + SETTLE, 다음 비교 시각) → 대기
  기준 있고 안정              → FINAL   /  그 외 → PROBE
onShot(결과, 비교에서 뺄 버튼 영역, 이 화면의 마지막 확보 화면 last):
  PREVIEW → 미리 확보
  PROBE, 기준 없음 → last 와 비교: (CHANGE: 30% 미만 / 그 외: 정지 수준) 이면 "찍을 것 없음"으로 끝
                     아니면 기준 = 이 화면, 안정으로 보고 CONFIRM 뒤 FINAL
  PROBE, 기준 있음 → 안정 = (기준 ≈ 이 화면). 기준 = 이 화면, CONFIRM 뒤 다시
  FINAL            → 강제거나 기준 ≈ 이 화면이면 확정. 아니면 기준 = 이 화면(불안정), CONFIRM 뒤 PROBE
```

정지한 화면: PROBE(버튼 그대로) 1장 + FINAL(버튼 숨김) 1장 → 깜빡임 1번, 약 1.5초 뒤 확보.
로딩 중인 화면: 로딩 표시가 도는 동안 PROBE 만 반복(깜빡임 없음) → 다 뜨면 FINAL.

### 3-4. 화면 상태 판단 (확정한 화면을 어디에 넣을지)

```
last = 이 방문의 마지막 확보 화면
없음                         → NEW
last 가 진입 직후 미리 확보   → REPLACE (완성된 화면으로 바꿈)
CHANGE(앱의 큰 변화)          → NEW
COMPLETION(스크롤 뒤 확인)    → 바뀐 칸 < 30% 이면 REPLACE, 아니면 NEW
ACTION(진입·터치·입력)        → 바뀐 칸 ≤ 1% 이면 REPLACE, 아니면 NEW
REPLACE 인데 last 와 똑같으면  → 아무것도 안 함
스크롤 멈춤 프레임            → SCROLL (상태가 없으면 NEW)
```

### 3-5. 마스터 스위치(일시정지/재개)

- 값: `Options.captureEnabled`(SharedPreferences, 기본 켜짐). 재부팅·앱 재시작·덮어쓰기 설치 후에도 유지된다.
- 바꾸는 곳: 메인 화면 상태 카드의 스위치, 알림창 빠른 설정 타일 "QA 캡처". 서비스가 SharedPreferences 변경 리스너로 바로 반영한다.
- 끔(일시정지): 기록 중이면 세션을 정지·저장 → 플로팅 버튼 제거 → 서비스 이벤트 종류를 0 으로 바꿔 **이벤트를 아예 받지 않음**.
- 켬(재개): 무시 목록 갱신 → 플로팅 버튼 표시 → 이벤트 종류 복원. 기록은 다시 버튼으로 시작한다.

### 3-6. 디버그 로그

- 설정 화면 스위치(기본 끔). 세션을 시작할 때 적용된다.
- 켜면 서비스가 받는 이벤트 종류를 `TYPES_ALL_MASK` 로 바꾸고, 이 앱 것을 뺀 모든 이벤트를 한 줄씩 기록한다. 정지하면 원래대로 돌린다.
- 형식(탭 구분): `시각(HH:mm:ss.SSS)  이벤트타입  패키지  클래스  텍스트  기타(win=창ID, change=contentChangeTypes, dx/dy)`
- 서비스 내부 판단도 남긴다: `#SHOT`(preview/probe/final/scroll), `#FRAME`(new/replace/scroll, 이유), `#NOCHANGE`, `#SCROLL`(start/stop), `#SHOT_FAIL`
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
     group 높이(max(pos)+H - min(pos)) > MAX_STITCH_HEIGHT(20000) 이면 새 group 시작(f, pos=0)
     아니면 현재 group 에 (f, pos) 추가
  last = f
```

### 4-4. group 하나를 1장으로 합성 `compose(group)`

```
프레임 1장뿐이면: 그 프레임 전체 → 결과 single

# (1) 고정 영역 판별 fixedArea(): 연속 프레임 쌍마다 위/아래에서부터 같은 위치 행이 동일한 길이
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

- Material 3 색 역할·글자 단계·모양을 따르되, 외부 라이브러리 금지 조건 때문에 Material Components 대신 프레임워크 뷰로 구현한다(`ui/Ui.kt`).
- 색: 남색 `primary`(#24457A, 다크 #AEC6FF) 1색 + 회색 톤. **상태 색만** 초록(동작 중)·회색(일시정지)·빨강(서비스 꺼짐).
- 다크모드: `values-night` 의 같은 이름 색 역할 + 어두운 기본 테마.
- 글자: 22sp 앱바 제목, 16sp 카드 제목/본문, 14sp 본문·버튼, 12sp 보조 설명. 여백: 화면 가장자리 16dp, 카드 안 16dp, 카드 사이 12dp.
- 앱 아이콘: adaptive icon(남색 배경 + 흰 문서·글줄·체크), 단색 테마 아이콘, 같은 모양의 빠른 설정 타일 아이콘.

### 6-2. 화면

- **메인**(위에서부터)
  1. 상태 카드: 상태 점·제목(동작 중 / 동작 중 · 기록 중 / 일시정지 / 서비스 꺼짐) + 설명 + 마스터 스위치
  2. 설정 안내 카드: 맨 위 "앱을 업데이트하면 이 과정을 다시 해야 합니다". 단계마다 번호/체크, 구체적인 메뉴 경로, 바로 가는 버튼.
     ① **제한된 설정 허용(가장 먼저)**: 홈 화면에서 앱 아이콘 길게 누르기 → ⓘ 앱 정보(또는 설정 → 애플리케이션 → 앱) → 오른쪽 위 ⋮ → 제한된 설정 허용 → PIN.
        [앱 정보 열기]. ⋮ 에 메뉴가 없으면 [접근성 설정 열기] → 설치된 앱 → 앱을 한 번 눌러 "제한된 설정" 안내를 띄운 뒤 다시.
     ② **접근성 서비스 켜기**: [접근성 설정 열기] → 설치된 앱 → 앱 → 스위치 켜기 → 허용
     ③ **배터리 "제한 없음", "사용하지 않는 앱 관리" 끄기**: [앱 정보 열기]
     ①②는 접근성 서비스가 켜지면 자동 완료(제한된 설정을 직접 확인하는 공개 API 가 없음), ③은 `PowerManager.isIgnoringBatteryOptimizations`.
     미완료(서비스 꺼짐 포함)면 펼친 상태, 모두 완료면 접힌 상태. 제목 줄을 눌러 직접 펼치고 접는다(완료 여부가 바뀔 때만 기본 상태로 돌아감).
  3. 저장된 세션: 날짜, 이미지 수·화면 수·기록 시간(또는 기록 중), [열기]
  4. 개인정보 및 권한: 읽는 정보, 저장 위치, 외부 전송 없음(네트워크 권한 없음), 사용자 권한은 접근성 1개
  - 앱바 오른쪽 설정 아이콘 → 설정 화면
- **설정**: 캡처 타이밍(SETTLE_MS, MAX_WAIT_MS 슬라이더 + 기본값으로 되돌리기, 바로 적용), 디버그 로그 스위치
- **세션**: 요약, [zip으로 공유], [세션 로그], 이미지 목록(썸네일·파일명·시각·"스크롤 이어붙임 · 화면 N장"). 누르면 기본 이미지 앱으로 연다.
- **플로팅 버튼**: 대기 `● 시작`(남색), 기록 중 `■ {확보 이미지 수}`(초록). 탭=시작/정지, 길게=메인 화면, 드래그=이동. 일시정지 중에는 숨김.
- **빠른 설정 타일** "QA 캡처": 누르면 일시정지/재개.

## 7. 권한과 개인정보

- 사용자가 켜는 권한: **접근성 서비스 1개**뿐.
- Manifest 권한 선언 없음. 네트워크 권한(INTERNET, ACCESS_NETWORK_STATE)은 `tools:node="remove"` 로 명시 제거하고, CI 에서 APK 권한에 네트워크 권한이 있으면 배포를 멈춘다.
- `<queries>` 로 HOME 인텐트와 IME 인텐트만 조회(권한 아님). 타일 서비스의 `BIND_QUICK_SETTINGS_TILE` 은 시스템이 바인딩할 때 쓰는 보호 권한이다.
- 쓰지 않음: 포그라운드 서비스, 알림, `SYSTEM_ALERT_WINDOW`, 저장소 권한, `QUERY_ALL_PACKAGES`, 네트워크.
- 접근성으로 읽는 것: 화면 전환·스크롤·내용 변경·터치·입력 이벤트(패키지, 클래스, 창 ID, 시각), 토스트 문구, 기록 중 화면 캡처.
  터치·입력 이벤트는 "찍을 때"를 정하는 데만 쓰고 내용(입력한 글자)은 저장하지 않는다. 화면 글자 내용은 디버그 로그를 켠 경우에만 기록.
  같은 내용을 메인 화면 "개인정보 및 권한"과 접근성 서비스 설명(`service_description`)에 적었다.
- 서비스 설정: `canTakeScreenshot=true`, `canRetrieveWindowContent=true`(창 ID, 기록 시작 시 앞 창 확인),
  이벤트 `typeWindowStateChanged|typeViewScrolled|typeWindowContentChanged|typeNotificationStateChanged|typeViewClicked|typeViewLongClicked|typeViewTextChanged`
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

`CapturePolicyTest` — 정지 확인은 화면을 시각의 함수로 두고(버튼이 찍힌 비교용 화면 포함) 서비스처럼 대기 시각마다 다시 판단하는 시뮬레이터로 검증한다.

| 테스트 | 기대 |
|---|---|
| 정적인 화면 진입 | 0.4초에 미리 1장, 1초에 비교 → 바뀐 게 없어 끝. 버튼 숨김 촬영 1번 |
| 로딩 표시가 도는 화면(이벤트 없음) | 도는 동안 확정하지 않고, 다 뜬 뒤 확인 간격 안에 다 뜬 화면으로 확정 |
| 이벤트가 이어짐 | 그동안 촬영 없음 |
| 끝내 정지하지 않음 | MAX_WAIT_MS 에 강제 1장 |
| 터치했는데 그대로 | 버튼 숨김 없이 끝 |
| 터치 뒤 바뀜(오류 문구) | 정지 확인 뒤 1장, 버튼 숨김 1번 |
| 비교용 화면의 버튼 | 비교에서 빼서 정지로 판단 |
| 앱의 작은 변화(배너 20%) / 큰 변화(다른 화면) / 계속 바뀜 | 안 찍음 / 찍음 / 안 찍고 끝 |
| 촬영 중 이벤트 | 그 결과 버리고 다시 조용해지길 기다림 |
| 스크롤 멈춤 | 멈추면 0.4초 뒤 1장, 멈출 때마다 1장, 멈춘 뒤 내용 변경은 그만큼 대기(최대 1.5초), 스크롤 없으면 모드 아님, 취소 |
| 축소 이미지 | 같음, 상태바 시계는 범위 밖, 커서는 정지, 로딩 표시는 정지 아님·작은 변화, 오류 문구 한 줄은 작은 변화 아님, 버튼 영역 제외, 크기 다름 = 전부 바뀜, 칸 평균 |

`StitcherTest`, `PngWriterTest` — 합성 원본(고정 헤더 + 긴 콘텐츠 + 고정 푸터)을 겹치게 잘라 만든 프레임으로
`plan → render → PNG → ImageIO 디코드` 결과가 기대 이미지와 **픽셀 단위로 동일**한지 본다(아래로 끝까지, 중복 프레임, 왕복,
중간 진입 후 위로, 시계 변화, 겹침 없는 점프 → 분리, 1장, 회전, 접히는 툴바, 높이 제한 20000px, scrollDeltaY 힌트 맞음/틀림,
반복 무늬를 힌트로 이어붙이기, 버린 프레임이 끼어도 힌트 누적, 힌트가 있어도 겹침 없으면 분리, 고정 영역 판별, 헤더·탭바 한 번씩, PNG 왕복).

## 10. 알려진 한계

- `FLAG_SECURE` 화면, 동영상/애니메이션 최적화는 범위 밖이다. 기본 디스플레이만 캡처한다(DeX/멀티 디스플레이 제외).
- **1초 넘게 머물러야 찍힌다**: 정지 확인에 약 1.5초(조용 1초 + 확인 0.5초)가 걸린다. 그보다 빨리 지나간 화면·상태는 남지 않는다
  (진입 직후 미리 1장은 0.4초 조용하면 확보).
- **작은 변화는 마지막 상태만 남는다**: 체크박스·글자 입력처럼 바뀐 칸 1% 이하의 변화는 새 이미지 없이 최신 화면으로 바뀐다(중간 상태는 안 남음).
- **앱이 스스로 바뀐 것은 대부분 안 찍는다**: 터치 이벤트 없이 바뀐 화면은 30% 이상 바뀌어야 찍는다. 터치 이벤트(`TYPE_VIEW_CLICKED`)를 보내지 않는
  화면(일부 Compose 화면)에서 누른 뒤 일부만 바뀌는 결과는 다음 터치·스크롤 때 함께 찍힌다.
- 로딩 표시가 매우 작거나(0.2% 이하) 거의 안 움직이면 정지로 보고 찍을 수 있다. 계속 움직이는 화면은 진입·터치 뒤 8초에 1장.
- 토스트가 떠 있는 동안 화면이 정지하면 토스트가 찍힌 화면이 저장된다(문구는 세션 로그에도 남음).
- 스크롤: 멈출 때만 찍으므로 한 번에 화면 높이보다 많이 넘기면(빠른 플링 등) 겹침이 없어 다음 번호 이미지로 나뉘고 그 사이 내용은 남지 않는다.
  오버스크롤 효과, 스크롤하며 접히는 툴바는 행 비교로 넘어가고 그래도 못 찾으면 나뉜다. 화면에 들어오자마자(0.4초 안) 스크롤하면 첫 멈춤 위치부터 이어진다.
- 플로팅 버튼은 저장할 1장을 찍을 때마다 약 0.1초 사라진다(화면 확보 1번에 1번).
- **단일 Activity 앱**(Fragment/Compose 내비게이션)은 화면이 바뀌어도 className 이 같아 화면 순번이 늘지 않고 같은 순번의 `_2`, `_3` 으로 저장된다.
- 화면을 떠난 뒤 이미 촬영 중이던 1장(수십 ms)은 홈 화면이 찍힐 수 있다.
- 제한된 설정 허용 여부는 공개 API 로 확인할 수 없어, 접근성 서비스가 켜졌는지로 대신 판단한다.
- 앱을 **삭제 후 재설치**하면 이전 파일의 MediaStore 소유권이 사라져 목록/공유에서 빠진다(파일은 남음).
- 배터리 최적화로 접근성 서비스가 강제 종료될 수 있으므로 "제한 없음" 설정이 필요하다(README).
