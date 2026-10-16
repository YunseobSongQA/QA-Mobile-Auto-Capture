package com.qa.autocapture.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qa.autocapture.capture.CaptureConfig
import com.qa.autocapture.capture.FrameDiff
import com.qa.autocapture.stitch.Stitcher
import com.qa.autocapture.stitch.renderPart
import com.qa.autocapture.storage.CaptureRecord
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.ScreenRecord
import com.qa.autocapture.storage.SessionData
import com.qa.autocapture.storage.TextLog
import com.qa.autocapture.storage.ToastRecord
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 세션 1개(시작~정지)를 기록한다. 공개 함수는 메인 스레드에서 부른다.
 *
 * - 캡처 저장: saveExecutor 에서 순서대로. 직전 저장 이미지와 축소 비교해 거의 같으면 생략한다.
 *   최종 캡처(_final)가 직전 저장 이미지와 같으면 새로 저장하지 않고 그 파일 이름에 _final 을 붙인다.
 * - 긴 이미지: 스크롤한 화면을 떠날 때 그 화면에서 저장한 캡처를 stitchExecutor 에서 이어붙인다(…_full.png).
 * 세션 기록(manifest·세션 로그)은 saveExecutor 에서만 고친다.
 */
class SessionRecorder(
    context: Context,
    private val topInset: Int,
    private val bottomInset: Int,
    /** 이미지가 저장될 때마다 (세션ID, 저장한 이미지 수) 로 메인 스레드에서 호출 */
    private val onSaved: (sessionId: String, savedImages: Int) -> Unit,
) {
    private val store = EvidenceStore(context)
    private val resolver = context.applicationContext.contentResolver
    private val session: SessionData
    private val log: TextLog

    val sessionId: String get() = session.id

    /** 현재 화면 구간 번호(메인 스레드). 최종 캡처가 같은 화면의 것인지 확인하는 데 쓴다. */
    var currentSegment = 0
        private set

    /** 현재 구간에서 세로 스크롤이 있었는가(메인 스레드). */
    private var scrolled = false

    /** 저장을 기다리는 캡처 수. 많이 쌓이면 서비스가 다음 캡처를 미룬다. */
    private val pending = AtomicInteger()
    val busy: Boolean get() = pending.get() >= CaptureConfig.MAX_PENDING_SAVES

    // 이하 saveExecutor 전용
    private var segment: Segment? = null
    private var last: Saved? = null

    /** 화면 방문 1번. 첫 캡처를 저장할 때 [record] 를 만들고 순번을 준다. */
    private class Segment(val packageName: String, val className: String, val enteredAt: Long) {
        var record: ScreenRecord? = null
        var captureNo = 0
        val saved = ArrayList<Uri>()
    }

    /** 직전에 저장한 이미지. */
    private class Saved(val segment: Segment, val uri: Uri, val capture: CaptureRecord, val thumb: FrameDiff.Thumb)

    init {
        val now = System.currentTimeMillis()
        session = SessionData(store.newSessionId(now), now)
        activeSessionId = session.id
        log = TextLog(
            context,
            EvidenceStore.sessionPath(session.id),
            EvidenceStore.SESSION_LOG_FILE,
            listOf("# QA 증적 캡처 세션 로그 / 세션 ${session.id}", "# 시각\t종류\t내용"),
        )
        log.write(now, "세션 시작", "")
        val s = session
        saveExecutor.execute { store.writeSession(s) }
    }

    /** 새 화면 구간을 연다. 이전 구간은 닫는다(스크롤했으면 긴 이미지 예약). */
    fun enterScreen(packageName: String, className: String) {
        currentSegment++
        val wasScrolled = scrolled
        scrolled = false
        val at = System.currentTimeMillis()
        onSaveThread {
            closeSegment(at, wasScrolled)
            segment = Segment(packageName, className, at)
            log.write(at, "화면", "$packageName / $className")
        }
    }

    fun onScrolled() {
        scrolled = true
    }

    /** 캡처 1장. 버퍼 소유권을 넘겨받는다(처리 후 close). [final] = 화면이 멈춘 뒤의 최종 캡처. */
    fun addCapture(buffer: HardwareBuffer, colorSpace: ColorSpace?, final: Boolean) {
        val at = System.currentTimeMillis()
        pending.incrementAndGet()
        saveExecutor.execute {
            try {
                processCapture(buffer, colorSpace, final, at)
            } catch (e: Exception) {
                Log.e(TAG, "캡처 저장 실패", e)
            } finally {
                buffer.close()
                pending.decrementAndGet()
            }
        }
    }

    /** 토스트 문구를 manifest 의 toasts 와 세션 로그에 남긴다. */
    fun logToast(at: Long, packageName: String, text: String, screen: String?) {
        onSaveThread {
            session.toasts += ToastRecord(at, packageName, text, screen)
            store.writeSession(session)
            log.write(at, "토스트", "[$packageName] $text")
        }
    }

    /** 세션을 끝낸다. 남은 저장(긴 이미지 포함)이 모두 끝나면 [onDone] 을 (저장한 이미지 수와 함께) 메인 스레드에서 부른다. */
    fun stop(reason: String, onDone: (savedImages: Int) -> Unit) {
        val at = System.currentTimeMillis()
        val wasScrolled = scrolled
        onSaveThread {
            closeSegment(at, wasScrolled)
            // 예약된 긴 이미지 작업이 끝나고 그 결과가 반영된 뒤에 마무리한다
            stitchExecutor.execute {
                onSaveThread {
                    try {
                        session.endedAt = at
                        session.ended = true
                        store.writeSession(session)
                    } finally {
                        if (activeSessionId == session.id) activeSessionId = null
                        val saved = session.imageCount
                        log.write(at, "세션 종료", "$reason · 이미지 ${saved}장")
                        log.close()
                        main.post { onDone(saved) }
                    }
                }
            }
        }
    }

    // ── saveExecutor ──────────────────────────────────────────────

    private fun processCapture(buffer: HardwareBuffer, colorSpace: ColorSpace?, final: Boolean, at: Long) {
        val seg = segment ?: return
        var hardware: Bitmap? = null
        var software: Bitmap? = null
        try {
            hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return
            software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return
            hardware.recycle()
            hardware = null
            val thumb = thumbnail(software)
            val prev = last
            if (prev != null) {
                if (final) {
                    // 최종 화면이 직전 저장 이미지와 같으면 그 파일을 최종으로 표시한다
                    if (prev.segment === seg && FrameDiff.same(prev.thumb, thumb) && (prev.capture.final || markFinal(prev, at))) return
                } else if (FrameDiff.similar(prev.thumb, thumb)) {
                    return // 직전 저장 이미지와 거의 같음
                }
            }
            save(seg, software, thumb, final, at)
        } finally {
            software?.recycle()
            hardware?.recycle()
        }
    }

    private fun thumbnail(bitmap: Bitmap): FrameDiff.Thumb {
        val w = bitmap.width
        val h = bitmap.height
        val top = topInset.coerceIn(0, h / 3)
        val bottom = h - bottomInset.coerceIn(0, h / 3)
        return FrameDiff.thumbnail(w, top, bottom) { y, count, into -> bitmap.getPixels(into, 0, w, 0, y, w, count) }
    }

    private fun save(seg: Segment, bitmap: Bitmap, thumb: FrameDiff.Thumb, final: Boolean, at: Long) {
        // 화면 순번은 그 방문에서 처음 저장에 성공할 때 정한다(저장한 게 없는 방문은 순번을 쓰지 않음)
        val record = seg.record ?: ScreenRecord(session.screens.size + 1, seg.packageName, seg.className, seg.enteredAt)
        val base = EvidenceStore.baseName(record.seq, record.packageName, record.className, record.enteredAt)
        val name = String.format(Locale.ROOT, "%s_%02d%s.png", base, seg.captureNo + 1, if (final) FINAL_SUFFIX else "")
        val uri = store.savePng(EvidenceStore.sessionPath(session.id), name, bitmap)
        if (seg.record == null) {
            session.screens += record
            seg.record = record
        }
        seg.captureNo++
        seg.saved += uri
        val capture = CaptureRecord(name, at, final)
        record.captures += capture
        last = Saved(seg, uri, capture, thumb)
        store.writeSession(session)
        log.write(at, if (final) "최종 캡처" else "캡처", name)
        notifySaved()
    }

    /** 직전 저장 이미지 이름에 _final 을 붙인다. 실패하면 false(새로 저장한다). */
    private fun markFinal(prev: Saved, at: Long): Boolean {
        val name = prev.capture.file.removeSuffix(".png") + "$FINAL_SUFFIX.png"
        if (!store.rename(prev.uri, name)) return false
        prev.capture.file = name
        prev.capture.final = true
        store.writeSession(session)
        log.write(at, "최종 캡처", "$name (직전 캡처와 같아 이름만 변경)")
        return true
    }

    private fun closeSegment(exitedAt: Long, scrolled: Boolean) {
        val seg = segment ?: return
        segment = null
        val record = seg.record ?: return // 저장한 캡처가 없는 방문
        record.exitedAt = exitedAt
        store.writeSession(session)
        if (!scrolled || seg.saved.size < 2) return

        val uris = seg.saved.toList()
        val base = EvidenceStore.baseName(record.seq, record.packageName, record.className, record.enteredAt)
        val path = EvidenceStore.sessionPath(session.id)
        stitchExecutor.execute {
            val names = try {
                saveFull(uris, path, base)
            } catch (e: Exception) {
                Log.e(TAG, "긴 이미지 저장 실패: $base", e)
                emptyList()
            }
            if (names.isNotEmpty()) {
                onSaveThread {
                    record.full = names
                    store.writeSession(session)
                    for (n in names) log.write(System.currentTimeMillis(), "긴 이미지", n)
                    notifySaved()
                }
            }
        }
    }

    private fun notifySaved() {
        val id = session.id
        val saved = session.imageCount
        main.post { onSaved(id, saved) }
    }

    // ── stitchExecutor ────────────────────────────────────────────

    /**
     * 구간에서 저장한 캡처를 스티칭해 2장 이상 이어붙은 부분만 …_full.png 로 저장한다.
     * 1장짜리 부분은 이미 개별 캡처로 남아 있으므로 만들지 않는다.
     */
    private fun saveFull(uris: List<Uri>, path: String, base: String): List<String> {
        val names = ArrayList<String>()
        EvidenceStore.FrameReader(resolver).use { reader ->
            // 1) 프레임마다 행 해시만 남긴다(픽셀 배열은 재사용)
            val frames = ArrayList<Stitcher.Frame>(uris.size)
            val usable = ArrayList<Uri>(uris.size)
            var buffer: IntArray? = null
            for (u in uris) {
                val px = reader.read(u, buffer) ?: continue
                buffer = px
                frames += Stitcher.Frame(reader.width, reader.height, Stitcher.rowHashes(px, reader.width, reader.height))
                usable += u
            }
            if (frames.size < 2) return names

            // 2) 합성 계획 → 이어붙은 part 마다 행 단위로 PNG 렌더링
            val parts = Stitcher.plan(frames, topInset, bottomInset).parts.filter { it.height > it.frameHeight }
            parts.forEachIndexed { i, part ->
                val name = if (i == 0) "${base}_full.png" else "${base}_full${i + 1}.png"
                store.saveFile(path, name, "image/png") { out ->
                    renderPart(part, out) { frame, into ->
                        val px = reader.read(usable[frame], into) ?: throw IOException("캡처를 읽을 수 없습니다: ${usable[frame]}")
                        if (px !== into) px.copyInto(into)
                    }
                }
                names += name
            }
        }
        return names
    }

    companion object {
        private const val TAG = "SessionRecorder"
        private const val FINAL_SUFFIX = "_final"

        private val saveExecutor = Executors.newSingleThreadExecutor { Thread(it, "qa-save") }
        private val stitchExecutor = Executors.newSingleThreadExecutor { Thread(it, "qa-stitch") }
        private val main = Handler(Looper.getMainLooper())

        /** 진행 중인 세션. 복구 작업이 건드리지 않게 한다. */
        @Volatile
        private var activeSessionId: String? = null

        private fun onSaveThread(task: () -> Unit) {
            saveExecutor.execute {
                try {
                    task()
                } catch (e: Exception) {
                    Log.e(TAG, "저장 작업 실패", e)
                }
            }
        }

        /**
         * 서비스가 비정상 종료돼 끝나지 않은 세션을 닫는다. 캡처는 찍을 때마다 저장되므로 기록만 정리한다.
         * 서비스가 연결될 때 한 번 부른다.
         */
        fun recoverUnfinished(context: Context) {
            val store = EvidenceStore(context)
            onSaveThread {
                for (s in store.loadSessions()) {
                    if (s.ended || s.id == activeSessionId) continue
                    for (screen in s.screens) {
                        if (screen.exitedAt == null) screen.exitedAt = screen.captures.maxOfOrNull { it.at } ?: screen.enteredAt
                    }
                    s.ended = true
                    s.endedAt = s.endedAt ?: (s.screens.flatMap { it.captures }.maxOfOrNull { it.at } ?: s.startedAt)
                    store.writeSession(s)
                }
            }
        }
    }
}
