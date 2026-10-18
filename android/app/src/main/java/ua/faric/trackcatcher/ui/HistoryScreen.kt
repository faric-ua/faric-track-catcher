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
import ua.faric.trackcatcher.capture.Renumber
import ua.faric.trackcatcher.capture.MediaStoreWriter
import ua.faric.trackcatcher.core.FileTags
import ua.faric.trackcatcher.core.Lossless
import ua.faric.trackcatcher.core.Playlist
import ua.faric.trackcatcher.core.RecStatus
import ua.faric.trackcatcher.core.TimeText
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
    private val thumbs = LruCache<String, Bitmap>(60)
    private var rows: List<RecordingRow> = emptyList()

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
            addView(ui.scroll(list, 16), ui.weight(1f, vertical = true))
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
        unobserve = HistoryDb.observe { reload() }
        unobserveBatch = FlacBatch.observe { onBatch(it) }
        InAppPlayer.onTrackChanged = follower
        reload()
        // Поки застосунок був схований, плеєр міг перейти далі (шторка, «дограла»).
        InAppPlayer.current?.let { follow(it) }
    }

    override fun onStop() {
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
                if (FlacBatch.pendingMode == FlacBatch.Mode.RENUMBER) confirmRenumber(rows) else confirmMakeFlac(rows)
                return true
            }
            KEY_FLAC_RUN, KEY_FLAC_DONE -> {
                val s = FlacBatch.state
                if (!s.running && !s.finished) return false
                showBatch(s)
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

    private fun togglePick(r: RecordingRow) {
        if (!picked.remove(r.id)) picked += r.id
        reload()
        host.refreshBack()
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
        actions.addView(ui.button("Видалити (${picked.size})", Ui.Kind.DANGER) { confirmDeleteMany() })
        if (rows.any { it.id !in picked }) actions.addView(ui.button("Вибрати всі видимі", Ui.Kind.NORMAL) {
            rows.forEach { picked += it.id }
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

    private fun reload() {
        val found = db.list(filter, query)
        // У групах — альбом (виконавець) разом; найсвіжіший угорі, усередині — новіші згори, як завжди.
        rows = if (group == Group.SESSIONS) found else Playlist.groupInOrder(found) { groupKeyOf(it) }.flatMap { it.second }
        renderGroupChips()
        renderChips(db.counts())
        renderPickBar()
        list.removeAllViews()
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
            if (group == Group.ALBUMS && picked.isEmpty() && query.isBlank()) {
                val taggable = db.list(null, "", limit = Int.MAX_VALUE).filter { Renumber.files(it).isNotEmpty() }
                if (taggable.isNotEmpty()) list.addView(ui.card(8).apply {
                    addView(ui.label("Номери пісень", LineIcon.LIST))
                    addView(ui.muted("TRACK 10 — 10, BONUS 03 — останній TRACK + 3. Після кожного запису Track Catcher перевіряє сам; для старих альбомів — тут.", 13.5f))
                    addView(ui.iconButton(LineIcon.LIST, "Перевірити номери в усіх альбомах", Ui.Kind.NORMAL) { confirmRenumber(taggable) }, ui.matchWrap())
                }, ui.matchWrap())
            }
            if (group == Group.SESSIONS) {
                val sessions = db.sessions()
                var currentSession = -1L
                for (r in rows) {
                    if (r.sessionId != currentSession) {
                        currentSession = r.sessionId
                        list.addView(sessionHeader(sessions[r.sessionId], rows.filter { it.sessionId == r.sessionId }), ui.matchWrap())
                    }
                    list.addView(rowView(r), ui.matchWrap())
                }
            } else {
                for ((_, members) in Playlist.groupInOrder(rows) { groupKeyOf(it) }) {
                    list.addView(groupHeader(members), ui.matchWrap())
                    for (r in members) list.addView(rowView(r), ui.matchWrap())
                }
            }
        }
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

    /** Шапка альбому / виконавця: назва, скільки пісень і часу, «Слухати» й «Поділитись» (T61). */
    private fun groupHeader(members: List<RecordingRow>): View {
        val first = members.first()
        val name = groupName(first).ifBlank { if (group == Group.ALBUMS) "Без альбому" else "Невідомий виконавець" }
        val withFiles = members.filter { it.hasFile }
        val box = ui.card(8)
        box.addView(ui.label(name, if (group == Group.ALBUMS) LineIcon.DISC else LineIcon.LIST))
        box.addView(ui.numbers(ui.muted(buildString {
            append(TimeText.plural(members.size, "запис", "записи", "записів"))
            if (withFiles.isNotEmpty()) {
                append(" · ").append(TimeText.plural(withFiles.size, "файл", "файли", "файлів"))
                append(" · ").append(TimeText.clock(withFiles.sumOf { it.lengthMs }))
            }
        }, 13.5f)))
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
            box.addView(actions, ui.matchWrap())
        }
        return box
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

    private fun sessionHeader(s: SessionRow?, members: List<RecordingRow>): View {
        val count = members.size
        val text = if (s == null) {
            "Сесія"
        } else {
            buildString {
                append(TimeText.dateTime(s.startedAt))
                if (s.stoppedAt > 0) append("–").append(TimeText.time(s.stoppedAt))
                append(" · ").append(TimeText.plural(count, "запис", "записи", "записів"))
                if (s.formatLabel.isNotBlank()) append(" · ").append(s.formatLabel)
            }
        }
        val label = ui.numbers(ui.label(text)).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0) }
        // T69 (faric 17.10 21:45: «по одній — бажання нема»): уся сесія одним натиском.
        val wavs = members.filter { isWav(it) }
        if (wavs.isEmpty() || picked.isNotEmpty()) return label
        return ui.column(6).apply {
            addView(label)
            addView(ui.iconButton(LineIcon.DISC, "Сесію в FLAC з обкладинками (${wavs.size})", Ui.Kind.NORMAL) { confirmMakeFlac(wavs) })
        }
    }

    private fun rowView(r: RecordingRow): View {
        val picking = picked.isNotEmpty()
        val selected = if (picking) r.id in picked else detailPane != null && r.id == selectedId
        val box = ui.row(12).apply {
            gravity = Gravity.TOP
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            background = ui.pressable(
                if (selected) ui.rounded(Palette.selectedFill, Palette.success, radiusDp = 14, strokeDp = 2)
                else ui.rounded(Palette.surface, Palette.border, radiusDp = 14),
            )
            isClickable = true
            isFocusable = true
            contentDescription = r.displayName + ", " + r.status.label + if (selected) Ui.SELECTED_SUFFIX else ""
            setOnClickListener {
                when {
                    picking -> togglePick(r)
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
        box.addView(thumb(r, 48), LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        val texts = ui.column(3)
        texts.addView(ui.text(r.displayName, 15.5f, medium = true).apply {
            // T44: довга назва — 3 рядки і «…» в кінці, а не мовчки обрізана посеред.
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(ui.numbers(ui.muted(metaLine(r), 13f)))
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
        if (r.sourcePackage.isNotBlank()) facts.addView(ui.fact("Плеєр", appLabel(r.sourcePackage)))
        facts.addView(ui.fact("Коли", TimeText.dateTime(r.createdAt)))
        if (r.id > 0) facts.addView(ui.fact("Запис №", r.id.toString())) // T18: свій номер, навіть коли назви однакові
        box.addView(facts, ui.matchWrap())

        // T68 / T69: що насправді у файлі; старий WAV — FLAC з обкладинкою з історії.
        if (r.hasFile) {
            val more = FlowLayout(host, ui.dp(8), ui.dp(8))
            more.addView(ui.iconButton(LineIcon.FILE, "Про файл", Ui.Kind.NORMAL) { openFileInfo(r) })
            if (isWav(r)) more.addView(ui.iconButton(LineIcon.REPEAT, "Зробити FLAC з обкладинкою", Ui.Kind.NORMAL) { confirmMakeFlac(listOf(r)) })
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
        Thread({
            val result = runCatching {
                val size = store.sizeOf(uri)
                app.contentResolver.openInputStream(Uri.parse(uri))?.buffered(64 * 1024)?.use { FileTags.read(it, size, name) to size }
                    ?: throw java.io.IOException("файл не відкривається — можливо, його прибрали в «Моїх файлах»")
            }
            val folder = store.folderLabel(uri)
            host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                result.fold(
                    { (info, size) ->
                        val extra = FileInfoPanel.Extra(
                            recorded = recordedFacts(r),
                            historyArtwork = r.artworkPath,
                            clips = r.clips,
                            makeFlac = if (isWav(r) && uri == r.wavUri) ({ confirmMakeFlac(listOf(r)) }) else null,
                        )
                        val view = FileInfoPanel(host).build(info, name, folder, size, extra) { closeFileInfo(r) }
                        host.overlay.show("fileinfo:${r.id}", view)
                    },
                    { e -> host.toast("Не вдалося прочитати файл: ${e.message ?: e.javaClass.simpleName}") },
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

    private var batchText: android.widget.TextView? = null

    /** Поступ або підсумок перетворення — з [FlacBatch.state], тож переживає поворот (faric 17.10 22:25). */
    private fun showBatch(s: FlacBatch.State) {
        if (s.running) {
            val text = ui.numbers(ui.muted(batchLine(s), 14.5f))
            batchText = text
            val box = ui.column(12).apply {
                addView(ui.title(if (s.mode == FlacBatch.Mode.RENUMBER) "Перевіряю номери пісень" else "Роблю FLAC з обкладинками", 19f))
                addView(text)
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
                s.mode == FlacBatch.Mode.RENUMBER -> "Перевірено файлів: ${s.total}. " +
                    (if (s.made == 0) "Усі номери правильні." else "Виправлено: ${s.made} (лише теги, звук той самий).")
                s.cancelled -> "Зупинено: зроблено ${s.made} з ${s.total}. WAV лишились на місці."
                else -> "Готово: " + TimeText.plural(s.made, "FLAC", "FLAC", "FLAC") + " з обкладинками й тегами. WAV лишились на місці."
            }
            val msg = head +
                if (s.problems.isEmpty()) "" else "\n\nНе вийшло (${s.problems.size}):\n" + s.problems.joinToString("\n")
            val body = host.overlay.dialog(if (s.mode == FlacBatch.Mode.RENUMBER) "Номери пісень" else "FLAC з обкладинками", msg, listOf("Закрити" to Ui.Kind.QUIET), {
                FlacBatch.acknowledge()
                host.overlay.dismiss()
            })
            host.overlay.show(KEY_FLAC_DONE, body)
        }
    }

    private fun batchLine(s: FlacBatch.State) = when {
        s.index == 0 -> "Готуюсь…" + if (s.total > 0) " (${s.total})" else ""
        s.mode == FlacBatch.Mode.RENUMBER -> "${s.index} з ${s.total}: ${s.label} · виправлено ${s.made}"
        else -> "${s.index} з ${s.total}: ${s.label} · ${s.percent} %"
    }

    private fun onBatch(s: FlacBatch.State) {
        when {
            s.running -> batchText?.takeIf { host.overlay.key == KEY_FLAC_RUN }?.text = batchLine(s)
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
        private const val KEY_LISTEN_WAV = "listen_wav"
        private const val KEY_GROUP = "group"
        private const val KEY_PICKED = "picked"
        private const val KEY_DELETE_MANY = "deletemany:0"
        private const val KEY_NO_CONVERTER = "noconverter"
        private const val KEY_FLAC_CONFIRM = "makeflac:confirm"
        private const val KEY_FLAC_RUN = "makeflac:run"
        private const val KEY_FLAC_DONE = "makeflac:done"

        /** Audio Converter (`faric-ua/faric-audio-converter`): звичайна збірка і збірка для перевірки (.dev). */
        private val CONVERTER_PACKAGES = listOf("ua.faric.audioconverter", "ua.faric.audioconverter.dev")
    }
}
