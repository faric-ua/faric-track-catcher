package ua.faric.trackcatcher.ui

import android.text.TextUtils
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import ua.faric.trackcatcher.capture.FlacBatch
import ua.faric.trackcatcher.capture.HoleScan
import ua.faric.trackcatcher.capture.Renumber
import ua.faric.trackcatcher.capture.RelinkScan
import ua.faric.trackcatcher.capture.MetaApply
import ua.faric.trackcatcher.core.MetaEdit
import ua.faric.trackcatcher.core.TrimFade
import ua.faric.trackcatcher.capture.TrimApply
import ua.faric.trackcatcher.capture.TitleFix
import ua.faric.trackcatcher.capture.MediaStoreWriter
import ua.faric.trackcatcher.core.FileTags
import ua.faric.trackcatcher.core.Holes
import ua.faric.trackcatcher.core.Lossless
import ua.faric.trackcatcher.core.Playlist
import ua.faric.trackcatcher.core.RecStatus
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.TitleTail
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow
import ua.faric.trackcatcher.data.SessionRow
import ua.faric.trackcatcher.data.Settings
import ua.faric.trackcatcher.state.RecorderHub

/**
 * «Історія»: усі записи з позначкою повний / неповний / невдало / пропущено, пошук, фільтр.
 * Книжкова — список, подробиці у вікні. Альбомна — список ліворуч, подробиці праворуч.
 */
class HistoryScreen(host: MainActivity, state: Bundle?) : Screen(host) {
    override val view: View

    private val db = HistoryDb.get(host)
    private var filter: RecStatus? = state?.getString(KEY_FILTER)?.let { RecStatus.fromCode(it) }
    private var query: String = state?.getString(KEY_QUERY) ?: ""
    private var selectedId: Long = state?.getLong(KEY_SELECTED, 0L) ?: 0L
    /** Є і MP3, і файл без втрат (WAV або FLAC) — який слухати в плеєрі. */
    private var listenWav: Boolean = state?.getBoolean(KEY_LISTEN_WAV, false) ?: false
    /** Режим вибору (T38): довге натискання на плитку. Порожньо — звичайний режим. */
    private val picked = LinkedHashSet<Long>().apply { state?.getLongArray(KEY_PICKED)?.forEach { add(it) } }
    private val pickBar = ui.card(10, fill = Palette.selectedFill, stroke = Palette.success)

    private val search = EditText(host).apply {
        hint = "Пошук: виконавець, назва, альбом"
        setHintTextColor(Palette.faint)
        setTextColor(Palette.text)
        textSize = 15f
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        minHeight = ui.dp(Ui.MIN_TOUCH_DP)
        id = View.generateViewId()
        setText(query)
    }
    private val chips = ui.row(8)
    /** T61: групувати за сесіями, альбомами чи виконавцями. */
    private enum class Group(val code: String, val label: String) {
        SESSIONS("sessions", "Сесії"), ALBUMS("albums", "Альбоми"), ARTISTS("artists", "Виконавці")
    }
    private val prefs = Settings(host)
    private var group: Group = Group.entries.firstOrNull { it.code == (state?.getString(KEY_GROUP) ?: prefs.historyGroup) } ?: Group.SESSIONS
    private val groupChips = ui.row(8)
    private val list = ui.column(10)
    private val detailPane: LinearLayout? = if (ui.twoPane) ui.column(12) else null
    private var detailScroll: android.widget.ScrollView? = null
    private var unobserve: (() -> Unit)? = null
    // Обкладинки плиток — за розміром у пам'яті (16 МБ ≈ 190 плиток), а не 60 штук: інакше на довгій історії
    // кожна перебудова списку декодувала картинки заново.
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Плитки списку за id запису — щоб вибір міняв лише рамку, без перебудови. */
    private val rowViews = HashMap<Long, View>()
    private var rows: List<RecordingRow> = emptyList()

    /**
     * T89: які групи користувач перемкнув (відкрив, коли типово закриті, або закрив, коли типово відкриті).
     * «g:<альбом>» — альбом / виконавець, «s:<сесія>» — сесія, «gs:<альбом>:<сесія>» — сесія всередині альбому.
     */
    private val toggled = LinkedHashSet<String>().apply { state?.getStringArray(KEY_TOGGLED)?.forEach { add(it) } }
    /** Записи, які зараз видно (у відкритих групах) — «Вибрати всі видимі» бере лише їх. */
    private val shownIds = LinkedHashSet<Long>()
    private lateinit var listScroll: android.widget.ScrollView
    private val marquee by lazy { GroupTile.Companion.Marquee(listScroll) }

    init {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                reload()
            }
        })
        val top = ui.column(10).apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(4))
            addView(search, ui.matchWrap())
            addView(ui.hscroll(groupChips), ui.matchWrap())
            addView(ui.hscroll(chips), ui.matchWrap())
            addView(pickBar, ui.matchWrap())
        }
        val listColumn = ui.column().apply {
            addView(top, ui.matchWrap())
            addView(ui.scroll(list, 16).also { s ->
                listScroll = s
                s.setOnScrollChangeListener { _, _, _, _, _ -> marquee.restart() } // T89: назви альбомів — біжучий рядок
            }, ui.weight(1f, vertical = true))
        }
        view = if (detailPane != null) {
            ui.row().apply {
                gravity = Gravity.TOP
                addView(listColumn, ui.weight(1.15f))
                addView(ui.divider().apply { layoutParams = LinearLayout.LayoutParams(maxOf(1, ui.dp(1) / 2), ViewGroup.LayoutParams.MATCH_PARENT) })
                addView(ui.scroll(detailPane, 16).also { detailScroll = it }, ui.weight(1f))
            }
        } else {
            listColumn
        }
    }

    private val follower: (InAppPlayer.Track) -> Unit = { t -> follow(t) }

    override fun onStart() {
        unobserve = HistoryDb.observe { fileInfoCache = null; reload(); checkMissing() } // теги змінились — «Про файл» читати наново
        unobserveBatch = FlacBatch.observe { onBatch(it) }
        InAppPlayer.onTrackChanged = follower
        checkMissing()
        reload()
        // Поки застосунок був схований, плеєр міг перейти далі (шторка, «дограла»).
        InAppPlayer.current?.let { follow(it) }
    }

    override fun onStop() {
        marquee.stop()
        unobserve?.invoke()
        unobserve = null
        unobserveBatch?.invoke()
        unobserveBatch = null
        if (InAppPlayer.onTrackChanged === follower) InAppPlayer.onTrackChanged = null
    }

    override fun save(out: Bundle) {
        filter?.let { out.putString(KEY_FILTER, it.code) }
        out.putString(KEY_QUERY, query)
        out.putLong(KEY_SELECTED, selectedId)
        out.putBoolean(KEY_LISTEN_WAV, listenWav)
        out.putLongArray(KEY_PICKED, picked.toLongArray())
        out.putString(KEY_GROUP, group.code)
        out.putStringArray(KEY_TOGGLED, toggled.toTypedArray())
    }

    override val wantsBack: Boolean get() = picked.isNotEmpty()

    override fun handleBack() = clearPicked()

    private var unobserveBatch: (() -> Unit)? = null

    override fun restoreDialog(key: String): Boolean {
        // Вікна, що мають пережити поворот (faric 17.10 22:25: «перевір усі вікна на переворот»).
        when (key) {
            KEY_FLAC_CONFIRM -> {
                val rows = FlacBatch.pendingConfirm?.toList().orEmpty().mapNotNull { db.get(it) }
                if (rows.isEmpty()) return false
                when (FlacBatch.pendingMode) {
                    FlacBatch.Mode.RENUMBER -> confirmRenumber(rows)
                    FlacBatch.Mode.TITLES -> confirmTitles(rows, FlacBatch.pendingTail)
                    FlacBatch.Mode.FLAC, FlacBatch.Mode.RETITLE, FlacBatch.Mode.EDIT, FlacBatch.Mode.TRIM, FlacBatch.Mode.HOLES -> confirmMakeFlac(rows)
                }
                return true
            }
            KEY_FLAC_RUN, KEY_FLAC_DONE -> {
                val s = FlacBatch.state
                if (!s.running && !s.finished) return false
                showBatch(s)
                return true
            }
            KEY_EDIT -> return showEditor()
            KEY_TRIM -> return showTrim()
            KEY_RELINK_RUN -> {
                if (RelinkScan.running) showRelinkWaiting() else showRelink(RelinkScan.last ?: return false)
                return true
            }
            KEY_RELINK_DONE -> {
                showRelink(RelinkScan.last ?: return false)
                return true
            }
            KEY_NO_CONVERTER -> {
                showNoConverter()
                return true
            }
        }
        if (key == KEY_DELETE_MANY) {
            if (picked.isEmpty()) return false
            confirmDeleteMany()
            return true
        }
        val parts = key.split(":")
        val id = parts.getOrNull(1)?.toLongOrNull() ?: return false
        val row = db.get(id) ?: return false
        return when (parts[0]) {
            "fileinfo" -> {
                openFileInfo(row)
                true
            }
            "detail" -> {
                if (detailPane == null) showDetailDialog(row) else select(row)
                true
            }
            "delete" -> {
                confirmDelete(row)
                true
            }
            else -> false
        }
    }

    // ---------- вибір і видалення кількох (T38) ----------

    /**
     * Вибрати / зняти плитку (faric 20.10 01:41: «коли вибираю плитки, додаток підвисає на пару секунд»): раніше
     * перебудовувався весь список (191 плитка, обкладинки заново) на кожен дотик. Тепер — лише рамка цієї плитки й
     * панель «Вибрано»; увесь список — тільки коли вибір почався чи скінчився (міняються кнопки в шапках).
     */
    private fun togglePick(r: RecordingRow) {
        val wasPicking = picked.isNotEmpty()
        if (!picked.remove(r.id)) picked += r.id
        val box = rowViews[r.id]
        if (wasPicking != picked.isNotEmpty() || box == null) {
            reload()
        } else {
            styleRow(box, r, r.id in picked)
            renderPickBar()
        }
        host.refreshBack()
    }

    private fun styleRow(box: View, r: RecordingRow, selected: Boolean) {
        box.background = ui.pressable(
            if (selected) ui.rounded(Palette.selectedFill, Palette.success, radiusDp = 14, strokeDp = 2)
            else ui.rounded(Palette.surface, Palette.border, radiusDp = 14),
        )
        box.contentDescription = r.displayName + ", " + r.status.label + if (selected) Ui.SELECTED_SUFFIX else ""
    }

    private fun clearPicked() {
        picked.clear()
        reload()
        host.refreshBack()
    }

    private fun renderPickBar() {
        pickBar.removeAllViews()
        // Записи, яких уже нема (видалено, фільтр), не рахуємо.
        val all = db.list(null, "").associateBy { it.id }
        picked.retainAll(all.keys)
        pickBar.visibility = if (picked.isEmpty()) View.GONE else View.VISIBLE
        if (picked.isEmpty()) return
        val withFiles = picked.count { all[it]?.hasFile == true }
        pickBar.addView(ui.numbers(ui.title("Вибрано: ${picked.size}", 16f)))
        pickBar.addView(ui.muted(
            if (withFiles > 0) "З файлом: $withFiles — файл теж буде видалено. Торкнись плитки — додати чи зняти." else "Торкнись плитки — додати чи зняти.",
            13f,
        ))
        val actions = FlowLayout(host, ui.dp(8), ui.dp(8))
        val pickedWavs = picked.mapNotNull { all[it] }.filter { isWav(it) }
        if (pickedWavs.isNotEmpty()) actions.addView(ui.iconButton(LineIcon.DISC, "У FLAC з обкладинками (${pickedWavs.size})", Ui.Kind.PRIMARY) {
            clearPicked()
            confirmMakeFlac(pickedWavs.sortedBy { it.createdAt })
        })
        val pickedEditable = picked.mapNotNull { all[it] }.filter { it.hasFile && it.id !in missing }
        if (pickedEditable.isNotEmpty()) actions.addView(ui.iconButton(LineIcon.SLIDERS, "Змінити теги (${pickedEditable.size})", Ui.Kind.NORMAL) {
            clearPicked()
            openEditor(pickedEditable.sortedBy { it.createdAt })
        })
        if (pickedEditable.isNotEmpty()) actions.addView(ui.iconButton(LineIcon.EDGES, "Обрізати / затухання (${pickedEditable.size})", Ui.Kind.NORMAL) {
            clearPicked()
            openTrim(pickedEditable.sortedBy { it.createdAt })
        })
        val pickedCheck = pickedEditable.filter { HoleScan.canCheck(it) }
        if (pickedCheck.isNotEmpty()) actions.addView(ui.iconButton(LineIcon.BARS, "Перевірити на тріски (${pickedCheck.size})", Ui.Kind.NORMAL) {
            clearPicked()
            checkHoles(pickedCheck.sortedBy { it.createdAt })
        })
        actions.addView(ui.button("Видалити (${picked.size})", Ui.Kind.DANGER) { confirmDeleteMany() })
        if (shownIds.any { it !in picked }) actions.addView(ui.button("Вибрати всі видимі", Ui.Kind.NORMAL) {
            shownIds.forEach { picked += it } // лише те, що видно: у закритих групах нічого не вибирається
            reload()
        })
        actions.addView(ui.button("Скасувати", Ui.Kind.QUIET) { clearPicked() })
        pickBar.addView(actions, ui.matchWrap())
    }

    private fun confirmDeleteMany() {
        val chosen = picked.mapNotNull { db.get(it) }
        if (chosen.isEmpty()) return
        val withFiles = chosen.count { it.hasFile }
        val message = buildString {
            append("З історії зникне ").append(TimeText.plural(chosen.size, "запис", "записи", "записів")).append(".")
            if (withFiles > 0) {
                append(" Разом з ними з телефона буде видалено файли ")
                append(TimeText.plural(withFiles, "пісні", "пісень", "пісень")).append(" (MP3 / WAV / FLAC).")
            } else {
                append(" Файлів у них нема — видаляються лише записи.")
            }
            append(" Повернути не вийде.")
        }
        val body = host.overlay.dialog(
            title = if (chosen.size == 1) "Видалити запис?" else "Видалити ${chosen.size} записів?",
            message = message,
            actions = listOf("Видалити" to Ui.Kind.DANGER, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i -> if (i == 0) deleteMany(chosen) else host.overlay.dismiss() },
        )
        host.overlay.show(KEY_DELETE_MANY, body)
    }

    private fun deleteMany(chosen: List<RecordingRow>) {
        val store = MediaStoreWriter(host)
        val gone = ArrayList<Long>()
        var stuck = 0
        for (r in chosen) {
            if (InAppPlayer.uri != null && (InAppPlayer.uri == r.mp3Uri || InAppPlayer.uri == r.wavUri)) InAppPlayer.stop()
            var ok = true
            for (uri in listOfNotNull(r.mp3Uri, r.wavUri)) {
                // Файл уже прибрали в «Моїх файлах» — теж добре; не вдалося видалити — запис лишаємо.
                if (!store.delete(uri) && store.stillThere(uri)) ok = false
            }
            if (ok) gone += r.id else stuck++
        }
        db.delete(gone)
        picked.removeAll(gone.toSet())
        if (selectedId in gone) selectedId = 0L
        host.overlay.dismiss()
        if (stuck > 0) host.toast("Не вдалося видалити файли ${TimeText.plural(stuck, "запису", "записів", "записів")} — вони лишились в історії.")
        reload()
        host.refreshBack()
    }

    // ---------- список ----------

    /**
     * Записи, чиїх файлів нема на місці (faric 18.10 16:12: «файли я переніс чи видалив — у загальній історії хай будуть,
     * а там, де працюєш з альбомами й перевіряєш файли, — щоб не відображались»). Перевірка у фоні; історію не змінює —
     * повернеш файл, і запис знову з'явиться в альбомі.
     */
    private fun checkMissing() {
        if (missingBusy) return
        missingBusy = true
        val app = host.applicationContext
        Thread({
            val found = runCatching {
                val store = MediaStoreWriter(app)
                val withFiles = db.all().filter { it.hasFile }
                val gone = withFiles.filter { r -> listOfNotNull(r.mp3Uri, r.wavUri).none { store.stillThere(it) } }.map { it.id }.toSet()
                // Не видно взагалі нічого — швидше вийнята карта пам'яті, ніж перенесені файли: нічого не ховаємо.
                if (gone.size == withFiles.size && withFiles.size > 3) emptySet() else gone
            }.getOrDefault(missing)
            host.runOnUiThread {
                missingBusy = false
                if (found != missing) {
                    missing = found
                    if (!host.isFinishing && !host.isDestroyed) reload()
                }
            }
        }, "history-missing").start()
    }

    private fun reload() {
        val all = db.list(filter, query)
        // В альбомах і виконавцях — лише записи з файлами на місці; у «Сесіях» — усе (загальна історія).
        val hidden = if (group == Group.SESSIONS) 0 else all.count { it.id in missing }
        val found = if (hidden == 0) all else all.filter { it.id !in missing }
        // У групах — альбом (виконавець) разом; найсвіжіший угорі, усередині — новіші згори, як завжди.
        rows = if (group == Group.SESSIONS) found else Playlist.groupInOrder(found) { groupKeyOf(it) }.flatMap { it.second }
        renderGroupChips()
        renderChips(db.counts())
        list.removeAllViews()
        rowViews.clear()
        shownIds.clear()
        marquee.clear()
        if (rows.isEmpty()) {
            list.addView(ui.card(8).apply {
                addView(ui.title(if (query.isBlank() && filter == null) "Поки порожньо" else "Нічого не знайдено", 17f))
                addView(ui.muted(
                    if (query.isBlank() && filter == null) "Запиши кілька пісень на вкладці «Запис» — тут буде видно кожну: повна, неповна, невдала чи пропущена."
                    else "Спробуй інший фільтр або пошук.",
                    14f,
                ))
            }, ui.matchWrap())
        } else {
            val allWavs = if (picked.isEmpty() && query.isBlank()) db.list(null, "", limit = Int.MAX_VALUE).filter { isWav(it) } else emptyList()
            if (allWavs.isNotEmpty()) list.addView(ui.card(8, fill = Palette.warningFill, stroke = Palette.warning).apply {
                addView(ui.text(ui.iconText(LineIcon.DISC, "WAV без обкладинки у файлі: ${allWavs.size}", Palette.warning, LineIcon.LABEL_DP - 6), 15f, Palette.warning, medium = true))
                addView(ui.muted("Плеєри не бачать у них картинки. Одним натиском — усі у FLAC: звук той самий, усередині обкладинка з історії, назви й номер. WAV лишаються.", 13.5f))
                addView(ui.iconButton(LineIcon.DISC, "Усі в FLAC з обкладинками (${allWavs.size})", Ui.Kind.PRIMARY) { confirmMakeFlac(allWavs.sortedBy { it.createdAt }) }, ui.matchWrap())
                addView(ui.muted("Або по частинах: кнопка в шапці сесії чи альбому, або довге натискання на плитку — вибрати кілька.", 12.5f))
            }, ui.matchWrap())
            if (hidden > 0) list.addView(ui.card(6).apply {
                addView(ui.muted("Сховано ${TimeText.plural(hidden, "запис", "записи", "записів")}: файлів нема на місці (перенесли чи видалили). Усі записи — у «Групувати: Сесії».", 13.5f))
                // T77: перейменував файли сам — знайти їх у тих самих папках і зв'язати з історією.
                addView(ui.iconButton(LineIcon.FOLDER, "Знайти перейменовані", Ui.Kind.NORMAL) { findRenamed() }, ui.matchWrap())
            }, ui.matchWrap())
            if (group == Group.ALBUMS && picked.isEmpty() && query.isBlank()) {
                val taggable = db.list(null, "", limit = Int.MAX_VALUE).filter { Renumber.files(it).isNotEmpty() && it.id !in missing }
                if (taggable.isNotEmpty()) list.addView(ui.card(8).apply {
                    addView(ui.label("Номери пісень", LineIcon.LIST))
                    addView(ui.muted("TRACK 10 — 10, BONUS 03 — останній TRACK + 3. Після кожного запису Track Catcher перевіряє сам; для старих альбомів — тут.", 13.5f))
                    addView(ui.iconButton(LineIcon.LIST, "Перевірити номери в усіх альбомах", Ui.Kind.NORMAL) { confirmRenumber(taggable) }, ui.matchWrap())
                }, ui.matchWrap())
            }
            renderGroups()
        }
        renderPickBar() // після списку: «Вибрати всі видимі» знає, що видно
        marquee.restart()
        detailPane?.let { pane ->
            val selected = rows.firstOrNull { it.id == selectedId } ?: db.get(selectedId)
            pane.removeAllViews()
            if (selected != null) {
                pane.addView(detailView(selected, inDialog = false), ui.matchWrap())
            } else {
                pane.addView(ui.card(8).apply {
                    addView(ui.label("Подробиці", LineIcon.LIST))
                    addView(ui.muted("Вибери запис ліворуч — тут буде все про нього: тривалість, формат, файл, чому неповний. І плеєр: ⏮ ▶ ⏭, «Поділитись».", 14f))
                }, ui.matchWrap())
            }
        }
    }

    // ---------- T89: закриті / відкриті групи ----------

    private fun recordingSessionId(): Long =
        RecorderHub.state.takeIf { it.phase == RecorderHub.Phase.RECORDING }?.sessionId ?: -1L

    /** Відкрита група? Пошук розкриває все; сесія, що зараз пишеться, — завжди відкрита. */
    private fun isOpen(key: String, sessionId: Long = -1L): Boolean {
        if (query.isNotBlank()) return true
        if (sessionId >= 0 && sessionId == recordingSessionId()) return true
        return prefs.historyOpen != (key in toggled)
    }

    private fun toggle(key: String) {
        if (!toggled.remove(key)) toggled += key
        reload()
    }

    /** «Розгорнути всі» / «Згорнути всі» (лише верхній рівень; сесії в альбомах — як були). */
    private fun setAll(open: Boolean, keys: List<String>) {
        toggled.removeAll { it in keys }
        if (open != prefs.historyOpen) toggled += keys
        reload()
    }

    /** Позначки на закритій плитці: що там не так (видно, не відкриваючи). */
    private fun marks(members: List<RecordingRow>): List<Pair<String, Int>> = buildList {
        val holes = members.count { Holes.decode(it.holes).isNotEmpty() }
        if (holes > 0) add("тріски: $holes" to Palette.warning)
        val gone = members.count { it.id in missing }
        if (gone > 0) add("файлів нема: $gone" to Palette.warning)
        val failed = members.count { it.status == RecStatus.FAILED }
        if (failed > 0) add("невдалих: $failed" to Palette.danger)
    }

    private fun countLine(members: List<RecordingRow>, extra: String = ""): String = buildString {
        append(TimeText.plural(members.size, "пісня", "пісні", "пісень"))
        val withFiles = members.filter { it.hasFile }
        if (withFiles.isNotEmpty()) append(" · ").append(TimeText.clock(withFiles.sumOf { it.lengthMs }))
        if (extra.isNotBlank()) append(" · ").append(extra)
    }

    private fun groupArt(members: List<RecordingRow>, sizeDp: Int): View? =
        members.firstOrNull { it.artworkPath != null }?.let { thumb(it, sizeDp) }

    private fun tile(key: String, title: String, sub: String, members: List<RecordingRow>, open: Boolean, nested: Boolean): View {
        val t = GroupTile(host, ui, title, sub, marks(members), groupArt(members, if (nested) 40 else 52), open, nested) { toggle(key) }
        marquee.add(t)
        return t
    }

    private fun addRows(members: List<RecordingRow>, indentDp: Int = 0) {
        for (r in members) {
            shownIds += r.id
            list.addView(rowView(r), ui.matchWrap().apply { leftMargin = ui.dp(indentDp) })
        }
    }

    private fun renderGroups() {
        val sessions = db.sessions()
        if (group == Group.SESSIONS) {
            val bySession = Playlist.groupInOrder(rows) { it.sessionId.toString() }
            val keys = bySession.map { "s:${it.first}" }
            if (bySession.size > 1 && query.isBlank()) list.addView(allRow(keys), ui.matchWrap())
            for ((sid, members) in bySession) {
                val s = sessions[sid.toLong()]
                val key = "s:$sid"
                val open = isOpen(key, sid.toLong())
                val fmt = s?.formatLabel.orEmpty()
                list.addView(tile(key, sessionTitle(s), countLine(members, fmt), members, open, nested = false), ui.matchWrap())
                if (!open) continue
                sessionActions(members)?.let { list.addView(it, ui.matchWrap()) }
                addRows(members)
            }
            return
        }
        val groups = Playlist.groupInOrder(rows) { groupKeyOf(it) }
        val keys = groups.map { "g:${it.first}" }
        if (groups.size > 1 && query.isBlank()) list.addView(allRow(keys), ui.matchWrap())
        for ((gk, members) in groups) {
            val key = "g:$gk"
            val open = isOpen(key)
            list.addView(tile(key, groupTitle(members.first()), countLine(members), members, open, nested = false), ui.matchWrap())
            if (!open) continue
            groupActions(members)?.let { list.addView(it, ui.matchWrap()) }
            // T89 (faric 21.10 20:14): альбом записано за кілька сесій — усередині сесії теж закритими плитками.
            val bySession = Playlist.groupInOrder(members) { it.sessionId.toString() }
            if (bySession.size < 2) {
                addRows(members)
                continue
            }
            for ((sid, part) in bySession) {
                val sk = "gs:$gk:$sid"
                val sOpen = isOpen(sk, sid.toLong())
                list.addView(tile(sk, "Сесія " + sessionTitle(sessions[sid.toLong()]), countLine(part), part, sOpen, nested = true),
                    ui.matchWrap().apply { leftMargin = ui.dp(16) })
                if (sOpen) addRows(part, indentDp = 16)
            }
        }
    }

    /** «Розгорнути всі» · «Згорнути всі». */
    private fun allRow(keys: List<String>): View = FlowLayout(host, ui.dp(8), ui.dp(8)).apply {
        addView(ui.button("Розгорнути всі", Ui.Kind.QUIET) { setAll(true, keys) })
        addView(ui.button("Згорнути всі", Ui.Kind.QUIET) { setAll(false, keys) })
    }

    private fun renderGroupChips() {
        groupChips.removeAllViews()
        groupChips.addView(ui.muted("Групувати:", 13.5f))
        for (g in Group.entries) groupChips.addView(ui.choice(g.label, group == g) {
            if (group != g) {
                group = g
                prefs.historyGroup = g.code
                reload()
            }
        })
    }

    private fun groupName(r: RecordingRow): String = when (group) {
        Group.ALBUMS -> r.album.trim()
        Group.ARTISTS -> r.artist.trim()
        Group.SESSIONS -> r.sessionId.toString()
    }

    private fun groupKeyOf(r: RecordingRow): String = if (group == Group.SESSIONS) r.sessionId.toString() else Playlist.groupKey(groupName(r))

    /** Записи тієї самої групи, що й [r] (в альбомі ⏮/⏭ і «дограла» лишаються в альбомі). */
    private fun scope(r: RecordingRow): List<RecordingRow> =
        if (group == Group.SESSIONS) rows else groupKeyOf(r).let { k -> rows.filter { groupKeyOf(it) == k } }

    private fun groupTitle(first: RecordingRow): String =
        groupName(first).ifBlank { if (group == Group.ALBUMS) "Без альбому" else "Невідомий виконавець" }

    /** Дії альбому / виконавця (T61…T88): «Слухати», «Поділитись», теги, тріски… — під відкритою плиткою групи. */
    private fun groupActions(members: List<RecordingRow>): View? {
        val name = groupTitle(members.first())
        val withFiles = members.filter { it.hasFile }
        val box = ui.card(8)
        if (withFiles.isNotEmpty()) {
            val actions = FlowLayout(host, ui.dp(8), ui.dp(8))
            actions.addView(ui.iconButton(LineIcon.PLAY, "Слухати", Ui.Kind.PRIMARY) { listenGroup(withFiles) })
            actions.addView(ui.iconButton(LineIcon.SHARE, "Поділитись", Ui.Kind.NORMAL) { shareGroup(name, withFiles) })
            actions.addView(ui.iconButton(LineIcon.REPEAT, "Конвертувати", Ui.Kind.NORMAL) { convert(withFiles) })
            val wavs = withFiles.filter { isWav(it) }
            if (wavs.isNotEmpty()) actions.addView(ui.iconButton(LineIcon.DISC, "FLAC з обкладинками (${wavs.size})", Ui.Kind.NORMAL) { confirmMakeFlac(wavs) })
            // T70: номери пісень альбому — перевірити й виправити в тегах.
            val taggable = withFiles.filter { Renumber.files(it).isNotEmpty() }
            if (group == Group.ALBUMS && taggable.isNotEmpty()) actions.addView(ui.iconButton(LineIcon.LIST, "Перевірити номери", Ui.Kind.NORMAL) { confirmRenumber(taggable) })
            // T78: теги й імена всіх пісень групи.
            actions.addView(ui.iconButton(LineIcon.SLIDERS, "Змінити теги (${withFiles.size})", Ui.Kind.NORMAL) { openEditor(withFiles) })
            actions.addView(ui.iconButton(LineIcon.EDGES, "Обрізати / затухання (${withFiles.size})", Ui.Kind.NORMAL) { openTrim(withFiles) })
            // T88: тріски (дірки тиші посеред музики) — лише FLAC / WAV.
            val checkable = withFiles.filter { HoleScan.canCheck(it) && it.id !in missing }
            if (checkable.isNotEmpty()) actions.addView(ui.iconButton(LineIcon.BARS, "Перевірити на тріски (${checkable.size})", Ui.Kind.NORMAL) { checkHoles(checkable) })
            // T71: у назвах повторюється чужий хвіст (Suno дописує назву пісні, з якої почалась черга) — прибрати.
            val tail = TitleTail.detect(members.map { it.title })
            val tailed = tail?.let { t -> members.filter { TitleTail.strip(it.title, t) != null } }.orEmpty()
            if (tail != null && tailed.isNotEmpty()) {
                actions.addView(ui.iconButton(LineIcon.CUT, "Прибрати зайве з назв (${tailed.size})", Ui.Kind.NORMAL) { confirmTitles(members, tail) })
            }
            box.addView(actions, ui.matchWrap())
        }
        return if (box.childCount == 0) null else box
    }

    /** Альбом з першої пісні (найстарішої — як у плеєрі), далі сам по черзі. */
    private fun listenGroup(members: List<RecordingRow>) {
        val start = members.last()
        listen(start)
        if (InAppPlayer.uri != null) host.toast("Грає ${TimeText.plural(members.size, "пісня", "пісні", "пісень")} — з першої")
    }

    private fun shareGroup(name: String, members: List<RecordingRow>) {
        val uris = ArrayList<Uri>()
        for (r in members.asReversed()) {
            r.mp3Uri?.let { uris += Uri.parse(it) }
            r.wavUri?.let { uris += Uri.parse(it) }
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            host.startActivity(Intent.createChooser(send, "Поділитись: $name"))
        } catch (e: ActivityNotFoundException) {
            host.toast("Немає застосунку, щоб поділитись")
        }
    }

    private fun renderChips(counts: Map<RecStatus, Int>) {
        chips.removeAllViews()
        val total = counts.values.sum()
        chips.addView(ui.choice("Усі $total", filter == null) { setFilter(null) })
        for (st in RecStatus.entries) {
            val n = counts[st] ?: 0
            chips.addView(ui.choice("${filterLabel(st)} $n", filter == st) { setFilter(st) })
        }
    }

    private fun filterLabel(s: RecStatus) = when (s) {
        RecStatus.COMPLETE -> "Повні"
        RecStatus.PARTIAL -> "Неповні"
        RecStatus.FAILED -> "Невдалі"
        RecStatus.SKIPPED -> "Пропущені"
    }

    private fun setFilter(s: RecStatus?) {
        filter = s
        reload()
    }

    /** «20.10, 14:13–14:40» — коли була сесія. */
    private fun sessionTitle(s: SessionRow?): String = if (s == null) "Сесія" else buildString {
        append(TimeText.dateTime(s.startedAt))
        if (s.stoppedAt > 0) append("–").append(TimeText.time(s.stoppedAt))
    }

    /** Дії сесії (T69, T78, T79, T88) — під відкритою плиткою сесії. */
    private fun sessionActions(members: List<RecordingRow>): View? {
        // T69 (faric 17.10 21:45: «по одній — бажання нема»): уся сесія одним натиском.
        val wavs = members.filter { isWav(it) }
        val editable = members.filter { it.hasFile && it.id !in missing }
        if ((wavs.isEmpty() && editable.isEmpty()) || picked.isNotEmpty()) return null
        return ui.column(6).apply {
            val row = FlowLayout(host, ui.dp(8), ui.dp(8))
            if (wavs.isNotEmpty()) row.addView(ui.iconButton(LineIcon.DISC, "Сесію в FLAC з обкладинками (${wavs.size})", Ui.Kind.NORMAL) { confirmMakeFlac(wavs) })
            // T78 (faric 19.10 19:46: «сесію нажав — змінити метадані сесії»).
            if (editable.isNotEmpty()) row.addView(ui.iconButton(LineIcon.SLIDERS, "Змінити теги сесії (${editable.size})", Ui.Kind.NORMAL) { openEditor(editable) })
            if (editable.isNotEmpty()) row.addView(ui.iconButton(LineIcon.EDGES, "Обрізати / затухання (${editable.size})", Ui.Kind.NORMAL) { openTrim(editable) })
            val checkable = editable.filter { HoleScan.canCheck(it) }
            if (checkable.isNotEmpty()) row.addView(ui.iconButton(LineIcon.BARS, "Перевірити на тріски (${checkable.size})", Ui.Kind.NORMAL) { checkHoles(checkable) })
            addView(row, ui.matchWrap())
        }
    }

    private fun rowView(r: RecordingRow): View {
        val picking = picked.isNotEmpty()
        val selected = if (picking) r.id in picked else detailPane != null && r.id == selectedId
        val box = ui.row(12).apply {
            gravity = Gravity.TOP
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                when {
                    picked.isNotEmpty() -> togglePick(r) // перевіряємо зараз, а не коли плитку будували
                    detailPane == null -> showDetailDialog(r)
                    else -> select(r)
                }
            }
            // Довге натискання — вибрати (далі торкання додає/знімає інші плитки).
            setOnLongClickListener {
                togglePick(r)
                true
            }
        }
        styleRow(box, r, selected)
        rowViews[r.id] = box
        box.addView(thumb(r, 48), LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        val texts = ui.column(3)
        texts.addView(ui.text(r.displayName, 15.5f, medium = true).apply {
            // T44: довга назва — 3 рядки і «…» в кінці, а не мовчки обрізана посеред.
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(ui.numbers(ui.muted(metaLine(r), 13f)))
        if (r.id in missing) texts.addView(ui.text("файла нема на місці — перенесли чи видалили", 12.5f, Palette.warning))
        // T88: тріски — видно одразу в списку, що перезаписати.
        Holes.decode(r.holes).takeIf { it.isNotEmpty() }?.let {
            texts.addView(ui.numbers(ui.text("тріски: ${Holes.describe(it)} — перезаписати", 12.5f, Palette.warning)).apply { maxLines = 3 })
        }
        if (r.status != RecStatus.COMPLETE && r.reason.isNotBlank()) {
            texts.addView(ui.text(r.reason, 12.5f, ui.statusColor(r.status)).apply { maxLines = 3 })
        }
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(ui.pill(r.status))
        return box
    }

    private fun metaLine(r: RecordingRow): String = buildString {
        append(TimeText.clock(r.lengthMs))
        val fmt = formats(r)
        if (fmt.isNotBlank()) append(" · ").append(fmt)
        val size = r.mp3Size + r.wavSize
        if (size > 0) append(" · ").append(TimeText.size(size))
        append(" · ").append(TimeText.time(r.createdAt))
    }

    private fun formats(r: RecordingRow): String = listOfNotNull(
        if (r.mp3Name != null) "MP3 ${r.mp3Bitrate}" else null,
        if (r.wavName != null) Lossless.ofName(r.wavName).label else null,
    ).joinToString(" + ")

    private fun thumb(r: RecordingRow, sizeDp: Int): View {
        val frame = FrameLayout(host).apply { background = ui.rounded(Palette.surfaceAlt, radiusDp = 10) }
        val bmp = r.artworkPath?.let { path ->
            thumbs.get(path) ?: decode(path, ui.dp(sizeDp))?.also { thumbs.put(path, it) }
        }
        if (bmp != null) {
            frame.addView(ImageView(host).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = ui.rounded(Palette.surfaceAlt, radiusDp = 10)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            frame.addView(ui.text("♪", sizeDp / 2.4f, Palette.faint).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return frame
    }

    private fun decode(path: String, px: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    // ---------- подробиці ----------

    private fun select(r: RecordingRow) {
        selectedId = r.id
        reload()
    }

    private fun showDetailDialog(r: RecordingRow, keepScroll: Boolean = false) {
        selectedId = r.id
        host.overlay.show("detail:${r.id}", detailView(r, inDialog = true), keepScroll = keepScroll)
    }

    private fun detailView(r: RecordingRow, inDialog: Boolean): View {
        val box = ui.column(12)
        val head = ui.row(14).apply { gravity = Gravity.TOP }
        head.addView(thumb(r, 96), LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)))
        val titles = ui.column(6)
        titles.addView(ui.title(r.title.ifBlank { r.displayName }, 19f))
        val sub = listOf(r.artist, r.album).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) titles.addView(ui.muted(sub, 14.5f))
        titles.addView(ui.pill(r.status), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(head, ui.matchWrap())
        if (r.reason.isNotBlank()) box.addView(ui.text(r.reason, 14f, ui.statusColor(r.status)))

        val facts = ui.column(8)
        facts.addView(ui.fact("Записано", TimeText.clock(r.lengthMs)))
        if (r.durationMs > 0) facts.addView(ui.fact("Пісня", TimeText.clock(r.durationMs)))
        formats(r).takeIf { it.isNotBlank() }?.let { facts.addView(ui.fact("Формат", it)) }
        r.mp3Name?.let { facts.addView(ui.fact("Файл MP3", "$it · ${TimeText.size(r.mp3Size)}")) }
        r.wavName?.let { facts.addView(ui.fact("Файл ${Lossless.ofName(it).label}", "$it · ${TimeText.size(r.wavSize)}")) }
        if (r.hasFile) {
            val folder = (r.mp3Uri ?: r.wavUri)?.let { MediaStoreWriter(host).folderLabel(it) } ?: MediaStoreWriter.FOLDER_LABEL
            facts.addView(ui.fact("Папка", folder))
        }
        facts.addView(ui.fact("Розріз", if (r.cutBySilence) "по тиші між піснями" else "по сигналу зі шторки"))
        if (r.clips >= 0 && r.hasFile) {
            facts.addView(ui.fact("Перевантаження", if (r.clips == 0) "немає" else "${r.clips} — пісня надто гучна, піки обрізано (можливий «чирк»)"))
        }
        if (r.holes != null && r.hasFile) {
            val places = Holes.decode(r.holes)
            facts.addView(ui.fact("Тріски", if (places.isEmpty()) "немає" else Holes.describe(places) + " — дірки тиші посеред музики. Track Catcher пише звук таким, яким він приходить: дірка була вже в тому, що грав плеєр (інтернет, сервер, плеєр). Цю пісню краще перезаписати"))
        }
        if (r.sourcePackage.isNotBlank()) facts.addView(ui.fact("Плеєр", appLabel(r.sourcePackage)))
        facts.addView(ui.fact("Коли", TimeText.dateTime(r.createdAt)))
        if (r.id > 0) facts.addView(ui.fact("Запис №", r.id.toString())) // T18: свій номер, навіть коли назви однакові
        box.addView(facts, ui.matchWrap())

        // T68 / T69: що насправді у файлі; старий WAV — FLAC з обкладинкою з історії.
        if (r.hasFile) {
            // На всю ширину (faric 18.10 15:19: «кнопку "Про файл" розтягни на всю ширину»).
            val more = ui.column(8)
            more.addView(ui.iconButton(LineIcon.FILE, "Про файл", Ui.Kind.NORMAL) { openFileInfo(r) }, ui.matchWrap())
            if (r.id !in missing) more.addView(ui.iconButton(LineIcon.SLIDERS, "Змінити теги й ім'я", Ui.Kind.NORMAL) { openEditor(listOf(r)) }, ui.matchWrap())
            if (r.id !in missing) more.addView(ui.iconButton(LineIcon.EDGES, "Обрізати / затухання", Ui.Kind.NORMAL) { openTrim(listOf(r)) }, ui.matchWrap())
            if (r.id !in missing && HoleScan.canCheck(r)) more.addView(ui.iconButton(LineIcon.BARS, "Перевірити на тріски", Ui.Kind.NORMAL) { checkHoles(listOf(r)) }, ui.matchWrap())
            if (isWav(r)) more.addView(ui.iconButton(LineIcon.REPEAT, "Зробити FLAC з обкладинкою", Ui.Kind.NORMAL) { confirmMakeFlac(listOf(r)) }, ui.matchWrap())
            box.addView(more, ui.matchWrap())
        }

        // Плеєр (v0.1.17, faric 15.10 15:36): одразу видно смугу й ⏮ ▶ ⏭; ⏮/⏭ — сусідні записи історії.
        if (r.hasFile) {
            val both = r.mp3Uri != null && r.wavUri != null
            if (both) {
                // Обидва формати — вибір, який слухати (зелена рамка, без галочок).
                val formats = ui.row(8)
                formats.addView(ui.muted("Слухати:", 14f))
                listOf(false to "MP3", true to Lossless.ofName(r.wavName).label).forEach { (wav, label) ->
                    formats.addView(ui.choice(label, listenWav == wav) {
                        if (listenWav == wav) return@choice
                        val wasPlaying = InAppPlayer.playing && isPlaying(r)
                        listenWav = wav
                        reopen(r)
                        if (wasPlaying) listen(r)
                    })
                }
                box.addView(formats, ui.matchWrap())
            }
            val uri = playUri(r)!!
            val prev = neighbor(r, next = false)
            val next = neighbor(r, next = true)
            // Дії — маленькими значками всередині плитки плеєра (faric 15.10 17:31).
            val actions = listOf(
                ActionTile(host, ui, ActionTile.Icon.SHARE, "Поділитись", danger = false) { share(r) },
                ActionTile(host, ui, ActionTile.Icon.OPEN, "Плеєр", danger = false) { InAppPlayer.pause(); playExternal(uri, playMime(r)) },
                ActionTile(host, ui, ActionTile.Icon.CONVERT, "Конвертер", danger = false) { convert(listOf(r)) },
                ActionTile(host, ui, ActionTile.Icon.DELETE, "Видалити", danger = true) { confirmDelete(r) },
            )
            box.addView(PlayerView(
                host, ui, uri, r.lengthMs,
                onPlay = { listen(r) },
                onPrevious = prev?.let { p -> { go(p) } },
                onNext = next?.let { n -> { go(n) } },
                actions = actions,
            ), ui.matchWrap())
        } else if (r.status != RecStatus.COMPLETE && r.status != RecStatus.PARTIAL) {
            box.addView(ui.muted("Файлу немає — запис не зберігався (див. причину вище).", 13f))
        }
        // «Закрити» — окремо, широка, в самому низу (faric 15.10: «внизу кнопочка широка на всю»).
        if (inDialog) box.addView(ui.button("Закрити", Ui.Kind.QUIET, large = true) { host.overlay.dismiss() }, ui.matchWrap())
        return box
    }

    // ---------- плеєр: формат, сусіди, «грати далі» ----------

    private fun playUri(r: RecordingRow): String? = if (listenWav) r.wavUri ?: r.mp3Uri else r.mp3Uri ?: r.wavUri

    private fun playMime(r: RecordingRow): String = if (playUri(r) == r.wavUri) Lossless.ofName(r.wavName).mime else "audio/mpeg"

    private fun isPlaying(r: RecordingRow): Boolean = InAppPlayer.uri != null && (InAppPlayer.uri == r.mp3Uri || InAppPlayer.uri == r.wavUri)

    private fun neighbor(r: RecordingRow, next: Boolean): RecordingRow? {
        val pool = scope(r)
        val id = Playlist.neighbor(pool.map { it.id }, { id -> pool.firstOrNull { it.id == id }?.hasFile == true }, r.id, next)
        return id?.let { found -> pool.firstOrNull { it.id == found } }
    }

    /**
     * Показати подробиці [r] замість поточних (⏮/⏭, «дограла», MP3/WAV) — там, де вони зараз
     * (вікно або права колонка), і **не стрибати вгору**: плеєр лишається на тому самому місці (T51).
     */
    private fun reopen(r: RecordingRow) {
        if (detailPane == null) {
            showDetailDialog(r, keepScroll = true)
        } else {
            val s = detailScroll
            val fromBottom = s?.let { distanceFromBottom(it) }
            select(r)
            if (s != null && fromBottom != null) restoreFromBottom(s, fromBottom)
        }
    }

    /** ⏮ / ⏭: відкрити сусіда; якщо грало — грати й його (як у плеєрі). */
    private fun go(to: RecordingRow) {
        val wasPlaying = InAppPlayer.playing
        reopen(to)
        if (wasPlaying) listen(to)
    }

    /**
     * Плеєр сам перейшов на інший запис (дограла, ⏮/⏭ у шторці): якщо відкриті подробиці іншого —
     * показати ті, що грають.
     */
    private fun follow(t: InAppPlayer.Track) {
        if (detailPane == null) {
            val key = host.overlay.key ?: return
            if (!key.startsWith("detail:") || key == "detail:${t.id}") return
            (rows.firstOrNull { it.id == t.id } ?: db.get(t.id))?.let { reopen(it) }
        } else if (selectedId != t.id) {
            (rows.firstOrNull { it.id == t.id } ?: db.get(t.id))?.let { reopen(it) }
        }
    }

    private fun track(r: RecordingRow): InAppPlayer.Track? {
        val uri = playUri(r) ?: return null
        return InAppPlayer.Track(r.id, uri, r.title.ifBlank { r.displayName }, r.artist, r.album, r.artworkPath)
    }

    private fun listen(r: RecordingRow) {
        if (RecorderHub.state.phase == RecorderHub.Phase.RECORDING) {
            // Наш звук забрав би «фокус» — плеєр, який записуємо, став би на паузу.
            host.toast("Іде запис — послухаєш після «Зупинити»: інакше плеєр, який записуєш, стане на паузу")
            return
        }
        val t = track(r) ?: return
        InAppPlayer.play(host, t, scope(r).filter { it.hasFile }.mapNotNull { track(it) })
    }

    private fun playExternal(uri: String, mime: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            host.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            host.toast("Немає плеєра, що відкриває цей файл")
        }
    }

    private fun share(r: RecordingRow) {
        val uris = ArrayList<Uri>()
        r.mp3Uri?.let { uris += Uri.parse(it) }
        r.wavUri?.let { uris += Uri.parse(it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(if (r.mp3Uri != null) "audio/mpeg" else Lossless.ofName(r.wavName).mime).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            host.startActivity(Intent.createChooser(send, "Поділитись записом"))
        } catch (e: ActivityNotFoundException) {
            host.toast("Немає застосунку, щоб поділитись")
        }
    }

    /**
     * T65: файли — в Audio Converter (окремий застосунок faric, там A2). Дається найкраща якість:
     * FLAC/WAV, а якщо його нема — MP3. Конвертера нема на телефоні — пояснення замість «нема застосунку».
     */
    private fun convert(rows: List<RecordingRow>) {
        val files = rows.mapNotNull { r -> (r.wavUri ?: r.mp3Uri)?.let { Uri.parse(it) to if (r.wavUri != null) Lossless.ofName(r.wavName).mime else "audio/mpeg" } }
        if (files.isEmpty()) return
        val send = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(files[0].second).putExtra(Intent.EXTRA_STREAM, files[0].first)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.first }))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (pkg in CONVERTER_PACKAGES) {
            try {
                host.startActivity(Intent(send).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
                // нема цього варіанта — пробуємо наступний
            }
        }
        showNoConverter()
    }

    private fun showNoConverter() {
        val body = host.overlay.dialog(
            title = "Audio Converter не встановлено",
            message = "Audio Converter — окремий застосунок faric: з найкращої якості (FLAC) робить MP3, M4A, Opus та інші формати. " +
                "Коли він стоїть на цьому телефоні, ця кнопка відкриває в ньому вибрані записи.",
            actions = listOf("Закрити" to Ui.Kind.QUIET),
            onAction = { host.overlay.dismiss() },
        )
        host.overlay.show(KEY_NO_CONVERTER, body)
    }

    // ---------- «Про файл» (T68) ----------

    private fun isWav(r: RecordingRow) = FlacBatch.isWav(r)

    /** Файл, який зараз вибрано для прослуховування (MP3 чи FLAC/WAV), — його й показуємо. */
    private fun openFileInfo(r: RecordingRow) {
        val uri = playUri(r) ?: return
        val name = (if (uri == r.wavUri) r.wavName else r.mp3Name) ?: ""
        val store = MediaStoreWriter(host)
        val app = host.applicationContext
        val key = "fileinfo:${r.id}"
        // Поворот (faric 21.10 01:17: «вікно "Про файл" після перевороту пропадає»): файл на M30s читається кілька
        // секунд, і весь цей час вікна не було — ще один поворот, і воно губилось зовсім. Тепер: прочитане — з пам'яті
        // одразу, а поки читається — вікно «Читаю файл…» з тим самим ключем (переживе й наступний поворот).
        fun showPanel(info: FileTags.FileInfo, size: Long, folder: String?) {
            val extra = FileInfoPanel.Extra(
                recorded = recordedFacts(r),
                historyArtwork = r.artworkPath,
                clips = r.clips,
                makeFlac = if (isWav(r) && uri == r.wavUri) ({ confirmMakeFlac(listOf(r)) }) else null,
            )
            host.overlay.show(key, FileInfoPanel(host).build(info, name, folder, size, extra) { closeFileInfo(r) })
        }
        fileInfoCache?.takeIf { it.uri == uri && System.currentTimeMillis() - it.at < 10 * 60_000L }?.let { c ->
            showPanel(c.info, c.size, c.folder)
            return
        }
        host.overlay.show(key, ui.column(12).apply {
            addView(ui.title("Про файл", 19f))
            addView(ui.muted("Читаю файл…\n$name", 14.5f))
            addView(ui.button("Закрити", Ui.Kind.QUIET) { closeFileInfo(r) }, ui.matchWrap())
        }, placeholder = true)
        Thread({
            val result = runCatching {
                val size = store.sizeOf(uri)
                val info = app.contentResolver.openInputStream(Uri.parse(uri))?.buffered(64 * 1024)?.use { FileTags.read(it, size, name) }
                    ?: throw java.io.IOException("файл не відкривається — можливо, його прибрали в «Моїх файлах»")
                FileInfoCache(uri, info, size, store.folderLabel(uri)).also { fileInfoCache = it }
            }
            host.runOnUiThread {
                // Новий екран після повороту покаже з пам'яті сам; цей — лише якщо досі чекає саме на це вікно.
                if (host.isFinishing || host.isDestroyed || host.overlay.key != key) return@runOnUiThread
                result.fold(
                    { c -> showPanel(c.info, c.size, c.folder) },
                    { e ->
                        host.overlay.dismiss()
                        host.toast("Не вдалося прочитати файл: ${e.message ?: e.javaClass.simpleName}")
                    },
                )
            }
        }, "file-info").start()
    }

    /** «Закрити» в «Про файл» — назад до подробиць запису (у вікні) або просто закрити (дві колонки). */
    private fun closeFileInfo(r: RecordingRow) {
        if (detailPane == null) db.get(r.id)?.let { showDetailDialog(it) } ?: host.overlay.dismiss() else host.overlay.dismiss()
    }

    private fun recordedFacts(r: RecordingRow): List<Pair<String, String>> = buildList {
        if (r.sourcePackage.isNotBlank()) add("Плеєр" to appLabel(r.sourcePackage))
        add("Записано" to TimeText.clock(r.lengthMs) + if (r.durationMs > 0) " з ${TimeText.clock(r.durationMs)}" else "")
        add("Статус" to r.status.label)
        add("Розріз" to if (r.cutBySilence) "по тиші між піснями" else "по сигналу зі шторки")
        add("Коли" to TimeText.dateTime(r.createdAt))
        if (r.id > 0) add("Запис №" to r.id.toString())
    }

    // ---------- WAV → FLAC з обкладинкою (T69) ----------

    private fun confirmMakeFlac(rows: List<RecordingRow>) {
        if (RecorderHub.state.phase != RecorderHub.Phase.IDLE) {
            host.toast("Спершу зупини запис — перетворення вантажить телефон")
            return
        }
        if (FlacBatch.state.running) {
            showBatch(FlacBatch.state)
            return
        }
        val ids = rows.map { it.id }.toLongArray()
        FlacBatch.pendingConfirm = ids
        FlacBatch.pendingMode = FlacBatch.Mode.FLAC
        val withArt = rows.count { r -> r.artworkPath?.let { java.io.File(it).isFile } == true }
        val approx = TimeText.size(rows.sumOf { it.wavSize } * 6 / 10)
        val body = host.overlay.dialog(
            title = if (rows.size == 1) "Зробити FLAC з обкладинкою?" else "Зробити ${TimeText.plural(rows.size, "FLAC", "FLAC", "FLAC")} з обкладинками?",
            message = "З WAV вийде FLAC: звук той самий до біта, а всередині — назва, виконавець, альбом, номер пісні й обкладинка з історії " +
                "(є для $withArt з ${rows.size}).\n\nWAV лишаються, як були; FLAC з'являться поруч (≈ $approx). В історії запис почне показувати FLAC.\n\n" +
                "Робота йде у фоні: можна повертати екран і згортати застосунок — поступ видно в шторці.",
            actions = listOf("Зробити" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                FlacBatch.pendingConfirm = null
                if (i == 0) {
                    FlacBatch.start(host, ids)
                    showBatch(FlacBatch.state)
                } else {
                    host.overlay.dismiss()
                }
            },
        )
        host.overlay.show(KEY_FLAC_CONFIRM, body)
    }

    /** T70: «Перевірити номери?» — перелік альбомів і що станеться. */
    private fun confirmRenumber(rows: List<RecordingRow>) {
        if (RecorderHub.state.phase != RecorderHub.Phase.IDLE) {
            host.toast("Спершу зупини запис")
            return
        }
        if (FlacBatch.state.running) {
            showBatch(FlacBatch.state)
            return
        }
        val ids = rows.map { it.id }.toLongArray()
        FlacBatch.pendingConfirm = ids
        FlacBatch.pendingMode = FlacBatch.Mode.RENUMBER
        val albums = rows.map { it.album.ifBlank { "без альбому" } }.distinct()
        val body = host.overlay.dialog(
            title = "Перевірити номери пісень?",
            message = (if (albums.size == 1) "Альбом «${albums[0]}»." else "Альбомів: ${albums.size}.") +
                " Track Catcher перечитає файли (FLAC, MP3) і там, де номер не той, перепише лише тег — звук не змінюється. " +
                "TRACK 10 — 10, BONUS 03 — останній TRACK + 3, повтор пісні — той самий номер.\n\n" +
                "Безпечно: перед заміною — копія, нове перевіряється; щось не так — файл лишається, як був.",
            actions = listOf("Перевірити" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                FlacBatch.pendingConfirm = null
                if (i == 0) {
                    FlacBatch.startRenumber(host, ids)
                    showBatch(FlacBatch.state)
                } else {
                    host.overlay.dismiss()
                }
            },
        )
        host.overlay.show(KEY_FLAC_CONFIRM, body)
    }

    /**
     * T71: «Прибрати зайве з назв?» — знайдений хвіст у полі (можна поправити), скільки пісень і приклад «було → стане».
     * [rows] — усі записи групи; виправляються ті, у чиїй назві в кінці стоїть цей текст.
     */
    private fun confirmTitles(rows: List<RecordingRow>, tail: String) {
        if (RecorderHub.state.phase != RecorderHub.Phase.IDLE) {
            host.toast("Спершу зупини запис")
            return
        }
        if (FlacBatch.state.running) {
            showBatch(FlacBatch.state)
            return
        }
        FlacBatch.pendingConfirm = rows.map { it.id }.toLongArray()
        FlacBatch.pendingMode = FlacBatch.Mode.TITLES
        FlacBatch.pendingTail = tail
        val preview = ui.text("", 14.5f, Palette.text).apply { minLines = 5 } // приклад змінюється — вікно не стрибає
        fun affected(t: String) = rows.filter { TitleTail.strip(it.title, t) != null }
        fun refresh(t: String) {
            val hit = affected(t)
            preview.text = if (hit.isEmpty()) "Цього тексту нема в кінці жодної назви — перевір, що він збігається до знака." else {
                val ex = hit.first()
                "Пісень: ${hit.size} з ${rows.size}.\n\nБуло: ${ex.title}\nСтане: ${TitleTail.strip(ex.title, t)}"
            }
        }
        val field = EditText(host).apply {
            setTextColor(Palette.text)
            textSize = 15f
            background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            minHeight = ui.dp(Ui.MIN_TOUCH_DP)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            id = View.generateViewId()
            setText(tail)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    FlacBatch.pendingTail = e?.toString().orEmpty()
                    refresh(FlacBatch.pendingTail)
                }
            })
        }
        refresh(tail)
        val extra = ui.column(10).apply {
            addView(ui.muted("Зайвий текст у кінці назви (можна поправити):", 13.5f))
            addView(field, ui.matchWrap())
            addView(preview)
        }
        val body = host.overlay.dialog(
            title = "Прибрати зайве з назв?",
            message = "Suno в шторці дописує до назви назву іншої пісні — так вона потрапила в записи. " +
                "Track Catcher обріже цей текст у назві всередині файлів (FLAC, MP3), в імені файлу й в історії. " +
                "Звук не змінюється; перед заміною — копія, щось не так — файл лишається, як був. " +
                "Нові записи з цим хвостом очищатимуться самі.",
            actions = listOf("Прибрати" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) {
                    val t = FlacBatch.pendingTail
                    val hit = affected(t)
                    if (hit.isEmpty()) {
                        host.toast("Цього тексту нема в кінці жодної назви")
                    } else {
                        FlacBatch.pendingConfirm = null
                        FlacBatch.startTitles(host, hit.map { it.id }.toLongArray(), t)
                        showBatch(FlacBatch.state)
                    }
                } else {
                    FlacBatch.pendingConfirm = null
                    host.overlay.dismiss()
                }
            },
            extra = extra,
        )
        host.overlay.show(KEY_FLAC_CONFIRM, body)
    }

    // ---------- T78: редактор тегів і імен файлів ----------

    private fun openEditor(rows: List<RecordingRow>) {
        if (RecorderHub.state.phase != RecorderHub.Phase.IDLE) {
            host.toast("Спершу зупини запис")
            return
        }
        if (FlacBatch.state.running) {
            showBatch(FlacBatch.state)
            return
        }
        val list = rows.filter { it.hasFile && it.id !in missing }
        if (list.isEmpty()) {
            host.toast("Нема файлів на місці")
            return
        }
        Editor.ids = list.map { it.id }.toLongArray()
        Editor.values.clear()
        Editor.name = MetaEdit.NameStyle.KEEP
        Editor.single = null
        if (list.size > 1) {
            showEditor()
            return
        }
        // Один запис — поля заповнені тим, що зараз (теги читаються з файлу).
        val app = host.applicationContext
        val r = list[0]
        Thread({
            val song = runCatching { MetaApply.current(app, r) }.getOrDefault(MetaEdit.Song(r.artist, r.title, r.album))
            host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                Editor.single = song
                Editor.values.putAll(mapOf("artist" to song.artist, "title" to song.title, "album" to song.album, "albumArtist" to song.albumArtist, "year" to song.year, "track" to song.track))
                showEditor()
            }
        }, "meta-read").start()
    }

    /** Що змінити — з полів редактора. Порожнє поле (чи те саме, що було) — не чіпати. */
    private fun editorSpec(): MetaEdit.Spec {
        val one = Editor.single
        fun v(key: String, was: String?): String? = Editor.values[key]?.trim()?.takeIf { it.isNotEmpty() && it != was?.trim() }
        return MetaEdit.Spec(
            artist = v("artist", one?.artist), title = if (one != null) v("title", one.title) else null, album = v("album", one?.album),
            albumArtist = v("albumArtist", one?.albumArtist), year = v("year", one?.year), track = if (one != null) v("track", one.track) else null,
            find = Editor.values["find"].orEmpty(), replace = Editor.values["replace"].orEmpty(), name = Editor.name,
        )
    }

    private fun showEditor(): Boolean {
        val rows = Editor.ids?.toList()?.mapNotNull { db.get(it) }.orEmpty()
        if (rows.isEmpty()) return false
        val one = Editor.single
        val plain = ua.faric.trackcatcher.data.Settings(host).snapshot().plainNames
        val preview = ui.text("", 14f, Palette.text).apply { minLines = 6 } // приклад змінюється — вікно не стрибає
        fun refresh() {
            val spec = editorSpec()
            val r = rows.first()
            val was = one ?: MetaEdit.Song(r.artist, r.title, r.album)
            val now = MetaEdit.apply(was, spec)
            val file = r.wavName ?: r.mp3Name ?: ""
            val newFile = MetaEdit.newName(file, now, spec.name, plain)
            val changes = rows.count { x ->
                val w = MetaEdit.Song(x.artist, x.title, x.album)
                val n = MetaEdit.apply(w, spec)
                n != w || listOfNotNull(x.wavName, x.mp3Name).any { MetaEdit.newName(it, n, spec.name, plain) != null } ||
                    spec.albumArtist != null || spec.year != null || spec.track != null
            }
            preview.text = if (spec.isEmpty) "Нічого не змінено — заповни поле, заміну чи вибери, як назвати файл." else buildString {
                append(if (rows.size == 1) "Зміниться:" else "Зміниться записів: $changes з ${rows.size}. Приклад — перший:")
                if (now.title != was.title) append("\n• Назва: ${was.title}\n   → ${now.title}")
                if (now.artist != was.artist) append("\n• Виконавець: ${was.artist} → ${now.artist}")
                if (now.album != was.album) append("\n• Альбом: ${was.album} → ${now.album}")
                spec.albumArtist?.let { append("\n• Виконавець альбому → $it") }
                spec.year?.let { append("\n• Рік → $it") }
                spec.track?.let { append("\n• Номер → $it") }
                if (newFile != null) append("\n• Файл: $file\n   → $newFile")
            }
        }
        fun field(key: String, label: String, hint: String): View = ui.column(4).apply {
            addView(ui.muted(label, 13f))
            addView(EditText(host).apply {
                setTextColor(Palette.text)
                setHintTextColor(Palette.faint)
                textSize = 15f
                this.hint = hint
                background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
                minHeight = ui.dp(Ui.MIN_TOUCH_DP)
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                id = View.generateViewId()
                setText(Editor.values[key].orEmpty())
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(e: Editable?) {
                        Editor.values[key] = e?.toString().orEmpty()
                        refresh()
                    }
                })
            }, ui.matchWrap())
        }
        fun common(values: List<String>): String = values.map { it.trim() }.distinct().let { d ->
            when {
                d.size == 1 && d[0].isNotEmpty() -> "не міняти (зараз: ${d[0]})"
                d.size > 1 -> "не міняти (різні)"
                else -> "не міняти"
            }
        }
        val extra = ui.column(12).apply {
            if (one != null) {
                addView(field("title", "Назва", "назва пісні"))
                addView(field("artist", "Виконавець", "виконавець"))
                addView(field("album", "Альбом", "без альбому"))
                addView(field("albumArtist", "Виконавець альбому", "нема"))
                addView(field("year", "Рік", "нема"))
                addView(field("track", "Номер пісні", "нема"))
            } else {
                addView(ui.muted("Порожнє поле — не змінювати; заповнене — поставити всім.", 13f))
                addView(field("artist", "Виконавець", common(rows.map { it.artist })))
                addView(field("album", "Альбом", common(rows.map { it.album })))
                addView(field("albumArtist", "Виконавець альбому", "не міняти"))
                addView(field("year", "Рік", "не міняти"))
                addView(ui.label("Замінити в назвах"))
                addView(field("find", "Що знайти (точно, з великими й малими літерами)", "напр. TRACK "))
                addView(field("replace", "На що замінити (порожньо — просто прибрати)", "нічого"))
            }
            addView(ui.label("Ім'я файлу"))
            val chips = FlowLayout(host, ui.dp(8), ui.dp(8))
            val views = HashMap<MetaEdit.NameStyle, android.widget.TextView>()
            MetaEdit.NameStyle.entries.forEach { st ->
                views[st] = ui.choice(st.label, Editor.name == st) {
                    Editor.name = st
                    views.forEach { (k, v) -> ui.styleChoice(v, k == st) }
                    refresh()
                }.also { chips.addView(it) }
            }
            addView(chips, ui.matchWrap())
            addView(ui.muted("Файли лишаються у своїх папках; звук не змінюється. Теги — у FLAC і MP3 (у WAV лише ім'я файлу й історія). Перед заміною — копія; щось не так — файл лишається, як був.", 12.5f))
            addView(preview)
        }
        refresh()
        val title = if (rows.size == 1) "Теги й ім'я файлу" else "Теги й імена — ${TimeText.plural(rows.size, "запис", "записи", "записів")}"
        val body = host.overlay.dialog(title, "", listOf("Застосувати" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET), { i ->
            if (i == 0) {
                val spec = editorSpec()
                if (spec.isEmpty) {
                    host.toast("Нічого не змінено")
                    return@dialog
                }
                val ids = Editor.ids ?: return@dialog
                Editor.ids = null
                FlacBatch.startEdit(host, ids, spec)
                showBatch(FlacBatch.state)
            } else {
                Editor.ids = null
                host.overlay.dismiss()
            }
        }, extra = extra)
        host.overlay.show(KEY_EDIT, body, dismissOnOutside = false)
        return true
    }

    // ---------- T79: обрізати / наростання / затухання ----------

    private fun openTrim(rows: List<RecordingRow>) {
        if (RecorderHub.state.phase != RecorderHub.Phase.IDLE) {
            host.toast("Спершу зупини запис")
            return
        }
        if (FlacBatch.state.running) {
            showBatch(FlacBatch.state)
            return
        }
        val list = rows.filter { it.hasFile && it.id !in missing }
        if (list.isEmpty()) {
            host.toast("Нема файлів на місці")
            return
        }
        Trim.ids = list.map { it.id }.toLongArray()
        Trim.values.clear()
        Trim.fadeIn = 0
        Trim.fadeOut = 0
        Trim.save = TrimFade.Save.REPLACE_KEEP
        showTrim()
    }

    /** «4,5» → 4500 мс; порожньо чи нісенітниця — 0. */
    private fun secToMs(s: String?): Long = s?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: 0L

    private fun trimPlan() = TrimFade.Plan(secToMs(Trim.values["start"]), secToMs(Trim.values["end"]), Trim.fadeIn, Trim.fadeOut, Trim.save)

    private fun showTrim(): Boolean {
        val rows = Trim.ids?.toList()?.mapNotNull { db.get(it) }.orEmpty()
        if (rows.isEmpty()) return false
        val one = rows.singleOrNull()
        val preview = ui.text("", 14f, Palette.text).apply { minLines = 3 }
        val saveNote = ui.muted("", 12.5f).apply { minLines = 2 }
        fun refresh() {
            val p = trimPlan()
            preview.text = when {
                p.isEmpty -> "Вкажи, скільки обрізати, або вибери наростання / затухання."
                one != null -> {
                    val left = one.lengthMs - p.cutStartMs - p.cutEndMs
                    if (left < TrimFade.MIN_LEFT_MS) "Забагато: лишиться менше секунди." else "Було ${TimeText.clock(one.lengthMs)} → стане ${TimeText.clock(left)}.\n" + TrimApply.describe(p).replaceFirstChar { it.uppercase() } + "."
                }
                else -> "Для кожного з ${rows.size}: " + TrimApply.describe(p) + "."
            }
            saveNote.text = when (Trim.save) {
                TrimFade.Save.REPLACE_KEEP -> "Файл на місці стане новим, а старий — копією в папці «Оригінали» (у «Track Catcher»). Захочеш повернути — він там."
                TrimFade.Save.NEW_FILE -> "Старий файл лишається, поруч з'явиться «… (обрізано)»; історія покаже новий."
                TrimFade.Save.REPLACE -> "Файл стане новим, копії не буде — повернути не можна."
            }
        }
        val fields = HashMap<String, EditText>()
        fun field(key: String, label: String): View = ui.column(4).apply {
            addView(ui.muted(label, 13f))
            addView(EditText(host).apply {
                setTextColor(Palette.text)
                setHintTextColor(Palette.faint)
                textSize = 15f
                hint = "0"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
                minHeight = ui.dp(Ui.MIN_TOUCH_DP)
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                id = View.generateViewId()
                setText(Trim.values[key].orEmpty())
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(e: Editable?) {
                        Trim.values[key] = e?.toString().orEmpty()
                        refresh()
                    }
                })
                fields[key] = this
            }, ui.matchWrap())
        }
        fun chips(options: List<Pair<String, Long>>, current: () -> Long, set: (Long) -> Unit): View {
            val box = FlowLayout(host, ui.dp(8), ui.dp(8))
            val views = HashMap<Long, android.widget.TextView>()
            options.forEach { (label, ms) ->
                views[ms] = ui.choice(label, current() == ms) {
                    set(ms)
                    views.forEach { (k, v) -> ui.styleChoice(v, k == ms) }
                    refresh()
                }.also { box.addView(it) }
            }
            return box
        }
        val fades = listOf("нема" to 0L, "0,5 с" to 500L, "1 с" to 1000L, "2 с" to 2000L, "3 с" to 3000L)
        val extra = ui.column(12).apply {
            addView(field("start", "Обрізати початок, секунд (напр. 4,5)"))
            addView(field("end", "Обрізати кінець, секунд"))
            // Один запис і він зараз грає — межу можна взяти з плеєра.
            val playing = one != null && InAppPlayer.uri != null && (InAppPlayer.uri == one.mp3Uri || InAppPlayer.uri == one.wavUri)
            if (playing && one != null) {
                val pos = InAppPlayer.positionMs
                val row = FlowLayout(host, ui.dp(8), ui.dp(8))
                row.addView(ui.button("Початок — звідси (${TimeText.clock(pos)})", Ui.Kind.NORMAL) {
                    fields["start"]?.setText(TrimApply.sec(InAppPlayer.positionMs).removeSuffix(" с"))
                })
                row.addView(ui.button("Кінець — звідси", Ui.Kind.NORMAL) {
                    fields["end"]?.setText(TrimApply.sec((one.lengthMs - InAppPlayer.positionMs).coerceAtLeast(0)).removeSuffix(" с"))
                })
                addView(row, ui.matchWrap())
            } else if (one != null) {
                addView(ui.muted("Підказка: увімкни пісню в плеєрі нижче, зупини там, де треба різати, і відкрий це вікно знову — з'являться кнопки «Початок — звідси» / «Кінець — звідси».", 12.5f))
            }
            addView(ui.label("Наростання на початку"))
            addView(chips(fades, { Trim.fadeIn }) { Trim.fadeIn = it }, ui.matchWrap())
            addView(ui.label("Затухання в кінці"))
            addView(chips(fades, { Trim.fadeOut }) { Trim.fadeOut = it }, ui.matchWrap())
            addView(ui.label("Як зберегти"))
            val saveBox = FlowLayout(host, ui.dp(8), ui.dp(8))
            val saveViews = HashMap<TrimFade.Save, android.widget.TextView>()
            TrimFade.Save.entries.forEach { st ->
                saveViews[st] = ui.choice(st.label, Trim.save == st) {
                    Trim.save = st
                    saveViews.forEach { (k, v) -> ui.styleChoice(v, k == st) }
                    refresh()
                }.also { saveBox.addView(it) }
            }
            addView(saveBox, ui.matchWrap())
            addView(saveNote)
            addView(ui.muted("FLAC і WAV — без втрат якості; MP3 кодується заново (на слух те саме). Теги й обкладинка лишаються.", 12.5f))
            addView(preview)
        }
        refresh()
        val title = if (one != null) "Обрізати / затухання" else "Обрізати / затухання — ${TimeText.plural(rows.size, "запис", "записи", "записів")}"
        val body = host.overlay.dialog(title, "", listOf("Обрізати" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET), { i ->
            if (i == 0) {
                val plan = trimPlan()
                if (plan.isEmpty) {
                    host.toast("Нічого не вибрано")
                    return@dialog
                }
                if (one != null && one.lengthMs - plan.cutStartMs - plan.cutEndMs < TrimFade.MIN_LEFT_MS) {
                    host.toast("Забагато — лишиться менше секунди")
                    return@dialog
                }
                val ids = Trim.ids ?: return@dialog
                Trim.ids = null
                FlacBatch.startTrim(host, ids, plan)
                showBatch(FlacBatch.state)
            } else {
                Trim.ids = null
                host.overlay.dismiss()
            }
        }, extra = extra)
        host.overlay.show(KEY_TRIM, body, dismissOnOutside = false)
        return true
    }

    /** T77: шукати перейменовані файли (у фоні), потім — вікно з тим, що знайдено. */
    private fun findRenamed() {
        if (FlacBatch.state.running) {
            showBatch(FlacBatch.state)
            return
        }
        val ids = missing.toList()
        if (RelinkScan.running) { showRelinkWaiting(); return }
        RelinkScan.running = true
        RelinkScan.last = null
        RelinkScan.lastError = null
        val app = host.applicationContext
        Thread({
            runCatching { RelinkScan.last = RelinkScan.scan(app, ids) }.onFailure { RelinkScan.lastError = ua.faric.trackcatcher.core.ErrorText.human(it) }
            RelinkScan.running = false
        }, "relink-scan").start()
        showRelinkWaiting()
    }

    /** «Шукаю…» і чекати кінця пошуку — так вікно переживає поворот (пошук іде окремо від екрана). */
    private fun showRelinkWaiting() {
        host.overlay.show(KEY_RELINK_RUN, ui.column(12).apply {
            addView(ui.title("Шукаю перейменовані файли", 19f))
            addView(ui.muted("Дивлюсь у папки, де лежали файли, яких нема на місці: нові файли тієї самої довжини (і з тією самою назвою в тегах). Файли не чіпаю.", 14f).apply { minLines = 3 })
        }, dismissOnOutside = false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                if (host.isFinishing || host.isDestroyed || host.overlay.key != KEY_RELINK_RUN) return
                if (RelinkScan.running) { handler.postDelayed(this, 300); return }
                RelinkScan.last?.let { showRelink(it) } ?: run {
                    host.overlay.dismiss()
                    host.toast("Не вдалося: ${RelinkScan.lastError ?: "невідома помилка"}")
                }
            }
        })
    }

    private fun showRelink(r: RelinkScan.Result) {
        val msg = buildString {
            if (r.matches.isEmpty()) {
                append("Не знайшов жодного: серед нових файлів у тих папках нема таких самих за довжиною. Мабуть, файли перенесли в іншу папку чи видалили.")
            } else {
                append("Знайдено: ${r.matches.size} з ${r.lost}.\n")
                r.matches.take(6).forEach { append("\n• ${it.lost.name}\n   → ${it.found.name}") }
                if (r.matches.size > 6) append("\n… і ще ${r.matches.size - 6}")
                val notFound = r.lost - r.matches.size
                if (notFound > 0) append("\n\nНе знайдено: $notFound — їх перенесли кудись інде чи видалили.")
                if (r.titles.isNotEmpty()) append("\n\nУ ${TimeText.plural(r.titles.size, "записі", "записах", "записах")} ім'я каже іншу назву, ніж історія — можна взяти назву з імені (тег «Назва» у файлі й історія; звук не міняється).")
            }
            if (r.strangers > 0) append("\n\nЩе нових файлів у цих папках: ${r.strangers} — їх нема в історії, не чіпаю.")
            append("\n\n«Зв'язати» лише записує в історію нові імена — файли не змінюються.")
        }
        val actions = buildList {
            if (r.matches.isNotEmpty()) {
                add("Зв'язати" to Ui.Kind.PRIMARY)
                if (r.titles.isNotEmpty()) add("Зв'язати й назви з імен (${r.titles.size})" to Ui.Kind.NORMAL)
            }
            add((if (r.matches.isEmpty()) "Закрити" else "Скасувати") to Ui.Kind.QUIET)
        }
        val body = host.overlay.dialog("Перейменовані файли", msg, actions, { i ->
            val label = actions[i].first
            when {
                label == "Зв'язати" || label.startsWith("Зв'язати й") -> {
                    val app = host.applicationContext
                    val withTitles = label.startsWith("Зв'язати й")
                    RelinkScan.last = null
                    host.overlay.dismiss()
                    Thread({
                        val done = runCatching { RelinkScan.apply(app, r) }.getOrDefault(emptyList())
                        host.runOnUiThread {
                            host.toast("Зв'язано: ${TimeText.plural(done.size, "запис", "записи", "записів")}")
                            if (withTitles) {
                                val t = r.titles.filterKeys { it in done }
                                if (t.isNotEmpty()) {
                                    FlacBatch.startRetitle(host, t.keys.toLongArray(), t.values.toTypedArray())
                                    showBatch(FlacBatch.state)
                                }
                            }
                        }
                    }, "relink-apply").start()
                }
                else -> {
                    RelinkScan.last = null
                    host.overlay.dismiss()
                }
            }
        })
        host.overlay.show(KEY_RELINK_DONE, body)
    }

    private var batchText: android.widget.TextView? = null
    /** T93: смужки поступу — пісня (обрізка, тріски, FLAC) і вся пачка. */
    private var batchFileBar: ProgressLine? = null
    private var batchFileText: android.widget.TextView? = null
    private var batchAllBar: ProgressLine? = null

    private fun filePercent(s: FlacBatch.State): Int = when (s.mode) {
        FlacBatch.Mode.TRIM, FlacBatch.Mode.HOLES -> s.filePercent
        FlacBatch.Mode.FLAC -> s.percent
        else -> -1
    }

    private fun allFraction(s: FlacBatch.State): Float {
        if (s.total <= 0) return 0f
        val inside = filePercent(s).coerceAtLeast(0) / 100f
        return ((s.index - 1).coerceAtLeast(0) + inside) / s.total
    }

    private fun renderBars(s: FlacBatch.State) {
        val fp = filePercent(s)
        batchFileBar?.let { it.fraction = if (fp < 0) 0f else fp / 100f; it.visibility = if (fp < 0) View.GONE else View.VISIBLE }
        batchFileText?.let { it.text = "Ця пісня: ${fp.coerceAtLeast(0)} %"; it.visibility = if (fp < 0) View.GONE else View.VISIBLE }
        batchAllBar?.fraction = allFraction(s)
    }

    /** Поступ або підсумок перетворення — з [FlacBatch.state], тож переживає поворот (faric 17.10 22:25). */
    private fun showBatch(s: FlacBatch.State) {
        if (s.running) {
            val text = ui.numbers(ui.muted(batchLine(s), 14.5f)).apply { minLines = 3 } // назва пісні в 1–3 рядки — вікно не стрибає
            batchText = text
            val box = ui.column(12).apply {
                addView(ui.title(when (s.mode) {
                    FlacBatch.Mode.RENUMBER -> "Перевіряю номери пісень"
                    FlacBatch.Mode.TITLES -> "Прибираю зайве з назв"
                    FlacBatch.Mode.RETITLE -> "Беру назви з імен файлів"
                    FlacBatch.Mode.EDIT -> "Змінюю теги й імена"
                    FlacBatch.Mode.TRIM -> "Обрізаю"
                    FlacBatch.Mode.HOLES -> "Шукаю тріски"
                    FlacBatch.Mode.FLAC -> "Роблю FLAC з обкладинками"
                }, 19f))
                addView(text)
                // T93 (faric 20.10 17:43): смужки — поступ пісні й усієї пачки (висота незмінна — вікно не стрибає).
                batchFileText = ui.numbers(ui.muted("", 13f)).also { addView(it) }
                batchFileBar = ProgressLine(host).also { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8))) }
                addView(ui.muted("Усього:", 13f))
                batchAllBar = ProgressLine(host).also { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8))) }
                renderBars(s)
                addView(ui.muted("Можна повертати екран і згортати застосунок — поступ і «Зупинити» є в шторці. Перервалось? Ту саму кнопку ще раз: зроблені пропускаються.", 13f))
                val row = FlowLayout(host, ui.dp(8), ui.dp(8), alignEnd = true)
                row.addView(ui.button("Зупинити", Ui.Kind.DANGER) { FlacBatch.stop(); host.toast("Зупиню після цієї пісні") })
                row.addView(ui.button("Сховати", Ui.Kind.QUIET) { batchText = null; host.overlay.dismiss() })
                addView(row, ui.matchWrap())
            }
            host.overlay.show(KEY_FLAC_RUN, box, dismissOnOutside = false)
        } else if (s.finished) {
            batchText = null
            val head = when {
                s.mode == FlacBatch.Mode.RENUMBER && s.cancelled -> "Зупинено: перевірено ${s.index} з ${s.total}, виправлено ${s.made}."
                s.mode == FlacBatch.Mode.TITLES && s.cancelled -> "Зупинено: назв виправлено ${s.made}."
                s.mode == FlacBatch.Mode.TRIM && s.cancelled -> "Зупинено: обрізано ${s.made}."
                s.mode == FlacBatch.Mode.HOLES -> (if (s.cancelled) "Зупинено. " else "") + "Перевірено: ${s.index}. " +
                    (if (s.report.isEmpty()) "Тріск не знайдено." else "Тріски в ${s.report.size} — їх варто перезаписати:\n" + s.report.joinToString("\n") { "• $it" }) +
                    "\n\nФайли не змінювались — лише перевірка." +
                    (if (s.report.isEmpty()) "" else "\nTrack Catcher пише звук таким, яким він приходить: дірки були вже в тому, що грав плеєр (інтернет, сервер, сам плеєр).")
                s.mode == FlacBatch.Mode.TRIM -> "Обрізано: ${s.made} з ${s.total}. Теги й обкладинка лишились; що зроблено — у подробицях запису."
                s.mode == FlacBatch.Mode.EDIT && s.cancelled -> "Зупинено: змінено ${s.made}."
                s.mode == FlacBatch.Mode.EDIT -> "Змінено: ${s.made} з ${s.total} (теги FLAC / MP3, імена файлів, історія; звук той самий)."
                s.mode == FlacBatch.Mode.RETITLE -> "Назви з імен файлів: ${s.made} з ${s.total} (тег «Назва» у FLAC / MP3 і історія; звук той самий)."
                s.mode == FlacBatch.Mode.TITLES -> "Назви виправлено: ${s.made} з ${s.total} (у тегах, іменах файлів і в історії; звук той самий)."
                s.mode == FlacBatch.Mode.RENUMBER -> "Перевірено файлів: ${s.total}. " +
                    (if (s.made == 0) "Усі номери правильні." else "Виправлено: ${s.made} (лише теги, звук той самий).")
                s.cancelled -> "Зупинено: зроблено ${s.made} з ${s.total}. WAV лишились на місці."
                else -> "Готово: " + TimeText.plural(s.made, "FLAC", "FLAC", "FLAC") + " з обкладинками й тегами. WAV лишились на місці."
            }
            val skippedText = if (s.skipped > 0) "\n\nПропущено: ${s.skipped} — файлів нема там, де їх пам'ятає історія (перенесли чи видалили). Їх не рахував і не чіпав." else ""
            val msg = head + skippedText + if (s.problems.isEmpty()) "" else "\n\nНе вийшло (${s.problems.size}):\n" + problemsText(s.problems)
            val head2 = when (s.mode) {
                FlacBatch.Mode.RENUMBER -> "Номери пісень"
                FlacBatch.Mode.TITLES, FlacBatch.Mode.RETITLE -> "Назви пісень"
                FlacBatch.Mode.EDIT -> "Теги й імена"
                FlacBatch.Mode.TRIM -> "Обрізати / затухання"
                FlacBatch.Mode.HOLES -> "Тріски"
                FlacBatch.Mode.FLAC -> "FLAC з обкладинками"
            }
            // faric 18.10 15:26: «додай кнопку копіювати текст — тикну й тобі відправлю».
            val actions = if (s.problems.isEmpty() && s.report.isEmpty()) listOf("Закрити" to Ui.Kind.QUIET) else listOf("Копіювати" to Ui.Kind.NORMAL, "Закрити" to Ui.Kind.QUIET)
            val body = host.overlay.dialog(head2, msg, actions, { i ->
                if (actions[i].first == "Копіювати") {
                    val cm = host.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText(head2, "$head2\n$msg"))
                    host.toast("Скопійовано — встав у чат")
                } else {
                    FlacBatch.acknowledge()
                    host.overlay.dismiss()
                }
            })
            host.overlay.show(KEY_FLAC_DONE, body)
        }
    }

    /** T88: перевірити на тріски — лише читає файли, тож без вікна «Зробити?». */
    private fun checkHoles(rows: List<RecordingRow>) {
        if (FlacBatch.state.running) { host.toast("Зачекай — ще йде попередня робота"); return }
        FlacBatch.startHoles(host, rows.map { it.id }.toLongArray())
        showBatch(FlacBatch.state)
    }

    /** «файл\tпричина» → згруповано за причиною: причина один раз, під нею — файли (faric 18.10 15:26: «дуже багато всього»). */
    private fun problemsText(problems: List<String>): String =
        problems.groupBy({ it.substringAfter('\t', "") }, { it.substringBefore('\t') }).entries.joinToString("\n\n") { (why, names) ->
            (if (why.isBlank()) "" else "$why (${names.size}):\n") + names.joinToString("\n") { "• $it" }
        }

    private fun batchLine(s: FlacBatch.State) = when {
        s.index == 0 -> "Готуюсь…" + if (s.total > 0) " (${s.total})" else ""
        s.mode == FlacBatch.Mode.RENUMBER || s.mode == FlacBatch.Mode.TITLES || s.mode == FlacBatch.Mode.RETITLE || s.mode == FlacBatch.Mode.EDIT || s.mode == FlacBatch.Mode.TRIM -> "${s.index} з ${s.total}: ${s.label} · виправлено ${s.made}"
        s.mode == FlacBatch.Mode.HOLES -> "${s.index} з ${s.total}: ${s.label} · з трісками ${s.made}"
        else -> "${s.index} з ${s.total}: ${s.label} · ${s.percent} %"
    }

    private fun onBatch(s: FlacBatch.State) {
        when {
            s.running -> if (host.overlay.key == KEY_FLAC_RUN) {
                batchText?.text = batchLine(s)
                renderBars(s)
            }
            s.finished && !s.worthShowing -> FlacBatch.acknowledge() // сам після запису й усе гаразд — мовчки
            s.finished && (host.overlay.key == null || host.overlay.key == KEY_FLAC_RUN) -> showBatch(s)
        }
    }

    private fun confirmDelete(r: RecordingRow) {
        val files = listOfNotNull(r.mp3Name, r.wavName).joinToString("\n")
        val body = host.overlay.dialog(
            title = "Видалити файл?",
            message = "$files\n\nФайл зникне з «Музики». Запис в історії лишиться з поміткою «файл видалено».",
            actions = listOf("Видалити" to Ui.Kind.DANGER, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) delete(r) else host.overlay.dismiss()
            },
        )
        host.overlay.show("delete:${r.id}", body)
    }

    private fun delete(r: RecordingRow) {
        if (InAppPlayer.uri != null && (InAppPlayer.uri == r.mp3Uri || InAppPlayer.uri == r.wavUri)) InAppPlayer.stop()
        val store = MediaStoreWriter(host)
        val mp3 = r.mp3Uri?.let { store.delete(it) } ?: false
        val wav = r.wavUri?.let { store.delete(it) } ?: false
        host.overlay.dismiss()
        if ((r.mp3Uri != null && !mp3) || (r.wavUri != null && !wav)) {
            host.toast("Не вдалося видалити. Можливо, файл уже прибрали в «Моїх файлах».")
        }
        if (mp3 || wav) db.markFileDeleted(r.id, mp3, wav)
    }

    private fun appLabel(pkg: String): String = try {
        host.packageManager.getApplicationLabel(host.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        private const val KEY_FILTER = "filter"
        private const val KEY_QUERY = "query"
        private const val KEY_SELECTED = "selected"
        private const val KEY_TOGGLED = "toggled"

        /** Останній прочитаний «Про файл»: після повороту вікно одразу на місці. */
        private class FileInfoCache(val uri: String, val info: FileTags.FileInfo, val size: Long, val folder: String?, val at: Long = System.currentTimeMillis())

        @Volatile private var fileInfoCache: FileInfoCache? = null
        private const val KEY_LISTEN_WAV = "listen_wav"
        private const val KEY_GROUP = "group"
        private const val KEY_PICKED = "picked"
        private const val KEY_DELETE_MANY = "deletemany:0"
        private const val KEY_NO_CONVERTER = "noconverter"
        private const val KEY_FLAC_CONFIRM = "makeflac:confirm"
        private const val KEY_FLAC_RUN = "makeflac:run"
        private const val KEY_FLAC_DONE = "makeflac:done"
        private const val KEY_RELINK_RUN = "relink:run"
        private const val KEY_EDIT = "metaedit"
        private const val KEY_TRIM = "trim"

        /** Відкрите вікно «Обрізати / затухання» (T79) — переживає поворот. */
        private object Trim {
            var ids: LongArray? = null
            val values = HashMap<String, String>()
            var fadeIn = 0L
            var fadeOut = 0L
            var save = TrimFade.Save.REPLACE_KEEP
        }

        /** Відкритий редактор тегів (T78) — переживає поворот. */
        private object Editor {
            var ids: LongArray? = null
            var single: MetaEdit.Song? = null
            val values = HashMap<String, String>()
            var name = MetaEdit.NameStyle.KEEP
        }
        private const val KEY_RELINK_DONE = "relink:done"

        /** Записи, чиїх файлів нема на місці (перевіряється у фоні, на весь процес — переживає поворот). */
        @Volatile private var missing: Set<Long> = emptySet()
        @Volatile private var missingBusy = false

        /** Audio Converter (`faric-ua/faric-audio-converter`): звичайна збірка і збірка для перевірки (.dev). */
        private val CONVERTER_PACKAGES = listOf("ua.faric.audioconverter", "ua.faric.audioconverter.dev")
    }
}
