package ua.faric.trackcatcher.core

import java.util.Calendar

object TimeText {
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) {
            "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        } else {
            "%d:%02d".format(s / 60, s % 60)
        }
    }

    /** 9 600 960 → «9,2 МБ». */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024) return oneDecimal(kb) + " КБ"
        val mb = kb / 1024.0
        if (mb < 1024) return oneDecimal(mb) + " МБ"
        return oneDecimal(mb / 1024.0) + " ГБ"
    }

    private fun oneDecimal(v: Double): String {
        val tenths = Math.round(v * 10)
        return "${tenths / 10},${tenths % 10}"
    }

    fun dateTime(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d.%02d, %02d:%02d".format(
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
    }

    fun time(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** Українські форми: 1 пісня, 2 пісні, 5 пісень. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        val word = when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
        return "$n $word"
    }
}
