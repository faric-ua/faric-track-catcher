package ua.faric.trackcatcher.core

import java.security.MessageDigest
import java.util.Locale

/**
 * Оновлення застосунку з Google Диска власника (faric 14.10 20:22: «як у Dorama.land — APK на
 * Google Диску для оновлення»). Перенесено з Dorama T88 (пас 14.10 04:31).
 *
 * Телефон автора один раз створює на своєму Диску два файли (вікном Android, вхід у Google не
 * потрібен) і далі завжди пише в ті самі файли — посилання не змінюються: APK і маленький
 * «файл версії». Обидва відкриті «Усім, хто має посилання». Будь-який телефон читає файл версії
 * без входу в Google, порівнює versionCode, завантажує APK, перевіряє SHA-256, пакет, версію й
 * ключ підпису — і лише тоді відкриває вікно Android «Оновити цей застосунок?».
 *
 * Тут — лише правила без Android (посилання, файл версії, рішення), щоб їх покривали тести.
 * JSON — свій маленький: у JVM-тестах org.json з Android — лише заглушка.
 */
object DriveUpdate {
    const val FORMAT = "track-catcher-update/1"
    const val APK_FILE_NAME = "Track Catcher.apk"
    const val VERSION_FILE_NAME = "track-catcher-onovlennia.json"

    /**
     * Посилання вставили не на файл версії (T46, 14.10 22:19: faric вставив посилання на сам APK —
     * «Файл на Диску завеликий (6,1 МБ)»). Таке посилання не зберігається, а вже збережене — забувається.
     */
    class WrongFileException(message: String) : IllegalArgumentException(message)

    const val LINK_HINT = "Потрібне посилання саме на маленький файл версії «$VERSION_FILE_NAME», не на APK. " +
        "Найпростіше: на телефоні автора «Опублікувати цю версію на Диск» → «Поділитися посиланням на оновлення»."

    fun linkIsApkMessage(size: Long): String =
        "За цим посиланням — сам застосунок (APK" + (if (size > 0) ", ${formatMb(size)}" else "") + "), а не файл версії.\n\n$LINK_HINT"

    /**
     * Які файли версії пробувати по черзі: вставлене зараз — лише його; інакше збережене, потім
     * вбудоване в застосунок (якщо збережене виявиться не тим файлом).
     */
    /** З прочитаних файлів версії — з найбільшим versionCode; при рівних — перший (збережене раніше за вбудоване). */
    fun newest(read: List<Pair<String, Manifest>>): Pair<String, Manifest>? =
        read.fold(null as Pair<String, Manifest>?) { best, cur -> if (best == null || cur.second.versionCode > best.second.versionCode) cur else best }

    fun manifestCandidates(pasted: String?, saved: String?, builtIn: String): List<String> =
        if (!pasted.isNullOrBlank()) listOf(pasted)
        else listOfNotNull(saved?.takeIf { it.isNotBlank() }, builtIn.takeIf { it.isNotBlank() }).distinct()

    private val BARE_ID = Regex("^[A-Za-z0-9_-]{20,80}$")
    private val PATH_ID = Regex("/(?:file/)?d/([A-Za-z0-9_-]{20,80})")
    private val QUERY_ID = Regex("[?&]id=([A-Za-z0-9_-]{20,80})")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /**
     * Те, що дає «Копіювати посилання» в Google Диску (`https://drive.google.com/file/d/ID/view?usp=sharing`),
     * старі форми `open?id=ID` / `uc?id=ID`, або сам ID. Посилання на папку — null навмисно:
     * папку без входу в Google не прочитати.
     */
    fun fileIdFromLink(text: String?): String? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (BARE_ID.matches(value)) return value
        val lower = value.lowercase(Locale.ROOT)
        if (!lower.contains("google.com")) return null
        if (lower.contains("/folders/")) return null
        PATH_ID.find(value)?.let { return it.groupValues[1] }
        QUERY_ID.find(value)?.let { return it.groupValues[1] }
        return null
    }

    /** Пряме завантаження відкритого файлу. `confirm=t` — без сторінки «не можу перевірити на віруси». */
    fun downloadUrl(fileId: String): String =
        "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

    /** Посилання для людей (пишеться у файл версії). */
    /** Код, з яким варто спробувати ще раз: тимчасовий збій Google (5xx) чи «забагато запитів» (429). */
    fun retryable(code: Int): Boolean = code == 429 || code in 500..599

    fun viewUrl(fileId: String): String = "https://drive.google.com/file/d/$fileId/view"

    /** Закритий чи видалений файл Диск віддає HTML-сторінкою входу/помилки, а не файлом. */
    fun looksLikeHtml(contentType: String?, head: ByteArray?, length: Int): Boolean {
        if (contentType != null && contentType.lowercase(Locale.ROOT).contains("text/html")) return true
        if (head == null) return false
        val end = minOf(length, head.size)
        var start = 0
        while (start < end && head[start].toInt().toChar().isWhitespace()) start++
        if (start >= end) return false
        val prefix = String(head, start, minOf(15, end - start), Charsets.US_ASCII).lowercase(Locale.ROOT)
        return prefix.startsWith("<!doctype") || prefix.startsWith("<html")
    }

    /** APK — це ZIP: починається з «PK». */
    fun looksLikeApk(head: ByteArray?, length: Int): Boolean =
        head != null && length >= 2 && head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

    fun sha256Hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun formatMb(bytes: Long): String = String.format(Locale.ROOT, "%.1f МБ", bytes / (1024.0 * 1024.0)).replace('.', ',')

    /** Файл версії, який пише телефон автора. */
    data class Manifest(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val apkSha256: String,
        val apkSize: Long,
        val apkId: String,
        val publishedAt: Long,
    ) {
        fun toJson(): String = buildString {
            append("{\n")
            val fields = listOf(
                "format" to FORMAT,
                "package" to packageName,
                "versionCode" to versionCode,
                "versionName" to versionName,
                "apkSha256" to apkSha256,
                "apkSize" to apkSize,
                "apkId" to apkId,
                "apkLink" to viewUrl(apkId),
                "publishedAt" to publishedAt,
            )
            fields.forEachIndexed { i, (k, v) ->
                append("  ").append(MiniJson.quote(k)).append(": ")
                append(if (v is String) MiniJson.quote(v) else v.toString())
                append(if (i == fields.lastIndex) "\n" else ",\n")
            }
            append("}\n")
        }

        companion object {
            /** Читає файл версії. Не наш чи неповний — [IllegalArgumentException] з реченням для екрана. */
            fun parse(text: String?): Manifest {
                val json = try {
                    MiniJson.parseFlat(text.orEmpty())
                } catch (e: IllegalArgumentException) {
                    throw WrongFileException("За цим посиланням — не файл версії Track Catcher.\n\n$LINK_HINT")
                }
                if (json["format"] != FORMAT) {
                    if (json["format"] == "track-catcher-update/0") throw IllegalArgumentException("Автор ще не опублікував жодної версії.")
                    throw WrongFileException("Це не файл оновлень Track Catcher (інший формат).\n\n$LINK_HINT")
                }
                val pkg = json["package"] as? String ?: ""
                val code = (json["versionCode"] as? Long) ?: -1L
                val name = json["versionName"] as? String ?: ""
                val sha = (json["apkSha256"] as? String ?: "").lowercase(Locale.ROOT)
                val size = (json["apkSize"] as? Long) ?: -1L
                val apkId = json["apkId"] as? String ?: ""
                if (pkg.isEmpty() || code <= 0 || !SHA256.matches(sha) || size <= 0 || !BARE_ID.matches(apkId)) {
                    throw IllegalArgumentException("Файл версії на Диску неповний — автор має опублікувати версію ще раз.")
                }
                return Manifest(pkg, code, name.ifEmpty { code.toString() }, sha, size, apkId, (json["publishedAt"] as? Long) ?: 0L)
            }
        }
    }

    /** Порожній файл версії, поки автор нічого не опублікував (щоб у Диску можна було скопіювати посилання). */
    const val PLACEHOLDER = "{\"format\": \"track-catcher-update/0\", \"note\": \"ще не опубліковано\"}\n"

    enum class Verdict { NEWER, SAME, OLDER, OTHER_PACKAGE }

    fun compare(m: Manifest, installedPackage: String, installedCode: Long): Verdict = when {
        m.packageName != installedPackage -> Verdict.OTHER_PACKAGE
        m.versionCode > installedCode -> Verdict.NEWER
        m.versionCode == installedCode -> Verdict.SAME
        else -> Verdict.OLDER
    }

    /**
     * Перевірка завантаженого APK проти файлу версії й установленого застосунку.
     * null — усе збіглося; інакше речення для екрана.
     */
    fun checkDownloaded(
        m: Manifest,
        sha256: String,
        size: Long,
        apkPackage: String?,
        apkVersionCode: Long,
        apkCertSha256: String?,
        installedCertSha256: String?,
    ): String? {
        if (size != m.apkSize || !m.apkSha256.equals(sha256, ignoreCase = true)) {
            return "Завантажений файл не збігається з файлом версії (контрольна сума). " +
                "Можливо, автор саме зараз публікує нову версію — спробуй через кілька хвилин."
        }
        if (apkPackage == null) return "Android не зміг прочитати завантажений APK."
        if (apkPackage != m.packageName) return "Завантажений APK — для іншого застосунку ($apkPackage). Не встановлюю."
        if (apkVersionCode != m.versionCode) {
            return "Версія всередині APK ($apkVersionCode) не та, що у файлі версії (${m.versionCode}). Не встановлюю."
        }
        if (apkCertSha256 != null && installedCertSha256 != null && !apkCertSha256.equals(installedCertSha256, ignoreCase = true)) {
            return "APK підписаний іншим ключем, ніж встановлений застосунок. Не встановлюю — це може бути чужий файл."
        }
        return null
    }
}

/** Плаский JSON-об'єкт: рядки, цілі числа, true/false/null. Вкладене — помилка. */
internal object MiniJson {
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun parseFlat(text: String): Map<String, Any?> {
        val p = Parser(text)
        p.ws()
        p.expect('{')
        val out = LinkedHashMap<String, Any?>()
        p.ws()
        if (p.peek() == '}') {
            p.i++
        } else {
            while (true) {
                p.ws()
                val key = p.string()
                p.ws()
                p.expect(':')
                p.ws()
                out[key] = p.value()
                p.ws()
                when (p.next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw IllegalArgumentException("json")
                }
            }
        }
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("json")
        return out
    }

    private class Parser(val s: String) {
        var i = 0
        fun peek(): Char = if (i < s.length) s[i] else throw IllegalArgumentException("json")
        fun next(): Char = peek().also { i++ }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) { if (next() != c) throw IllegalArgumentException("json") }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw IllegalArgumentException("json")
                            sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("json"))
                            i += 4
                        }
                        else -> throw IllegalArgumentException("json: \\$e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun value(): Any? {
            val c = peek()
            return when {
                c == '"' -> string()
                c == '-' || c.isDigit() -> {
                    val start = i
                    i++
                    while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
                    val raw = s.substring(start, i)
                    raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw IllegalArgumentException("json")
                }
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> throw IllegalArgumentException("json")
            }
        }
    }
}
