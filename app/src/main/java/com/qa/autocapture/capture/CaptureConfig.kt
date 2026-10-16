package com.qa.autocapture.capture

/**
 * 캡처 관련 상수. 값을 바꿀 때는 여기만 고친다.
 * ENTRY_SETTLE_MS 는 기본값이고, 설정 화면에서 바꾼 값(Options)이 우선한다.
 */
object CaptureConfig {

    // ── 찍는 때 ──────────────────────────────────────────────────

    /** 화면에 들어간 뒤 창 이벤트가 이만큼 잠잠하면(전환 애니메이션이 끝나면) 1장 찍는다. */
    const val ENTRY_SETTLE_MS = 500L

    /** 설정 화면에서 고를 수 있는 진입 대기 범위와 간격. */
    const val ENTRY_SETTLE_MIN_MS = 300L
    const val ENTRY_SETTLE_MAX_MS = 3000L
    const val ENTRY_SETTLE_STEP_MS = 100L

    /** 창 이벤트가 계속 오거나 토스트·로딩 표시가 안 사라져도 화면에 들어간 뒤 이만큼 지나면 그 화면을 찍는다. */
    const val ENTRY_MAX_WAIT_MS = 5000L

    /** 스크롤 이벤트가 이만큼 없으면 "스크롤이 멈춤"으로 보고 1장 찍는다. */
    const val SCROLL_DEBOUNCE_MS = 300L

    /** 멈추지 않고 길게 스크롤해도 프레임끼리 겹치도록, 이동량이 화면 높이의 이 비율을 넘을 때마다 1장. */
    const val SCROLL_STEP_RATIO = 0.5

    /** 이동량을 알려 주지 않는 스크롤(일부 앱·웹뷰)은 스크롤하는 동안 이 간격마다 1장. */
    const val SCROLL_STEP_MS = 450L

    /** 스크롤한 영역의 높이가 화면 높이의 이 비율보다 작으면(뉴스 티커 등) 이어붙일 스크롤로 보지 않는다. */
    const val SCROLL_MIN_HEIGHT_RATIO = 0.25

    // ── 걸러 낼 화면(토스트·로딩) — 화면 진입 사진에만 적용 ─────────────

    /** 토스트가 뜬 뒤 이만큼은 화면에 남아 있다고 보고 진입 사진을 미룬다(LENGTH_LONG 3.5초 + 사라지는 시간). */
    const val TOAST_VISIBLE_MS = 4000L

    /** 이 시각보다 조금 전에 뜬 토스트도 그 순간 찍은 화면에 들어 있을 수 있다(이벤트 지연). */
    const val TOAST_EVENT_SLACK_MS = 300L

    /** 화면에 돌아가는 로딩 표시(ProgressBar)가 있으면 이 간격으로 다시 확인한다. */
    const val LOADING_RECHECK_MS = 300L

    /** 로딩 표시를 찾을 때 훑는 화면 요소 수 상한(메인 스레드 보호). */
    const val LOADING_SCAN_MAX_NODES = 400

    // ── 촬영 ────────────────────────────────────────────────────

    /** takeScreenshot 최소 간격(시스템 제한 333ms + 여유). */
    const val MIN_SHOT_INTERVAL_MS = 350L

    /** 오버레이를 숨긴 뒤 화면에 반영될 때까지 기다리는 시간. */
    const val HIDE_SETTLE_MS = 80L

    /** 저장을 기다리는 화면이 이만큼 쌓이면 다음 촬영을 잠시 미룬다(메모리 보호). */
    const val MAX_PENDING_SAVES = 3

    /** 저장이 밀려 촬영을 미뤘을 때 다시 확인하는 간격. */
    const val BACKLOG_RETRY_MS = 100L

    // ── 스크롤 이어붙이기 ─────────────────────────────────────────

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
}
