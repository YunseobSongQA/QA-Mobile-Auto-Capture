package com.qa.autocapture.session

import com.qa.autocapture.stitch.Stitcher
import com.qa.autocapture.stitch.renderPart
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.FrameReader
import com.qa.autocapture.storage.FrameStore
import java.io.File
import java.io.IOException

/** 저장한 이미지 1장: 파일 이름과 이어붙인 화면 수. */
internal class SavedImage(val name: String, val frames: Int)

/**
 * 화면 상태 1개의 임시 프레임 → 결과 이미지. stitch 스레드에서 부른다.
 *
 * 프레임이 1장이면 임시 PNG 를 그대로 복사하고, 여러 장이면 겹치는 부분으로 이어붙인 긴 이미지 1장을 쓴다.
 * 겹침을 못 찾은 지점과 MAX_STITCH_HEIGHT 를 넘는 지점에서는 다음 장으로 나눈다(Stitcher.plan).
 */
internal object GroupSaver {

    fun save(
        frameStore: FrameStore,
        evidence: EvidenceStore,
        dir: File,
        relativePath: String,
        topInset: Int,
        bottomInset: Int,
        nextName: () -> String,
    ): List<SavedImage> {
        val saved = ArrayList<SavedImage>()
        FrameReader().use { reader ->
            // 1) 프레임마다 행 해시만 남긴다(픽셀 배열은 재사용)
            val frames = ArrayList<Stitcher.Frame>()
            val files = ArrayList<File>()
            val hints = ArrayList<Int?>()
            var buffer: IntArray? = null
            var skipped = false
            for (f in frameStore.listFrames(dir)) {
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
                evidence.saveFile(relativePath, name, "image/png") { out ->
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
                saved += SavedImage(name, part.slices.map { it.frame }.distinct().size)
            }
        }
        return saved
    }
}
