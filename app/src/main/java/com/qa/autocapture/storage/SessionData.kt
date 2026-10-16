package com.qa.autocapture.storage

/** 세션 1개의 기록. 내부 session.json 과 공개 manifest.json 의 원본(SessionStore 가 읽고 쓴다). */
class SessionData(val id: String, val startedAt: Long) {
    var endedAt: Long? = null
    var ended = false
    val screens = mutableListOf<ScreenRecord>()
    val toasts = mutableListOf<ToastRecord>()

    /** 저장한 이미지 수(캡처 + 이전 버전 스크롤 이미지). */
    val imageCount: Int get() = screens.sumOf { it.captures.size + it.scroll.size }
}

/** 화면 방문 1번(manifest 의 screens 항목). 그 방문에서 첫 화면을 확보할 때 만든다. */
class ScreenRecord(val seq: Int, val packageName: String, val className: String, val enteredAt: Long) {
    var exitedAt: Long? = null
    val captures = mutableListOf<CaptureRecord>()

    /** 이전 버전의 스크롤 이미지 파일(…_scroll.png, …_full.png). 지금은 captures 에 함께 담는다. */
    var scroll: List<String> = emptyList()
}

/** 저장한 이미지 1장(manifest 의 captures 항목). [at] = 첫 화면을 찍은 시각(epoch millis), [frames] = 이어붙인 화면 수. */
class CaptureRecord(val file: String, val at: Long, val frames: Int)

/** 세션 중 감지한 토스트(manifest 의 toasts 항목). [screen] = 그때 화면의 className. */
class ToastRecord(val at: Long, val packageName: String, val text: String, val screen: String?)
