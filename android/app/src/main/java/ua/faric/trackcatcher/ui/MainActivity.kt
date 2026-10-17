package ua.faric.trackcatcher.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.net.Uri
import ua.faric.trackcatcher.data.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import ua.faric.trackcatcher.BuildConfig
import ua.faric.trackcatcher.capture.CaptureService
import ua.faric.trackcatcher.capture.NowPlayingTracker
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.TrackMeta
import ua.faric.trackcatcher.state.RecorderHub

/**
 * Одне вікно, три вкладки. Книжкова: шапка + вкладки знизу. Альбомна (ширина ≥ 600 dp):
 * вкладки збоку, екрани у дві колонки. Поворот перестворює вікно, але стан
 * (вкладка, фільтр, пошук, вибраний запис, відкрите вікно) відновлюється, а запис іде у фоні.
 */
class MainActivity : Activity() {
    /** Вкладки; значки — ті самі, що в інструкції (T58). */
    enum class Tab(val label: String, val icon: LineIcon) {
        RECORD("Запис", LineIcon.REC),
        HISTORY("Історія", LineIcon.LIST),
        SETTINGS("Налаштування", LineIcon.SLIDERS),
    }

    lateinit var ui: Ui
        private set
    lateinit var overlay: Overlay
        private set
    lateinit var tracker: NowPlayingTracker
        private set

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var statusChip: TextView
    private val tabButtons = LinkedHashMap<Tab, TextView>()
    private var tab = Tab.RECORD
    private var screen: Screen? = null
    private val screenStates = Bundle()
    private var started = false
    private var unobserveHub: (() -> Unit)? = null
    private var backCallback: Any? = null
    private var backRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private var night: NightScreen? = null
    private val tick = object : Runnable {
        override fun run() {
            renderChip(RecorderHub.state)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Palette.transparent
            window.navigationBarColor = Palette.transparent
        }
        tracker = NowPlayingTracker(this, object : NowPlayingTracker.Listener {
            override fun onTrack(meta: TrackMeta, positionMs: Long?, restart: Boolean) {
                screen?.onNowPlaying()
            }

            override fun onDetails(meta: TrackMeta) {
                screen?.onNowPlaying()
            }

            override fun onPlaying(playing: Boolean) {
                screen?.onNowPlaying()
            }
        })
        savedInstanceState?.let { s ->
            tab = s.getString(KEY_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } } ?: Tab.RECORD
            s.getBundle(KEY_SCREENS)?.let { screenStates.putAll(it) }
        }
        buildChrome()
        showTab(tab)
        savedInstanceState?.getString(KEY_DIALOG)?.let { key -> restoreDialog(key) }
        if (savedInstanceState == null) applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    /** Відкрити потрібну вкладку (і демо-стан для знімків екрана в CI — лише debug). */
    private fun applyIntent(i: Intent?) {
        if (i == null) return
        DemoData.apply(this, i)
        i.getStringExtra(EXTRA_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } }?.let { if (it != tab) showTab(it) }
        if (i.getBooleanExtra(EXTRA_START, false)) {
            i.removeExtra(EXTRA_START) // лише один раз: поворот чи повернення з «Недавніх» не почне запис знову
            startCapture()
        }
        if (BuildConfig.DEBUG) {
            i.getStringExtra("dialog")?.let { restoreDialog(it) }
        }
    }

    /** Вікно після повороту: нічний екран, оновлення (будь-яка вкладка) або вікно самого екрана. */
    private fun restoreDialog(key: String) {
        when {
            key == NightScreen.KEY -> showNightScreen()
            key.startsWith("update:") -> UpdateUi(this).restore(key)
            else -> screen?.restoreDialog(key)
        }
    }

    // ---------- каркас ----------

    private fun buildChrome() {
        root = FrameLayout(this).apply { setBackgroundColor(Palette.bg) }
        content = FrameLayout(this)
        statusChip = ui.numbers(ui.text("", 13f, Palette.rec, medium = true)).apply {
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(7))
            background = ui.pressable(ui.rounded(Palette.recFill, Palette.rec, radiusDp = 20), 20)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { showTab(Tab.RECORD) }
        }
        tabButtons.clear()
        val frame: LinearLayout
        if (ui.twoPane) {
            frame = ui.row()
            frame.gravity = Gravity.TOP
            val rail = ui.column(8).apply {
                setPadding(ui.dp(12), ui.dp(16), ui.dp(8), ui.dp(16))
                gravity = Gravity.CENTER_HORIZONTAL
            }
            rail.addView(brand(vertical = true), ui.matchWrap())
            for (t in Tab.entries) {
                val b = ui.choice(t.label, t == tab) { showTab(t) }.apply {
                    textSize = 13f
                    oneLineLabel(this)
                    setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
                }
                tabLabel(b, t, t == tab)
                tabButtons[t] = b
                rail.addView(b, ui.matchWrap())
            }
            rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            rail.addView(statusChip, ui.matchWrap())
            // Великий шрифт: смуга прокручується, а не обрізається.
            val railScroll = android.widget.ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(rail, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            frame.addView(railScroll, LinearLayout.LayoutParams(ui.dp(152), ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(ui.divider().apply { layoutParams = LinearLayout.LayoutParams(maxOf(1, ui.dp(1) / 2), ViewGroup.LayoutParams.MATCH_PARENT) })
            frame.addView(content, ui.weight(1f))
        } else {
            frame = ui.column()
            val header = ui.row(10).apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(4)) }
            header.addView(brand(vertical = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(statusChip)
            frame.addView(header, ui.matchWrap())
            frame.addView(content, ui.weight(1f, vertical = true))
            val bar = ui.row(8).apply {
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(10))
                setBackgroundColor(Palette.surface)
            }
            for (t in Tab.entries) {
                val b = ui.choice(t.label, t == tab) { showTab(t) }.apply {
                    textSize = 12.5f
                    oneLineLabel(this)
                    setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
                }
                tabLabel(b, t, t == tab)
                tabButtons[t] = b
                bar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            frame.addView(bar, ui.matchWrap())
        }
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = Overlay(root, ui) { key ->
            if (key != NightScreen.KEY) night?.let { it.close(); night = null }
            updateBackCallback()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            frame.setPadding(b.left, b.top, b.right, b.bottom)
            overlay.setSystemBars(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
    }

    /**
     * Вкладка: значок + підпис в ОДИН рядок; не влазить — «Налашту…» (власник, 13.10 20:11).
     * Повна назва лишається для озвучування.
     */
    /** Значок над назвою вкладки; вибрана — світліший значок і зелена рамка. */
    private fun tabLabel(b: TextView, t: Tab, selected: Boolean) {
        b.text = ui.iconText(t.icon, t.label, if (selected) Palette.text else Palette.muted, sizeDp = LineIcon.TAB_DP, above = true)
        ui.styleChoice(b, selected)
    }

    private fun oneLineLabel(tv: TextView) {
        tv.maxLines = 2
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        tv.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    }

    private fun brand(vertical: Boolean): View {
        val box = if (vertical) ui.column(6).apply { gravity = Gravity.CENTER_HORIZONTAL } else ui.row(8)
        val dot = View(this).apply { background = ui.rounded(Palette.rec, radiusDp = 6) }
        box.addView(dot, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)))
        box.addView(ui.text(if (vertical) "Track\nCatcher" else "Track Catcher", if (vertical) 15f else 17f, Palette.text, medium = true).apply {
            if (vertical) gravity = Gravity.CENTER
        })
        box.setPadding(0, 0, 0, if (vertical) ui.dp(10) else 0)
        return box
    }

    fun showTab(t: Tab) {
        screen?.let { s ->
            val b = Bundle()
            s.save(b)
            screenStates.putBundle(tab.name, b)
            if (started) s.onStop()
        }
        if (::overlay.isInitialized && overlay.isShowing) overlay.dismiss()
        tab = t
        val state = screenStates.getBundle(t.name)
        val s = when (t) {
            Tab.RECORD -> RecordScreen(this, state)
            Tab.HISTORY -> HistoryScreen(this, state)
            Tab.SETTINGS -> SettingsScreen(this, state)
        }
        screen = s
        content.removeAllViews()
        content.addView(s.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabButtons.forEach { (k, v) -> tabLabel(v, k, k == t) }
        renderChip(RecorderHub.state)
        if (started) {
            s.onStart()
            s.onResume()
        }
        if (::overlay.isInitialized) updateBackCallback()
    }

    /** Чорний екран на мінімальній яскравості: телефон не блокується сам — Android не зупинить запис. */
    fun showNightScreen() {
        if (RecorderHub.state.phase != RecorderHub.Phase.RECORDING) return
        val n = NightScreen(this) { overlay.dismiss() }
        night = n
        overlay.showFullScreen(NightScreen.KEY, n.view)
        n.open()
    }

    private fun renderChip(s: RecorderHub.State) {
        if (s.phase != RecorderHub.Phase.RECORDING && night != null && overlay.key == NightScreen.KEY) overlay.dismiss()
        val text = when (s.phase) {
            RecorderHub.Phase.RECORDING -> "● " + TimeText.clock(System.currentTimeMillis() - s.startedAt) + " · " + s.tracks.coerceAtLeast(1)
            RecorderHub.Phase.FINISHING -> "Зберігаю…"
            RecorderHub.Phase.IDLE -> null
        }
        // На вкладці «Запис» стан і так видно великим — значок лише на інших вкладках.
        statusChip.visibility = if (text != null && tab != Tab.RECORD) View.VISIBLE else View.GONE
        if (text != null) {
            statusChip.text = text
            statusChip.contentDescription = if (s.phase == RecorderHub.Phase.RECORDING) "Іде запис. Відкрити вкладку «Запис»" else "Зберігаю записи"
        }
    }

    // ---------- життєвий цикл ----------

    override fun onStart() {
        super.onStart()
        started = true
        tracker.start()
        screen?.onStart()
        unobserveHub = RecorderHub.observe { renderChip(it) }
        handler.post(tick)
        Updater.attach(this)
        UpdateUi(this).autoCheck()
    }

    override fun onResume() {
        super.onResume()
        tracker.start()
        tracker.refresh()
        screen?.onResume()
        if (overlay.key == NightScreen.KEY) night?.open()
        if (RecorderHub.state.phase == RecorderHub.Phase.IDLE && CaptureService.hasOrphans(this)) {
            runCatching { startService(CaptureService.recoverIntent(this)) }
        }
        UpdateUi(this).resumePendingInstall()
    }

    override fun onStop() {
        started = false
        if (overlay.key == NightScreen.KEY) night?.pause()
        handler.removeCallbacks(tick)
        unobserveHub?.invoke()
        unobserveHub = null
        screen?.onStop()
        tracker.stop()
        Updater.detach(this)
        // v0.1.18: сховали застосунок — плеєр грає далі, керування — у шторці (PlaybackService).
        // (До v0.1.18 тут ставилась пауза: шторки не було, і звук без вікна був би «нічиїм».)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        screen?.let { s ->
            val b = Bundle()
            s.save(b)
            screenStates.putBundle(tab.name, b)
        }
        outState.putString(KEY_TAB, tab.name)
        outState.putBundle(KEY_SCREENS, screenStates)
        overlay.key?.let { outState.putString(KEY_DIALOG, it) }
    }

    // ---------- «Назад» ----------

    /** Екран змінив, чи хоче він «Назад» (режим вибору в історії). */
    fun refreshBack() = updateBackCallback()

    private fun backWanted(): Boolean = overlay.isShowing || screen?.wantsBack == true

    private fun onBackAction() {
        if (overlay.isShowing) overlay.dismiss() else screen?.handleBack()
        updateBackCallback()
    }

    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = (backCallback as? OnBackInvokedCallback) ?: OnBackInvokedCallback { onBackAction() }.also { backCallback = it }
        val want = backWanted()
        if (want && !backRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backRegistered = true
        } else if (!want && backRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            backRegistered = false
        }
    }

    @Deprecated("Для Android 10–12; з 13 — OnBackInvokedCallback")
    override fun onBackPressed() {
        if (backWanted()) {
            onBackAction()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------- дозволи й запис ----------

    fun requestRuntimePermissions(thenStart: Boolean = false) {
        val missing = Perms.runtimeToRequest(this)
        if (missing.isEmpty()) {
            screen?.onResume()
            if (thenStart) startCapture()
            return
        }
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("asked_runtime", false)
        val blocked = askedBefore && missing.none { shouldShowRequestPermissionRationale(it) }
        if (blocked && missing.contains(Manifest.permission.RECORD_AUDIO)) {
            toast("Android більше не питає. Увімкни «Мікрофон» у налаштуваннях Track Catcher → Дозволи.")
            PermissionsCard.openAppInfo(this)
            return
        }
        prefs.edit().putBoolean("asked_runtime", true).apply()
        requestPermissions(missing, if (thenStart) REQ_PERMS_THEN_START else REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        screen?.onResume()
        if (requestCode == REQ_PERMS_THEN_START && Perms.recordAudio(this)) startCapture()
    }

    fun startCapture() {
        InAppPlayer.stop() // власне прослуховування не повинно ставити на паузу плеєр, який записуємо
        if (RecorderHub.state.phase != RecorderHub.Phase.IDLE) return
        if (!Perms.recordAudio(this)) {
            requestRuntimePermissions(thenStart = true)
            return
        }
        RecorderHub.update { it.copy(error = null) }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: одразу «весь екран» — тоді звук пишеться з будь-якого плеєра.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PROJECTION)
        } catch (e: ActivityNotFoundException) {
            toast("На цьому телефоні немає вікна дозволу на запис")
        }
    }

    @Deprecated("startActivityForResult — найпростіший шлях для android.app.Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE) {
            onFolderPicked(resultCode, data)
            return
        }
        if (requestCode == REQ_BACKUP_SAVE || requestCode == REQ_BACKUP_OPEN) {
            onBackupPicked(requestCode, resultCode, data)
            return
        }
        if (requestCode == UpdateUi.REQ_APK || requestCode == UpdateUi.REQ_JSON) {
            UpdateUi(this).onFilePicked(requestCode, resultCode, data)
            return
        }
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(CaptureService.startIntent(this, resultCode, data))
        } else {
            RecorderHub.update { it.copy(error = "Запис не почато: у вікні Android не натиснули «Почати».") }
        }
    }

    // ---------- копія історії (T64) ----------

    fun saveHistoryCopy() {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "track-catcher-istoriya-$day.json")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_BACKUP_SAVE)
        } catch (e: ActivityNotFoundException) {
            toast("На цьому телефоні немає вікна «Зберегти»")
        }
    }

    fun openHistoryCopy() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_BACKUP_OPEN)
        } catch (e: ActivityNotFoundException) {
            toast("На цьому телефоні немає вікна вибору файлу")
        }
    }

    private fun onBackupPicked(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val save = requestCode == REQ_BACKUP_SAVE
        val app = applicationContext
        Thread({
            val message = try {
                val db = ua.faric.trackcatcher.data.HistoryDb.get(app)
                if (save) {
                    val n = app.contentResolver.openOutputStream(uri, "wt")?.use {
                        ua.faric.trackcatcher.data.HistoryBackup.export(db, it, ua.faric.trackcatcher.BuildConfig.VERSION_NAME)
                    } ?: throw java.io.IOException("файл не відкривається на запис")
                    "Збережено: " + ua.faric.trackcatcher.core.TimeText.plural(n, "запис", "записи", "записів") +
                        ". Тримай файл у надійному місці (напр. Google Диск) — з нього історію можна відновити."
                } else {
                    val r = app.contentResolver.openInputStream(uri)?.use { ua.faric.trackcatcher.data.HistoryBackup.import(db, it) }
                        ?: throw java.io.IOException("файл не відкривається")
                    "Додано " + ua.faric.trackcatcher.core.TimeText.plural(r.added, "запис", "записи", "записів") +
                        (if (r.skipped > 0) ", уже були — ${r.skipped}" else "") + ". Наявні записи не змінено."
                }
            } catch (e: ua.faric.trackcatcher.data.HistoryBackup.BadFile) {
                e.message ?: "Це не копія історії."
            } catch (e: Exception) {
                (if (save) "Не вдалося зберегти: " else "Не вдалося відновити: ") + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val body = overlay.dialog(
                    title = if (save) "Копія історії" else "Відновлення історії",
                    message = message,
                    actions = listOf("Закрити" to Ui.Kind.QUIET),
                    onAction = { overlay.dismiss() },
                )
                overlay.show("backup:result", body)
            }
        }, "history-backup").start()
    }

    /** Своя папка (T29): вікно Android «Вибрати папку» — там видно й карту пам'яті. */
    fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_TREE)
        } catch (e: ActivityNotFoundException) {
            toast("На цьому телефоні немає вікна вибору папки")
        }
    }

    private fun onFolderPicked(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            toast("Android не дав дозволу на цю папку — вибери іншу")
            return
        }
        val settings = Settings(this)
        val old = settings.treeUri
        // Попередню свою папку відпускаємо — дозволів у застосунку не повинно накопичуватись.
        if (old.isNotBlank() && old != uri.toString()) {
            runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        settings.treeUri = uri.toString()
        screen?.onResume()
    }

    /** Повернути «Музику» замість своєї папки. */
    fun resetFolder() {
        val settings = Settings(this)
        val old = settings.treeUri
        if (old.isNotBlank()) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        settings.treeUri = ""
        screen?.onResume()
    }

    fun stopCapture() {
        startService(CaptureService.stopIntent(this))
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_TAB = "tab"

        /** Плитка «Запис» у шторці (T62): відкрити й одразу почати (вікно Android «Почати запис?»). */
        const val EXTRA_START = "start_recording"
        private const val KEY_TAB = "tab"
        private const val KEY_SCREENS = "screens"
        private const val KEY_DIALOG = "dialog"
        private const val REQ_PERMS = 10
        private const val REQ_PERMS_THEN_START = 11
        private const val REQ_PROJECTION = 20
        private const val REQ_TREE = 30
        private const val REQ_BACKUP_SAVE = 40
        private const val REQ_BACKUP_OPEN = 41
    }
}
