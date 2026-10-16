package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.qa.autocapture.overlay.FloatingButton
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 촬영 큐. 한 번에 1장만, takeScreenshot 최소 간격(MIN_SHOT_INTERVAL_MS)을 지켜 찍는다. 메인 스레드 전용.
 *
 * - 대기 요청은 1건으로 합친다(새 요청이 오면 바꿈).
 * - 찍기 직전 플로팅 버튼을 숨기고 HIDE_SETTLE_MS 뒤에 찍는다(이미 숨겨져 있으면 기다리지 않음). 찍은 뒤 다시 보인다.
 * - 찍은 화면은 별도 스레드에서 ARGB Bitmap 으로 바꿔 [Callback.onCaptured] 로 돌려준다.
 */
class ScreenshotTaker(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val overlay: FloatingButton,
    private val callback: Callback,
) {
    enum class Kind {
        /** 화면 진입 뒤 1장. */
        ENTRY,

        /** 길게 스크롤하는 중 1장(프레임끼리 겹치게). */
        SCROLL_STEP,

        /** 스크롤이 멈춘 뒤 1장. */
        SCROLL_STOP,
    }

    /** 찍을 것. [recorder]·[segment]·[scrollDy]·[takenAt] 는 찍는 순간 [Callback.onBeforeShot] 이 채운다. */
    class Request(val kind: Kind) {
        var recorder: Any? = null
        var segment = 0
        var scrollDy: Int? = null
        var takenAt = 0L
    }

    interface Callback {
        /** 저장이 밀려 있으면 true. 잠시 미룬다. */
        fun isBusy(): Boolean

        /** 찍기 직전. false 면 이 요청을 버린다. true 면 요청의 기록 정보를 채운다. */
        fun onBeforeShot(request: Request): Boolean

        /** 찍은 화면(메인 스레드). 변환에 실패하면 [bitmap] 이 null. Bitmap 소유권을 넘긴다. */
        fun onCaptured(request: Request, bitmap: Bitmap?)

        /** takeScreenshot 실패. [retrying] 이면 같은 요청을 곧 다시 찍는다. */
        fun onShotFailed(request: Request, retrying: Boolean)
    }

    private val converter = Executors.newSingleThreadExecutor { Thread(it, "qa-convert") }

    private var wanted: Request? = null
    private var shooting = false // 촬영 예약 ~ 결과 수신
    private var inFlight = false // takeScreenshot 호출 ~ 결과 수신
    private var lastShotAt = 0L

    private val prepare = Runnable { prepareShot() }
    private val shoot = Runnable { takeShot() }
    private val pump = Runnable { pumpShot() }

    /** 요청을 대기열에 넣는다(기존 대기 요청은 바뀐다). */
    fun want(request: Request) {
        wanted = request
        pumpShot()
    }

    /** 대기 요청과 아직 takeScreenshot 을 부르지 않은 촬영을 모두 취소한다(화면이 바뀌었거나 사용자가 떠남). */
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
        converter.shutdown()
    }

    /** 찍을 것이 있으면 최소 간격에 맞춰 1장 예약한다. */
    private fun pumpShot() {
        if (wanted == null || shooting) return
        if (callback.isBusy()) {
            // 저장이 밀리면 화면이 쌓이지 않게 잠시 미룬다
            handler.removeCallbacks(pump)
            handler.postDelayed(pump, CaptureConfig.BACKLOG_RETRY_MS)
            return
        }
        shooting = true
        // 버튼을 숨겨야 하면 숨긴 뒤 HIDE_SETTLE_MS 를 기다리는 만큼 먼저 준비한다
        val hide = if (overlay.hiddenSince != 0L) 0 else CaptureConfig.HIDE_SETTLE_MS
        val at = lastShotAt + CaptureConfig.MIN_SHOT_INTERVAL_MS - hide
        handler.postAtTime(prepare, maxOf(SystemClock.uptimeMillis(), at))
    }

    private fun prepareShot() {
        if (wanted == null) {
            finishShot()
            return
        }
        overlay.hide(FloatingButton.Hide.CAPTURE)
        val readyAt = overlay.hiddenSince + CaptureConfig.HIDE_SETTLE_MS
        if (readyAt > SystemClock.uptimeMillis()) handler.postAtTime(shoot, readyAt) else takeShot()
    }

    private fun takeShot() {
        val s = wanted
        wanted = null
        if (s == null || !callback.onBeforeShot(s)) {
            finishShot()
            pumpShot()
            return
        }
        inFlight = true
        lastShotAt = SystemClock.uptimeMillis()
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    finishShot()
                    convert(s, result)
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
        overlay.show(FloatingButton.Hide.CAPTURE)
    }

    /** 별도 스레드에서 HardwareBuffer → ARGB_8888 Bitmap. 결과는 메인 스레드로. */
    private fun convert(s: Request, result: AccessibilityService.ScreenshotResult) {
        val buffer = result.hardwareBuffer
        val colorSpace = result.colorSpace
        try {
            converter.execute {
                val bitmap = try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                    hardware?.copy(Bitmap.Config.ARGB_8888, false).also { hardware?.recycle() }
                } catch (e: Exception) {
                    Log.w(TAG, "화면 변환 실패", e)
                    null
                } finally {
                    buffer.close()
                }
                handler.post { callback.onCaptured(s, bitmap) }
            }
        } catch (e: RejectedExecutionException) {
            buffer.close() // 서비스 종료 중
        }
    }

    private companion object {
        const val TAG = "ScreenshotTaker"
    }
}
