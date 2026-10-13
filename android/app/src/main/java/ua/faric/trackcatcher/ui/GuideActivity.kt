package ua.faric.trackcatcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Інструкція користувача — сторінка з assets/guide (працює без інтернету).
 * Головна: список можливостей; дотик → докладний розділ. «Назад» — на попередній розділ.
 */
class GuideActivity : Activity() {
    private lateinit var web: WebView
    private var backCallback: Any? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        web = WebView(this).apply {
            setBackgroundColor(Palette.bg)
            settings.javaScriptEnabled = true // лише перемикання розділів у локальній сторінці
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "file") return false
                    // Зовнішні посилання (GitHub) — у браузері.
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    return true
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(web)
        val section = savedInstanceState?.getString(KEY_URL) ?: intent.getStringExtra(EXTRA_SECTION)?.let { "$START#$it" } ?: START
        web.loadUrl(section)
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { goBack() }
            backCallback = cb
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        }
    }

    private fun goBack() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    @Deprecated("Для Android 10–12; з 13 — OnBackInvokedCallback")
    override fun onBackPressed() {
        goBack()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.url?.let { outState.putString(KEY_URL, it) }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val START = "file:///android_asset/guide/index.html"
        private const val KEY_URL = "url"
        private const val EXTRA_SECTION = "section"

        fun open(context: Context, section: String? = null) {
            context.startActivity(Intent(context, GuideActivity::class.java).apply { section?.let { putExtra(EXTRA_SECTION, it) } })
        }
    }
}
