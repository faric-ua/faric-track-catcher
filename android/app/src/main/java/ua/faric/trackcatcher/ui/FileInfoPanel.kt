package ua.faric.trackcatcher.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import ua.faric.trackcatcher.core.FileTags
import ua.faric.trackcatcher.core.TagNames
import ua.faric.trackcatcher.core.TimeText

/**
 * ── СПІЛЬНА ПАНЕЛЬ «ПРО ФАЙЛ» (Track Catcher → Audio Converter) ── версія [PANEL_VERSION].
 * Цей файл, `core/FileTags.kt`, `core/TagNames.kt`, `ui/FlowLayout.kt` і `core/FlowRows.kt` копіюються в Audio Converter
 * (`tools/dev/sync_style.py` там). Змінив панель — підніми [PANEL_VERSION] і рядок у «Журнал стилю».
 *
 * Вікно «Про файл» (T68, faric 17.10 20:31: «щоб усі метадані були максимально; відкрив файл — зразу показало і картинку,
 * і хто виконує, і альбом, і все»). Показує те, що **лежить у самому файлі** ([FileTags]), тож видно й поля,
 * які дописав інший застосунок (Audio Converter). Будується лише з [Ui] і [Palette].
 *
 * Версія 2 (faric 18.10 02:01): теги словами й по групах — [TagNames].
 * Версія 3 (faric 18.10 03:21, відео: «інформація вся є, але оформлення не дуже»; макет — прев'ю, екран 4):
 * шапка — обкладинка (або хрестик) + назва + плашки звуку; теги **один раз** (без «Що бачить плеєр»), підпис над значенням,
 * короткі — по два в ряд; порожні — одним рядком «Не заповнено: …»; довгий текст «Як записано» — під «Докладніше»;
 * шлях — цеглинками. 03:28: «у кінці — усі теги просто підряд, як було, тільки без скорочень» → «Усі теги підряд» (`TITLE = …`).
 */
class FileInfoPanel(private val host: MainActivity) {
    private val ui: Ui get() = host.ui

    companion object {
        /** Версія спільної панелі (faric 17.10 23:39: «як у Track Catcher "Про файл" — зроби й у конвертері»). */
        const val PANEL_VERSION = 5

        /** Значення до стількох знаків стає в пів рядка (по два в ряд). */
        private const val SHORT = 18
    }

    /** Що знає застосунок про файл, але чого нема в самому файлі ([recordedTitle] — заголовок цієї групи), і що можна зробити. */
    class Extra(
        val recorded: List<Pair<String, String>> = emptyList(),
        val historyArtwork: String? = null,
        val clips: Int = -1,
        val makeFlac: (() -> Unit)? = null,
        val recordedTitle: String = "Як записано",
    )

    fun build(info: FileTags.FileInfo, fileName: String, folder: String?, size: Long, extra: Extra, onClose: () -> Unit): View {
        val box = ui.column(12)
        box.addView(ui.label("Про файл", LineIcon.FILE))
        box.addView(header(info, size, extra), ui.matchWrap())
        box.addView(tagsCard(info), ui.matchWrap())
        if (extra.recorded.isNotEmpty()) box.addView(recordedCard(extra), ui.matchWrap())
        box.addView(fileCard(info, fileName, folder), ui.matchWrap())
        if (info.raw.isNotEmpty()) box.addView(rawCard(info), ui.matchWrap())

        // faric 18.10 03:35: «кнопки різних розмірів — хай займають на двох один рядок»: порівну завширшки
        // й однакової висоти (кнопка зі значком вища — друга тягнеться до неї), підпис в один рядок.
        val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ui.iconButton(LineIcon.FILE, "Копіювати", Ui.Kind.NORMAL) { copy(info, fileName, folder, size, extra) }.apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        actions.addView(ui.button("Закрити", Ui.Kind.QUIET) { onClose() }.apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = ui.dp(8) })
        box.addView(actions, ui.matchWrap())
        return box
    }

    // ---------- шапка: обкладинка, назва, плашки звуку ----------

    private fun header(info: FileTags.FileInfo, size: Long, extra: Extra): View {
        val card = ui.card(12)
        val top = ui.row(14).apply { gravity = Gravity.TOP }
        val pic = info.picture
        val bmp = pic?.let { decode(it.bytes, ui.dp(208)) }
        top.addView(coverTile(bmp), LinearLayout.LayoutParams(ui.dp(104), ui.dp(104)))
        val words = ui.column(6)
        words.addView(ui.title(info[FileTags.Field.TITLE] ?: "Без назви", 19f).apply { if (info[FileTags.Field.TITLE] == null) setTextColor(Palette.faint) })
        listOfNotNull(info[FileTags.Field.ARTIST], info[FileTags.Field.ALBUM]).joinToString(" · ").takeIf { it.isNotBlank() }
            ?.let { words.addView(ui.muted(it, 14.5f)) }
        top.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top, ui.matchWrap())

        val chips = FlowLayout(host, ui.dp(6), ui.dp(6), fill = false)
        chips.addView(chip(info.format, Palette.accent, Palette.accentFill))
        if (info.bitrateKbps > 0) chips.addView(chip((if (info.format == "MP3") "" else "≈ ") + "${info.bitrateKbps} кбіт/с"))
        if (info.durationMs > 0) chips.addView(chip(TimeText.clock(info.durationMs)))
        val sound = listOfNotNull(
            info.sampleRate.takeIf { it > 0 }?.let { if (it % 1000 == 0) "${it / 1000} кГц" else "${"%.1f".format(it / 1000.0).replace('.', ',')} кГц" },
            info.channels.takeIf { it > 0 }?.let { when (it) { 1 -> "моно"; 2 -> "стерео"; else -> "$it кан." } },
            info.bits.takeIf { it > 0 }?.let { "$it біт" },
        ).joinToString(" · ")
        if (sound.isNotBlank()) chips.addView(chip(sound))
        if (size > 0) chips.addView(chip(TimeText.size(size)))
        if (extra.clips >= 0) chips.addView(
            if (extra.clips == 0) chip("без перевантаження", Palette.success, Palette.selectedFill)
            else chip("${extra.clips} піків обрізано", Palette.warning, Palette.warningFill),
        )
        card.addView(chips, ui.matchWrap())
        card.addView(ui.muted(formatHint(info), 12.5f))

        if (pic != null) {
            card.addView(ui.muted("Обкладинка: ${pic.mime.substringAfter('/').uppercase().replace("JPG", "JPEG")} ${pic.width}×${pic.height} · " +
                "${TimeText.size(pic.bytes.size.toLong())} · ${TagNames.value("PICTURE", pic.typeLabel)}", 12.5f))
        } else {
            val why = if (info.format == "WAV") "WAV обкладинку не тримає — тому її не бачать ні плеєр, ні конвертер." else "Обкладинки у файлі нема — плеєр покаже заглушку."
            card.addView(ui.text(why, 13f, Palette.warning))
            val inHistory = extra.historyArtwork?.let { decode(java.io.File(it).takeIf { f -> f.isFile }?.readBytes(), ui.dp(64)) }
            if (inHistory != null) {
                val r = ui.row(12).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = ui.rounded(Palette.surfaceAlt, radiusDp = 14)
                    setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                }
                r.addView(image(inHistory), LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
                r.addView(ui.text("Track Catcher зберіг обкладинку в історії, коли записував", 14f, Palette.text),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(r, ui.matchWrap())
                extra.makeFlac?.let { make ->
                    card.addView(ui.iconButton(LineIcon.REPEAT, "Зробити FLAC з обкладинкою", Ui.Kind.PRIMARY, large = true) { make() }, ui.matchWrap())
                    card.addView(ui.muted("Звук той самий (без втрат), обкладинка, назви й номер — усередині файлу. WAV лишається, як був.", 13f))
                }
            }
        }
        return card
    }

    private fun coverTile(bmp: android.graphics.Bitmap?): View {
        if (bmp != null) return image(bmp)
        val frame = FrameLayout(host).apply {
            background = ui.rounded(Palette.dangerFill, radiusDp = 14).apply {
                setStroke(ui.dp(1), Palette.danger, ui.dp(4).toFloat(), ui.dp(3).toFloat())
            }
            contentDescription = "Нема обкладинки"
        }
        val inner = ui.column(4).apply { gravity = Gravity.CENTER_HORIZONTAL }
        inner.addView(ImageView(host).apply { setImageDrawable(LineIcon.CROSS.drawable(Palette.danger, ui.dp(40))) },
            LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        inner.addView(ui.text("нема обкладинки", 11f, Palette.danger).apply { gravity = Gravity.CENTER })
        frame.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return frame
    }

    private fun image(bmp: android.graphics.Bitmap): View = FrameLayout(host).apply {
        addView(ImageView(host).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = ui.rounded(Palette.surfaceAlt, radiusDp = 14)
            contentDescription = "Обкладинка"
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun formatHint(info: FileTags.FileInfo): String = when (info.format) {
        "FLAC" -> "FLAC — без втрат"
        "WAV" -> "WAV — без втрат, без стиснення"
        "MP3" -> "MP3 — зі стисненням"
        else -> info.format
    }

    // ---------- теги — один раз, по групах ----------

    private fun tagsCard(info: FileTags.FileInfo): View {
        val card = ui.card(12)
        card.addView(ui.label("Теги у файлі · ${info.raw.size}", LineIcon.LIST))
        if (info.raw.isEmpty()) card.addView(ui.muted("У файлі нема жодного тегу.", 13.5f))
        TagNames.grouped(info.raw) { it.key }.forEachIndexed { i, (group, items) ->
            if (i > 0) card.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)))
            card.addView(groupTitle(group.title))
            var k = 0
            while (k < items.size) {
                val a = items[k]
                val b = items.getOrNull(k + 1)
                if (b != null && short(a) && short(b)) {
                    val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(tagCell(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(tagCell(b), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(14) })
                    card.addView(row, ui.matchWrap())
                    k += 2
                } else {
                    card.addView(tagCell(a), ui.matchWrap())
                    k += 1
                }
            }
        }
        // Що плеєр показав би, якби було, — одним рядком (а не «— нема» по рядку на кожне).
        val missing = FileTags.Field.entries.filter { it != FileTags.Field.ENCODER && info[it] == null }
        if (missing.isNotEmpty()) {
            val flow = FlowLayout(host, ui.dp(6), ui.dp(6), fill = false)
            flow.addView(ui.text("Не заповнено:", 12.5f, Palette.faint))
            missing.forEach { f ->
                flow.addView(ui.text(f.label, 12.5f, Palette.muted).apply {
                    background = ui.rounded(Palette.transparent, radiusDp = 8).apply {
                        setStroke(ui.dp(1), Palette.border, ui.dp(3).toFloat(), ui.dp(2).toFloat())
                    }
                    setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
                })
            }
            card.addView(flow, ui.matchWrap())
            card.addView(ui.muted("Вписати можна в Audio Converter («Теги»).", 12f))
        }
        return card
    }

    private fun short(r: FileTags.Raw): Boolean = TagNames.of(r.key).group != TagNames.Group.PICTURES &&
        TagNames.value(r.key, r.value).length <= SHORT && TagNames.of(r.key).label.length <= 20

    /** Підпис («Назва  TITLE») над значенням — довгі назви не ламаються. */
    private fun tagCell(r: FileTags.Raw): View = ui.column(2).apply {
        val name = TagNames.of(r.key)
        addView(ui.text(android.text.SpannableStringBuilder().apply {
            append(name.label)
            val start = length
            append("  ${name.word}")
            setSpan(android.text.style.ForegroundColorSpan(Palette.faint), start, length, 0)
            setSpan(android.text.style.RelativeSizeSpan(0.84f), start, length, 0)
            setSpan(android.text.style.TypefaceSpan("monospace"), start, length, 0)
        }, 12.5f, Palette.muted))
        val v = TagNames.value(r.key, r.value)
        addView(if (v.isBlank()) ui.text("— порожньо", 15.5f, Palette.faint) else ui.numbers(ui.text(v, 15.5f, Palette.text)))
    }

    private fun groupTitle(s: String): View = ui.text(s.uppercase(), 11.5f, Palette.faint, medium = true).apply { letterSpacing = 0.08f }

    private fun divider(): View = View(host).apply { setBackgroundColor(Palette.surfaceAlt) }

    // ---------- як записано / як зроблено ----------

    private fun recordedCard(extra: Extra): View {
        val card = ui.card(10)
        card.addView(ui.label(extra.recordedTitle, LineIcon.REC))
        val shortOnes = extra.recorded.filter { it.second.length <= 60 }
        val longOnes = extra.recorded.filter { it.second.length > 60 }
        shortOnes.forEach { (k, v) -> card.addView(pair(k, v), ui.matchWrap()) }
        if (longOnes.isNotEmpty()) {
            val more = ui.column(10).apply { visibility = View.GONE }
            longOnes.forEach { (k, v) -> more.addView(pair(k, v), ui.matchWrap()) }
            val toggle = ui.text("Докладніше ▾", 14f, Palette.accent, medium = true).apply {
                minHeight = ui.dp(Ui.MIN_TOUCH_DP)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener {
                    val open = more.visibility != View.VISIBLE
                    more.visibility = if (open) View.VISIBLE else View.GONE
                    text = if (open) "Сховати ▴" else "Докладніше ▾"
                }
            }
            card.addView(more, ui.matchWrap())
            card.addView(toggle)
        }
        return card
    }

    private fun pair(k: String, v: String): View = ui.column(2).apply {
        addView(ui.muted(k, 12.5f))
        addView(ui.numbers(ui.text(v, 15f, Palette.text)))
    }

    // ---------- файл ----------

    private fun fileCard(info: FileTags.FileInfo, fileName: String, folder: String?): View {
        val card = ui.card(10)
        card.addView(ui.label("Файл", LineIcon.FOLDER))
        card.addView(ui.text(fileName, 15f, Palette.text))
        folder?.takeIf { it.isNotBlank() }?.let { path ->
            val crumbs = FlowLayout(host, ui.dp(4), ui.dp(6), fill = false)
            val parts = path.split(" › ")
            parts.forEachIndexed { i, p ->
                if (i > 0) crumbs.addView(ui.text("›", 13f, Palette.faint))
                crumbs.addView(ui.text(p, 13f, if (i == parts.lastIndex) Palette.text else Palette.muted).apply {
                    background = ui.rounded(Palette.surfaceAlt, radiusDp = 8)
                    setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
                })
            }
            card.addView(crumbs, ui.matchWrap())
        }
        val notes = listOfNotNull(
            info[FileTags.Field.ENCODER]?.let { "Створив $it" },
            if (info.format == "FLAC") (if (info.hasMd5) "контрольна сума є (плеєр може перевірити, що звук цілий)" else "контрольної суми нема") else null,
            "гучність (ReplayGain) — " + (info.replayGain ?: "нема"),
        )
        card.addView(ui.muted(notes.joinToString(" · "), 12.5f))
        return card
    }

    // ---------- усі теги підряд ----------

    /** faric 18.10 03:28: «у кінці — усі теги просто підряд, як було, тільки без скорочення». */
    private fun rawCard(info: FileTags.FileInfo): View {
        val card = ui.card(10)
        card.addView(ui.label("Усі теги підряд · ${info.raw.size}", LineIcon.LIST))
        val mono = ui.column(4).apply {
            background = ui.rounded(Palette.bg, radiusDp = 12)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        }
        info.raw.forEach { r ->
            mono.addView(ui.text("${TagNames.of(r.key).word} = ${TagNames.value(r.key, r.value)}", 12.5f, Palette.text).apply { typeface = Typeface.MONOSPACE })
        }
        card.addView(mono, ui.matchWrap())
        return card
    }

    private fun chip(text: String, color: Int = Palette.text, fill: Int = Palette.surfaceAlt): View = ui.text(text, 12.5f, color, medium = true).apply {
        background = ui.rounded(fill, radiusDp = 10)
        setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
    }

    private fun copy(info: FileTags.FileInfo, fileName: String, folder: String?, size: Long, extra: Extra) {
        val text = buildString {
            appendLine(fileName)
            folder?.let { appendLine(it) }
            appendLine("${formatHint(info)} · ${info.sampleRate} Гц · ${info.channels} кан. · ${TimeText.clock(info.durationMs)} · ${TimeText.size(size)}")
            appendLine("Обкладинка: " + (info.picture?.let { "${it.width}×${it.height} у файлі" } ?: "нема у файлі"))
            extra.recorded.forEach { (k, v) -> appendLine("$k: $v") }
            info.raw.forEach { appendLine(TagNames.line(it.key, it.value)) }
        }
        val cm = host.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Про файл", text))
        host.toast("Скопійовано")
    }

    private fun decode(bytes: ByteArray?, maxSide: Int): android.graphics.Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
