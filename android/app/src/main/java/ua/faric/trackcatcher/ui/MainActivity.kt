package ua.faric.trackcatcher.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
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
    enum class Tab(val label: String, val glyph: String) {
        RECORD("Запис", "●"),
        HISTORY("Історія", "☰"),
        SETTINGS("Налаштування", "⚙︎"),
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
        savedInstanceState?.getString(KEY_DIALOG)?.let { key ->
            if (key == NightScreen.KEY) showNightScreen() else screen?.restoreDialog(key)
        }
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
        if (BuildConfig.DEBUG && i.hasExtra("guide")) GuideActivity.open(this, i.getStringExtra("guide")?.ifBlank { null })
        if (BuildConfig.DEBUG) {
            i.getStringExtra("dialog")?.let { if (it == NightScreen.KEY) showNightScreen() else screen?.restoreDialog(it) }
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
                val b = ui.choice("${t.glyph}\n${t.label}", t == tab) { showTab(t) }.apply {
                    textSize = 13f
                    oneLineLabel(this)
                    setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
                }
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
                val b = ui.choice("${t.glyph}\n${t.label}", t == tab) { showTab(t) }.apply {
                    textSize = 12.5f
                    oneLineLabel(this)
                    setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
                }
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
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
    }

    /**
     * Вкладка: значок + підпис в ОДИН рядок; не влазить — «Налашту…» (власник, 13.10 20:11).
     * Повна назва лишається для озвучування.
     */
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
        tabButtons.forEach { (k, v) -> ui.styleChoice(v, k == t) }
        renderChip(RecorderHub.state)
        if (started) {
            s.onStart()
            s.onResume()
        }
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
    }

    override fun onStop() {
        started = false
        if (overlay.key == NightScreen.KEY) night?.pause()
        handler.removeCallbacks(tick)
        unobserveHub?.invoke()
        unobserveHub = null
        screen?.onStop()
        tracker.stop()
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

    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = (backCallback as? OnBackInvokedCallback) ?: OnBackInvokedCallback { overlay.dismiss() }.also { backCallback = it }
        if (overlay.isShowing && !backRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backRegistered = true
        } else if (!overlay.isShowing && backRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            backRegistered = false
        }
    }

    @Deprecated("Для Android 10–12; з 13 — OnBackInvokedCallback")
    override fun onBackPressed() {
        if (overlay.isShowing) {
            overlay.dismiss()
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
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(CaptureService.startIntent(this, resultCode, data))
        } else {
            RecorderHub.update { it.copy(error = "Запис не почато: у вікні Android не натиснули «Почати».") }
        }
    }

    fun stopCapture() {
        startService(CaptureService.stopIntent(this))
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_TAB = "tab"
        private const val KEY_TAB = "tab"
        private const val KEY_SCREENS = "screens"
        private const val KEY_DIALOG = "dialog"
        private const val REQ_PERMS = 10
        private const val REQ_PERMS_THEN_START = 11
        private const val REQ_PROJECTION = 20
    }
}
