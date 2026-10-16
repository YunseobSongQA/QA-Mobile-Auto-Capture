package com.qa.autocapture.session

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qa.autocapture.capture.CaptureConfig
import com.qa.autocapture.stitch.Stitcher
import com.qa.autocapture.stitch.renderPart
import com.qa.autocapture.storage.CaptureRecord
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.GroupInfo
import com.qa.autocapture.storage.ScreenRecord
import com.qa.autocapture.storage.SessionData
import com.qa.autocapture.storage.TextLog
import com.qa.autocapture.storage.ToastRecord
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 세션 1개(시작~정지)를 기록한다. 공개 함수는 메인 스레드에서 부른다.
 *
 * 보고용 결과: 화면 방문마다 "화면 상태" 단위로 이미지 1장. 서비스가 확정한 화면을 받아
 * - [Op.NEW]: 새 상태 시작(이전 상태는 이미지로 저장)
 * - [Op.REPLACE]: 같은 상태의 작은 변화 → 마지막 화면을 최신 것으로 바꿈
 * - [Op.SCROLL]: 스크롤이 멈춘 화면 → 같은 상태에 덧붙임(나중에 이어붙여 긴 이미지 1장)
 * 프레임은 임시 PNG 로만 두고, 상태가 끝나면(새 상태·화면 전환·정지) stitchExecutor 에서 이미지로 저장한다.
 * 세션 기록(manifest·세션 로그)은 saveExecutor 에서만 고친다.
 */
class SessionRecorder(
    context: Context,
    private val topInset: Int,
    private val bottomInset: Int,
    /** 확보한 이미지 수(저장 중인 것 포함)가 바뀔 때마다 (세션ID, 수) 로 메인 스레드에서 호출 */
    private val onSaved: (sessionId: String, images: Int) -> Unit,
) {
    enum class Op { NEW, REPLACE, SCROLL }

    private val store = EvidenceStore(context)
    private val session: SessionData
    private val log: TextLog

    val sessionId: String get() = session.id

    /** 현재 화면 방문 번호(메인 스레드). 촬영 결과가 같은 화면의 것인지 확인하는 데 쓴다. */
    var currentSegment = 0
        private set

    /** 처리를 기다리는 화면 수. 많이 쌓이면 서비스가 다음 촬영을 미룬다. */
    private val pending = AtomicInteger()
    val busy: Boolean get() = pending.get() >= CaptureConfig.MAX_PENDING_SAVES

    // 이하 saveExecutor 전용
    private var visit: Visit? = null

    /** 상태는 끝났지만 아직 이미지로 저장 중인 수. */
    private var savingGroups = 0

    /** 화면 방문 1번. 첫 화면을 받을 때 [record] 를 만들고 순번을 준다. */
    private class Visit(val number: Int, val packageName: String, val className: String, val enteredAt: Long) {
        var record: ScreenRecord? = null
        var group: Group? = null
        var groupNo = 0

        /** 이 방문에서 저장한 이미지 수(stitchExecutor 전용). 파일 이름 번호에 쓴다. */
        var files = 0
    }

    /** 화면 상태 1개 = 결과 이미지 1장(이어붙이지 못하면 여러 장). */
    private class Group(val dir: File, val startedAt: Long) {
        var count = 0
        var lastHint: Int? = null
    }

    private class SavedFile(val name: String, val frames: Int)

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

    /** 새 화면 방문을 연다. 이전 방문의 상태는 이미지로 저장한다. */
    fun enterScreen(packageName: String, className: String) {
        currentSegment++
        val number = currentSegment
        val at = System.currentTimeMillis()
        onSaveThread {
            closeVisit(at)
            visit = Visit(number, packageName, className, at)
            log.write(at, "화면", "$packageName / $className")
        }
    }

    /**
     * 확정한 화면 1장. Bitmap 소유권을 넘겨받는다(임시 저장 후 recycle).
     * @param scrollDy [Op.SCROLL] 일 때 직전 화면 이후 scrollDeltaY 합(모르면 null). 이어붙일 때 겹침 힌트.
     */
    fun addFrame(bitmap: Bitmap, op: Op, scrollDy: Int?) {
        val at = System.currentTimeMillis()
        pending.incrementAndGet()
        saveExecutor.execute {
            try {
                processFrame(bitmap, op, scrollDy, at)
            } catch (e: Exception) {
                Log.e(TAG, "화면 저장 실패", e)
            } finally {
                bitmap.recycle()
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

    /** 세션을 끝낸다. 남은 저장이 모두 끝나면 [onDone] 을 (저장한 이미지 수와 함께) 메인 스레드에서 부른다. */
    fun stop(reason: String, onDone: (savedImages: Int) -> Unit) {
        val at = System.currentTimeMillis()
        onSaveThread {
            closeVisit(at)
            // 예약된 이미지 저장이 끝나고 그 결과가 반영된 뒤에 마무리한다
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

    private fun processFrame(bitmap: Bitmap, op: Op, scrollDy: Int?, at: Long) {
        val v = visit ?: return
        val g = v.group
        when {
            op == Op.REPLACE && g != null -> store.writeFrame(g.dir, g.count - 1, g.lastHint, bitmap)
            op == Op.SCROLL && g != null -> {
                store.writeFrame(g.dir, g.count, scrollDy, bitmap)
                g.count++
                g.lastHint = scrollDy
            }
            else -> {
                closeGroup(v)
                val record = v.record ?: ScreenRecord(session.screens.size + 1, v.packageName, v.className, v.enteredAt).also {
                    session.screens += it
                    v.record = it
                    store.writeSession(session)
                }
                v.groupNo++
                val group = Group(store.groupDir(session.id, v.number, v.groupNo), at)
                store.writeGroupInfo(group.dir, GroupInfo(record.seq, baseName(record)))
                store.writeFrame(group.dir, 0, null, bitmap)
                group.count = 1
                v.group = group
                notifyCount()
            }
        }
    }

    /** 현재 상태를 끝내고 이미지로 저장한다(stitchExecutor). */
    private fun closeGroup(v: Visit) {
        val g = v.group ?: return
        v.group = null
        val record = v.record ?: return
        val base = baseName(record)
        val path = EvidenceStore.sessionPath(session.id)
        savingGroups++
        stitchExecutor.execute {
            val files = try {
                saveGroup(store, g.dir, path, topInset, bottomInset) { if (v.files++ == 0) "$base.png" else "${base}_${v.files}.png" }
                    .also { g.dir.deleteRecursively() }
            } catch (e: Exception) {
                // 임시 프레임은 남겨 두고 다음 서비스 연결 때 다시 시도한다
                Log.e(TAG, "이미지 저장 실패: $base", e)
                emptyList()
            }
            onSaveThread {
                savingGroups--
                for (f in files) {
                    record.captures += CaptureRecord(f.name, g.startedAt, f.frames)
                    log.write(System.currentTimeMillis(), "저장", if (f.frames > 1) "${f.name} (화면 ${f.frames}장 이어붙임)" else f.name)
                }
                if (files.isNotEmpty()) store.writeSession(session)
                notifyCount()
            }
        }
    }

    private fun closeVisit(exitedAt: Long) {
        val v = visit ?: return
        visit = null
        closeGroup(v)
        val record = v.record ?: return // 확보한 화면이 없는 방문
        record.exitedAt = exitedAt
        store.writeSession(session)
    }

    /** 저장한 이미지 + 저장 중 + 지금 만드는 상태. 플로팅 버튼 숫자. */
    private fun notifyCount() {
        val id = session.id
        val count = session.imageCount + savingGroups + (if (visit?.group != null) 1 else 0)
        main.post { onSaved(id, count) }
    }

    companion object {
        private const val TAG = "SessionRecorder"

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
         * 임시 폴더의 프레임을 순서대로 이어붙여 저장하고 (파일 이름, 이어붙인 화면 수)를 돌려준다.
         * 프레임이 1장이면 그대로, 여러 장이면 겹치는 부분으로 이어붙인 긴 이미지 1장.
         * 겹침을 못 찾은 지점과 MAX_STITCH_HEIGHT 를 넘는 지점에서는 다음 장으로 나눈다.
         */
        private fun saveGroup(
            store: EvidenceStore,
            dir: File,
            path: String,
            topInset: Int,
            bottomInset: Int,
            nextName: () -> String,
        ): List<SavedFile> {
            val saved = ArrayList<SavedFile>()
            EvidenceStore.FrameReader().use { reader ->
                // 1) 프레임마다 행 해시만 남긴다(픽셀 배열은 재사용)
                val frames = ArrayList<Stitcher.Frame>()
                val files = ArrayList<File>()
                val hints = ArrayList<Int?>()
                var buffer: IntArray? = null
                var skipped = false
                for (f in store.listFrames(dir)) {
                    val px = reader.read({ f.file.inputStream() }, buffer)
                    if (px == null) {
                        skipped = true // 읽지 못한 프레임 다음은 이동량을 모른다
                        continue
                    }
                    buffer = px
                    frames += Stitcher.Frame(reader.width, reader.height, Stitcher.rowHashes(px, reader.width, reader.height))
                    files += f.file
                    hints += if (skipped) null else f.hint
                    skipped = false
                }
                if (frames.isEmpty()) return saved

                // 2) 합성 계획 → 장마다 저장. 한 프레임 전체면 임시 PNG 를 그대로 복사한다.
                for (part in Stitcher.plan(frames, topInset, bottomInset, hints).parts) {
                    val name = nextName()
                    val only = part.slices.singleOrNull()
                    store.saveFile(path, name, "image/png") { out ->
                        if (only != null && only.top == 0 && only.bottom == part.frameHeight) {
                            files[only.frame].inputStream().use { it.copyTo(out) }
                        } else {
                            renderPart(part, out) { frame, into ->
                                val px = reader.read({ files[frame].inputStream() }, into)
                                    ?: throw IOException("임시 프레임을 읽을 수 없습니다: ${files[frame].name}")
                                if (px !== into) px.copyInto(into)
                            }
                        }
                    }
                    saved += SavedFile(name, part.slices.map { it.frame }.distinct().size)
                }
            }
            return saved
        }

        /**
         * 서비스가 비정상 종료돼 끝나지 않은 세션을 닫고, 남은 임시 프레임을 이미지로 저장한다.
         * 서비스가 연결될 때 한 번 부른다.
         */
        fun recoverUnfinished(context: Context, topInset: Int, bottomInset: Int) {
            val store = EvidenceStore(context)
            // 앞서 예약된 저장이 끝난 뒤에 남은 것만 처리한다
            stitchExecutor.execute {
                onSaveThread {
                    for (s in store.loadSessions()) {
                        if (s.id == activeSessionId) continue
                        var changed = false
                        for (dir in store.pendingGroupDirs(s.id)) {
                            val info = store.readGroupInfo(dir)
                            val record = s.screens.firstOrNull { it.seq == info?.seq }
                            val at = dir.lastModified()
                            val files = if (info == null || record == null) {
                                emptyList()
                            } else {
                                var n = 0
                                try {
                                    saveGroup(store, dir, EvidenceStore.sessionPath(s.id), topInset, bottomInset) {
                                        if (n++ == 0) "${info.base}_recovered.png" else "${info.base}_recovered$n.png"
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "이미지 복구 실패: ${dir.path}", e)
                                    emptyList()
                                }
                            }
                            dir.deleteRecursively() // 복구는 한 번만 시도한다
                            if (record != null && files.isNotEmpty()) {
                                for (f in files) record.captures += CaptureRecord(f.name, at, f.frames)
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
