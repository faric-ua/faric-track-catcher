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
    private val permsSlot = FrameLayout(host)

    init {
        val folderCard = ui.card(10).apply {
            addView(ui.label("Куди зберігається"))
            addView(ui.title(MediaStoreWriter.FOLDER_LABEL, 17f))
            addView(ui.muted("Файли видно в «Моїх файлах» і в будь-якому плеєрі. Назва — «Виконавець — Назва», повтор отримує «(2)».", 13.5f))
            addView(ui.button("Відкрити папку", Ui.Kind.NORMAL) { openFolder() }, wrap())
        }
        val aboutCard = ui.card(8).apply {
            addView(ui.label("Про застосунок"))
            addView(ui.title("Track Catcher ${BuildConfig.VERSION_NAME}", 17f))
            addView(ui.button("Інструкція користувача", Ui.Kind.PRIMARY) { GuideActivity.open(host) }, wrap())
            addView(ui.numbers(ui.muted("Збірка ${BuildConfig.VERSION_CODE}", 13f)))
            addView(ui.muted("Записує звук, який грає телефон, і ріже його на пісні за назвою зі шторки. " +
                "Записи лишаються на телефоні: без інтернету, реклами й аналітики. Лише для особистого користування.", 13.5f))
            addView(ui.text("MP3 — LAME 3.98.4 у Java-версії jump3r (LGPL-2.1).", 12.5f, Palette.faint))
            addView(ui.text("github.com/faric-ua/faric-track-catcher", 12.5f, Palette.faint))
        }
        val all = listOf(formatCard, qualityCard, lengthCard, repeatCard, folderCard, permsSlot, aboutCard)
        view = if (ui.twoPane) {
            val left = ui.column(12)
            val right = ui.column(12)
            listOf(formatCard, qualityCard, lengthCard, repeatCard).forEach { left.addView(it, ui.matchWrap()) }
            listOf(folderCard, permsSlot, aboutCard).forEach { right.addView(it, ui.matchWrap()) }
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
        return when (v.format) {
            OutputFormat.MP3 -> "4 хв пісні ≈ $mp3. Підходить для будь-якого плеєра."
            OutputFormat.WAV -> "4 хв пісні ≈ $wav. Без втрат — точно як грало, але займає багато місця."
            OutputFormat.BOTH -> "4 хв пісні ≈ $mp3 + $wav. Два файли на кожну пісню."
        }
    }

    private fun flow(children: List<View>): FlowLayout = FlowLayout(host, ui.dp(8), ui.dp(8)).apply {
        children.forEach { addView(it) }
    }

    private fun openFolder() {
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
