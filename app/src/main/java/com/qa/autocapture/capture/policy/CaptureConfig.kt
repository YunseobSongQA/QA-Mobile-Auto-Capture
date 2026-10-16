package com.qa.autocapture.capture.policy

/**
 * 캡처 관련 상수. 값을 바꿀 때는 여기만 고친다.
 * SETTLE_MS·MAX_WAIT_MS 는 기본값이고, 설정 화면에서 바꾼 값(Options)이 우선한다.
 */
object CaptureConfig {
    /** 화면 이벤트가 이만큼 없으면 "조용해짐"으로 보고 정지 확인을 시작한다. */
    const val SETTLE_MS = 1000L

    /**
     * 이벤트가 계속 와도(배너·타이머·로딩 표시가 이벤트를 쉬지 않고 보내는 앱) 시작 후 SETTLE_MS + 이만큼 지나면
     * 더 기다리지 않고 화면 비교만으로 완성 여부를 판단한다.
     */
    const val QUIET_WAIT_EXTRA_MS = 1000L

    /** 정지 확인: 이 간격을 두고 찍은 두 화면이 같아야 "정적으로 완성됨"으로 본다. */
    const val CONFIRM_MS = 500L

    /** 터치·화면 진입 뒤 계속 움직여(동영상, 애니메이션) 정지하지 않아도 이만큼 지나면 그 시점 화면을 찍는다. */
    const val MAX_WAIT_MS = 8000L

    /** 설정 화면에서 고를 수 있는 범위와 간격. 두 범위가 겹치지 않아 항상 SETTLE ≤ MAX_WAIT 이다. */
    const val SETTLE_MIN_MS = 500L
    const val SETTLE_MAX_MS = 3000L
    const val SETTLE_STEP_MS = 100L
    const val MAX_WAIT_MIN_MS = 3000L
    const val MAX_WAIT_MAX_MS = 30_000L
    const val MAX_WAIT_STEP_MS = 500L

    /**
     * 화면에 들어온 뒤 이만큼 조용하면 미리 1장을 확보한다(곧바로 스크롤해도 화면 맨 위가 빠지지 않게).
     * 정지 확인으로 완성된 화면을 찍으면 이 1장을 대신한다.
     */
    const val ENTRY_PREVIEW_MS = 400L

    /** 화면에 들어온 뒤 이벤트가 계속 와도 이만큼 지나면 미리 1장을 확보한다. */
    const val ENTRY_PREVIEW_MAX_MS = 1000L

    /** 앱이 스스로 바뀐 경우(터치 없음) 이 비율 이상 바뀌어야 새 화면으로 보고 찍는다(배너·시계 무시). */
    const val PAGE_CHANGE_RATIO = 0.3

    /** 앱이 스스로 바뀐 것을 확인해 보고 찍을 게 없었으면 이만큼은 다시 확인하지 않는다. */
    const val CHANGE_COOLDOWN_MS = 3000L

    /** takeScreenshot 최소 간격(시스템 제한 333ms + 여유). */
    const val MIN_SHOT_INTERVAL_MS = 350L

    /** 오버레이를 숨긴 뒤 화면에 반영될 때까지 기다리는 시간. */
    const val HIDE_SETTLE_MS = 80L

    /** 저장을 기다리는 화면이 이만큼 쌓이면 다음 촬영을 잠시 미룬다(메모리 보호). */
    const val MAX_PENDING_SAVES = 3

    /** 저장이 밀려 촬영을 미뤘을 때 다시 확인하는 간격. */
    const val BACKLOG_RETRY_MS = 100L

    // ── 화면 비교(축소 이미지) ────────────────────────────────────

    /** 비교용 축소 이미지의 가로 칸 수(폭 1080px 이면 칸 1개 = 15x15px). */
    const val THUMB_COLUMNS = 72

    /** 칸 평균색이 채널(R/G/B) 중 하나라도 이 값보다 크게 다르면 "바뀐 칸"으로 센다. */
    const val CELL_DIFF = 24

    /** 바뀐 칸이 이 비율 이하이면 "정지"(깜빡이는 커서 정도는 허용, 돌아가는 로딩 표시는 정지 아님). */
    const val STATIC_MAX_CHANGED_RATIO = 0.002

    /** 바뀐 칸이 이 비율 이하이면 "작은 변화": 새 파일을 만들지 않고 그 화면 파일을 최신 상태로 바꾼다. */
    const val SIMILAR_MAX_CHANGED_RATIO = 0.01

    /** 버튼이 보이는 채로 찍은 비교 화면에서 버튼 둘레로 더 빼는 여백(px, 그림자·경계). */
    const val OVERLAY_MASK_MARGIN_PX = 12

    // ── 스크롤 이어붙이기 ─────────────────────────────────────────

    /** 스크롤 이벤트와 내용 변경이 이만큼 없으면 "스크롤이 멈춤"으로 보고 이어붙일 프레임 1장을 찍는다. */
    const val SCROLL_SETTLE_MS = 400L

    /** 스크롤이 멈춘 뒤 내용 변경이 계속돼도(이미지 로딩, 배너) 마지막 스크롤 이벤트 후 이만큼 지나면 찍는다. */
    const val SCROLL_MAX_WAIT_MS = 1500L

    /**
     * 스크롤하는 동안에도 이동량(scrollDeltaY 합)이 화면 높이의 이 비율을 넘을 때마다 1장을 찍는다.
     * 한 번에 화면 높이보다 많이 넘겨도 프레임끼리 겹치게 해서 한 장으로 이어붙이기 위함이다.
     */
    const val SCROLL_STEP_RATIO = 0.35

    /** 스크롤한 영역의 높이가 화면 높이의 이 비율보다 작으면(뉴스 티커, 작은 목록) 이어붙이지 않고 내용 변경으로 본다. */
    const val SCROLL_MIN_HEIGHT_RATIO = 0.3

    /** 이동량을 알려 주지 않는 스크롤(일부 앱·웹뷰)은 스크롤하는 동안 이 간격마다 1장. */
    const val SCROLL_STEP_MS = 450L

    /** 이어붙인 이미지 1장의 최대 높이(px). 넘으면 프레임 경계에서 다음 장으로 나눈다. */
    const val MAX_STITCH_HEIGHT = 20_000

    /** 행 비교에서 뺄 오른쪽 스크롤바 자리(폭 대비 비율). */
    const val STITCH_EDGE_MARGIN_RATIO = 0.03

    /** 행 해시를 만들 때 가로 샘플링 간격(px). */
    const val STITCH_SAMPLE_STEP = 2

    /** 행 비교로 찾은 겹침을 인정하기 위한 최소 득표 수(검증에 쓰는 최소 행 수도 같음). */
    const val STITCH_MIN_VOTES = 10

    /** 행 비교로 찾은 겹침 검증: 겹치는 구간에서 움직인 행 중 맞아야 하는 최소 비율. */
    const val STITCH_MIN_MATCH_RATIO = 0.5

    /** scrollDeltaY 로 받은 겹침 검증: 겹치는 구간에서 움직인 행 중 맞아야 하는 최소 비율(독립 근거라 더 엄격하게). */
    const val STITCH_HINT_MIN_MATCH_RATIO = 0.8

    /** 겹침을 못 찾았어도 바뀐 행이 이 비율 이하이면 같은 위치(시계·커서 변화)로 보고 버린다. */
    const val STITCH_SAME_MAX_CHANGED_RATIO = 0.15

    /** 겹침을 끝내 못 찾은 곳(너무 빠른 스크롤)은 파일을 나누지 않고 이 높이(px)의 구분선을 넣어 한 장으로 잇는다. */
    const val STITCH_GAP_HEIGHT = 48
}
