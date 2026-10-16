package com.qa.autocapture.storage

import android.content.Context
import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/** 화면 상태 1개(결과 이미지 1장)의 임시 폴더 정보: 그 화면의 순번과 결과 파일 이름 앞부분. */
class GroupInfo(val seq: Int, val base: String)

/** 임시 프레임 1장. [hint] = 직전 프레임부터의 scrollDeltaY 합(모르면 null). */
class FrameFile(val file: File, val hint: Int?)

/**
 * 임시 프레임 폴더. 화면 상태가 끝나 이미지로 저장할 때까지 프레임을 여기 둔다.
 *
 * noBackupFiles/sessions/{세션}/groups/{방문4자리}_{상태2자리}/
 * ├── info.txt                 화면 순번, 결과 파일 이름 앞부분
 * └── {번호4자리}_{scrollDy|n}.png
 */
class FrameStore(context: Context) {

    private val root = StoragePaths.internalRoot(context)

    fun groupDir(sessionId: String, visit: Int, group: Int) =
        File(root, "$sessionId/groups/" + String.format(Locale.ROOT, "%04d_%02d", visit, group))

    /** 저장되지 않고 남은(비정상 종료) 임시 폴더. 이전 버전의 scroll/ 폴더도 포함한다. */
    fun pendingGroupDirs(sessionId: String): List<File> =
        listOf("groups", "scroll").flatMap { sub ->
            File(root, "$sessionId/$sub").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        }

    fun writeGroupInfo(dir: File, info: GroupInfo) {
        dir.mkdirs()
        File(dir, GROUP_INFO_FILE).writeText("${info.seq}\n${info.base}")
    }

    fun readGroupInfo(dir: File): GroupInfo? = try {
        val lines = File(dir, GROUP_INFO_FILE).readLines()
        GroupInfo(lines[0].trim().toInt(), lines[1].trim())
    } catch (e: Exception) {
        null
    }

    /** 프레임을 임시 PNG 로 쓴다(같은 번호면 덮어씀). 끝까지 쓴 파일만 .png 로 보이게 해서 반쯤 쓴 프레임을 읽지 않는다. */
    fun writeFrame(dir: File, index: Int, hint: Int?, bitmap: Bitmap) {
        dir.mkdirs()
        val name = String.format(Locale.ROOT, "%04d_%s.png", index, hint?.toString() ?: "n")
        val tmp = File(dir, "$name.tmp")
        BufferedOutputStream(FileOutputStream(tmp)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 같은 번호의 이전 파일(이동량이 다른 이름일 수 있음)을 지운다
        dir.listFiles { f -> f.name.startsWith(name.substring(0, 5)) && f.name.endsWith(".png") }?.forEach { it.delete() }
        if (!tmp.renameTo(File(dir, name))) throw IOException("임시 프레임 저장 실패: $name")
    }

    /** 번호순. 이름 = {번호}_{이동량|n}[_…](이전 버전 이름도 읽는다). */
    fun listFrames(dir: File): List<FrameFile> =
        dir.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }?.mapNotNull { f ->
            val parts = f.nameWithoutExtension.split('_')
            if (parts.size < 2) null else FrameFile(f, parts[1].toIntOrNull())
        } ?: emptyList()

    private companion object {
        const val GROUP_INFO_FILE = "info.txt"
    }
}
