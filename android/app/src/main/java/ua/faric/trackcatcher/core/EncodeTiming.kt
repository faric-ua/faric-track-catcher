package ua.faric.trackcatcher.core

/**
 * Коли стискати записані пісні у FLAC / MP3 (T85, faric 19.10 22:45: «на M30s ривки по всім файлам — оптимізуй для запису,
 * щоб систему ніхто не нагружав»; 20.10 00:14: «робимо заради якості»).
 *
 * Раніше попередня пісня стискалась, поки пишеться наступна. На слабкому телефоні (M30s: 3,5 ГБ, вільно ~80 МБ) це відбирає
 * процесор і пам'ять у тракту звуку Android — він губить шматочки по 10–20 мс («ривки», T84).
 * «Після «Стоп»» — під час запису лише сирий звук на диск, стискання — коли запис зупинено.
 */
enum class EncodeTiming(val code: String, val label: String) {
    AUTO("auto", "Сам вирішує"),
    NOW("now", "Одразу після пісні"),
    AFTER("after", "Після «Стоп»");

    companion object {
        /** Менше пам'яті — «слабкий» телефон (M30s — 3,5 ГБ; A26 — 7,2 ГБ). */
        const val WEAK_RAM_BYTES = 5L * 1024 * 1024 * 1024

        fun fromCode(code: String?): EncodeTiming = entries.firstOrNull { it.code == code } ?: AUTO

        /** Чи відкладати стискання до «Стоп». [totalRamBytes] 0 — невідомо (тоді — як «одразу», якщо Android не каже «мало пам'яті»). */
        fun defer(timing: EncodeTiming, totalRamBytes: Long, lowRamDevice: Boolean): Boolean = when (timing) {
            NOW -> false
            AFTER -> true
            AUTO -> lowRamDevice || (totalRamBytes in 1 until WEAK_RAM_BYTES)
        }

        /** «3,5 ГБ» для підпису в «Налаштуваннях». */
        fun ramText(totalRamBytes: Long): String {
            if (totalRamBytes <= 0) return "невідомо скільки"
            val tenths = Math.round(totalRamBytes * 10.0 / (1024.0 * 1024 * 1024))
            return "${tenths / 10},${tenths % 10} ГБ"
        }
    }
}
