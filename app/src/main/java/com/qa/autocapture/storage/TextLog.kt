package com.qa.autocapture.storage

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MediaStore 텍스트 파일에 한 줄씩 덧붙여 쓴다. 한 줄 = 시각(HH:mm:ss.SSS) + 탭으로 구분한 필드.
 * 세션 로그(세션 폴더의 session_log.txt)와 디버그 로그(debug/{세션}.txt)에 쓴다.
 * 쓰기는 전용 스레드에서 하고, 마지막 쓰기 1초 뒤 flush 해 비정상 종료 때도 대부분 남긴다.
 */
class TextLog(context: Context, relativePath: String, name: String, header: List<String>) : Closeable {

    /** io 스레드에서만 쓴다. */
    private var writer: Writer? = null
    private var flushScheduled = false

    init {
        val store = EvidenceStore(context)
        io.execute {
            try {
                val out = store.createStream(relativePath, name, "text/plain")
                writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024).apply {
                    for (line in header) write("$line\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "로그 파일을 만들 수 없습니다: $relativePath$name", e)
            }
        }
    }

    /** 한 줄 기록. 접근성 이벤트 객체는 재사용되므로 호출자가 값을 꺼내서 넘긴다. */
    fun write(millis: Long, vararg fields: CharSequence?) {
        io.execute {
            val w = writer ?: return@execute
            try {
                w.write(TIME.format(Instant.ofEpochMilli(millis)))
                for (field in fields) {
                    w.write('\t'.code)
                    w.write(clean(field))
                }
                w.write('\n'.code)
                if (!flushScheduled) {
                    flushScheduled = true
                    io.schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            } catch (e: IOException) {
                Log.w(TAG, "로그 쓰기 실패", e)
                runCatching { w.close() }
                writer = null
            }
        }
    }

    private fun flush() {
        flushScheduled = false
        runCatching { writer?.flush() }
    }

    override fun close() {
        io.execute {
            runCatching { writer?.close() }
            writer = null
        }
    }

    private companion object {
        const val TAG = "TextLog"
        const val FLUSH_DELAY_MS = 1000L
        val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "qa-log") }
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault())

        fun clean(s: CharSequence?): String = s?.toString()?.replace('\t', ' ')?.replace('\n', ' ')?.replace('\r', ' ') ?: ""
    }
}
