package com.qa.autocapture.storage

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 시각 표시·기록 형식. 기기 시간대 기준. */
object Times {

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

    /** 화면 표시·파일 이름용. 예: format(t, "yyyy-MM-dd HH:mm:ss") */
    fun format(millis: Long, pattern: String): String =
        DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** manifest·session.json 기록용(밀리초 + 시간대). */
    fun iso(millis: Long): String = ISO.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** [iso] 의 역. 밀리초가 없는 이전 버전 기록도 읽는다. */
    fun parseIso(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
}
