# QA 모바일 자동 증적 캡처

안드로이드 수동 QA 중 테스터가 방문한 화면을 **화면마다 위에서 아래까지 이어붙인 PNG 1장**으로 자동 저장하는 앱(MVP)입니다.
테스터는 평소처럼 앱을 쓰고 스크롤만 하면 됩니다. 캡처, 분할, 이어붙이기는 앱이 알아서 합니다.

- 기준 기기: Galaxy S21 / Android 15 (One UI 7) / 1080x2400 (Android 11 이상에서 동작)
- 사용자가 켜야 하는 권한: **접근성 서비스 1개**
- 설계 문서: [DESIGN.md](DESIGN.md)

**APK 고정 다운로드 주소**

```
https://github.com/YunseobSongQA/QA-Mobile-Auto-Capture/releases/download/latest/qa-capture.apk
```

---

## 1. 설치와 초기 설정 (Galaxy S21 / Android 15 기준)

> One UI 버전에 따라 메뉴 이름이 조금 다를 수 있습니다.

### 1-1. 크롬으로 Release에서 APK 받기

1. 휴대폰 **Chrome**에서 아래 주소를 엽니다.
   `https://github.com/YunseobSongQA/QA-Mobile-Auto-Capture/releases/tag/latest`
   (위의 고정 다운로드 주소를 열면 바로 받아집니다.)
2. **Assets**를 펼치고 `qa-capture.apk`를 누릅니다.
3. "이 유형의 파일은 기기에 해를 끼칠 수 있습니다"라는 경고가 뜨면 **다운로드**를 누릅니다.
4. 다운로드가 끝나면 화면 아래 알림에서 **열기**를 누릅니다.
   알림을 놓쳤다면 **내 파일 → 다운로드**에서 `qa-capture.apk`를 누릅니다.

### 1-2. 출처를 알 수 없는 앱 설치 허용

처음 설치하면 "보안을 위해 이 출처의 알 수 없는 앱을 설치할 수 없습니다"라는 안내가 뜹니다.

1. 안내 창에서 **설정**을 누릅니다.
2. **이 출처 허용**을 켜고 뒤로 갑니다.
3. **설치**를 누릅니다.

미리 켜 두려면 **설정 → 애플리케이션 → 오른쪽 위 ⋮ → 특별한 접근 → 출처를 알 수 없는 앱 설치 → Chrome → 이 출처 허용**으로 들어갑니다.

> **자동 차단 기능**이 켜져 있으면 스토어 밖의 앱 설치가 막힙니다.
> **설정 → 보안 및 개인정보 보호 → 자동 차단 기능**에서 끈 뒤 설치하고, 필요하면 다시 켭니다.

### 1-3. Play 프로텍트 경고 처리

사내에서 직접 서명한 앱이라 Google Play 프로텍트가 경고할 수 있습니다. 새 버전을 설치할 때마다 다시 뜰 수 있습니다.

- **앱 스캔을 권장하는 창**이 뜨면 **앱 스캔**을 누르거나 **스캔하지 않고 설치**를 누릅니다.
- **"안전하지 않은 앱 차단됨" 또는 "Play 프로텍트에서 차단됨"**이 뜨면 **세부정보 더보기 → 무시하고 설치(그래도 설치)**를 누릅니다.

Play 프로텍트를 아예 끄는 방법은 권장하지 않습니다.

### 1-4. 제한된 설정 허용

Android 13 이상에서는 브라우저로 받은 앱이 접근성 서비스를 바로 켤 수 없고 "제한된 설정" 안내가 뜹니다.

1. **설정 → 접근성 → 설치된 앱 → QA 증적 캡처**를 누르고 스위치를 켜 봅니다.
   "제한된 설정" 안내가 뜨면 **확인**을 누릅니다. 이 단계를 먼저 해야 2번 메뉴가 나타납니다.
2. **설정 → 애플리케이션 → QA 증적 캡처 → 오른쪽 위 ⋮ → 제한된 설정 허용**을 누르고 본인 확인(PIN, 지문)을 합니다.
   앱 메인 화면의 **[앱 정보 열기]** 버튼으로도 이 화면에 갈 수 있습니다.
3. 다시 접근성 설정으로 가서 서비스를 켭니다(다음 절).

덮어쓰기 설치를 하면 이 설정이 유지됩니다. 앱을 **삭제한 뒤 다시 설치하면** 다시 해야 합니다.

### 1-5. 접근성 서비스 켜기와 바로가기 설정

**켜기**

1. **설정 → 접근성 → 설치된 앱 → QA 증적 캡처**에서 스위치를 켭니다.
2. 기기 전체 제어 권한을 묻는 창이 뜨면 **허용**을 누릅니다.
3. 화면 오른쪽에 회색 **[● 시작]** 버튼이 뜨면 정상입니다.

**바로가기**: 볼륨키나 접근성 버튼으로 서비스를 켜고 끕니다.

1. 같은 화면(**설정 → 접근성 → 설치된 앱 → QA 증적 캡처**)에서 **QA 증적 캡처 바로가기**를 켭니다.
2. 바로가기 방식을 고릅니다.
   - **음량 위/아래 버튼 길게 누르기**: 두 버튼을 동시에 약 3초 누르면 서비스가 켜지거나 꺼집니다. 처음 한 번은 확인 창이 뜹니다.
   - **접근성 버튼**: 화면의 접근성 버튼을 누르면 서비스가 켜지거나 꺼집니다.

바로가기로 서비스를 끄면 진행 중이던 기록이 저장되고 세션이 끝납니다.

### 1-6. 배터리 "제한 없음" 설정

배터리 최적화가 서비스를 강제로 끄지 않게 합니다.

1. **설정 → 애플리케이션 → QA 증적 캡처 → 배터리 → 제한 없음**을 고릅니다.
2. **설정 → 배터리 → 백그라운드 사용 제한**에 들어가, **절전 상태 앱**과 **초절전 상태 앱** 목록에 QA 증적 캡처가 없는지 확인합니다.

---

## 2. 사용법

1. 테스트할 앱을 엽니다.
2. 플로팅 버튼 **[● 시작]**을 누릅니다. 버튼이 빨간 **[■ 0]**으로 바뀌면 기록 중입니다.
3. 평소처럼 앱을 사용하고 스크롤합니다. 캡처는 **화면이 안정된 뒤**에 찍습니다.
   - 화면에 들어가거나 스크롤을 멈춘 뒤 **1초 동안 화면 변화가 없으면** 1장을 찍습니다.
   - 로딩 표시(ProgressBar)가 보이면 사라질 때까지, 토스트가 뜨면 3.5초 동안 기다립니다. 다만 최대 5초가 지나면 그냥 찍습니다(자동으로 넘어가는 배너 등).
   - 그래서 **화면마다 1초 이상 머물고, 화면 절반 정도씩 스크롤한 뒤 1초 멈추는** 식으로 쓰면 결과가 좋습니다.
     멈추지 않고 계속 스크롤하면 5초마다 1장만 찍혀서 겹치는 부분이 없으면 `_part2`로 나뉘어 저장됩니다.
   - 캡처하는 순간에는 플로팅 버튼이 잠깐 사라집니다. 그래서 결과 이미지에 버튼이 찍히지 않습니다.
4. 다른 화면으로 이동하면 직전 화면이 1장으로 저장되고 버튼 숫자가 올라갑니다.
   같은 화면을 다시 방문하면 새 순번으로 따로 저장됩니다. A→B→A로 이동하면 파일 3개가 생깁니다.
5. 버튼을 다시 누르면 정지합니다. "QA 기록 저장 완료" 메시지가 뜹니다.
6. 버튼을 **길게 누르면** 앱 메인 화면이 열립니다. 세션 목록에서 **[공유]**를 누르면 세션 폴더를 zip으로 묶어 공유합니다.

- 버튼은 끌어서 옮길 수 있습니다.
- 홈 화면, 알림창, 키보드, 이 앱 자신은 기록하지 않습니다.
- 토스트 문구는 시각과 함께 `manifest.json`의 `toasts`에 기록됩니다.

### 옵션 (앱 메인 화면, 둘 다 기본 끔)

- **토스트 뜬 순간도 별도 저장**: 토스트가 뜨면 그 순간 화면을 1장 더 저장합니다. 파일명 끝에 `_toast`가 붙습니다.
  기본 화면 캡처는 그대로 토스트가 사라진 뒤에 찍습니다.
- **디버그 로그**: 기록 중 받은 모든 접근성 이벤트(시각, 이벤트 타입, 패키지, 클래스, 텍스트)를
  `Documents/QAEvidence/debug/{세션}.txt`로 저장합니다. 대상 앱의 토스트와 로딩이 어떤 이벤트로 오는지 확인하는 용도입니다.
  앱이 언제 왜 캡처했는지(`#CAPTURE`, `#WAIT`, `#PROGRESS`)도 같이 남습니다. 다음 기록 시작부터 적용되고, 켜 두면 배터리를 더 씁니다.

### 결과물

```
내장 저장공간/Documents/QAEvidence/
├── 20261015_103000/                         ← 세션 시작 시각
│   ├── 001_settings_Settings_103001.png
│   ├── 002_settings_SubSettings_103045.png
│   ├── 003_settings_Settings_103120.png
│   ├── 004_chrome_ChromeTabbedActivity_103200.png
│   ├── 004_chrome_ChromeTabbedActivity_103200_part2.png   ← 이어붙이지 못해 나뉜 부분
│   ├── 005_settings_SubSettings_103300.png
│   ├── 005_settings_SubSettings_103300_toast.png          ← "토스트 뜬 순간도 별도 저장" 옵션
│   └── manifest.json
├── 20261015_103000.zip                      ← [공유]를 누르면 생성 (디버그 로그가 있으면 debug_log.txt 포함)
└── debug/
    └── 20261015_103000.txt                  ← "디버그 로그" 옵션
```

- 파일 이름: `{순번3자리}_{앱 패키지 끝부분}_{화면 클래스 짧은 이름}_{진입 시각 HHmmss}.png`
- `manifest.json`: 화면별 순번, 파일명, packageName, className, 진입/이탈 시각, 프레임 수, 스티칭 결과와 토스트 기록(`toasts`)을 담습니다.
  스티칭 결과 값은 `ok`(정상 합성), `split`(여러 장으로 나뉨), `single`(프레임 1장), `none`(토스트 순간 사진만 있음) 중 하나입니다.
- 사양에는 `Pictures/`로 되어 있었지만, 저장소 권한 없이는 `Pictures/`에 manifest.json을 만들 수 없어서 `Documents/`에 저장합니다([DESIGN.md](DESIGN.md) 0-1). PNG는 갤러리 앱에서도 보입니다.

### 업데이트

`main`에 push하면 새 APK가 같은 주소에 올라갑니다. 받아서 **덮어 설치**하면 됩니다. 접근성 권한, 제한된 설정 허용, 세션 목록은 그대로 유지됩니다.
항상 같은 서명 키로 빌드하므로 앱을 삭제할 필요가 없습니다.

### 실기기 확인 체크리스트

- [ ] 설정 앱의 긴 목록을 끝까지 스크롤하면 중복이나 잘림 없이 긴 PNG 1장이 생긴다.
- [ ] 화면 A→B→A로 이동하면 파일 3개가 순서대로 생긴다.
- [ ] 상단 헤더와 하단 탭바가 결과 이미지에 한 번씩만 나온다.
- [ ] 플로팅 버튼이 결과 이미지에 찍히지 않는다.
- [ ] 새 버전 APK를 덮어 설치해도 접근성 권한이 유지된다.
- [ ] 30분 연속 사용해도 앱이 죽지 않는다.

---

## 3. 개발

### 로컬 빌드 (GitHub Codespaces)

Android Studio 없이 Gradle CLI로 빌드합니다. JDK 17 이상이 필요하고, CI는 JDK 21을 씁니다.
Android SDK(platform 35, build-tools 35)의 위치는 `local.properties`의 `sdk.dir` 또는 `ANDROID_HOME`으로 지정합니다.

```bash
./gradlew testDebugUnitTest   # stitch·안정화 판단 단위테스트 (JVM)
./gradlew assembleRelease     # 서명 환경변수가 없으면 app-release-unsigned.apk
```

로컬에서 서명된 APK를 만들려면 환경변수 `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`를 지정합니다.

### 서명 키 준비 (최초 1회)

항상 같은 키로 서명해야 덮어쓰기 설치와 접근성 권한 유지가 됩니다. **키스토어 파일은 절대 커밋하지 않습니다.**
`.gitignore`에 `*.jks`, `*.keystore`가 들어 있지만, 실수를 막기 위해 저장소 밖(홈 폴더)에서 만듭니다.

**1) 키스토어 생성** (Codespaces 터미널)

```bash
cd ~
keytool -genkeypair -v \
  -keystore qa-capture-release.jks -storetype PKCS12 \
  -alias qa-capture -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=QA Auto Capture"
```

비밀번호를 두 번 입력합니다. PKCS12 형식에서는 **키 비밀번호가 키스토어 비밀번호와 같습니다.**

**2) base64로 변환**

```bash
base64 -w 0 ~/qa-capture-release.jks > ~/qa-capture-release.jks.b64
```

**3) GitHub Secrets 등록**

저장소 페이지 → **Settings → Secrets and variables → Actions → New repository secret**에서 4개를 등록합니다.

| 이름 | 값 |
|---|---|
| `KEYSTORE_BASE64` | `qa-capture-release.jks.b64` 파일 내용 전체 (`cat ~/qa-capture-release.jks.b64`) |
| `KEYSTORE_PASSWORD` | 1)에서 정한 비밀번호 |
| `KEY_ALIAS` | `qa-capture` |
| `KEY_PASSWORD` | 1)에서 정한 비밀번호 (키스토어 비밀번호와 동일) |

`gh` CLI에 저장소 Secrets 권한이 있으면 터미널에서도 등록할 수 있습니다.

```bash
gh secret set KEYSTORE_BASE64 < ~/qa-capture-release.jks.b64
gh secret set KEYSTORE_PASSWORD   # 입력 프롬프트에 비밀번호 붙여넣기
gh secret set KEY_ALIAS --body qa-capture
gh secret set KEY_PASSWORD
```

**4) 백업 후 삭제**

Codespace를 삭제하면 파일도 사라집니다. `qa-capture-release.jks`(VS Code 탐색기에서 우클릭 → 다운로드)와 비밀번호를 안전한 곳(비밀번호 관리자 등)에 보관하세요. 그다음 Codespace에서는 지웁니다.

```bash
rm ~/qa-capture-release.jks ~/qa-capture-release.jks.b64
```

> 키를 잃어버리면 새 키로 서명한 APK는 덮어 설치할 수 없습니다. 앱을 삭제하고 다시 설치해야 하고, 접근성 권한과 제한된 설정 허용을 다시 해야 합니다. 이전 세션도 목록과 공유에서 빠집니다(파일은 남습니다).

### 배포 (GitHub Actions)

`.github/workflows/release.yml`은 `main`에 push하거나 Actions 탭에서 수동 실행하면 아래 순서로 동작합니다.

1. 서명 Secrets 4개 확인. 하나라도 없으면 실패하고 이 절을 안내합니다.
2. 단위테스트 `./gradlew testDebugUnitTest`
3. 키스토어 복원 → `./gradlew assembleRelease` → `qa-capture.apk`로 이름 변경
4. `latest` 태그를 현재 커밋으로 옮기고, GitHub Release `latest`(prerelease)에 `qa-capture.apk`를 덮어쓰기로 업로드

versionCode는 GitHub run number로 자동 증가합니다. versionName은 `1.0.{run number}`입니다.

### 폴더 구조

```
app/src/main/java/com/qa/autocapture/
├── MainActivity.kt              # 메인 화면: 서비스 상태, 설정 이동, 세션 목록·공유
├── capture/CaptureService.kt    # 접근성 서비스: 화면 전환·스크롤·토스트 감지, 캡처 큐, 디버그 로그
├── capture/Stabilizer.kt        # 순수 로직: 화면 안정화 판단, 타이밍 상수(CaptureTiming)
├── session/SessionRecorder.kt   # 세션 기록: 임시 프레임 저장, 화면 단위 스티칭·저장
├── stitch/Stitcher.kt           # 순수 로직: 오프셋 계산, 고정 영역, 합성 계획
├── stitch/PngWriter.kt          # 순수 로직: 행 단위 스트리밍 PNG 인코더
├── storage/EvidenceStore.kt     # 저장: 임시 파일, MediaStore, manifest, zip
└── overlay/FloatingButton.kt    # 플로팅 버튼 (TYPE_ACCESSIBILITY_OVERLAY)
```

자세한 이벤트 흐름과 스티칭 알고리즘은 [DESIGN.md](DESIGN.md)에 있습니다.

## 4. 권한

- 사용자가 켜는 권한은 **접근성 서비스 1개**뿐입니다.
- 포그라운드 서비스, 알림, 다른 앱 위에 표시(오버레이), 저장소 권한을 쓰지 않습니다.

## 5. 알려진 한계

- `FLAG_SECURE` 화면(은행 앱 등), 가로 스크롤, 동영상과 애니메이션이 많은 화면은 지원 범위 밖입니다.
- 한 Activity 안에서 화면이 바뀌는 앱(Fragment, Compose 내비게이션)은 새 순번이 생기지 않습니다. 대신 같은 순번의 `_part2…`로 나뉘어 저장됩니다.
- 스크롤 후 1초 멈추지 않고 계속 스크롤하거나 빠르게 튕기면, 프레임 사이에 겹침이 없어 `_part2`로 나뉘고 그 사이 내용은 남지 않습니다.
- 화면에 1초도 머물지 않고 넘어가면 그 화면은 저장되지 않습니다.
- 로딩이 아닌 진행률 막대(저장공간 사용량 등)나 계속 바뀌는 내용(시계, 동영상)이 있는 화면은 캡처마다 5초씩 기다립니다.
- 토스트는 기본 Toast만 감지합니다. Snackbar 등은 화면 변화가 멈출 때까지 기다리는 방식으로만 피합니다.
- 자동으로 넘어가는 배너가 있는 화면은 나뉘어 저장될 수 있습니다.
- 나머지는 [DESIGN.md](DESIGN.md) 10장을 참고하세요.
