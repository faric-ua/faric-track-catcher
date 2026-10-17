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
 * Цей файл, `core/FileTags.kt`, `ui/FlowLayout.kt` і `core/FlowRows.kt` копіюються в Audio Converter (`tools/dev/sync_style.py` там).
 * Змінив панель — підніми [PANEL_VERSION] і рядок у «Журнал стилю».
 * Версія 2 (faric 18.10): «Усі теги у файлі» словами й по групах — [TagNames] (копіюється разом з панеллю).
 *
 * Вікно «Про файл» (T68, faric 17.10 20:31: «щоб усі метадані були максимально; відкрив файл — зразу показало і картинку,
 * і хто виконує, і альбом, і все»). Макет — артефакт «Про файл — макет». Показує те, що **лежить у самому файлі**
 * ([FileTags]), тож видно й поля, які дописав інший застосунок (Audio Converter), — у «Усі теги у файлі».
 * Будується лише з [Ui] і [Palette] — як плитка плеєра, його можна взяти в Audio Converter.
 */
class FileInfoPanel(private val host: MainActivity) {
    private val ui: Ui get() = host.ui

    companion object {
        /** Версія спільної панелі (faric 17.10 23:39: «як у Track Catcher "Про файл" — зроби й у конвертері»). */
        const val PANEL_VERSION = 2
    }

    /** Що знає застосунок про запис, але чого нема у файлі (група «Як записано»), і що можна зробити. */
    class Extra(
        val recorded: List<Pair<String, String>> = emptyList(),
        val historyArtwork: String? = null,
        val clips: Int = -1,
        val makeFlac: (() -> Unit)? = null,
    )

    fun build(info: FileTags.FileInfo, fileName: String, folder: String?, size: Long, extra: Extra, onClose: () -> Unit): View {
        val box = ui.column(14)
        box.addView(ui.label("Про файл", LineIcon.FILE))
        box.addView(coverCard(info, extra), ui.matchWrap())

        section(box, "Що бачить плеєр", LineIcon.PLAY) { card ->
            for (f in FileTags.Field.entries) if (f != FileTags.Field.ENCODER) card.addView(row(f.label, info[f]))
            card.addView(ui.muted("«— нема» — цього поля у файлі нема. Вписати можна в Audio Converter («Теги»).", 12.5f))
        }
        section(box, "Звук", LineIcon.BARS) { card ->
            card.addView(row("Формат", formatName(info)))
            if (info.sampleRate > 0) card.addView(row("Частота", "${"%,d".format(info.sampleRate).replace(',', ' ')} Гц"))
            if (info.bits > 0) card.addView(row("Розрядність", "${info.bits} біт"))
            if (info.channels > 0) card.addView(row("Канали", when (info.channels) { 1 -> "моно"; 2 -> "стерео"; else -> "${info.channels}" }))
            if (info.durationMs > 0) card.addView(row("Тривалість", TimeText.clock(info.durationMs)))
            if (info.bitrateKbps > 0) card.addView(row("Бітрейт", (if (info.format == "MP3") "" else "≈ ") + "${info.bitrateKbps} кбіт/с"))
            if (size > 0) card.addView(row("Розмір", TimeText.size(size)))
        }
        section(box, "Гучність", LineIcon.EDGES) { card ->
            if (extra.clips >= 0) card.addView(row("Перевантаження", if (extra.clips == 0) "немає" else "${extra.clips} — піки обрізано", if (extra.clips == 0) Palette.success else Palette.warning))
            card.addView(row("Мітка ReplayGain", info.replayGain))
        }
        if (extra.recorded.isNotEmpty()) section(box, "Як записано", LineIcon.REC) { card ->
            extra.recorded.forEach { (k, v) -> card.addView(row(k, v)) }
        }
        section(box, "Файл", LineIcon.FOLDER) { card ->
            card.addView(row("Назва файлу", fileName))
            folder?.let { card.addView(row("Папка", it)) }
            card.addView(row("Створив", info[FileTags.Field.ENCODER]))
            if (info.format == "FLAC") card.addView(row("Контрольна сума", if (info.hasMd5) "є (плеєр може перевірити, що звук цілий)" else null))
        }
        section(box, "Усі теги у файлі · ${info.raw.size}", LineIcon.LIST) { card ->
            if (info.raw.isEmpty()) {
                card.addView(ui.muted("У файлі нема жодного тегу.", 13.5f))
            } else {
                // Версія 2 (faric 18.10): не «TIT2 = …», а «Назва» + дрібно «TITLE», по групах.
                TagNames.grouped(info.raw) { it.key }.forEachIndexed { i, (group, items) ->
                    if (i > 0) card.addView(View(host).apply { setBackgroundColor(Palette.surfaceAlt) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)))
                    card.addView(ui.text(group.title.uppercase(), 11.5f, Palette.faint, medium = true).apply { letterSpacing = 0.08f })
                    items.forEach { r -> card.addView(tagRow(r)) }
                }
            }
        }

        val actions = FlowLayout(host, ui.dp(8), ui.dp(8), alignEnd = true)
        actions.addView(ui.iconButton(LineIcon.FILE, "Копіювати все", Ui.Kind.NORMAL) { copy(info, fileName, folder, size, extra) })
        actions.addView(ui.button("Закрити", Ui.Kind.QUIET) { onClose() })
        box.addView(actions, ui.matchWrap())
        return box
    }

    private fun coverCard(info: FileTags.FileInfo, extra: Extra): View {
        val card = ui.card(12)
        val pic = info.picture
        val bmp = pic?.let { decode(it.bytes, ui.dp(520)) }
        if (bmp != null) {
            card.addView(ImageView(host).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                clipToOutline = true
                background = ui.rounded(Palette.surfaceAlt, radiusDp = 16)
                contentDescription = "Обкладинка з файлу"
            }, ui.matchWrap())
        }
        info[FileTags.Field.TITLE]?.let { card.addView(ui.title(it, 19f)) }
        listOfNotNull(info[FileTags.Field.ARTIST], info[FileTags.Field.ALBUM]).joinToString(" · ").takeIf { it.isNotBlank() }
            ?.let { card.addView(ui.muted(it, 14.5f)) }
        card.addView(chip(formatName(info), Palette.accent, Palette.accentFill), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (pic != null) {
            card.addView(note(
                "Обкладинка є у файлі",
                "${pic.mime.substringAfter('/').uppercase()} ${pic.width}×${pic.height} · ${TimeText.size(pic.bytes.size.toLong())} · ${pic.typeLabel}. " +
                    "Будь-який плеєр, що вміє картинки, її покаже.",
                Palette.success, Palette.selectedFill, LineIcon.INFO,
            ), ui.matchWrap())
        } else {
            val why = if (info.format == "WAV") "WAV обкладинку не тримає — тому її не бачать ні плеєр, ні конвертер." else "Плеєр покаже замість неї заглушку."
            val inHistory = extra.historyArtwork?.let { decode(java.io.File(it).takeIf { f -> f.isFile }?.readBytes(), ui.dp(64)) }
            card.addView(note(
                "У файлі обкладинки нема",
                why + if (inHistory != null) " Але Track Catcher зберіг її в історії, коли записував:" else "",
                Palette.warning, Palette.warningFill, LineIcon.SHIELD,
            ), ui.matchWrap())
            if (inHistory != null) {
                val r = ui.row(12).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = ui.rounded(Palette.surfaceAlt, radiusDp = 14)
                    setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                }
                r.addView(FrameLayout(host).apply {
                    addView(ImageView(host).apply {
                        setImageBitmap(inHistory)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        background = ui.rounded(Palette.surfaceAlt, radiusDp = 10)
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)))
                r.addView(ui.text("Обкладинка з історії", 14.5f, Palette.text, medium = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(r, ui.matchWrap())
                extra.makeFlac?.let { make ->
                    card.addView(ui.iconButton(LineIcon.REPEAT, "Зробити FLAC з обкладинкою", Ui.Kind.PRIMARY, large = true) { make() }, ui.matchWrap())
                    card.addView(ui.muted("Звук той самий (без втрат), обкладинка, назви й номер — усередині файлу. WAV лишається, як був.", 13f))
                }
            }
        }
        return card
    }

    private fun section(parent: LinearLayout, title: String, icon: LineIcon, fill: (LinearLayout) -> Unit) {
        val card = ui.card(10)
        card.addView(ui.label(title, icon))
        fill(card)
        parent.addView(card, ui.matchWrap())
    }

    /** «назва — значення»; нема значення — «— нема» блідо. */
    private fun row(name: String, value: String?, color: Int = Palette.text): View = ui.row(12).apply {
        gravity = Gravity.TOP
        addView(ui.muted(name, 13f), LinearLayout.LayoutParams(ui.dp(124), ViewGroup.LayoutParams.WRAP_CONTENT))
        val v = if (value.isNullOrBlank()) ui.text("— нема", 14f, Palette.faint) else ui.numbers(ui.text(value, 14f, color))
        addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Тег словами: ліворуч «Назва» і під нею дрібно «TITLE», праворуч — значення. */
    private fun tagRow(r: FileTags.Raw): View = ui.row(12).apply {
        gravity = Gravity.TOP
        val name = TagNames.of(r.key)
        val left = ui.column(0)
        left.addView(ui.muted(name.label, 13f))
        left.addView(ui.text(name.word, 10.5f, Palette.faint).apply { typeface = Typeface.MONOSPACE })
        addView(left, LinearLayout.LayoutParams(ui.dp(124), ViewGroup.LayoutParams.WRAP_CONTENT))
        val v = TagNames.value(r.key, r.value)
        addView(if (v.isBlank()) ui.text("— порожньо", 14f, Palette.faint) else ui.numbers(ui.text(v, 14f, Palette.text)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun chip(text: String, color: Int, fill: Int): View = ui.text(text, 12.5f, color, medium = true).apply {
        background = ui.rounded(fill, radiusDp = 12)
        setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
    }

    private fun note(title: String, body: String, color: Int, fill: Int, icon: LineIcon): View = ui.column(4).apply {
        background = ui.rounded(fill, radiusDp = 14)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        addView(ui.text(ui.iconText(icon, title, color, LineIcon.LABEL_DP - 6), 15f, color, medium = true))
        addView(ui.text(body, 13.5f, Palette.muted))
    }

    private fun formatName(info: FileTags.FileInfo): String = when (info.format) {
        "FLAC" -> "FLAC · без втрат"
        "WAV" -> "WAV · без втрат, без стиснення"
        "MP3" -> "MP3 · зі стисненням"
        else -> info.format
    }

    private fun copy(info: FileTags.FileInfo, fileName: String, folder: String?, size: Long, extra: Extra) {
        val text = buildString {
            appendLine(fileName)
            folder?.let { appendLine(it) }
            appendLine("${formatName(info)} · ${info.sampleRate} Гц · ${info.channels} кан. · ${TimeText.clock(info.durationMs)} · ${TimeText.size(size)}")
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
