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
import ua.faric.trackcatcher.capture.MediaStoreWriter
import ua.faric.trackcatcher.core.Lossless
import ua.faric.trackcatcher.core.Playlist
import ua.faric.trackcatcher.core.RecStatus
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow
import ua.faric.trackcatcher.data.SessionRow
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
        InAppPlayer.onTrackChanged = follower
        reload()
        // Поки застосунок був схований, плеєр міг перейти далі (шторка, «дограла»).
        InAppPlayer.current?.let { follow(it) }
    }

    override fun onStop() {
        unobserve?.invoke()
        unobserve = null
        if (InAppPlayer.onTrackChanged === follower) InAppPlayer.onTrackChanged = null
    }

    override fun save(out: Bundle) {
        filter?.let { out.putString(KEY_FILTER, it.code) }
        out.putString(KEY_QUERY, query)
        out.putLong(KEY_SELECTED, selectedId)
        out.putBoolean(KEY_LISTEN_WAV, listenWav)
        out.putLongArray(KEY_PICKED, picked.toLongArray())
    }

    override val wantsBack: Boolean get() = picked.isNotEmpty()

    override fun handleBack() = clearPicked()

    override fun restoreDialog(key: String): Boolean {
        if (key == KEY_DELETE_MANY) {
            if (picked.isEmpty()) return false
            confirmDeleteMany()
            return true
        }
        val parts = key.split(":")
        val id = parts.getOrNull(1)?.toLongOrNull() ?: return false
        val row = db.get(id) ?: return false
        return when (parts[0]) {
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
        rows = db.list(filter, query)
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
            val sessions = db.sessions()
            var currentSession = -1L
            for (r in rows) {
                if (r.sessionId != currentSession) {
                    currentSession = r.sessionId
                    list.addView(sessionHeader(sessions[r.sessionId], rows.count { it.sessionId == r.sessionId }), ui.matchWrap())
                }
                list.addView(rowView(r), ui.matchWrap())
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

    private fun sessionHeader(s: SessionRow?, count: Int): View {
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
        return ui.numbers(ui.label(text)).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0) }
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
        val id = Playlist.neighbor(rows.map { it.id }, { id -> rows.firstOrNull { it.id == id }?.hasFile == true }, r.id, next)
        return id?.let { found -> rows.firstOrNull { it.id == found } }
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
        InAppPlayer.play(host, t, rows.filter { it.hasFile }.mapNotNull { track(it) })
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
        private const val KEY_PICKED = "picked"
        private const val KEY_DELETE_MANY = "deletemany:0"
    }
}
