package com.qa.autocapture.session

import android.content.Context
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qa.autocapture.stitch.Stitcher
import com.qa.autocapture.stitch.renderPart
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.ScreenInfo
import com.qa.autocapture.storage.ScreenRecord
import com.qa.autocapture.storage.SessionData
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * 세션 1개(시작~정지)를 기록한다. 공개 함수는 메인 스레드에서 부른다.
 *
 * - 프레임 기록: frameExecutor (임시 PNG 로 바로 내려 메모리에 쌓지 않음)
 * - 스티칭·저장: stitchExecutor (화면 단위로 순서대로)
 * 구간을 닫을 때는 frameExecutor 를 한 번 거쳐 그 구간의 프레임 기록이 끝난 뒤 스티칭한다.
 */
class SessionRecorder(
    context: Context,
    private val topInset: Int,
    private val bottomInset: Int,
    /** 화면 하나가 저장될 때마다 (세션ID, 저장된 화면 수) 로 메인 스레드에서 호출 */
    private val onSaved: (sessionId: String, savedScreens: Int) -> Unit,
) {
    private val store = EvidenceStore(context)

    /** 스티칭 스레드에서만 고친다. */
    private val session: SessionData

    val sessionId: String get() = session.id

    /** 현재 화면 구간 번호. 캡처 결과가 아직 같은 구간의 것인지 확인하는 데 쓴다. */
    var currentSegment = 0
        private set

    private var segmentDir: File? = null
    private var frameIndex = 0

    init {
        val now = System.currentTimeMillis()
        session = SessionData(store.newSessionId(now), now)
        activeSessionId = session.id
        val s = session
        afterFrames { store.writeSession(s) }
    }

    /** 새 화면 구간을 연다. 이전 구간은 닫고 스티칭을 예약한다. */
    fun enterScreen(packageName: String, className: String) {
        closeSegment()
        currentSegment++
        frameIndex = 0
        val dir = store.segmentDir(session.id, currentSegment)
        segmentDir = dir
        val info = ScreenInfo(packageName, className, System.currentTimeMillis())
        frameExecutor.execute { runCatching { store.writeScreenInfo(dir, info) } }
    }

    /** 현재 구간에 프레임을 추가한다. 버퍼 소유권을 넘겨받는다(기록 후 close). */
    fun addFrame(buffer: HardwareBuffer, colorSpace: ColorSpace?) {
        val dir = segmentDir
        if (dir == null) {
            buffer.close()
            return
        }
        val index = frameIndex++
        frameExecutor.execute { store.writeFrame(dir, index, buffer, colorSpace) }
    }

    /** 세션을 끝낸다. 남은 저장이 모두 끝나면 [onDone] 을 (저장된 화면 수와 함께) 메인 스레드에서 부른다. */
    fun stop(onDone: (savedScreens: Int) -> Unit) {
        closeSegment()
        val endedAt = System.currentTimeMillis()
        val s = session
        afterFrames {
            s.endedAt = endedAt
            s.ended = true
            try {
                store.writeSession(s)
            } finally {
                if (activeSessionId == s.id) activeSessionId = null
                val saved = s.screens.size
                main.post { onDone(saved) }
            }
        }
    }

    private fun closeSegment() {
        val dir = segmentDir ?: return
        segmentDir = null
        val exitedAt = System.currentTimeMillis()
        val s = session
        afterFrames {
            if (saveScreen(store, s, dir, exitedAt, topInset, bottomInset)) {
                val saved = s.screens.size
                main.post { onSaved(s.id, saved) }
            }
        }
    }

    companion object {
        private const val TAG = "SessionRecorder"

        private val frameExecutor = Executors.newSingleThreadExecutor { Thread(it, "qa-frame") }
        private val stitchExecutor = Executors.newSingleThreadExecutor { Thread(it, "qa-stitch") }
        private val main = Handler(Looper.getMainLooper())

        /** 진행 중인 세션. 복구 작업이 건드리지 않게 한다. */
        @Volatile
        private var activeSessionId: String? = null

        /** 이미 예약된 프레임 기록이 끝난 뒤 스티칭 스레드에서 [task] 를 실행한다. */
        private fun afterFrames(task: () -> Unit) {
            frameExecutor.execute {
                stitchExecutor.execute {
                    try {
                        task()
                    } catch (e: Exception) {
                        Log.e(TAG, "저장 작업 실패", e)
                    }
                }
            }
        }

        /**
         * 서비스가 비정상 종료돼 남은 임시 프레임을 저장하고, 끝나지 않은 세션을 닫는다.
         * 증적 누락을 막기 위한 것으로 서비스가 연결될 때 한 번 부른다.
         */
        fun recoverUnfinished(context: Context, topInset: Int, bottomInset: Int) {
            val store = EvidenceStore(context)
            afterFrames {
                for (s in store.loadSessions()) {
                    if (s.id == activeSessionId) continue
                    for (dir in store.pendingSegments(s.id)) {
                        val exitedAt = store.listFrames(dir).maxOfOrNull { it.lastModified() } ?: dir.lastModified()
                        try {
                            saveScreen(store, s, dir, exitedAt, topInset, bottomInset)
                        } catch (e: Exception) {
                            Log.e(TAG, "복구 실패: ${dir.path}", e)
                        }
                    }
                    if (!s.ended) {
                        s.ended = true
                        s.endedAt = s.endedAt ?: (s.screens.maxOfOrNull { it.exitedAt } ?: s.startedAt)
                        store.writeSession(s)
                    }
                }
            }
        }

        /**
         * 구간 폴더의 프레임을 스티칭해 PNG 로 저장하고 manifest 에 항목을 추가한다(스티칭 스레드 전용).
         * 저장한 화면이 있으면 true. 실패 시 예외를 던지고 임시 프레임은 다음 복구를 위해 남긴다.
         */
        private fun saveScreen(
            store: EvidenceStore,
            session: SessionData,
            dir: File,
            exitedAt: Long,
            topInset: Int,
            bottomInset: Int,
        ): Boolean {
            val info = store.readScreenInfo(dir)
            val frameFiles = store.listFrames(dir)
            if (info == null || frameFiles.isEmpty()) {
                dir.deleteRecursively()
                return false
            }

            EvidenceStore.FrameReader().use { reader ->
                // 1) 프레임마다 행 해시만 남긴다(픽셀 배열은 재사용)
                val frames = ArrayList<Stitcher.Frame>(frameFiles.size)
                val usable = ArrayList<File>(frameFiles.size)
                var buffer: IntArray? = null
                for (f in frameFiles) {
                    val px = reader.read(f, buffer) ?: continue
                    buffer = px
                    frames += Stitcher.Frame(reader.width, reader.height, Stitcher.rowHashes(px, reader.width, reader.height))
                    usable += f
                }
                if (frames.isEmpty()) {
                    dir.deleteRecursively()
                    return false
                }

                // 2) 합성 계획 → part 마다 행 단위로 PNG 렌더링
                val plan = Stitcher.plan(frames, topInset, bottomInset)
                val seq = session.screens.size + 1
                val base = EvidenceStore.baseName(seq, info.packageName, info.className, info.enteredAt)
                val names = plan.parts.mapIndexed { i, part ->
                    val name = if (i == 0) "$base.png" else "${base}_part${i + 1}.png"
                    store.saveFile(EvidenceStore.sessionPath(session.id), name, "image/png") { out ->
                        renderPart(part, out) { frame, into ->
                            val px = reader.read(usable[frame], into)
                                ?: throw IOException("프레임을 읽을 수 없습니다: ${usable[frame].name}")
                            if (px !== into) px.copyInto(into)
                        }
                    }
                    name
                }

                // 3) manifest 갱신
                session.screens += ScreenRecord(
                    seq = seq,
                    files = names,
                    packageName = info.packageName,
                    className = info.className,
                    enteredAt = info.enteredAt,
                    exitedAt = exitedAt,
                    frames = frameFiles.size,
                    stitch = plan.result,
                )
                store.writeSession(session)
            }
            dir.deleteRecursively()
            return true
        }
    }
}
