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
    private val nightButton = ui.button("☾ Нічний екран — щоб телефон не заблокувався", Ui.Kind.PRIMARY) { host.showNightScreen() }
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
    private val recentCard = ui.card(10)
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
        errorBox.addView(ui.button("Зрозуміло", Ui.Kind.QUIET) { RecorderHub.update { it.copy(error = null) } }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        recentCard.addView(ui.label("Ця сесія"))
        recentCard.addView(recentList)

        val statusCard = ui.card(10).apply {
            addView(ui.label("Стан"))
            addView(statusTitle)
            addView(statusSub)
            addView(meter, ui.matchWrap())
            addView(warning)
            addView(errorBox)
        }
        val nowCard = ui.card(10).apply {
            addView(ui.label("Зараз грає"))
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
                addView(mainButton, ui.matchWrap())
                addView(nightButton, ui.matchWrap())
                addView(nightHint, ui.matchWrap())
                addView(formatLine, ui.matchWrap())
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
                addView(mainButton, ui.matchWrap())
                addView(nightButton, ui.matchWrap())
                addView(nightHint, ui.matchWrap())
                addView(formatLine, ui.matchWrap())
                addView(permsSlot, ui.matchWrap())
                addView(recentCard, ui.matchWrap())
            }
            ui.scroll(col, 16)
        }
    }

    override fun onStart() {
        unobserve = RecorderHub.observe { render(it) }
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
                mainButton.text = "■ Зупинити запис"
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
        meter.setLevels(s.levelL, s.levelR)
        renderRecent(s)
    }

    private fun renderStatus(s: RecorderHub.State) {
        when (s.phase) {
            RecorderHub.Phase.IDLE -> {
                statusTitle.text = "Готово до запису"
                statusSub.text = "Увімкни музику в будь-якому плеєрі й натисни «Почати запис». Кожна пісня стане окремим файлом."
            }
            RecorderHub.Phase.RECORDING -> {
                val sb = SpannableStringBuilder("● ")
                sb.setSpan(ForegroundColorSpan(Palette.rec), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("Записую  ").append(TimeText.clock(System.currentTimeMillis() - s.startedAt))
                statusTitle.text = sb
                statusSub.text = sessionLine(s) + if (!s.playing) " · плеєр на паузі" else ""
            }
            RecorderHub.Phase.FINISHING -> {
                statusTitle.text = "Зберігаю останні пісні…"
                statusSub.text = sessionLine(s)
            }
        }
    }

    private fun sessionLine(s: RecorderHub.State): String = buildString {
        append(TimeText.plural(s.tracks.coerceAtLeast(if (s.phase == RecorderHub.Phase.RECORDING) 1 else 0), "пісня", "пісні", "пісень"))
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

    private fun renderRecent(s: RecorderHub.State) {
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
            RecorderHub.Phase.RECORDING -> host.stopCapture()
            RecorderHub.Phase.FINISHING -> Unit
        }
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
}
