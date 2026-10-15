# QA 증적 자동 캡처 — 설계 문서 (MVP)

안드로이드 수동 QA 중 테스터가 방문한 화면을 **화면마다 위에서 아래까지 이어붙인 PNG 1장**으로 자동 저장한다.
테스터는 평소처럼 앱을 쓰고 스크롤만 한다. 캡처·분할·이어붙이기는 앱이 한다.

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

즉 **`Pictures/QAEvidence/{세션}/manifest.json` 은 권한 없이 만들 수 없다**
(`Primary directory Pictures not allowed ...` / `MIME type application/json cannot be inserted ...` 오류).
"저장소 권한 없음"과 "세션 폴더에 manifest.json"을 함께 지키려면 경로를 바꿔야 한다.

- **A안 (채택)**: 모든 산출물을 `Documents/QAEvidence/{세션시작시각}/` 한 폴더에 저장한다.
  PNG도 이미지로 색인되므로 갤러리 앱에 앨범으로 보인다. zip은 `Documents/QAEvidence/{세션}.zip`.
- B안(미채택): PNG는 사양대로 `Pictures/QAEvidence/{세션}/`, manifest.json은 `Documents/QAEvidence/{세션}/` 로 나눈다(폴더가 두 곳). zip에는 둘 다 넣는다.

**A안으로 확정**했다. 경로는 `storage/EvidenceStore.kt` 의 상수 하나로 관리한다.

### 0-2. 사양을 보완한 부분 (승인됨)

| 항목 | 내용 | 이유 |
|---|---|---|
| 진입 캡처 지연 | 화면 진입 후 **500ms** 동안 다른 창 이벤트가 없으면 1장 | 전환 애니메이션 중간 프레임을 피함 |
| 겹침 보장 캡처 | 스크롤이 멈추지 않아도 누적 `scrollDeltaY`가 화면 높이의 **50%**를 넘으면 1장 추가 | 길게 끄는 스크롤에서 프레임 사이 겹침이 사라져 내용이 잘리는 것을 줄임 |
| 가로 스크롤 무시 | `scrollDeltaX≠0 && scrollDeltaY==0` 이벤트는 무시 | 자동 롤링 배너가 캡처를 계속 유발하는 것 방지 |
| 창 ID 매핑 | `windowId → 화면 식별자` 를 기억. 같은 창에서 온 다른 className(Drawer, DecorView 등)은 같은 화면으로 취급. 스크롤 이벤트의 창이 현재 화면과 다르면 그 화면으로 전환 | 다이얼로그/팝업이 닫힌 뒤 원래 화면으로 돌아온 것을 인식. 이를 위해 `canRetrieveWindowContent=true` 필요(사용자 권한은 그대로 접근성 1개) |
| 최대 높이 | 결과 1장이 **30,000px** 를 넘으면 프레임 경계에서 `_part2` 로 분리 | 뷰어 호환성 |
| 비정상 종료 복구 | 임시 프레임이 남아 있으면 서비스 재시작 시 스티칭해 저장 | 증적 누락 방지 |
| PNG 직접 인코딩 | 결과 PNG는 순수 Kotlin 스트리밍 인코더로 행 단위로 기록 | 긴 이미지도 메모리는 프레임 1~2장분만 사용 |
| 공유 | zip을 MediaStore에 저장하고 그 URI로 공유 | FileProvider(AndroidX) 없이 공유 가능 |

---

## 1. 폴더 구조

```
.
├── DESIGN.md
├── README.md
├── .gitignore                      # *.jks, *.keystore, local.properties, build/ 등
├── .github/workflows/release.yml   # 테스트 → release APK → GitHub Release "latest"
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat, gradle/wrapper/*
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/qa/autocapture/
        │   │   ├── MainActivity.kt
        │   │   ├── capture/CaptureService.kt
        │   │   ├── session/SessionRecorder.kt
        │   │   ├── stitch/Stitcher.kt
        │   │   ├── stitch/PngWriter.kt
        │   │   ├── storage/EvidenceStore.kt
        │   │   └── overlay/FloatingButton.kt
        │   └── res/
        │       ├── drawable/ic_launcher.xml        # 벡터 아이콘
        │       ├── values/strings.xml
        │       └── xml/accessibility_service_config.xml
        └── test/java/com/qa/autocapture/stitch/
            ├── StitcherTest.kt
            └── PngWriterTest.kt
```

## 2. 파일 목록과 역할

| 파일 | 계층 | 역할 |
|---|---|---|
| `capture/CaptureService.kt` | Android | 접근성 서비스. 창/스크롤 이벤트 수신, 무시 대상 필터, 화면 식별, 300ms 디바운스, 333ms 간격 캡처 큐, 캡처 전후 오버레이 숨김/복원, 세션 시작/정지 |
| `session/SessionRecorder.kt` | Android | 세션 1개의 상태. 화면 구간 열기/닫기, 프레임을 임시 PNG로 기록(프레임 스레드), 구간 종료 시 스티칭→저장(스티칭 스레드), 순번 부여, manifest 갱신, 비정상 종료 복구 |
| `stitch/Stitcher.kt` | **순수 Kotlin** | 행 해시 계산, 프레임 간 오프셋 투표, 중복 제거, 분리 판단, 고정 영역 검출, 행 단위 합성 계획(Plan) 생성 |
| `stitch/PngWriter.kt` | **순수 Kotlin** | 스트리밍 PNG 인코더(`java.util.zip`만 사용) + Plan을 PNG로 렌더링 |
| `storage/EvidenceStore.kt` | Android | 임시 프레임 파일 경로/읽기, MediaStore 저장(PNG, manifest.json, zip), 세션 목록(내부 session.json), 공유 인텐트 |
| `overlay/FloatingButton.kt` | Android UI | `TYPE_ACCESSIBILITY_OVERLAY` 플로팅 버튼. 탭=시작/정지, 길게=앱 열기, 드래그 이동, 저장된 화면 수 표시, 캡처용 숨김/복원, 시스템바 높이 제공 |
| `MainActivity.kt` | Android UI | 서비스 활성 여부, 접근성 설정/앱 정보 이동 버튼, "제한된 설정 허용" 안내, 세션 목록(날짜·화면 수)과 공유 버튼. 레이아웃 XML 없이 코드로 구성 |
| `StitcherTest.kt`, `PngWriterTest.kt` | JVM 테스트 | 합성 이미지로 원본 복원 검증, PNG 왕복 검증(`javax.imageio`) |

인터페이스·DI 없음. 계층 간 호출은 구체 클래스 직접 호출.

## 3. 이벤트 흐름

### 3-1. 화면 식별

```
이벤트 수신(TYPE_WINDOW_STATE_CHANGED | TYPE_VIEW_SCROLLED)
  └─ packageName ∈ 무시 목록 → 버림
       무시 목록 = 이 앱, 홈 런처(HOME 인텐트로 조회), com.android.systemui,
                  활성 IME 패키지(InputMethodManager), 삼성 엣지패널·스마트캡처
  └─ WINDOW_STATE_CHANGED(pkg, cls, windowId)
       key = windowKeys[windowId] 가 있으면 그것, 없으면 (pkg, cls) 로 새로 등록
       (같은 창에서 온 Drawer/DecorView 등 다른 className 은 같은 화면으로 본다)
  └─ VIEW_SCROLLED(windowId)
       key = windowKeys[windowId] (모르는 창이면 현재 화면 패키지와 같을 때만 현재 화면으로 간주)
화면 식별자 = packageName + className. 세션 중이 아니어도 마지막 화면은 기억해 둔다(세션 시작 직후 사용).
```

### 3-2. 진입 → 스크롤 → 이탈 → 스티칭

```
[세션 시작] 플로팅 버튼 탭
   SessionRecorder 생성(세션ID = yyyyMMdd_HHmmss), 내부 session.json 기록
   마지막으로 본 화면이 지금 활성 창 패키지와 같으면 그 화면으로 즉시 진입

[진입] key ≠ 현재 화면
   이전 화면 구간 닫기(아래 [이탈])
   새 구간 열기: 임시 폴더 noBackupFiles/sessions/{세션}/frames/{구간ID}/, screen.json(pkg, cls, 진입시각)
   500ms 진입 타이머 → 만료 시 캡처 요청 (그 사이 창 이벤트가 또 오면 타이머 재시작)

[스크롤] VIEW_SCROLLED (현재 화면)
   가로 전용 스크롤이면 무시
   누적 |scrollDeltaY| ≥ 화면높이×0.5 → 즉시 캡처 요청, 누적 0
   300ms 디바운스 타이머 재시작 → 만료(스크롤 멈춤) 시 캡처 요청, 누적 0

[캡처 큐] (메인 스레드, 요청은 1건으로 합쳐짐)
   요청 있음 && 진행 중 아님 →
     오버레이 숨김(창 alpha=0 + View INVISIBLE)
     실행시각 = max(지금+80ms, 마지막촬영+350ms)   ← 333ms 제한 + 여유
     takeScreenshot(DEFAULT_DISPLAY)
       성공: 오버레이 복원 → 요청 당시 구간이 아직 현재 구간이면 프레임 전달, 아니면 버퍼 폐기
       실패: 오버레이 복원 → INTERVAL_TIME_SHORT 이면 재요청, 그 외는 이번 1장 포기
     대기 중 요청이 또 있으면 다음 촬영 예약

[프레임 기록] (프레임 스레드)
   HardwareBuffer → Bitmap.wrapHardwareBuffer → ARGB_8888 복사 → 버퍼 close, 하드웨어 Bitmap recycle
   → frames/{구간ID}/{번호3자리}.png 로 저장 → 복사본 recycle   (메모리에 프레임을 쌓지 않음)

[이탈] 다른 화면 진입 / 세션 정지 / 서비스 종료
   프레임 스레드에 "구간 닫기" 표식 → 그 이전 프레임 기록이 끝난 뒤 스티칭 스레드로 넘김

[스티칭·저장] (스티칭 스레드, 화면 단위 순차)
   프레임 0장 → 폴더 삭제, 저장 안 함(순번 소비 안 함)
   각 프레임 디코드(재사용 Bitmap/IntArray) → 행 해시 → Stitcher.plan()
   순번 = 다음 번호, 파일명 결정
   part 마다 MediaStore 항목(IS_PENDING=1) 생성 → PngWriter 로 행 단위 렌더링 → IS_PENDING=0
   manifest 항목 추가 → 내부 session.json + 공개 manifest.json 갱신
   임시 프레임 폴더 삭제 → 메인 스레드에 저장 화면 수 알림 → 플로팅 버튼 숫자 갱신

[세션 정지] 플로팅 버튼 탭 / 서비스 꺼짐
   현재 구간 이탈 처리 → 모든 저장 완료 후 endedAt 기록, manifest 최종 저장, "저장 완료" 토스트

[복구] 서비스 연결 시
   ended=false 인 세션의 frames/* 가 남아 있으면 같은 절차로 저장(이탈시각 = 마지막 프레임 파일 시각), ended=true
```

같은 화면을 다시 방문하면 새 구간 = 새 순번이다(A→B→A = 파일 3개, 시간순).
홈 화면/알림창/키보드는 무시 대상이라 화면을 바꾸지 않는다(A→홈→A 는 같은 구간이 이어짐).

## 4. 스티칭 알고리즘

모든 판단은 **행 해시(LongArray)** 로 하고, 픽셀은 마지막 렌더링 때만 다시 읽는다.

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

결과 상태: part 2개 이상 = `split`, 실제 사용 프레임 1장 = `single`, 그 외 = `ok`.

## 5. 저장

- 폴더: `Documents/QAEvidence/{yyyyMMdd_HHmmss}/` (0-1 A안)
- 파일: `{순번3자리}_{패키지 끝부분}_{화면클래스 짧은이름}_{HHmmss}.png`, 분리 시 `…_part2.png`, `…_part3.png`
  - 예: `003_settings_SubSettings_103512.png`
  - 클래스 짧은이름 = 마지막 `.` 뒤, 영문/숫자 외 문자는 `_`, 최대 40자. HHmmss = 화면 진입 시각
- `manifest.json` (화면 저장 때마다 덮어씀)

```json
{
  "session": "20261015_103000",
  "startedAt": "2026-10-15T10:30:00+09:00",
  "endedAt": "2026-10-15T10:42:10+09:00",
  "device": { "model": "SM-G991N", "android": "15", "sdk": 35 },
  "app": { "versionName": "1.0.12", "versionCode": 12 },
  "screens": [
    {
      "seq": 1,
      "files": ["001_settings_Settings_103001.png"],
      "packageName": "com.android.settings",
      "className": "com.android.settings.Settings",
      "enteredAt": "2026-10-15T10:30:01+09:00",
      "exitedAt": "2026-10-15T10:30:40+09:00",
      "frames": 7,
      "stitch": "ok"
    }
  ]
}
```

- 내부 `noBackupFiles/sessions/{세션}/session.json`: 위 manifest + `ended` 플래그. 메인 화면 목록·복구에 사용.
- 공유: 메인 화면 공유 버튼 → `Documents/QAEvidence/{세션}.zip` 생성(기존 것은 교체) → `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`.
  zip 내용은 MediaStore에서 그 세션 폴더(RELATIVE_PATH)의 내 파일을 조회해 담는다.

## 6. 앱 메인 화면 / 플로팅 버튼

- 메인: 서비스 상태(켜짐/꺼짐), 꺼져 있으면 [접근성 설정 열기], "제한된 설정 허용" 안내 + [앱 정보 열기], 세션 목록(날짜·화면 수·진행 중 표시) + [공유].
- 플로팅 버튼: 대기 `● 시작`(회색), 녹화 중 `■ {저장된 화면 수}`(빨강). 탭=시작/정지, 길게 누르기=메인 화면, 드래그=이동.
- 접근성 바로가기: 서비스가 `FLAG_REQUEST_ACCESSIBILITY_BUTTON` 을 요청하지 않으므로, 바로가기(음량 키 길게/접근성 버튼)는 서비스 자체를 켜고 끈다. 끄면 진행 중 세션은 정지·저장된다.

## 7. 권한

- 사용자가 켜는 권한: **접근성 서비스 1개**뿐.
- Manifest 권한 선언 없음. `<queries>` 로 HOME 인텐트와 IME 인텐트만 조회(권한 아님).
- 쓰지 않음: 포그라운드 서비스, 알림, `SYSTEM_ALERT_WINDOW`, 저장소 권한, `QUERY_ALL_PACKAGES`.
- 서비스 설정: `canTakeScreenshot=true`, `canRetrieveWindowContent=true`(창 ID 획득), 이벤트 `typeWindowStateChanged|typeViewScrolled`, `notificationTimeout=0`.
- 덮어쓰기 설치 시 권한 유지 조건: 같은 서명 + 서비스 클래스 이름(`com.qa.autocapture.capture.CaptureService`) 고정.

## 8. 빌드/배포

- `app/build.gradle.kts`: `versionCode = GITHUB_RUN_NUMBER`(없으면 1), `versionName = "1.0.{run}"`.
  서명은 환경변수 `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` 가 있을 때만 적용(로컬은 미서명 빌드 가능).
- `.github/workflows/release.yml` (main push, 수동 실행):
  1. JDK 21, Gradle 캐시
  2. Secrets 4개 확인(없으면 한국어 오류로 실패)
  3. `./gradlew testDebugUnitTest`
  4. `KEYSTORE_BASE64` → `$RUNNER_TEMP/release.jks` 복원 → `./gradlew assembleRelease`
  5. `qa-capture.apk` 로 이름 변경
  6. `latest` 태그를 현재 커밋으로 이동, 릴리스가 없으면 prerelease 로 생성, `gh release upload --clobber` 로 덮어쓰기
- 다운로드 고정 주소: `https://github.com/<owner>/<repo>/releases/download/latest/qa-capture.apk`

## 9. 테스트 계획 (JVM 단위 테스트)

합성 원본 = 고정 헤더(상태바+툴바) + 긴 콘텐츠(텍스트 같은 랜덤 행, 빈 줄, 반복 구분선) + 고정 푸터(탭바+내비바).
원본을 겹치게 잘라 프레임을 만든 뒤 `plan → render → PNG → ImageIO 디코드` 결과가 기대 이미지와 **픽셀 단위로 동일**한지 본다.

| 테스트 | 기대 |
|---|---|
| 아래로 끝까지 스크롤(다양한 겹침) | 원본과 동일, `ok` |
| 같은 위치 프레임 중복 포함 | 중복 버림, 원본과 동일 |
| 아래→위→아래 왕복 | 원본과 동일 |
| 중간부터 진입해 위로 스크롤 | 위쪽에 붙어 원본과 동일 |
| 상태바 시계가 중간에 바뀜 | 첫 프레임 상태바로 원본과 동일 |
| 겹침 없는 점프 | `split`, part 별로 각 구간과 동일 |
| 프레임 1장 | `single`, 프레임과 동일 |
| 회전(크기 변경) | `split` |
| 접히는 툴바 / FAB | 헤더·FAB 가 중간에 반복되지 않음 |
| PngWriter 왕복 | 임의 이미지 인코딩 → ImageIO 디코드 동일 |

## 10. 알려진 한계

- `FLAG_SECURE` 화면, 가로 스크롤 내용, 동영상/애니메이션 최적화는 범위 밖이다.
- **단일 Activity 앱**(Fragment/Compose 내비게이션)은 화면이 바뀌어도 className 이 같아 새 순번이 생기지 않는다. 대신 내용이 이어지지 않으므로 같은 순번의 `_part2…` 로 분리 저장된다.
- **빠른 플링**: 캡처 최소 간격(333ms) 때문에 프레임 사이 이동량이 콘텐츠 영역보다 크면 겹침이 없어 분리 저장되고, 그 사이 내용은 남지 않는다.
- 자동 롤링 배너·로딩 스피너 등 같은 위치에서 크게 바뀌는 화면은 분리 저장될 수 있다.
- 스크롤바 영역(오른쪽 3%)은 비교에서 빠지므로 사라지는 중인 스크롤바 조각이 결과에 남을 수 있다.
- 오버스크롤 효과(글로우/스트레치) 도중 찍힌 프레임은 맞춤 실패 → 분리될 수 있다.
- 마지막 화면 구간에만 보이는 FAB·플로팅 요소는 결과 하단에 그대로 남는다(실제 화면과 같음).
- 다이얼로그가 닫힌 뒤 원래 화면 복귀는 그 화면에서 스크롤할 때 인식한다.
- 상태바 시계·알림 아이콘은 첫(가장 위) 프레임 것이 쓰인다.
- 키보드가 떠 있으면 하단 고정 영역이 커져 한 번에 붙는 양이 줄어든다.
- 기본 디스플레이만 캡처한다(DeX/멀티 디스플레이 제외).
- 앱을 **삭제 후 재설치**하면 이전 파일의 MediaStore 소유권이 사라져 목록/공유에서 빠진다(파일은 남음). 덮어쓰기 설치는 문제없다.
- 배터리 최적화로 접근성 서비스가 강제 종료될 수 있으므로 "제한 없음" 설정이 필요하다(README).
