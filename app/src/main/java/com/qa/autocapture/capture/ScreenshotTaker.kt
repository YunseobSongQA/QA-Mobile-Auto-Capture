package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.qa.autocapture.capture.policy.CaptureConfig
import com.qa.autocapture.capture.policy.FrameDiff
import com.qa.autocapture.overlay.FloatingButton
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 촬영 큐. 한 번에 1장만, takeScreenshot 최소 간격(MIN_SHOT_INTERVAL_MS)을 지켜 찍는다. 메인 스레드 전용.
 *
 * - 비교용(PROBE)은 플로팅 버튼을 그대로 두고 찍는다. 그 외(저장될 수 있는 화면)는 버튼을 숨기고 HIDE_SETTLE_MS 뒤에 찍는다.
 * - 찍은 화면은 별도 스레드에서 ARGB Bitmap 과 비교용 축소 이미지로 바꿔 [Callback.onAnalyzed] 로 돌려준다.
 *   비교용은 축소 이미지만 남기고 Bitmap 은 바로 해제한다.
 * - 대기 중인 요청은 1개뿐이다. 새 요청이 오면 바꾼다.
 */
class ScreenshotTaker(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val overlay: FloatingButton,
    private val callback: Callback,
) {
    enum class Purpose {
        /** 화면 진입 직후 미리 확보(버튼 숨김). */
        PREVIEW,

        /** 비교용(버튼 그대로, 저장 안 함). */
        PROBE,

        /** 정지 확인 뒤 저장용(버튼 숨김). */
        FINAL,

        /** 스크롤이 멈춘 화면(버튼 숨김, 이어붙임). */
        SCROLL,
    }

    /**
     * 찍을 것. [generation] = SettleCycle 의 판단 번호(스크롤은 0).
     * [recorder]·[segment]·[scrollDy] 는 찍는 순간 [Callback.onBeforeShot] 이 채운다.
     */
    class Request(val purpose: Purpose, val generation: Int) {
        var recorder: Any? = null
        var segment = 0
        var scrollDy: Int? = null
    }

    interface Callback {
        /** 저장이 밀려 있으면 true. 잠시 미룬다. */
        fun isBusy(): Boolean

        /** 찍기 직전. false 면 이 요청을 버린다. true 면 요청의 기록 정보를 채운다. */
        fun onBeforeShot(request: Request): Boolean

        /** 찍은 결과(메인 스레드). [thumb] 가 null 이면 변환 실패. [bitmap] 은 PROBE 면 null, 소유권을 넘긴다. */
        fun onAnalyzed(request: Request, thumb: FrameDiff.Thumb?, bitmap: Bitmap?, mask: FrameDiff.Box?)

        /** takeScreenshot 실패. [retrying] 이면 같은 요청을 곧 다시 찍는다. */
        fun onShotFailed(request: Request, retrying: Boolean)
    }

    private val analyzer = Executors.newSingleThreadExecutor { Thread(it, "qa-analyze") }

    /** 대기 중인 요청. */
    var wanted: Request? = null
        private set

    private var shooting = false // 촬영 예약 ~ 결과 수신
    private var inFlight = false // takeScreenshot 호출 ~ 결과 수신
    private var lastShotAt = 0L

    /** 비교에서 뺄 상태바·내비게이션바 높이. */
    private var insetTop = 0
    private var insetBottom = 0

    private val prepare = Runnable { prepareShot() }
    private val shoot = Runnable { takeShot() }
    private val pump = Runnable { pumpShot() }

    fun setInsets(top: Int, bottom: Int) {
        insetTop = top
        insetBottom = bottom
    }

    /** 요청을 대기열에 넣는다(기존 대기 요청은 바뀐다). */
    fun want(request: Request) {
        wanted = request
        pumpShot()
    }

    /** 대기 중인 요청을 버린다. [purpose] 를 주면 그 종류일 때만. */
    fun drop(purpose: Purpose? = null) {
        if (purpose == null || wanted?.purpose == purpose) wanted = null
    }

    /** 대기 요청과 아직 takeScreenshot 을 부르지 않은 촬영을 모두 취소한다(사용자가 화면을 떠남). */
    fun cancel() {
        wanted = null
        handler.removeCallbacks(pump)
        if (shooting && !inFlight) {
            handler.removeCallbacks(prepare)
            handler.removeCallbacks(shoot)
            finishShot()
        }
    }

    fun shutdown() {
        cancel()
        analyzer.shutdown()
    }

    /** 찍을 것이 있으면 최소 간격에 맞춰 1장 예약한다. 버튼은 저장될 수 있는 화면을 찍기 직전에만 숨긴다. */
    private fun pumpShot() {
        val s = wanted ?: return
        if (shooting) return
        if (callback.isBusy()) {
            // 저장이 밀리면 화면이 쌓이지 않게 잠시 미룬다
            handler.removeCallbacks(pump)
            handler.postDelayed(pump, CaptureConfig.BACKLOG_RETRY_MS)
            return
        }
        shooting = true
        val hide = if (s.purpose == Purpose.PROBE) 0 else CaptureConfig.HIDE_SETTLE_MS
        handler.postAtTime(prepare, maxOf(SystemClock.uptimeMillis(), lastShotAt + CaptureConfig.MIN_SHOT_INTERVAL_MS - hide))
    }

    private fun prepareShot() {
        val s = wanted
        if (s == null) {
            finishShot()
            return
        }
        if (s.purpose == Purpose.PROBE) {
            takeShot()
        } else {
            overlay.hideForCapture()
            handler.postDelayed(shoot, CaptureConfig.HIDE_SETTLE_MS)
        }
    }

    private fun takeShot() {
        val s = wanted
        wanted = null
        if (s == null || !callback.onBeforeShot(s)) {
            finishShot()
            pumpShot()
            return
        }
        val mask = if (s.purpose == Purpose.PROBE) overlay.screenBox() else null
        inFlight = true
        lastShotAt = SystemClock.uptimeMillis()
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    finishShot()
                    analyze(s, result, mask)
                    pumpShot()
                }

                override fun onFailure(errorCode: Int) {
                    finishShot()
                    Log.w(TAG, "takeScreenshot 실패: $errorCode")
                    val retrying = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && wanted == null
                    if (retrying) wanted = s
                    callback.onShotFailed(s, retrying)
                    pumpShot()
                }
            },
        )
    }

    private fun finishShot() {
        shooting = false
        inFlight = false
        overlay.restoreAfterCapture()
    }

    /** 별도 스레드에서 HardwareBuffer → ARGB_8888 Bitmap → 축소 이미지. 결과는 메인 스레드로. */
    private fun analyze(s: Request, result: AccessibilityService.ScreenshotResult, mask: FrameDiff.Box?) {
        val buffer = result.hardwareBuffer
        val colorSpace = result.colorSpace
        val keep = s.purpose != Purpose.PROBE
        val top = insetTop
        val bottom = insetBottom
        try {
            analyzer.execute {
                var bitmap: Bitmap? = null
                val thumb = try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                    bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    bitmap?.let { thumbnail(it, top, bottom) }
                } catch (e: Exception) {
                    Log.w(TAG, "화면 변환 실패", e)
                    null
                } finally {
                    buffer.close()
                }
                if (!keep || thumb == null) {
                    bitmap?.recycle()
                    bitmap = null
                }
                val frame = bitmap
                handler.post { callback.onAnalyzed(s, thumb, frame, mask) }
            }
        } catch (e: RejectedExecutionException) {
            buffer.close() // 서비스 종료 중
        }
    }

    private fun thumbnail(bitmap: Bitmap, topInset: Int, bottomInset: Int): FrameDiff.Thumb {
        val w = bitmap.width
        val h = bitmap.height
        val top = topInset.coerceIn(0, h / 3)
        val bottom = h - bottomInset.coerceIn(0, h / 3)
        return FrameDiff.thumbnail(w, top, bottom) { y, count, into -> bitmap.getPixels(into, 0, w, 0, y, w, count) }
    }

    private companion object {
        const val TAG = "ScreenshotTaker"
    }
}
