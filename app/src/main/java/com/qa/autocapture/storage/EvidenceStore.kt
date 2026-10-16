package com.qa.autocapture.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** MediaStore 에 저장된 내 파일 1개. */
class StoredFile(val uri: Uri, val name: String, val mime: String?)

/**
 * 결과물 파일(사용자에게 보이는 것) 입출력. MediaStore 로 Documents/QAEvidence/ 아래에 쓰고 읽는다.
 * 저장소 권한 없이 이 앱이 만든 파일만 다룬다. 경로 규칙은 [StoragePaths].
 */
class EvidenceStore(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** [overwrite] 한 파일의 URI. 쓸 때마다 다시 조회하지 않게 기억한다. */
    private val overwriteUris = HashMap<String, Uri>()

    /** 새 파일을 만들고 [write] 로 내용을 쓴다. 실패하면 만든 항목을 지운다. */
    fun saveFile(relativePath: String, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore 항목을 만들 수 없습니다: $relativePath$name")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("파일을 열 수 없습니다: $name")
            BufferedOutputStream(out, 256 * 1024).use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** 파일 내용을 통째로 바꾼다(manifest.json). 없거나 지워졌으면 새로 만든다. */
    fun overwrite(relativePath: String, name: String, mime: String, bytes: ByteArray) {
        val key = relativePath + name
        val existing = overwriteUris[key] ?: findFile(relativePath, name)
        if (existing != null) {
            try {
                val out = resolver.openOutputStream(existing, "wt")
                if (out != null) {
                    out.use { it.write(bytes) }
                    overwriteUris[key] = existing
                    return
                }
            } catch (e: IOException) {
                Log.w(TAG, "덮어쓰기 실패, 새로 만듭니다: $name", e)
            }
        }
        overwriteUris[key] = saveFile(relativePath, name, mime) { it.write(bytes) }
    }

    /** 계속 덧붙여 쓸 파일을 만든다(세션 로그, 디버그 로그). 스트림은 호출자가 닫는다. */
    fun createStream(relativePath: String, name: String, mime: String): OutputStream {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore 항목을 만들 수 없습니다: $relativePath$name")
        return resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            throw IOException("파일을 열 수 없습니다: $name")
        }
    }

    fun findFile(relativePath: String, name: String): Uri? = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(relativePath, name),
        null,
    )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

    /** 세션 폴더의 내 파일(이미지, manifest.json, session_log.txt)을 이름순으로. */
    fun listFiles(sessionId: String): List<StoredFile> {
        val out = ArrayList<StoredFile>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(StoragePaths.sessionPath(sessionId)),
            "${MediaStore.MediaColumns.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) out += StoredFile(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1), c.getString(2))
        }
        return out
    }

    /** 텍스트 파일을 읽는다. [maxBytes] 보다 길면 뒷부분만. */
    fun readText(uri: Uri, maxBytes: Int): String {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        val from = maxOf(0, bytes.size - maxBytes)
        return String(bytes, from, bytes.size - from, Charsets.UTF_8)
    }

    /**
     * 세션 폴더의 내 파일을 zip 으로 묶어 Documents/QAEvidence/{세션}.zip 에 저장한다(기존 zip 은 교체).
     * 디버그 로그가 있으면 debug_log.txt 로 넣는다. 공유 인텐트에 그대로 쓸 수 있는 URI 를 돌려준다.
     */
    fun exportZip(sessionId: String): Uri {
        val items = listFiles(sessionId).map { it.uri to it.name }.toMutableList()
        if (items.isEmpty()) throw IOException("저장된 파일이 없습니다")
        findFile(StoragePaths.DEBUG_PATH, StoragePaths.debugLogFile(sessionId))?.let { items += it to "debug_log.txt" }

        val zipName = StoragePaths.zipFile(sessionId)
        findFile(StoragePaths.ROOT_PATH, zipName)?.let { resolver.delete(it, null, null) }
        return saveFile(StoragePaths.ROOT_PATH, zipName, "application/zip") { out ->
            val zip = ZipOutputStream(out)
            zip.setLevel(Deflater.BEST_SPEED) // PNG 는 이미 압축돼 있다
            for ((uri, name) in items) {
                zip.putNextEntry(ZipEntry("$sessionId/$name"))
                resolver.openInputStream(uri)?.use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    private companion object {
        const val TAG = "EvidenceStore"
    }
}
