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
import com.qa.autocapture.storage.ScrollInfo
import com.qa.autocapture.storage.SessionData
import com.qa.autocapture.storage.TextLog
import com.qa.autocapture.storage.ToastRecord
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 세션 1개(시작~정지)를 기록한다. 공개 함수는 메인 스레드에서 부른다.
 *
 * - 변화·최종 캡처: saveExecutor 에서 순서대로 저장. 직전 저장 이미지와 축소 비교해 거의 같으면 생략한다.
 *   최종 캡처(_final)가 직전 저장 이미지와 같으면 새로 저장하지 않고 그 파일 이름에 _final 을 붙인다.
 * - 스크롤 프레임: 개별 저장하지 않고 임시 폴더에 모은다. 스크롤 직전 화면(직전 촬영)을 시작 프레임으로 함께 넣는다.
 *   화면을 떠날 때 stitchExecutor 에서 이어붙여 …_scroll.png 로 저장한다.
 * 세션 기록(manifest·세션 로그)은 saveExecutor 에서만 고친다.
 */
class SessionRecorder(
    context: Context,
    private val topInset: Int,
    private val bottomInset: Int,
    /** 이미지가 저장될 때마다 (세션ID, 저장한 이미지 수) 로 메인 스레드에서 호출 */
    private val onSaved: (sessionId: String, savedImages: Int) -> Unit,
) {
    enum class Kind {
        /** 스크롤이 아닌 화면 변화 */
        CHANGE,

        /** 화면이 멈춘 뒤의 최종 화면 */
        FINAL,

        /** 스크롤이 멈출 때마다 찍은 이어붙이기용 프레임 */
        SCROLL,
    }

    private val store = EvidenceStore(context)
    private val session: SessionData
    private val log: TextLog

    val sessionId: String get() = session.id

    /** 현재 화면 구간 번호(메인 스레드). 촬영 결과가 같은 화면의 것인지 확인하는 데 쓴다. */
    var currentSegment = 0
        private set

    /** 저장을 기다리는 캡처 수. 많이 쌓이면 서비스가 다음 캡처를 미룬다. */
    private val pending = AtomicInteger()
    val busy: Boolean get() = pending.get() >= CaptureConfig.MAX_PENDING_SAVES

    // 이하 saveExecutor 전용
    private var segment: Segment? = null

    /** 중복 생략 기준: 직전에 저장한 이미지(스크롤 프레임은 스크롤 이미지로 저장되므로 포함). */
    private var lastSaved: Saved? = null

    /** 직전에 찍은 화면(저장 생략된 것 포함). 스크롤을 시작하면 이어붙이기의 시작 프레임이 된다. */
    private var lastFrame: Frame? = null
    private var frameIds = 0L

    /** 화면 방문 1번. 첫 저장 때 [record] 를 만들고 순번을 준다. */
    private class Segment(val number: Int, val packageName: String, val className: String, val enteredAt: Long) {
        var record: ScreenRecord? = null
        var captureNo = 0
        var scroll: ScrollFrames? = null
    }

    /** 이 방문의 이어붙이기 임시 프레임. */
    private class ScrollFrames(val dir: File) {
        var count = 0
        var lastFrameId = -1L
    }

    private class Saved(val segment: Segment, val uri: Uri?, val capture: CaptureRecord?, val thumb: FrameDiff.Thumb)

    private class Frame(val segment: Segment, val id: Long, val bitmap: Bitmap)

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

    /** 새 화면 구간을 연다. 이전 구간은 닫는다(이어붙이던 프레임이 있으면 스크롤 이미지 저장). */
    fun enterScreen(packageName: String, className: String) {
        currentSegment++
        val number = currentSegment
        val at = System.currentTimeMillis()
        onSaveThread {
            closeSegment(at)
            segment = Segment(number, packageName, className, at)
            log.write(at, "화면", "$packageName / $className")
        }
    }

    /**
     * 촬영 1장. 버퍼 소유권을 넘겨받는다(처리 후 close).
     * @param scrollDy 직전 촬영 이후 scrollDeltaY 합(모르면 null). [Kind.SCROLL] 의 겹침 계산 힌트.
     */
    fun addCapture(buffer: HardwareBuffer, colorSpace: ColorSpace?, kind: Kind, scrollDy: Int?) {
        val at = System.currentTimeMillis()
        pending.incrementAndGet()
        saveExecutor.execute {
            try {
                processCapture(buffer, colorSpace, kind, scrollDy, at)
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

    /** 세션을 끝낸다. 남은 저장(스크롤 이미지 포함)이 모두 끝나면 [onDone] 을 (저장한 이미지 수와 함께) 메인 스레드에서 부른다. */
    fun stop(reason: String, onDone: (savedImages: Int) -> Unit) {
        val at = System.currentTimeMillis()
        onSaveThread {
            closeSegment(at)
            lastFrame?.bitmap?.recycle()
            lastFrame = null
            // 예약된 스크롤 이미지 작업이 끝나고 그 결과가 반영된 뒤에 마무리한다
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

    private fun processCapture(buffer: HardwareBuffer, colorSpace: ColorSpace?, kind: Kind, scrollDy: Int?, at: Long) {
        val seg = segment ?: return
        var hardware: Bitmap? = null
        var software: Bitmap? = null
        try {
            hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return
            software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return
            hardware.recycle()
            hardware = null
            val thumb = thumbnail(software)
            val id = ++frameIds
            if (kind == Kind.SCROLL) {
                addScrollFrame(seg, software, thumb, scrollDy, id)
            } else {
                saveIfChanged(seg, software, thumb, kind == Kind.FINAL, at)
            }
            // 다음 스크롤의 시작 프레임으로 들고 있는다(직전 것은 해제)
            lastFrame?.bitmap?.recycle()
            lastFrame = Frame(seg, id, software)
            software = null
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

    private fun saveIfChanged(seg: Segment, bitmap: Bitmap, thumb: FrameDiff.Thumb, final: Boolean, at: Long) {
        val prev = lastSaved
        if (prev != null) {
            if (final) {
                // 최종 화면이 직전 저장 이미지와 같으면 그 파일을 최종으로 표시한다
                if (prev.segment === seg && FrameDiff.same(prev.thumb, thumb) && (prev.capture?.final == true || markFinal(prev, at))) return
            } else if (FrameDiff.similar(prev.thumb, thumb)) {
                return // 직전 저장 이미지와 거의 같음
            }
        }
        save(seg, bitmap, thumb, final, at)
    }

    private fun save(seg: Segment, bitmap: Bitmap, thumb: FrameDiff.Thumb, final: Boolean, at: Long) {
        // 화면 순번은 그 방문에서 처음 저장할 때 정한다(저장한 게 없는 방문은 순번을 쓰지 않음)
        val record = seg.record ?: newRecord(seg)
        val name = String.format(Locale.ROOT, "%s_%02d%s.png", baseName(record), seg.captureNo + 1, if (final) FINAL_SUFFIX else "")
        val uri = store.savePng(EvidenceStore.sessionPath(session.id), name, bitmap)
        attach(seg, record)
        seg.captureNo++
        val capture = CaptureRecord(name, at, final)
        record.captures += capture
        lastSaved = Saved(seg, uri, capture, thumb)
        store.writeSession(session)
        log.write(at, if (final) "최종 캡처" else "캡처", name)
        notifySaved()
    }

    /** 직전 저장 이미지 이름에 _final 을 붙인다. 실패하거나 파일이 아니면(스크롤 프레임) false → 새로 저장한다. */
    private fun markFinal(prev: Saved, at: Long): Boolean {
        val capture = prev.capture ?: return false
        val uri = prev.uri ?: return false
        val name = capture.file.removeSuffix(".png") + "$FINAL_SUFFIX.png"
        if (!store.rename(uri, name)) return false
        capture.file = name
        capture.final = true
        store.writeSession(session)
        log.write(at, "최종 캡처", "$name (직전 캡처와 같아 이름만 변경)")
        return true
    }

    /** 스크롤 프레임을 임시 폴더에 넣는다. 스크롤 직전 화면이 아직 없으면 먼저 시작 프레임으로 넣는다. */
    private fun addScrollFrame(seg: Segment, bitmap: Bitmap, thumb: FrameDiff.Thumb, dy: Int?, id: Long) {
        val frames = seg.scroll ?: run {
            val record = seg.record ?: newRecord(seg).also { attach(seg, it) }
            store.writeSession(session)
            ScrollFrames(store.scrollDir(session.id, seg.number)).also {
                store.writeScrollInfo(it.dir, ScrollInfo(record.seq, baseName(record)))
                seg.scroll = it
            }
        }
        val base = lastFrame
        if (base != null && base.segment === seg && base.id != frames.lastFrameId) {
            store.writeScrollFrame(frames.dir, frames.count++, null, true, base.bitmap)
        }
        store.writeScrollFrame(frames.dir, frames.count++, dy, false, bitmap)
        frames.lastFrameId = id
        // 이 프레임은 스크롤 이미지로 저장되므로, 같은 화면을 다시 개별 저장하지 않게 중복 생략 기준으로 삼는다
        lastSaved = Saved(seg, null, null, thumb)
    }

    private fun newRecord(seg: Segment) =
        ScreenRecord(session.screens.size + 1, seg.packageName, seg.className, seg.enteredAt)

    private fun attach(seg: Segment, record: ScreenRecord) {
        if (seg.record != null) return
        session.screens += record
        seg.record = record
    }

    private fun closeSegment(exitedAt: Long) {
        val seg = segment ?: return
        segment = null
        val record = seg.record ?: return // 저장한 것이 없는 방문
        record.exitedAt = exitedAt
        store.writeSession(session)
        val frames = seg.scroll ?: return
        val base = baseName(record)
        val path = EvidenceStore.sessionPath(session.id)
        stitchExecutor.execute {
            val names = try {
                saveScroll(store, frames.dir, path, base, topInset, bottomInset).also { frames.dir.deleteRecursively() }
            } catch (e: Exception) {
                // 임시 프레임은 남겨 두고 다음 서비스 연결 때 다시 시도한다
                Log.e(TAG, "스크롤 이미지 저장 실패: $base", e)
                emptyList()
            }
            if (names.isNotEmpty()) {
                onSaveThread {
                    record.scroll = record.scroll + names
                    store.writeSession(session)
                    for (n in names) log.write(System.currentTimeMillis(), "스크롤 이미지", n)
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

        private fun baseName(record: ScreenRecord) =
            EvidenceStore.baseName(record.seq, record.packageName, record.className, record.enteredAt)

        /**
         * 임시 폴더의 프레임을 순서대로 이어붙여 …_scroll.png(…_scroll2.png …)로 저장하고 파일 이름을 돌려준다.
         * 겹침을 못 찾은 지점과 MAX_STITCH_HEIGHT 를 넘는 지점에서 다음 장으로 나눈다.
         * 스크롤 직전 화면(이미 개별 캡처됨)만으로 된 장은 만들지 않는다.
         */
        private fun saveScroll(store: EvidenceStore, dir: File, path: String, base: String, topInset: Int, bottomInset: Int): List<String> {
            val names = ArrayList<String>()
            EvidenceStore.FrameReader().use { reader ->
                // 1) 프레임마다 행 해시만 남긴다(픽셀 배열은 재사용)
                val frames = ArrayList<Stitcher.Frame>()
                val files = ArrayList<File>()
                val hints = ArrayList<Int?>()
                val isBase = ArrayList<Boolean>()
                var buffer: IntArray? = null
                var skipped = false
                for (f in store.listScrollFrames(dir)) {
                    val px = reader.read({ f.file.inputStream() }, buffer)
                    if (px == null) {
                        skipped = true // 읽지 못한 프레임 다음은 이동량을 모른다
                        continue
                    }
                    buffer = px
                    frames += Stitcher.Frame(reader.width, reader.height, Stitcher.rowHashes(px, reader.width, reader.height))
                    files += f.file
                    hints += if (skipped) null else f.hint
                    isBase += f.base
                    skipped = false
                }
                if (frames.isEmpty()) return names

                // 2) 합성 계획 → 장마다 행 단위로 PNG 렌더링
                val parts = Stitcher.plan(frames, topInset, bottomInset, hints).parts
                    .filter { part -> part.slices.any { !isBase[it.frame] } }
                parts.forEachIndexed { i, part ->
                    val name = if (i == 0) "${base}_scroll.png" else "${base}_scroll${i + 1}.png"
                    store.saveFile(path, name, "image/png") { out ->
                        renderPart(part, out) { frame, into ->
                            val px = reader.read({ files[frame].inputStream() }, into)
                                ?: throw IOException("임시 프레임을 읽을 수 없습니다: ${files[frame].name}")
                            if (px !== into) px.copyInto(into)
                        }
                    }
                    names += name
                }
            }
            return names
        }

        /**
         * 서비스가 비정상 종료돼 끝나지 않은 세션을 닫고, 남은 스크롤 프레임을 스크롤 이미지로 저장한다.
         * 변화·최종 캡처는 찍을 때마다 저장되므로 기록만 정리한다. 서비스가 연결될 때 한 번 부른다.
         */
        fun recoverUnfinished(context: Context, topInset: Int, bottomInset: Int) {
            val store = EvidenceStore(context)
            // 앞서 예약된 스크롤 이미지 작업이 끝난 뒤에 남은 것만 처리한다
            stitchExecutor.execute {
                onSaveThread {
                    for (s in store.loadSessions()) {
                        if (s.id == activeSessionId) continue
                        var changed = false
                        for (dir in store.pendingScrollDirs(s.id)) {
                            val info = store.readScrollInfo(dir)
                            val names = if (info == null) {
                                emptyList()
                            } else {
                                try {
                                    saveScroll(store, dir, EvidenceStore.sessionPath(s.id), info.base, topInset, bottomInset)
                                } catch (e: Exception) {
                                    Log.e(TAG, "스크롤 이미지 복구 실패: ${dir.path}", e)
                                    emptyList()
                                }
                            }
                            dir.deleteRecursively() // 복구는 한 번만 시도한다
                            val record = s.screens.firstOrNull { it.seq == info?.seq }
                            if (record != null && names.isNotEmpty()) {
                                record.scroll = record.scroll + names
                                changed = true
                            }
                        }
                        if (!s.ended) {
                            for (screen in s.screens) {
                                if (screen.exitedAt == null) screen.exitedAt = screen.captures.maxOfOrNull { it.at } ?: screen.enteredAt
                            }
                            s.ended = true
                            s.endedAt = s.endedAt ?: (s.screens.flatMap { it.captures }.maxOfOrNull { it.at } ?: s.startedAt)
                            changed = true
                        }
                        if (changed) store.writeSession(s)
                    }
                }
            }
        }
    }
}
