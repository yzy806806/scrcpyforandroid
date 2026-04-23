package io.github.miuzarte.scrcpyforandroid.services

import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import java.time.LocalDateTime

object RecordFilenameTemplate {
    data class Entry(
        val value: String,
        val description: String?,
        val isTemplate: Boolean,
    )

    fun resolve(
        template: String,
        sessionInfo: Scrcpy.Session.SessionInfo,
        now: LocalDateTime = LocalDateTime.now(),
    ) =
        if (template.isBlank()) ""
        else buildString(template.length) {
            var index = 0
            while (index < template.length) {
                val start = template.indexOf($$"${", index)
                if (start < 0) {
                    append(template.substring(index))
                    break
                }
                append(template.substring(index, start))
                val end = template.indexOf('}', start + 2)
                if (end < 0) {
                    append(template.substring(start))
                    break
                }
                val key = template.substring(start + 2, end)
                if (key.all { it.isLetterOrDigit() || it == '_' }) {
                    append(valueOf(key, sessionInfo, now))
                } else {
                    append(template.substring(start, end + 1))
                }
                index = end + 1
            }
        }.trim()


    val entries
        get() = listOf(
            Entry("-", null, false),
            Entry("_", null, false),
            Entry($$"${YYYY}", "四位年份，例如 2026", true),
            Entry($$"${YY}", "两位年份，例如 26", true),
            Entry($$"${MM}", "月份，两位，例如 09", true),
            Entry($$"${M}", "月份，一位或两位，例如 9", true),
            Entry($$"${DD}", "日期，两位，例如 19", true),
            Entry($$"${D}", "日期，一位或两位，例如 9", true),
            Entry($$"${HH}", "24 小时制小时，两位，例如 23", true),
            Entry($$"${H}", "24 小时制小时，一位或两位，例如 9", true),
            Entry($$"${hh}", "12 小时制小时，两位，例如 11", true),
            Entry($$"${h}", "12 小时制小时，一位或两位，例如 9", true),
            Entry($$"${mm}", "分钟，两位，例如 09", true),
            Entry($$"${m}", "分钟，一位或两位，例如 9", true),
            Entry($$"${SS}", "秒，两位，例如 09", true),
            Entry($$"${S}", "秒，一位或两位，例如 9", true),
            Entry($$"${timestamp}", "秒级时间戳，例如 1776952809", true),
            Entry($$"${deviceName}", "设备名", true),
            Entry($$"${deviceIp}", "设备 IP", true),
            Entry($$"${devicePort}", "设备端口", true),
            Entry($$"${videoCodec}", "视频串流编码（非文件实际编码）", true),
            Entry($$"${audioCodec}", "音频串流编码（非文件实际编码）", true),
            Entry($$"${width}", "视频宽度", true),
            Entry($$"${height}", "视频高度", true),
            Entry(".mp4", null, false),
            Entry(".m4a", null, false),
            Entry(".aac", null, false),
            Entry(".wav", null, false),
        )

    private fun valueOf(
        key: String,
        sessionInfo: Scrcpy.Session.SessionInfo,
        now: LocalDateTime,
    ) = when (key) {
        "YYYY" -> now.year.toString().padStart(4, '0')
        "YY" -> (now.year % 100).toString().padStart(2, '0')
        "MM" -> now.monthValue.toString().padStart(2, '0')
        "M" -> now.monthValue.toString()
        "DD" -> now.dayOfMonth.toString().padStart(2, '0')
        "D" -> now.dayOfMonth.toString()
        "HH" -> now.hour.toString().padStart(2, '0')
        "H" -> now.hour.toString()
        "hh" -> (now.hour % 12).let { h -> ((if (h == 0) 12 else h).toString().padStart(2, '0')) }
        "h" -> (now.hour % 12).let { h -> (if (h == 0) 12 else h).toString() }
        "mm" -> now.minute.toString().padStart(2, '0')
        "m" -> now.minute.toString()
        "SS" -> now.second.toString().padStart(2, '0')
        "S" -> now.second.toString()
        "timestamp" -> now.atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toString()
        "deviceName" -> sessionInfo.deviceName
        "deviceIp" -> sessionInfo.host
        "devicePort" -> sessionInfo.port.toString()
        "videoCodec" -> sessionInfo.codec?.string ?: "unknown"
        "audioCodec" -> sessionInfo.audioCodec?.string ?: "unknown"
        "width" -> sessionInfo.width.toString()
        "height" -> sessionInfo.height.toString()
        else -> $$"${$$key}"
    }
}
