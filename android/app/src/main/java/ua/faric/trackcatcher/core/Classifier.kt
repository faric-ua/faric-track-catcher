package ua.faric.trackcatcher.core

enum class RecStatus(val code: String, val label: String) {
    COMPLETE("complete", "повний"),
    PARTIAL("partial", "неповний"),
    FAILED("failed", "невдало"),
    SKIPPED("skipped", "пропущено");

    companion object {
        fun fromCode(code: String?): RecStatus = entries.firstOrNull { it.code == code } ?: FAILED
    }
}

data class Verdict(val status: RecStatus, val reasons: List<String>) {
    val reasonText: String get() = reasons.joinToString("; ")
}

/** Повний / неповний / невдало / пропущено — і чому. */
object Classifier {
    const val START_TOLERANCE_MS = 4_000L

    fun classify(
        meta: TrackMeta,
        lengthMs: Long,
        audibleMs: Long,
        startPositionMs: Long?,
        end: EndReason,
        minLengthSec: Int,
    ): Verdict {
        if (audibleMs < 1_000) {
            // Тиша до того, як щось заграло (назви ще немає), — не помилка плеєра.
            if (meta.isUnknown) return Verdict(RecStatus.SKIPPED, listOf("тиша — музика ще не грала"))
            return if (lengthMs >= 5_000) {
                Verdict(RecStatus.FAILED, listOf("тиша — цей плеєр, схоже, не дає себе записувати"))
            } else {
                Verdict(RecStatus.SKIPPED, listOf("порожній шматок"))
            }
        }
        if (lengthMs < minLengthSec * 1000L) {
            return Verdict(RecStatus.SKIPPED, listOf("коротше $minLengthSec с"))
        }
        val reasons = mutableListOf<String>()
        val start = startPositionMs ?: 0L
        if (start > START_TOLERANCE_MS) reasons += "почато посеред (з ${TimeText.clock(start)})"
        val dur = meta.durationMs
        if (dur > 0) {
            val tol = maxOf(4_000L, dur * 4 / 100)
            if (start + lengthMs + tol < dur) {
                reasons += if (end == EndReason.STOP) "зупинено посеред" else "перемкнуто до кінця пісні"
            }
        } else if (end == EndReason.STOP) {
            reasons += "зупинено (тривалість пісні невідома)"
        }
        return if (reasons.isEmpty()) Verdict(RecStatus.COMPLETE, emptyList()) else Verdict(RecStatus.PARTIAL, reasons)
    }
}
