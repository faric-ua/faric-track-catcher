package ua.faric.trackcatcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Інструкція користувача — сторінка з assets/guide (працює без інтернету).
 * Віддається через WebViewAssetLoader (https://appassets.androidplatform.net/assets/…):
 * з file:// WebView не показував картинки (знайдено на емуляторі, v0.1.2).
 * Головна: список можливостей; дотик → докладний розділ. «Назад» — на попередній розділ.
 */
class GuideActivity : Activity() {
    private lateinit var web: WebView
    private var backCallback: Any? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this).apply {
            setBackgroundColor(Palette.bg)
            settings.javaScriptEnabled = true // лише перемикання розділів у локальній сторінці
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                    // Зовнішні посилання (GitHub) — у браузері.
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    return true
                }
            }
        }
        // WebView не зсуває вміст через padding — відступи від смуг дає контейнер (знайдено на емуляторі).
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Palette.bg)
            addView(web, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(frame)
        web.loadUrl(savedInstanceState?.getString(KEY_URL) ?: urlFor(intent.getStringExtra(EXTRA_SECTION)))
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { goBack() }
            backCallback = cb
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        web.loadUrl(urlFor(intent.getStringExtra(EXTRA_SECTION)))
    }

    private fun urlFor(section: String?): String =
        if (section.isNullOrBlank() || section == "home") START else "$START#$section"

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
        private const val START = "https://" + WebViewAssetLoader.DEFAULT_DOMAIN + "/assets/guide/index.html"
        private const val KEY_URL = "url"
        private const val EXTRA_SECTION = "section"

        fun open(context: Context, section: String? = null) {
            context.startActivity(Intent(context, GuideActivity::class.java).apply { section?.let { putExtra(EXTRA_SECTION, it) } })
        }
    }
}
