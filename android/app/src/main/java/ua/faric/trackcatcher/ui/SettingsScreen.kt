package ua.faric.trackcatcher.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import ua.faric.trackcatcher.BuildConfig
import ua.faric.trackcatcher.capture.MediaStoreWriter
import ua.faric.trackcatcher.capture.TreeWriter
import ua.faric.trackcatcher.core.OutputFormat
import ua.faric.trackcatcher.core.RepeatPolicy
import ua.faric.trackcatcher.core.SizeEstimate
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.data.Settings
import ua.faric.trackcatcher.encode.Mp3Encoder
import ua.faric.trackcatcher.state.RecorderHub

/**
 * «Налаштування»: формат, якість MP3, мінімальна довжина, повтори, папка, дозволи, про застосунок.
 * Книжкова — одна колонка; альбомна — дві.
 */
class SettingsScreen(host: MainActivity, state: Bundle?) : Screen(host) {
    override val view: View
    private val settings = Settings(host)
    private val formatCard = ui.card(12)
    private val qualityCard = ui.card(12)
    private val lengthCard = ui.card(12)
    private val repeatCard = ui.card(12)
    private val edgesCard = ui.card(12)
    private val permsSlot = FrameLayout(host)
    private val albumSlot = ui.column(8)
    private val folderTitle = ui.title("", 17f)
    private val folderHint = ui.muted("", 13.5f)
    private val folderButtons = FlowLayout(host, ui.dp(8), ui.dp(8))

    init {
        val folderCard = ui.card(10).apply {
            addView(ui.label("Куди зберігається"))
            addView(folderTitle)
            addView(folderHint)
            addView(albumSlot)
            addView(folderButtons, ui.matchWrap())
        }
        val aboutCard = ui.card(8).apply {
            addView(ui.label("Про застосунок"))
            addView(ui.title("Track Catcher ${BuildConfig.VERSION_NAME}", 17f))
            addView(ui.button("Інструкція користувача", Ui.Kind.PRIMARY) { GuideActivity.open(host) }, wrap())
            addView(ui.numbers(ui.muted("Збірка ${BuildConfig.VERSION_CODE}", 13f)))
            addView(ui.muted("Записує звук, який грає телефон, і ріже його на пісні за назвою зі шторки. " +
                "Записи лишаються на телефоні й нікуди не передаються: без реклами й аналітики. Інтернет — лише для перевірки оновлень " +
                "з Google Диска автора. Лише для особистого користування.", 13.5f))
            addView(ui.text("MP3 — LAME 3.98.4 у Java-версії jump3r (LGPL-2.1).", 12.5f, Palette.faint))
            addView(ui.text("github.com/faric-ua/faric-track-catcher", 12.5f, Palette.faint))
        }
        val updateCard = UpdateUi(host).card()
        val all = listOf(formatCard, qualityCard, lengthCard, repeatCard, edgesCard, folderCard, permsSlot, updateCard, aboutCard)
        view = if (ui.twoPane) {
            val left = ui.column(12)
            val right = ui.column(12)
            listOf(formatCard, qualityCard, lengthCard, repeatCard, edgesCard).forEach { left.addView(it, ui.matchWrap()) }
            listOf(folderCard, permsSlot, updateCard, aboutCard).forEach { right.addView(it, ui.matchWrap()) }
            ui.scroll(ui.row(12).apply {
                gravity = Gravity.TOP
                addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, 16)
        } else {
            ui.scroll(ui.column(12).apply { all.forEach { addView(it, ui.matchWrap()) } }, 16)
        }
        renderChoices()
    }

    override fun onResume() {
        renderChoices()
        permsSlot.removeAllViews()
        PermissionsCard.build(host, alwaysShow = true)?.let { permsSlot.addView(it) }
    }

    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun renderChoices() {
        val v = settings.snapshot()
        val locked = RecorderHub.state.phase != RecorderHub.Phase.IDLE

        formatCard.removeAllViews()
        formatCard.addView(ui.label("Формат файлів"))
        formatCard.addView(flow(OutputFormat.entries.map { f -> ui.choice(f.label, v.format == f) { settings.format = f; renderChoices() } }))
        formatCard.addView(ui.muted(formatHint(v), 13.5f))
        if (locked) formatCard.addView(ui.text("Зміни почнуть діяти з наступного запису.", 12.5f, Palette.warning))

        qualityCard.removeAllViews()
        qualityCard.addView(ui.label("Якість MP3"))
        val q = flow(Mp3Encoder.BITRATES.map { kbps -> ui.choice("$kbps кбіт/с", v.mp3Bitrate == kbps) { settings.mp3Bitrate = kbps; renderChoices() } })
        qualityCard.addView(q)
        qualityCard.addView(ui.muted("320 — на слух майже як оригінал. 192 — добре й удвічі менше місця.", 13.5f))
        ui.setEnabled(q, v.format.mp3)
        for (i in 0 until q.childCount) q.getChildAt(i).isEnabled = v.format.mp3

        lengthCard.removeAllViews()
        lengthCard.addView(ui.label("Коротші не зберігати"))
        lengthCard.addView(flow(Settings.MIN_LENGTH_CHOICES.map { sec ->
            ui.choice(if (sec == 0) "Зберігати все" else "$sec с", v.minLengthSec == sec) { settings.minLengthSec = sec; renderChoices() }
        }))
        lengthCard.addView(ui.muted("Реклама й короткі уривки підуть в історію як «пропущено», без файлу.", 13.5f))

        renderEdges(v)

        renderFolder(v)

        albumSlot.removeAllViews()
        albumSlot.addView(ui.label("Смайлики в назві файлу"))
        albumSlot.addView(flow(listOf(true to "Прибирати", false to "Лишати").map { (on, text) ->
            ui.choice(text, v.plainNames == on) { settings.plainNames = on; renderChoices() }
        }))
        albumSlot.addView(ui.muted(
            if (v.plainNames) "«🎵 TRACK 02 — VELVET TEMPLE. 🏛» → файл «Saney — TRACK 02 — VELVET TEMPLE.wav»: відкриється будь-де (магнітола, старий плеєр, комп'ютер). " +
                "Усередині файлу назва лишається зі смайликами — плеєри її так і покажуть."
            else "Назва файлу — як у плеєрі, зі смайликами. Деякі магнітоли й старі плеєри покажуть «?» або не відкриють такий файл.",
            13.5f,
        ))
        albumSlot.addView(ui.label("Папка для кожного альбому"))
        albumSlot.addView(flow(listOf(true to "Так", false to "Ні").map { (on, text) ->
            ui.choice(text, v.albumFolders == on) { settings.albumFolders = on; renderChoices() }
        }))
        albumSlot.addView(ui.muted(
            if (v.albumFolders) "Пісні з альбому «Neon» підуть у «Track Catcher › Neon». Без альбому — просто в «Track Catcher»."
            else "Усі пісні — в одну папку «Track Catcher».",
            13.5f,
        ))

        repeatCard.removeAllViews()
        repeatCard.addView(ui.label("Якщо пісня вже є"))
        repeatCard.addView(flow(RepeatPolicy.entries.map { p -> ui.choice(p.label, v.repeat == p) { settings.repeat = p; renderChoices() } }))
        repeatCard.addView(ui.muted(
            if (v.repeat == RepeatPolicy.SKIP) "Якщо в історії вже є повний запис цієї пісні — новий не зберігається."
            else "Кожен раз — новий файл з номером «(2)», «(3)»…",
            13.5f,
        ))
    }

    private fun formatHint(v: Settings.Values): String {
        val mp3 = TimeText.size(SizeEstimate.mp3BytesPerMinute(v.mp3Bitrate) * 4)
        val wav = TimeText.size(SizeEstimate.wavBytesPerMinute() * 4)
        val flac = TimeText.size(SizeEstimate.flacBytesPerMinute() * 4)
        return when (v.format) {
            OutputFormat.FLAC -> "4 хв пісні ≈ $flac. Без втрат — точно як грало, а місця майже вдвічі менше, ніж WAV. Теги й обкладинка — у файлі."
            OutputFormat.MP3 -> "4 хв пісні ≈ $mp3. Підходить для будь-якого плеєра."
            OutputFormat.WAV -> "4 хв пісні ≈ $wav. Без втрат — точно як грало, але займає багато місця."
            OutputFormat.MP3_FLAC -> "4 хв пісні ≈ $mp3 + $flac. Два файли на кожну пісню: MP3 для будь-якого плеєра і FLAC без втрат."
            OutputFormat.BOTH -> "4 хв пісні ≈ $mp3 + $wav. Два файли на кожну пісню."
        }
    }

    /** «Краї пісні» (faric 14.10 05:29–05:31): зрізати по 1 с, наростання/затухання, зсув розрізу. */
    private fun renderEdges(v: Settings.Values) {
        edgesCard.removeAllViews()
        edgesCard.addView(ui.label("Краї пісні"))
        edgesCard.addView(ui.muted("Діє з наступного запису.", 13f))

        edgesCard.addView(ui.title("Зрізати по 1 с на початку й у кінці", 15.5f))
        edgesCard.addView(flow(listOf(false to "Ні", true to "Так").map { (on, text) ->
            ui.choice(text, v.trimEdges == on) { settings.trimEdges = on; renderChoices() }
        }))
        edgesCard.addView(ui.muted("Прибирає хвіст попередньої пісні й «чирк» перемикання, але й 1 с самої пісні.", 13.5f))

        edgesCard.addView(ui.title("Наростання й затухання", 15.5f))
        edgesCard.addView(flow(Settings.FADE_CHOICES.map { ms ->
            ui.choice(if (ms == 0) "Без" else secs(ms), v.fadeMs == ms) { settings.fadeMs = ms; renderChoices() }
        }))
        edgesCard.addView(ui.muted("Гучність плавно росте з нуля на початку файлу й спадає до нуля в кінці.", 13.5f))

        edgesCard.addView(ui.title("Зсув розрізу", 15.5f))
        edgesCard.addView(flow(Settings.SHIFT_CHOICES.map { ms ->
            ui.choice(if (ms == 0) "0" else (if (ms > 0) "+" else "−") + secs(Math.abs(ms)), v.cutShiftMs == ms) { settings.cutShiftMs = ms; renderChoices() }
        }))
        edgesCard.addView(ui.muted(
            when {
                v.cutShiftMs > 0 -> "Розріз пізніше: якщо на початку файлу чути кінець попередньої пісні."
                v.cutShiftMs < 0 -> "Розріз раніше: якщо в кінці файлу чути початок наступної пісні."
                else -> "«+» — якщо на початку чути кінець попередньої пісні; «−» — якщо в кінці чути початок наступної."
            },
            13.5f,
        ))
    }

    /** 500 → «0,5 с», 1000 → «1 с». */
    private fun secs(ms: Int): String {
        val whole = ms / 1000
        val rest = ms % 1000
        return if (rest == 0) "$whole с" else "$whole," + (rest / 10).toString().padStart(2, '0').trimEnd('0') + " с"
    }

    private fun flow(children: List<View>): FlowLayout = FlowLayout(host, ui.dp(8), ui.dp(8)).apply {
        children.forEach { addView(it) }
    }

    /** Своя папка (T29) чи «Музика»; кнопки «Відкрити», «Вибрати свою», «Повернути «Музику»». */
    private fun renderFolder(v: Settings.Values) {
        val tree = v.treeUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val ok = tree?.let { runCatching { TreeWriter(host, it).available() }.getOrDefault(false) } ?: true
        folderTitle.text = if (tree == null) MediaStoreWriter.FOLDER_LABEL else TreeWriter.labelForTree(tree)
        folderHint.text = when {
            tree == null -> "Файли видно в «Моїх файлах» і в будь-якому плеєрі. Назва — «Виконавець — Назва», повтор отримує «(2)». " +
                "Можна вибрати свою папку — і на карті пам'яті."
            !ok -> "Своя папка зараз недоступна (карту вийнято чи дозвіл забрано) — пісні тимчасово йдуть у «Музика › Track Catcher»."
            else -> "Своя папка: альбоми — папками всередині неї. Назва — «Виконавець — Назва», повтор отримує «(2)»."
        }
        folderHint.setTextColor(if (ok) Palette.muted else Palette.warning)
        folderButtons.removeAllViews()
        folderButtons.addView(ui.button("Відкрити папку", Ui.Kind.NORMAL) { openFolder() })
        folderButtons.addView(ui.button(if (tree == null) "Вибрати свою папку" else "Змінити папку", Ui.Kind.PRIMARY) { host.pickFolder() })
        if (tree != null) folderButtons.addView(ui.button("Повернути «Музику»", Ui.Kind.QUIET) { host.resetFolder() })
    }

    private fun openFolder() {
        val treeUri = settings.treeUri
        if (treeUri.isNotBlank()) {
            val tree = TreeWriter(host, Uri.parse(treeUri))
            if (tree.isEmpty()) {
                host.toast("Папка ще порожня: пісня зберігається, коли почнеться наступна або після «Зупинити»")
                return
            }
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(tree.rootDocument(), "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                host.startActivity(view)
            } catch (e: Exception) {
                host.toast("Відкрий «Мої файли» → ${TreeWriter.labelForTree(Uri.parse(treeUri))}")
            }
            return
        }
        // Папку Android створює лише з першим файлом; без неї «Мої файли» відкривають «Завантаження».
        if (MediaStoreWriter(host).count() == 0) {
            host.toast("Папка ще порожня: пісня зберігається, коли почнеться наступна або після «Зупинити»")
            return
        }
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2FTrack%20Catcher")
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            host.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            host.toast("Відкрий «Мої файли» → Музика → Track Catcher")
        } catch (e: SecurityException) {
            host.toast("Відкрий «Мої файли» → Музика → Track Catcher")
        }
    }
}
