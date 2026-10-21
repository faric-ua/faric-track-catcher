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
import ua.faric.trackcatcher.capture.DeviceRam
import ua.faric.trackcatcher.core.EncodeTiming
import ua.faric.trackcatcher.core.OutputFormat
import ua.faric.trackcatcher.core.RepeatPolicy
import ua.faric.trackcatcher.core.SizeEstimate
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.data.AutoBackup
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
    private val encodeCard = ui.card(12)
    private val lengthCard = ui.card(12)
    private val repeatCard = ui.card(12)
    private val historyCard = ui.card(10)
    private val soundCard = ui.card(12)
    private val loudCard = ui.card(12)
    private val edgesCard = ui.card(12)
    private val permsSlot = FrameLayout(host)
    private val autoSlot = ui.column(8)
    private val albumSlot = ui.column(8)
    private val folderTitle = ui.title("", 17f)
    private val folderHint = ui.muted("", 13.5f)
    private val folderButtons = FlowLayout(host, ui.dp(8), ui.dp(8))

    init {
        val folderCard = ui.card(10).apply {
            addView(ui.label("Куди зберігається", LineIcon.FOLDER))
            addView(folderTitle)
            addView(folderHint)
            addView(albumSlot)
            addView(folderButtons, ui.matchWrap())
        }
        val aboutCard = ui.card(8).apply {
            addView(ui.label("Про застосунок", LineIcon.INFO))
            addView(ui.title("Track Catcher ${BuildConfig.VERSION_NAME}", 17f))
            addView(ui.iconButton(LineIcon.BOOK, "Інструкція користувача", Ui.Kind.PRIMARY) { GuideActivity.open(host) }, wrap())
            addView(ui.numbers(ui.muted("Збірка ${BuildConfig.VERSION_CODE}", 13f)))
            addView(ui.muted("Записує звук, який грає телефон, і ріже його на пісні за назвою зі шторки. " +
                "Записи лишаються на телефоні й нікуди не передаються: без реклами й аналітики. Інтернет — лише для перевірки оновлень " +
                "з Google Диска автора. Лише для особистого користування.", 13.5f))
            addView(ui.text("MP3 — LAME 3.98.4 у Java-версії jump3r (LGPL-2.1).", 12.5f, Palette.faint))
            addView(ui.text("github.com/faric-ua/faric-track-catcher", 12.5f, Palette.faint))
        }
        // T64: копія історії.
        val backupCard = ui.card(10).apply {
            addView(ui.label("Копія історії", LineIcon.LIST))
            addView(ui.muted(
                "Історія (статуси, причини, назви, де лежать файли) живе всередині застосунку — після перевстановлення вона пропала б. " +
                    "Копія — один файл: збережи його в «Мої файли» або на Google Диск. Файли пісень у копію не йдуть — вони й так у «Музиці».",
                13.5f,
            ))
            addView(FlowLayout(host, ui.dp(8), ui.dp(8)).apply {
                addView(ui.iconButton(LineIcon.CLOUD, "Зберегти копію", Ui.Kind.PRIMARY) { host.saveHistoryCopy() })
                addView(ui.iconButton(LineIcon.FOLDER, "Відновити з копії", Ui.Kind.NORMAL) { host.openHistoryCopy() })
            }, ui.matchWrap())
            addView(ui.text("Відновлення лише додає записи, яких ще нема; наявні не змінює. Обкладинки в копію не входять.", 12.5f, Palette.faint))
            addView(autoSlot, ui.matchWrap())
        }
        val updateCard = UpdateUi(host).card()
        val all = listOf(formatCard, encodeCard, qualityCard, lengthCard, repeatCard, soundCard, loudCard, edgesCard, historyCard, folderCard, backupCard, permsSlot, updateCard, aboutCard)
        view = if (ui.twoPane) {
            val left = ui.column(12)
            val right = ui.column(12)
            listOf(formatCard, encodeCard, qualityCard, lengthCard, repeatCard, soundCard, loudCard, edgesCard).forEach { left.addView(it, ui.matchWrap()) }
            listOf(historyCard, folderCard, backupCard, permsSlot, updateCard, aboutCard).forEach { right.addView(it, ui.matchWrap()) }
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
        renderAuto()
        permsSlot.removeAllViews()
        PermissionsCard.build(host, alwaysShow = true)?.let { permsSlot.addView(it) }
    }

    /** T67: автокопія — один файл, який застосунок сам оновлює після кожного запису. */
    private fun renderAuto() {
        val a = AutoBackup.state(host)
        autoSlot.removeAllViews()
        autoSlot.addView(ui.title("Автокопія", 16f))
        if (!a.on) {
            autoSlot.addView(ui.muted(
                "Вибери один раз файл — у «Моїх файлах» або на своєму Google Диску (☰ → «Диск» у вікні вибору). " +
                    "Далі застосунок сам оновлює його після кожного запису: історія й налаштування. На новому телефоні — «Відновити з копії» → цей файл.",
                13.5f,
            ))
            autoSlot.addView(ui.iconButton(LineIcon.CLOUD, "Увімкнути автокопію", Ui.Kind.NORMAL) { host.enableAutoBackup() }, wrap())
            return
        }
        val last = if (a.lastAt > 0) java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(a.lastAt)) else "ще ні"
        autoSlot.addView(ui.text("Увімкнено: ${a.name}", 15f, Palette.text, medium = true))
        autoSlot.addView(ui.numbers(ui.muted("Остання копія: $last", 13.5f)))
        a.error?.let { autoSlot.addView(ui.text("Не вдалося: $it", 12.5f, Palette.warning)) }
        autoSlot.addView(FlowLayout(host, ui.dp(8), ui.dp(8)).apply {
            addView(ui.iconButton(LineIcon.CLOUD, "Копія зараз", Ui.Kind.NORMAL) {
                AutoBackup.writeSoon(host) { r ->
                    host.runOnUiThread {
                        host.toast(r.fold({ "Автокопію оновлено" }, { "Не вдалося: ${it.message ?: it.javaClass.simpleName}" }))
                        renderAuto()
                    }
                }
            })
            addView(ui.iconButton(LineIcon.FOLDER, "Інший файл", Ui.Kind.NORMAL) { host.enableAutoBackup() })
            addView(ui.button("Вимкнути", Ui.Kind.QUIET) { AutoBackup.disable(host); renderAuto() })
        }, ui.matchWrap())
        autoSlot.addView(ui.text("Вимкнути — застосунок перестане писати; сам файл лишиться, де був.", 12.5f, Palette.faint))
    }

    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun renderChoices() {
        val v = settings.snapshot()
        val locked = RecorderHub.state.phase != RecorderHub.Phase.IDLE

        formatCard.removeAllViews()
        formatCard.addView(ui.label("Формат файлів", LineIcon.FILE))
        // Вибір формату — як був, без розтягування (faric 19.10 20:09: «де MP3, FLAC — тип файлу — не міняй»).
        formatCard.addView(flow(OutputFormat.entries.map { f -> ui.choice(f.label, v.format == f) { settings.format = f; renderChoices() } }, fill = false))
        formatCard.addView(ui.muted(formatHint(v), 13.5f))
        if (locked) formatCard.addView(ui.text("Зміни почнуть діяти з наступного запису.", 12.5f, Palette.warning))

        // T85 (faric 19.10 22:45, 20.10 00:14: «на M30s ривки — робимо заради якості»).
        encodeCard.removeAllViews()
        encodeCard.addView(ui.label("Коли стискати у FLAC / MP3", LineIcon.BARS))
        encodeCard.addView(flow(EncodeTiming.entries.map { t -> ui.choice(t.label, v.encodeTiming == t) { settings.encodeTiming = t; renderChoices() } }))
        val ram = DeviceRam.total(host)
        val deferHere = DeviceRam.deferEncoding(host, v.encodeTiming)
        encodeCard.addView(ui.muted(
            when (v.encodeTiming) {
                EncodeTiming.NOW -> "Пісня стискається, поки пишеться наступна. Файли готові одразу, але на слабкому телефоні можливі ривки у звуці."
                EncodeTiming.AFTER -> "Під час запису телефон лише пише звук — без ривків. У FLAC / MP3 пісні стискаються після «Стоп»; на це треба кілька хвилин."
                EncodeTiming.AUTO -> "На слабкому телефоні (менше 5 ГБ пам'яті) — після «Стоп», щоб не було ривків; на сильному — одразу."
            } + " Цей телефон: ${EncodeTiming.ramText(ram)} пам'яті → " + (if (deferHere) "після «Стоп»." else "одразу."),
            13.5f,
        ))
        if (locked) encodeCard.addView(ui.text("Зміни почнуть діяти з наступного запису.", 12.5f, Palette.warning))

        qualityCard.removeAllViews()
        qualityCard.addView(ui.label("Якість MP3", LineIcon.BARS))
        val q = flow(Mp3Encoder.BITRATES.map { kbps -> ui.choice("$kbps кбіт/с", v.mp3Bitrate == kbps) { settings.mp3Bitrate = kbps; renderChoices() } }, fill = false)
        qualityCard.addView(q)
        qualityCard.addView(ui.muted("320 — на слух майже як оригінал. 192 — добре й удвічі менше місця.", 13.5f))
        ui.setEnabled(q, v.format.mp3)
        for (i in 0 until q.childCount) q.getChildAt(i).isEnabled = v.format.mp3

        lengthCard.removeAllViews()
        lengthCard.addView(ui.label("Коротші не зберігати", LineIcon.CUT))
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
        repeatCard.addView(ui.label("Якщо пісня вже є", LineIcon.REPEAT))
        repeatCard.addView(flow(RepeatPolicy.entries.map { p -> ui.choice(p.label, v.repeat == p) { settings.repeat = p; renderChoices() } }))
        repeatCard.addView(ui.muted(
            if (v.repeat == RepeatPolicy.SKIP) "Якщо в історії вже є повний запис цієї пісні — новий не зберігається."
            else "Кожен раз — новий файл з номером «(2)», «(3)»…",
            13.5f,
        ))

        // T43 (faric 14.10 19:48: «приходять повідомлення — звук буль-буль — і додаток їх записує»).
        // T89 (faric 21.10 20:13): альбоми й сесії в «Історії» — одразу закриті чи відкриті.
        historyCard.removeAllViews()
        historyCard.addView(ui.label("Групи в «Історії»", LineIcon.LIST))
        historyCard.addView(flow(listOf(false to "Закриті", true to "Відкриті").map { (on, text) ->
            ui.choice(text, settings.historyOpen == on) { settings.historyOpen = on; renderChoices() }
        }))
        historyCard.addView(ui.muted(
            if (settings.historyOpen) "Альбоми, виконавці й сесії одразу показують усі пісні. Торкнись плитки групи — згорнеться."
            else "Альбоми, виконавці й сесії — закриті плитки: видно назву, скільки пісень і часу. Торкнись — розкриється.",
            13.5f,
        ))

        soundCard.removeAllViews()
        soundCard.addView(ui.label("Звук для запису", LineIcon.SHIELD))
        soundCard.addView(flow(listOf(true to "Лише плеєр", false to "Усе, що грає").map { (on, text) ->
            ui.choice(text, v.onlyPlayer == on) { settings.onlyPlayer = on; renderChoices() }
        }))
        soundCard.addView(ui.muted(
            if (v.onlyPlayer) "Пишеться лише звук плеєра, чия пісня в шторці. Звуки повідомлень, TikTok, YouTube у файл не потраплять. " +
                "Плеєр не видно в шторці — пишеться все, як раніше."
            else "Пишеться все, що телефон грає як «медіа»: і звуки повідомлень, і інші відео. Вибери, якщо плеєр «Лише плеєр» не записується.",
            13.5f,
        ))
        if (locked) soundCard.addView(ui.text("Зміни почнуть діяти з наступного запису.", 12.5f, Palette.warning))

        // T63 (faric 17.10: «не хочу нормалізації — самий кайф цих хвиль; можна як опцію, з одним, другим, третім вікном підтвердження»).
        loudCard.removeAllViews()
        loudCard.addView(ui.label("Рівна гучність", LineIcon.BARS))
        loudCard.addView(flow(listOf(false to "Вимкнено", true to "Мітка гучності").map { (on, text) ->
            ui.choice(text, v.replayGain == on) {
                if (on && !v.replayGain) askLoudness(1) else if (!on) { settings.replayGain = false; renderChoices() }
            }
        }))
        loudCard.addView(ui.muted(
            if (v.replayGain) "У теги FLAC і MP3 дописується, наскільки голосна пісня (ReplayGain). Сам звук не змінюється. " +
                "Плеєр з увімкненим ReplayGain зробить усю пісню тихішою чи голоснішою — однаково, без зрізання піків. У WAV мітки нема."
            else "Звук пишеться точно як грав — без вирівнювання. Мітку гучності можна ввімкнути, але це експеримент.",
            13.5f,
        ))
        if (locked) loudCard.addView(ui.text("Зміни почнуть діяти з наступного запису.", 12.5f, Palette.warning))
    }

    /** Три вікна поспіль, як просив faric: що це, чого ми не знаємо, остаточне «Увімкнути». */
    private fun askLoudness(step: Int) {
        val (title, message, yes) = when (step) {
            1 -> Triple(
                "Рівна гучність — що це?",
                "Track Catcher виміряє, наскільки голосна кожна пісня, і допише це число в теги файлу (ReplayGain).\n\n" +
                    "Сам звук НЕ змінюється: ні нормалізації, ні стиснення — хвилі й піки лишаються такими, як грав плеєр.",
                "Далі",
            )
            2 -> Triple(
                "Ми цього ще не перевіряли",
                "Що зробить з міткою твій плеєр — залежить від плеєра. Якщо в ньому ввімкнено ReplayGain, він зробить пісню " +
                    "тихішою або голоснішою — усю однаково. Голосні пісні можуть зазвучати помітно тихіше, ніж ти звик.\n\n" +
                    "Як це буде з твоїми плеєрами, невідомо, доки не спробуєш.",
                "Розумію, далі",
            )
            else -> Triple(
                "Увімкнути мітку гучності?",
                "Лише для нових записів у FLAC і MP3. Уже записані файли не змінюються.\n\n" +
                    "Не сподобається — тут «Вимкнено», або вимкни ReplayGain у плеєрі.",
                "Увімкнути",
            )
        }
        val body = host.overlay.dialog(
            title = title,
            message = message,
            actions = listOf(yes to (if (step < 3) Ui.Kind.NORMAL else Ui.Kind.PRIMARY), "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                when {
                    i != 0 -> host.overlay.dismiss()
                    step < 3 -> askLoudness(step + 1)
                    else -> {
                        settings.replayGain = true
                        host.overlay.dismiss()
                        renderChoices()
                    }
                }
            },
        )
        host.overlay.show("$KEY_LOUD$step", body)
    }

    override fun restoreDialog(key: String): Boolean {
        if (!key.startsWith(KEY_LOUD)) return false
        askLoudness(key.removePrefix(KEY_LOUD).toIntOrNull()?.coerceIn(1, 3) ?: 1)
        return true
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
        edgesCard.addView(ui.label("Краї пісні", LineIcon.EDGES))
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
                else -> "«0» — найкраща точка, знайдена на телефоні (колишні +0,5 с). «+» — якщо на початку чути кінець попередньої пісні; «−» — якщо в кінці чути початок наступної."
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

    private fun flow(children: List<View>, fill: Boolean = true): FlowLayout = FlowLayout(host, ui.dp(8), ui.dp(8), fill = fill).apply {
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
        folderButtons.addView(ui.iconButton(LineIcon.FOLDER, "Відкрити папку", Ui.Kind.NORMAL) { openFolder() })
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

    companion object {
        private const val KEY_LOUD = "loud:"
    }
}
