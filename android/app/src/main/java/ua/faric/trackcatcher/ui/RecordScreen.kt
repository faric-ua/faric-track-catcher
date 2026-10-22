package ua.faric.trackcatcher.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import ua.faric.trackcatcher.capture.MediaStoreWriter
import ua.faric.trackcatcher.capture.TreeWriter
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.TrackMeta
import ua.faric.trackcatcher.data.Settings
import ua.faric.trackcatcher.state.RecorderHub

/**
 * «Запис»: стан + рівень, що грає, велика кнопка, дозволи, результати сесії.
 * Книжкова — одна колонка; альбомна — ліворуч керування, праворуч дозволи й результати.
 */
class RecordScreen(host: MainActivity, state: Bundle?) : Screen(host) {
    override val view: View

    private val statusTitle = ui.title("", 22f)
    private val statusSub = ui.numbers(ui.muted("", 14f))
    private val meter = LevelMeterView(host, ui)
    private val diagLine = ui.numbers(ui.text("", 12.5f, Palette.faint))
    private val warning = ui.text("", 14f, Palette.warning).apply {
        background = ui.rounded(Palette.warningFill, Palette.warning, radiusDp = 12)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
    }
    private val errorBox = ui.column(8).apply {
        background = ui.rounded(Palette.dangerFill, Palette.danger, radiusDp = 12)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12))
    }
    private val errorText = ui.text("", 14f, Palette.danger)

    private val art = ImageView(host).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = ui.rounded(Palette.surfaceAlt, radiusDp = 12)
    }
    private val artGlyph = ui.text("♪", 28f, Palette.faint).apply { gravity = Gravity.CENTER }
    private val npTitle = ui.text("", 18f, medium = true).apply { maxLines = 2 }
    private val npArtist = ui.text("", 15f, Palette.muted).apply { maxLines = 2 }
    private val npExtra = ui.numbers(ui.text("", 13f, Palette.faint)).apply { maxLines = 2 }

    private val mainButton = ui.button("", Ui.Kind.REC, large = true) { onMain() }
    private val nightButton = ui.iconButton(LineIcon.MOON, "Нічний екран — щоб телефон не заблокувався", Ui.Kind.PRIMARY) { host.showNightScreen() }
    private val nightHint = ui.text(
        "Android 15+ зупиняє запис, коли екран блокується. Увімкни музику, повернись сюди й натисни «Нічний екран» — " +
            "екран стане чорним і майже без підсвітки, а запис ітиме далі.",
        12.5f, Palette.faint,
    )
    private val formatLine = ui.text("", 14f, Palette.muted).apply {
        setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        isClickable = true
        setOnClickListener { host.showTab(MainActivity.Tab.SETTINGS) }
    }
    private val permsSlot = FrameLayout(host)
    private val guideButton = ui.iconButton(LineIcon.BOOK, "Як записувати — інструкція", Ui.Kind.QUIET) { GuideActivity.open(host, "record") }
    private val recentCard = ui.card(10)
    /** «Цей запис»: свій альбом (T39) і «Зупинитись після N пісень» (T40). */
    private val planCard = ui.card(10)
    private val settings = Settings(host)
    private val recentList = ui.column(8)

    private var unobserve: (() -> Unit)? = null
    private var lastArt: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderStatus(RecorderHub.state)
            handler.postDelayed(this, 500)
        }
    }

    init {
        errorBox.addView(errorText)
        // Копіювати — щоб надіслати причину в чат (faric 20.10 17:10).
        errorBox.addView(FlowLayout(host, ui.dp(8), ui.dp(8)).apply {
            addView(ui.button("Копіювати", Ui.Kind.NORMAL) {
                val cm = host.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Track Catcher", "${ua.faric.trackcatcher.core.AppInfo.encoder}\n${errorText.text}"))
                host.toast("Скопійовано — встав у чат")
            })
            addView(ui.button("Зрозуміло", Ui.Kind.QUIET) { RecorderHub.update { it.copy(error = null) } })
        }, ui.matchWrap())
        recentCard.addView(ui.label("Ця сесія", LineIcon.LIST))
        recentCard.addView(recentList)

        val statusCard = ui.card(10).apply {
            addView(ui.label("Стан", LineIcon.REC))
            addView(statusTitle)
            addView(statusSub)
            addView(meter, ui.matchWrap())
            addView(diagLine)
            addView(warning)
            addView(errorBox)
        }
        val nowCard = ui.card(10).apply {
            addView(ui.label("Зараз грає", LineIcon.PLAY))
            val r = ui.row(14)
            val artBox = FrameLayout(host)
            artBox.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            artBox.addView(artGlyph, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            r.addView(artBox, LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)))
            val texts = ui.column(2)
            texts.addView(npTitle)
            texts.addView(npArtist)
            texts.addView(npExtra)
            r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(r, ui.matchWrap())
        }

        view = if (ui.twoPane) {
            // Альбомна: головна кнопка має бути видно без прокрутки — стан і кнопка ліворуч, пісня праворуч.
            val left = ui.column(12).apply {
                addView(statusCard, ui.matchWrap())
                addView(planCard, ui.matchWrap())
                addView(mainButton, ui.matchWrap())
                addView(nightButton, ui.matchWrap())
                addView(nightHint, ui.matchWrap())
                addView(formatLine, ui.matchWrap())
                addView(guideButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val right = ui.column(12).apply {
                addView(nowCard, ui.matchWrap())
                addView(permsSlot, ui.matchWrap())
                addView(recentCard, ui.matchWrap())
            }
            ui.row().apply {
                gravity = Gravity.TOP
                addView(ui.scroll(left, 16), ui.weight(1f))
                addView(ui.scroll(right, 16).apply { setPadding(0, ui.dp(16), ui.dp(16), ui.dp(24)) }, ui.weight(1f))
            }
        } else {
            val col = ui.column(12).apply {
                addView(statusCard, ui.matchWrap())
                addView(nowCard, ui.matchWrap())
                addView(planCard, ui.matchWrap())
                addView(mainButton, ui.matchWrap())
                addView(nightButton, ui.matchWrap())
                addView(nightHint, ui.matchWrap())
                addView(formatLine, ui.matchWrap())
                addView(guideButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(permsSlot, ui.matchWrap())
                addView(recentCard, ui.matchWrap())
            }
            ui.scroll(col, 16)
        }
    }

    /**
     * Стан без рівнів — що екран уже показав. Рівні звуку приходять 10 разів на секунду; раніше кожен раз
     * перебудовувався весь екран (список «Ця сесія», кнопка, тексти) — на M30s це 56–70 % ядра під час запису
     * (Phone Diagnostics 22.10 21:46; у фоні — 3 %). Тепер на рівні — лише смужки.
     */
    private var shown: RecorderHub.State? = null

    private fun onHub(s: RecorderHub.State) {
        val key = s.copy(levelL = 0f, levelR = 0f)
        if (key == shown) {
            meter.setLevels(s.levelL, s.levelR)
            return
        }
        shown = key
        render(s)
    }

    override fun onStart() {
        shown = null
        unobserve = RecorderHub.observe { onHub(it) }
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        unobserve?.invoke()
        unobserve = null
    }

    override fun onResume() {
        permsSlot.removeAllViews()
        PermissionsCard.build(host, alwaysShow = false)?.let { permsSlot.addView(it) }
        formatLine.text = "Формат: ${Settings(host).snapshot().label} · змінити в «Налаштуваннях»"
        render(RecorderHub.state)
    }

    override fun onNowPlaying() = renderNowPlaying(RecorderHub.state)

    private fun render(s: RecorderHub.State) {
        renderStatus(s)
        renderNowPlaying(s)
        when (s.phase) {
            RecorderHub.Phase.IDLE -> {
                mainButton.text = "● Почати запис"
                ui.styleButton(mainButton, Ui.Kind.REC)
                ui.setEnabled(mainButton, true)
            }
            RecorderHub.Phase.RECORDING -> {
                mainButton.text = if (s.stopAfterSong) "■ Зупинити зараз" else "■ Зупинити запис"
                ui.styleButton(mainButton, Ui.Kind.DANGER)
                ui.setEnabled(mainButton, true)
            }
            RecorderHub.Phase.FINISHING -> {
                mainButton.text = "Зберігаю…"
                ui.styleButton(mainButton, Ui.Kind.NORMAL)
                ui.setEnabled(mainButton, false)
            }
        }
        val recording = s.phase == RecorderHub.Phase.RECORDING
        nightButton.visibility = if (recording) View.VISIBLE else View.GONE
        nightHint.visibility = if (recording) View.VISIBLE else View.GONE
        warning.visibility = if (s.warning != null) View.VISIBLE else View.GONE
        warning.text = s.warning ?: ""
        errorBox.visibility = if (s.error != null) View.VISIBLE else View.GONE
        errorText.text = s.error ?: ""
        meter.visibility = if (s.phase == RecorderHub.Phase.RECORDING) View.VISIBLE else View.GONE
        diagLine.visibility = if (s.phase == RecorderHub.Phase.RECORDING && s.diag != null) View.VISIBLE else View.GONE
        diagLine.text = s.diag ?: ""
        meter.setLevels(s.levelL, s.levelR)
        renderRecent(s)
        renderPlan(s)
    }

    // ---------- цей запис: альбом і ліміт пісень ----------

    private var planKey = ""

    private fun renderPlan(s: RecorderHub.State) {
        val locked = s.phase != RecorderHub.Phase.IDLE
        val useAlbum = settings.useCustomAlbum
        val album = settings.customAlbum
        val limit = settings.stopAfter
        val minutes = settings.stopAfterMin
        val key = "$locked|$useAlbum|$album|$limit|$minutes"
        if (key == planKey) return
        planKey = key
        planCard.removeAllViews()
        planCard.addView(ui.label("Цей запис", LineIcon.DISC))

        planCard.addView(ui.title("Альбом", 15.5f))
        val albumRow = FlowLayout(host, ui.dp(8), ui.dp(8))
        albumRow.addView(ui.choice("Як у плеєрі", !useAlbum) { settings.useCustomAlbum = false; planKey = ""; renderPlan(RecorderHub.state) })
        albumRow.addView(ui.choice(if (album.isBlank()) "Свій…" else "Свій: $album", useAlbum) { askAlbum() })
        planCard.addView(albumRow, ui.matchWrap())

        planCard.addView(ui.title("Зупинитись після", 15.5f))
        val limitRow = FlowLayout(host, ui.dp(8), ui.dp(8))
        limitRow.addView(ui.choice("Без обмеження", limit == 0 && minutes == 0) {
            settings.stopAfter = 0
            settings.stopAfterMin = 0
            planKey = ""
            renderPlan(RecorderHub.state)
        })
        limitRow.addView(ui.choice(if (limit == 0) "Кількість пісень…" else TimeText.plural(limit, "пісні", "пісень", "пісень"), limit > 0) { askLimit() })
        limitRow.addView(ui.choice(if (minutes == 0) "Хвилин…" else "$minutes хв", minutes > 0) { askMinutes() })
        planCard.addView(limitRow, ui.matchWrap())

        planCard.addView(ui.muted(
            buildString {
                append(if (useAlbum && album.isNotBlank()) "Усі пісні підуть в альбом «$album» (папка й теги). " else "Альбом — як показує плеєр. ")
                append(
                    when {
                        limit > 0 -> "Після ${limit}-ї пісні плеєр стане на паузу, а запис зупиниться сам."
                        minutes > 0 -> "Через $minutes хв записаного звуку — на першій зміні пісні плеєр стане на паузу, а запис зупиниться " +
                            "(пісня, що почнеться в останні ~45 с, уже не пишеться). Паузи плеєра не рахуються."
                        else -> "Запис іде, доки не натиснеш «Зупинити»."
                    }
                )
                if (locked) append(" Змінити можна після цього запису.")
            },
            13.5f,
        ))
        for (i in 0 until planCard.childCount) {
            val c = planCard.getChildAt(i)
            if (c is FlowLayout) for (j in 0 until c.childCount) c.getChildAt(j).isEnabled = !locked
            if (c is FlowLayout) ui.setEnabled(c, !locked)
        }
    }

    private fun input(text: String, number: Boolean): EditText = EditText(host).apply {
        setText(text)
        setSelection(text.length)
        setTextColor(Palette.text)
        setHintTextColor(Palette.faint)
        textSize = 17f
        isSingleLine = true
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        minHeight = ui.dp(Ui.MIN_TOUCH_DP)
    }

    /** Свій альбом: ввести назву або вибрати з папок, що вже є. */
    private fun askAlbum() {
        val field = input(settings.customAlbum, number = false).apply { hint = "Назва альбому, напр. Neon" }
        val extra = ui.column(10)
        extra.addView(field, ui.matchWrap())
        val tree = settings.treeUri.takeIf { it.isNotBlank() }
        val existing = if (tree != null) TreeWriter(host, android.net.Uri.parse(tree)).albumFolders() else MediaStoreWriter(host).albumFolders()
        if (existing.isNotEmpty()) {
            extra.addView(ui.muted("Уже є в «Track Catcher»:", 13f))
            val row = FlowLayout(host, ui.dp(8), ui.dp(8))
            existing.take(30).forEach { name -> row.addView(ui.choice(name, name == field.text.toString()) { field.setText(name); field.setSelection(name.length) }) }
            extra.addView(row, ui.matchWrap())
        }
        val body = host.overlay.dialog(
            title = "Свій альбом",
            message = "Усі пісні цього запису підуть у папку альбому (у «Track Catcher» чи у твоїй папці), і назва альбому запишеться в самі файли.",
            actions = listOf("Готово" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) {
                    val name = field.text.toString().trim()
                    if (name.isNotBlank()) {
                        settings.customAlbum = name
                        settings.useCustomAlbum = true
                    }
                }
                host.overlay.dismiss()
                planKey = ""
                renderPlan(RecorderHub.state)
            },
            extra = extra,
        )
        host.overlay.show(KEY_ALBUM, body)
    }

    /** Скільки пісень записати, після чого — пауза плеєра й зупинка. */
    private fun askLimit() {
        val current = settings.stopAfter
        val field = input(if (current > 0) current.toString() else "", number = true).apply { hint = "Напр. 26" }
        val extra = ui.column(10)
        extra.addView(field, ui.matchWrap())
        val row = FlowLayout(host, ui.dp(8), ui.dp(8))
        listOf(10, 12, 13, 15, 20, 26).forEach { n -> row.addView(ui.choice("$n", n == current) { field.setText("$n"); field.setSelection(field.text.length) }) }
        extra.addView(row, ui.matchWrap())
        val body = host.overlay.dialog(
            title = "Зупинитись після",
            message = "Скільки пісень записати. Після останньої Track Catcher поставить плеєр на паузу й сам зупинить запис.",
            actions = listOf("Готово" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) {
                    val n = field.text.toString().trim().toIntOrNull() ?: 0
                    settings.stopAfter = n
                }
                host.overlay.dismiss()
                planKey = ""
                renderPlan(RecorderHub.state)
            },
            extra = extra,
        )
        host.overlay.show(KEY_LIMIT, body)
    }

    /** T60: скільки хвилин записати (як пише Suno в кінці альбому). */
    private fun askMinutes() {
        val current = settings.stopAfterMin
        val field = input(if (current > 0) current.toString() else "", number = true).apply { hint = "Напр. 47" }
        val extra = ui.column(10)
        extra.addView(field, ui.matchWrap())
        val row = FlowLayout(host, ui.dp(8), ui.dp(8))
        listOf(30, 40, 45, 60, 90).forEach { n -> row.addView(ui.choice("$n хв", n == current) { field.setText("$n"); field.setSelection(field.text.length) }) }
        extra.addView(row, ui.matchWrap())
        val body = host.overlay.dialog(
            title = "Зупинитись через",
            message = "Скільки хвилин записати — напр. скільки пише Suno в кінці альбому. Коли час майже вийде, Track Catcher дочекається " +
                "кінця пісні, поставить плеєр на паузу й зупинить запис.",
            actions = listOf("Готово" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) settings.stopAfterMin = field.text.toString().trim().toIntOrNull() ?: 0
                host.overlay.dismiss()
                planKey = ""
                renderPlan(RecorderHub.state)
            },
            extra = extra,
        )
        host.overlay.show(KEY_MINUTES, body)
    }

    override fun restoreDialog(key: String): Boolean = when (key) {
        KEY_ALBUM -> { askAlbum(); true }
        KEY_LIMIT -> { askLimit(); true }
        KEY_MINUTES -> { askMinutes(); true }
        KEY_STOP -> { if (RecorderHub.state.phase == RecorderHub.Phase.RECORDING) askStop(); true }
        else -> false
    }

    private fun renderStatus(s: RecorderHub.State) {
        when (s.phase) {
            RecorderHub.Phase.IDLE -> {
                statusTitle.text = "Готово до запису"
                statusSub.text = s.notice ?: "Увімкни музику в будь-якому плеєрі й натисни «Почати запис». Кожна пісня стане окремим файлом."
            }
            RecorderHub.Phase.RECORDING -> {
                val sb = SpannableStringBuilder("● ")
                sb.setSpan(ForegroundColorSpan(Palette.rec), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("Записую  ").append(TimeText.clock(System.currentTimeMillis() - s.startedAt))
                statusTitle.text = sb
                statusSub.text = (s.notice?.let { "$it\n" } ?: "") + sessionLine(s) + if (!s.playing) " · плеєр на паузі" else ""
            }
            RecorderHub.Phase.FINISHING -> {
                statusTitle.text = "Зберігаю останні пісні…"
                statusSub.text = sessionLine(s)
            }
        }
    }

    private fun sessionLine(s: RecorderHub.State): String = buildString {
        append(TimeText.plural(s.tracks.coerceAtLeast(if (s.phase == RecorderHub.Phase.RECORDING) 1 else 0), "пісня", "пісні", "пісень"))
        if (s.limit > 0 && s.phase == RecorderHub.Phase.RECORDING) append(" (").append(s.songs.coerceAtMost(s.limit)).append(" з ").append(s.limit).append(")")
        if (s.limitMin > 0 && s.phase == RecorderHub.Phase.RECORDING) {
            append(" (").append(TimeText.clock(s.recordedMs)).append(" з ").append(TimeText.clock(s.limitMin * 60_000L)).append(")")
        }
        append(" · збережено ").append(s.saved)
        if (s.queued > 0) append(" · у черзі ").append(s.queued)
        if (s.failed > 0) append(" · невдало ").append(s.failed)
    }

    private fun renderNowPlaying(s: RecorderHub.State) {
        val meta: TrackMeta? = if (s.phase == RecorderHub.Phase.RECORDING) s.meta ?: host.tracker.meta else host.tracker.meta
        when {
            !Perms.nowPlaying(host) -> {
                npTitle.text = "Назви пісень вимкнено"
                npArtist.text = "Дай «Доступ до сповіщень» (нижче) — і тут з'явиться пісня з шторки."
                npExtra.text = ""
            }
            meta == null || meta.isUnknown -> {
                npTitle.text = "Нічого не грає"
                npArtist.text = "Увімкни пісню в плеєрі — назва з'явиться тут."
                npExtra.text = ""
            }
            else -> {
                npTitle.text = meta.title.ifBlank { TrackMeta.UNKNOWN_NAME }
                npArtist.text = listOf(meta.artist, meta.album).filter { it.isNotBlank() }.joinToString(" · ")
                npExtra.text = buildString {
                    if (meta.durationMs > 0) append(TimeText.clock(meta.durationMs))
                    val source = appLabel(meta.sourcePackage)
                    if (source.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(source)
                    }
                    if (!host.tracker.playing && s.phase != RecorderHub.Phase.RECORDING) append(" · пауза")
                }
            }
        }
        val path = meta?.artworkPath
        if (path != lastArt) {
            lastArt = path
            val bmp = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
            art.setImageBitmap(bmp)
            artGlyph.visibility = if (bmp == null) View.VISIBLE else View.GONE
        }
    }

    private var shownRecent: Pair<List<RecorderHub.Result>, RecorderHub.Phase>? = null

    private fun renderRecent(s: RecorderHub.State) {
        // Список перебудовувати лише коли він справді змінився (нова пісня), а не на кожне оновлення стану.
        val key = s.recent to (if (s.phase == RecorderHub.Phase.IDLE) RecorderHub.Phase.IDLE else RecorderHub.Phase.RECORDING)
        if (key == shownRecent) return
        shownRecent = key
        recentList.removeAllViews()
        if (s.recent.isEmpty()) {
            recentList.addView(ui.muted(
                if (s.phase == RecorderHub.Phase.IDLE) "Після запису тут з'являться збережені пісні. Уся історія — на вкладці «Історія»."
                else "Перша пісня збережеться, щойно почнеться наступна (або після «Зупинити»).",
                13.5f,
            ))
            return
        }
        for (r in s.recent) {
            val row = ui.row(10).apply { gravity = Gravity.TOP }
            row.addView(ui.pill(r.status))
            val texts = ui.column(2)
            texts.addView(ui.text(r.name, 14.5f).apply { maxLines = 2 })
            if (r.reason.isNotBlank()) texts.addView(ui.muted(r.reason, 12.5f))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            recentList.addView(row, ui.matchWrap())
        }
    }

    private fun onMain() {
        when (RecorderHub.state.phase) {
            RecorderHub.Phase.IDLE -> host.startCapture()
            RecorderHub.Phase.RECORDING -> askStop()
            RecorderHub.Phase.FINISHING -> Unit
        }
    }

    /**
     * T98 (faric 22.10 17:39): «Зупинити» посеред пісні — спитати: дочекатись кінця (пісня збережеться повною)
     * чи зупинити зараз. Уже чекаємо, плеєр на паузі чи пісня невідома — зупинити одразу, без питань.
     */
    private fun askStop() {
        val s = RecorderHub.state
        val meta = s.meta
        if (s.stopAfterSong || !s.playing || meta == null || meta.isUnknown) {
            host.stopCapture()
            return
        }
        val pos = host.tracker.positionMs()
        val where = if (pos != null && meta.durationMs > 0) " (${TimeText.clock(pos)} з ${TimeText.clock(meta.durationMs)})" else ""
        val body = host.overlay.dialog(
            title = "Зупинити запис?",
            message = "Зараз пишеться «${meta.title.ifBlank { meta.displayName() }}»$where.\n\n" +
                "«Дочекатись кінця пісні» — вона збережеться повною, а наступна вже не почне писатись (плеєр стане на паузу).\n" +
                "«Зупинити зараз» — ця пісня збережеться неповною.",
            actions = listOf("Дочекатись кінця пісні" to Ui.Kind.PRIMARY, "Зупинити зараз" to Ui.Kind.DANGER, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                host.overlay.dismiss()
                when (i) {
                    0 -> host.stopAfterSong()
                    1 -> host.stopCapture()
                }
            },
        )
        host.overlay.show(KEY_STOP, body)
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return ""
        return try {
            val info = host.packageManager.getApplicationInfo(pkg, 0)
            host.packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    companion object {
        private const val KEY_ALBUM = "plan:album"
        private const val KEY_LIMIT = "plan:limit"
        private const val KEY_MINUTES = "plan:minutes"
        private const val KEY_STOP = "plan:stop"
    }
}
