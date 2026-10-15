package ua.faric.trackcatcher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import ua.faric.trackcatcher.BuildConfig
import ua.faric.trackcatcher.core.DriveUpdate
import ua.faric.trackcatcher.update.DriveHttp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Стан оновлення, що переживає поворот екрана: робота йде у фоновому потоці, а вікно
 * (нове після повороту) лише показує поточний текст.
 */
object Updater {
    var busy = false
    var progressTitle = ""
    var progressText = ""
    var progressFinished = false
    var verdict: DriveUpdate.Manifest? = null
    var problem: String? = null
    var problemOffersLink = false
    var pendingApk: File? = null
    internal var progressView: TextView? = null
    internal var progressButton: TextView? = null

    /** Видиме вікно застосунку (між onStart і onStop). */
    private var host: MainActivity? = null
    private val pending = ArrayList<(MainActivity) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun attach(activity: MainActivity) {
        host = activity
        val todo = ArrayList(pending)
        pending.clear()
        todo.forEach { it(activity) }
    }

    fun detach(activity: MainActivity) {
        if (host === activity) host = null
        progressView = null
        progressButton = null
    }

    /** Виконати в головному потоці на видимому вікні; сховано — коли застосунок відкриють знову. */
    fun onMain(action: (MainActivity) -> Unit) {
        main.post {
            val h = host
            if (h != null && !h.isFinishing && !h.isDestroyed) action(h) else pending += action
        }
    }

    fun setProgress(text: String, finished: Boolean = false) {
        main.post {
            progressText = text
            progressFinished = finished
            progressView?.text = text
            progressButton?.let { b ->
                b.text = if (finished) "Закрити" else "Сховати (робота йде далі)"
            }
        }
    }
}

/**
 * «Оновлення застосунку» в «Налаштуваннях» (faric 14.10 20:22: як у Dorama.land).
 * Для всіх: «Перевірити оновлення» (з Google Диска автора). Для автора: «Опублікувати цю версію на Диск».
 */
class UpdateUi(private val host: MainActivity) {
    private val ui: Ui get() = host.ui
    private val prefs: SharedPreferences = host.getSharedPreferences("update", Context.MODE_PRIVATE)

    // ---------- картка в «Налаштуваннях» ----------

    fun card(): View = ui.card(10).apply {
        addView(ui.label("Оновлення застосунку"))
        addView(ui.muted(
            if (manifestId() == null) "Посилання на оновлення ще не задано — натисни «Перевірити оновлення» й встав посилання від автора."
            else "Нові версії беруться з Google Диска автора. Записи, історія й налаштування лишаються.",
            13.5f,
        ))
        addView(ui.button("Перевірити оновлення", Ui.Kind.PRIMARY) { check(quiet = false) }, ui.matchWrap())
        if (manifestId() != null) {
            // T54: змінити посилання можна завжди, а не лише після помилки перевірки.
            addView(ui.button("Інше посилання на оновлення", Ui.Kind.QUIET) { askForLink() }, ui.matchWrap())
        }
        addView(ui.title("Перевіряти самому раз на день", 15.5f))
        val auto = prefs.getBoolean(KEY_AUTO, true)
        val choices = ArrayList<Pair<Boolean, TextView>>()
        fun pick(on: Boolean) {
            prefs.edit().putBoolean(KEY_AUTO, on).apply()
            choices.forEach { (value, view) -> ui.styleChoice(view, value == on) }
        }
        addView(FlowLayout(host, ui.dp(8), ui.dp(8)).apply {
            listOf(true to "Так", false to "Ні").forEach { (on, text) ->
                val c = ui.choice(text, auto == on) { pick(on) }
                choices += on to c
                addView(c)
            }
        })
        addView(ui.muted("Під час відкриття застосунку, без зайвих вікон: скаже лише, якщо є нова версія.", 13f))
        addView(ui.button("Опублікувати цю версію на Диск (автор)", Ui.Kind.NORMAL) { showPublishSheet() }, ui.matchWrap())
    }

    // ---------- автор: публікація ----------

    fun showPublishSheet() {
        val apkUri = prefs.getString(KEY_APK_URI, "").orEmpty()
        val jsonUri = prefs.getString(KEY_JSON_URI, "").orEmpty()
        val apkId = prefs.getString(KEY_APK_ID, "").orEmpty()
        val jsonId = prefs.getString(KEY_JSON_ID, "").orEmpty()
        val apkReady = apkUri.isNotBlank()
        val jsonReady = jsonUri.isNotBlank()
        val apkLink = apkId.isNotBlank()
        val jsonLink = jsonId.isNotBlank()

        val box = ui.column(10)
        box.addView(ui.title("Публікація версії на Диск", 19f))
        box.addView(ui.muted(
            "Ця версія: ${BuildConfig.VERSION_NAME} · збірка ${BuildConfig.VERSION_CODE}\n" +
                "Один раз: два файли на Диску (кроки 1–2) і посилання на них (кроки 3–4). " +
                "Далі після кожної нової версії — лише «Опублікувати зараз».",
            14f,
        ))
        fun step(label: String, kind: Ui.Kind, enabled: Boolean, action: () -> Unit) {
            val b = ui.button(label, kind) { action() }
            ui.setEnabled(b, enabled)
            box.addView(b, ui.matchWrap())
        }
        step(
            if (apkReady) "1. Файл APK — готово: ${prefs.getString(KEY_APK_NAME, "")}" else "1. Створити файл APK на Диску",
            if (apkReady) Ui.Kind.QUIET else Ui.Kind.PRIMARY, !apkReady,
        ) { createFile(REQ_APK, "application/vnd.android.package-archive", DriveUpdate.APK_FILE_NAME) }
        step(
            if (jsonReady) "2. Файл версії — готово: ${prefs.getString(KEY_JSON_NAME, "")}" else "2. Створити файл версії на Диску",
            if (jsonReady) Ui.Kind.QUIET else Ui.Kind.PRIMARY, apkReady && !jsonReady,
        ) { createFile(REQ_JSON, "application/json", DriveUpdate.VERSION_FILE_NAME) }
        step(
            if (apkLink) "3. Посилання на APK — вставлено" else "3. Вставити посилання на APK",
            if (apkLink) Ui.Kind.QUIET else Ui.Kind.PRIMARY, apkReady && jsonReady && !apkLink,
        ) { if (pasteLink(KEY_APK_ID)) showPublishSheet() }
        step(
            if (jsonLink) "4. Посилання на файл версії — вставлено" else "4. Вставити посилання на файл версії",
            if (jsonLink) Ui.Kind.QUIET else Ui.Kind.PRIMARY, apkReady && jsonReady && apkLink && !jsonLink,
        ) { if (pasteLink(KEY_JSON_ID)) showPublishSheet() }
        if (!(apkReady && jsonReady && apkLink && jsonLink)) {
            box.addView(ui.muted(
                "Кроки 1–2: у вікні вибору натисни ☰ ліворуч → «Google Диск» → потрібна папка → «Зберегти».\n" +
                    "Кроки 3–4: відкрий Google Диск → ⋮ біля файлу → «Надати доступ» → «Усі, хто має посилання» → " +
                    "«Копіювати посилання» → повернись сюди й натисни крок.",
                13f,
            ))
        }
        if (jsonLink && BUILT_IN_MANIFEST_ID.isNotBlank() && jsonId != BUILT_IN_MANIFEST_ID) {
            box.addView(ui.text(
                "Увага: файл версії не той, що вбудований у застосунок. Телефони без вставленого посилання цієї " +
                    "публікації не побачать — скажи про це в чаті Track Catcher (вбудоване посилання треба замінити).",
                13.5f, Palette.warning,
            ))
        }
        val ready = apkReady && jsonReady && apkLink
        val publish = ui.button("Опублікувати зараз", Ui.Kind.PRIMARY, large = true) { publish() }
        ui.setEnabled(publish, ready)
        box.addView(publish, ui.matchWrap())
        prefs.getString(KEY_LAST_RESULT, "").orEmpty().takeIf { it.isNotBlank() }?.let { box.addView(ui.muted(it, 13.5f)) }
        if (jsonLink) {
            box.addView(ui.button("Поділитися посиланням на оновлення", Ui.Kind.NORMAL) { shareLink(jsonId) }, ui.matchWrap())
        }
        if (apkReady || jsonReady || apkLink || jsonLink) {
            box.addView(ui.button("Почати налаштування заново", Ui.Kind.QUIET) { confirmReset() }, ui.matchWrap())
        }
        box.addView(ui.button("Закрити", Ui.Kind.QUIET) { host.overlay.dismiss() }, ui.matchWrap())
        host.overlay.show(KEY_DIALOG_PUBLISH, box)
    }

    private fun createFile(request: Int, mime: String, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        host.overlay.dismiss()
        host.toast("У вікні вибору натисни ☰ ліворуч → «Google Диск».")
        try {
            @Suppress("DEPRECATION")
            host.startActivityForResult(i, request)
        } catch (e: ActivityNotFoundException) {
            host.toast("На цьому телефоні немає вікна створення файлу")
        }
    }

    /** Після вікна вибору: тримаємо постійний дозвіл на файл і запам'ятовуємо його. */
    fun onFilePicked(request: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || data == null || uri == null) {
            showPublishSheet()
            return
        }
        try {
            var flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (flags == 0) flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            host.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            showProblem("Android не дав постійного доступу до цього файлу. Створи файл саме через «Google Диск» у меню ☰ вікна вибору.", offerLink = false)
            return
        }
        val apk = request == REQ_APK
        var name = if (apk) DriveUpdate.APK_FILE_NAME else DriveUpdate.VERSION_FILE_NAME
        runCatching {
            host.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        prefs.edit()
            .putString(if (apk) KEY_APK_URI else KEY_JSON_URI, uri.toString())
            .putString(if (apk) KEY_APK_NAME else KEY_JSON_NAME, name)
            .apply()
        if (!apk) {
            // Файл версії має мати вміст, щоб у Диску можна було скопіювати посилання.
            val resolver = host.contentResolver
            Thread({ runCatching { writeText(resolver, uri, DriveUpdate.PLACEHOLDER) } }, "update-json-placeholder").start()
        }
        showPublishSheet()
    }

    /** Посилання з буфера → ID файлу; немає — вікно з поясненням і null. */
    private fun linkFromClipboard(): String? {
        val text = runCatching {
            val clip = (host.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(host)?.toString().orEmpty() else ""
        }.getOrDefault("")
        val id = DriveUpdate.fileIdFromLink(text)
        if (id == null) {
            val shown = if (text.isBlank()) "" else "\n\nЗараз у буфері: «" + (if (text.length > 80) text.take(80) + "…" else text) + "»"
            host.toast("У буфері немає посилання на файл Диска")
            showProblem(
                "У буфері немає посилання на файл Google Диска.\n\nВідкрий Google Диск → ⋮ біля файлу → «Надати доступ» → " +
                    "«Усі, хто має посилання» → «Копіювати посилання». Потім повернись сюди й натисни кнопку ще раз.$shown",
                offerLink = false,
            )
        }
        return id
    }

    /** Кроки 3–4 автора. Те саме посилання в обох кроках — помилка (T46). */
    private fun pasteLink(key: String): Boolean {
        val id = linkFromClipboard() ?: return false
        val other = prefs.getString(if (key == KEY_APK_ID) KEY_JSON_ID else KEY_APK_ID, "").orEmpty()
        if (id == other) {
            showProblem(
                "Це те саме посилання, що вже вставлене в " + (if (key == KEY_APK_ID) "кроці 4" else "кроці 3") + ".\n\n" +
                    "Крок 3 — посилання на «${DriveUpdate.APK_FILE_NAME}», крок 4 — на «${DriveUpdate.VERSION_FILE_NAME}».",
                offerLink = false,
            )
            return false
        }
        val edit = prefs.edit().putString(key, id)
        if (key == KEY_JSON_ID) edit.putString(KEY_MANIFEST_ID, id)
        edit.apply()
        host.toast("Посилання вставлено")
        return true
    }

    /** Посилання для перевірки: зберігається лише тоді, коли за ним справді файл версії (T46). */
    private fun checkPastedLink() {
        val id = linkFromClipboard() ?: return
        host.toast("Посилання вставлено")
        check(quiet = false, pasted = id)
    }

    private fun shareLink(jsonId: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
            Intent.EXTRA_TEXT,
            "Оновлення Track Catcher: ${DriveUpdate.viewUrl(jsonId)}\n" +
                "Скопіюй це посилання, потім у Track Catcher: «Налаштування» → «Оновлення застосунку» → " +
                "«Перевірити оновлення» → «Вставити посилання».",
        )
        try {
            host.startActivity(Intent.createChooser(send, "Поділитися посиланням"))
        } catch (e: ActivityNotFoundException) {
            host.toast("Немає застосунку, щоб поділитись")
        }
    }

    private fun confirmReset() {
        val body = host.overlay.dialog(
            title = "Почати заново?",
            message = "Телефон забуде обидва файли й посилання. На Диску нічого не зміниться — старі файли можна вибрати знову або прибрати вручну в Google Диску.",
            actions = listOf("Почати заново" to Ui.Kind.DANGER, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                if (i == 0) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    listOf(KEY_APK_URI, KEY_JSON_URI).mapNotNull { prefs.getString(it, "")?.takeIf { s -> s.isNotBlank() } }.forEach { u ->
                        runCatching { host.contentResolver.releasePersistableUriPermission(Uri.parse(u), flags) }
                    }
                    val keepManifest = prefs.getString(KEY_MANIFEST_ID, "")
                    val keepAuto = prefs.getBoolean(KEY_AUTO, true)
                    prefs.edit().clear().putString(KEY_MANIFEST_ID, keepManifest).putBoolean(KEY_AUTO, keepAuto).apply()
                    showPublishSheet()
                } else {
                    showPublishSheet()
                }
            },
        )
        host.overlay.show(KEY_DIALOG_RESET, body)
    }

    private fun publish() {
        if (Updater.busy) {
            host.toast("Уже працюю…")
            return
        }
        val apkUri = prefs.getString(KEY_APK_URI, "").orEmpty()
        val jsonUri = prefs.getString(KEY_JSON_URI, "").orEmpty()
        val apkId = prefs.getString(KEY_APK_ID, "").orEmpty()
        if (apkUri.isBlank() || jsonUri.isBlank() || apkId.isBlank()) {
            showPublishSheet()
            return
        }
        val info = host.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) {
            showProblem("Ця версія встановлена кількома файлами (split APK) — її не передати одним APK. Встав версію з меню Track Catcher і спробуй ще раз.", offerLink = false)
            return
        }
        val source = File(info.sourceDir)
        val resolver = host.contentResolver
        val pkg = host.packageName
        startProgress("Публікую версію на Диск", "Копіюю APK на Диск…")
        Thread({
            var ok = false
            val outcome = try {
                val total = source.length()
                val sha = DriveUpdate.newSha256()
                var done = 0L
                val raw = runCatching { resolver.openOutputStream(Uri.parse(apkUri), "wt") }.getOrNull()
                    ?: resolver.openOutputStream(Uri.parse(apkUri), "w")
                    ?: throw IOException("файл APK на Диску не відкрився для запису")
                raw.use { out ->
                    FileInputStream(source).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastShown = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            sha.update(buffer, 0, n)
                            done += n
                            if (done - lastShown > 1024 * 1024 || done == total) {
                                lastShown = done
                                Updater.setProgress("Копіюю APK на Диск… ${DriveUpdate.formatMb(done)} з ${DriveUpdate.formatMb(total)}")
                            }
                        }
                    }
                }
                val manifest = DriveUpdate.Manifest(
                    pkg, BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME,
                    DriveUpdate.sha256Hex(sha.digest()), done, apkId, System.currentTimeMillis(),
                )
                Updater.setProgress("Записую файл версії…")
                writeText(resolver, Uri.parse(jsonUri), manifest.toJson())
                ok = true
                "Опубліковано ${BuildConfig.VERSION_NAME} (${DriveUpdate.formatMb(done)}) · " +
                    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date()) +
                    "\nGoogle Диск вивантажує файл у фоні — інші телефони побачать його за кілька хвилин."
            } catch (e: SecurityException) {
                "Доступ до файлу на Диску втрачено — «Почати налаштування заново»."
            } catch (e: Exception) {
                "Не вдалося: " + (e.message ?: "невідома помилка")
            }
            prefs.edit().putString(KEY_LAST_RESULT, outcome).apply()
            Updater.busy = false
            Updater.setProgress(outcome, finished = true)
            Updater.onMain { it.toast(if (ok) "Опубліковано на Диск" else "Публікація не вдалася — подробиці у «Опублікувати цю версію на Диск»") }
        }, "update-publish").start()
    }

    // ---------- кожен телефон: перевірити, завантажити, встановити ----------

    fun manifestId(): String? =
        prefs.getString(KEY_MANIFEST_ID, "")?.takeIf { it.isNotBlank() } ?: BUILT_IN_MANIFEST_ID.takeIf { it.isNotBlank() }

    /** При відкритті застосунку: не частіше раз на 24 год; мовчить, якщо нової версії нема. */
    fun autoCheck() {
        if (manifestId() == null || !prefs.getBoolean(KEY_AUTO, true)) return
        val last = prefs.getLong(KEY_LAST_AUTO, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_MS) return
        prefs.edit().putLong(KEY_LAST_AUTO, System.currentTimeMillis()).apply()
        check(quiet = true)
    }

    /**
     * [pasted] — щойно вставлене посилання: перевіряється лише воно й зберігається, тільки якщо за ним
     * файл версії. Інакше — збережене, а якщо воно не на файл версії (напр. на APK, T46), воно
     * забувається й пробується вбудоване в застосунок.
     */
    fun check(quiet: Boolean, pasted: String? = null) {
        val saved = prefs.getString(KEY_MANIFEST_ID, "")?.takeIf { it.isNotBlank() }
        val candidates = DriveUpdate.manifestCandidates(pasted, saved, BUILT_IN_MANIFEST_ID)
        if (candidates.isEmpty()) {
            if (!quiet) askForLink()
            return
        }
        if (Updater.busy) {
            if (!quiet) host.toast("Уже працюю…")
            return
        }
        Updater.busy = true
        if (!quiet && pasted == null) host.toast("Перевіряю оновлення…")
        val pkg = host.packageName
        val store = prefs
        Thread({
            var manifest: DriveUpdate.Manifest? = null
            var problem: String? = null
            for (id in candidates) {
                try {
                    manifest = DriveUpdate.Manifest.parse(DriveHttp.readText(id, 64 * 1024))
                    if (pasted != null) store.edit().putString(KEY_MANIFEST_ID, id).apply()
                    problem = null
                    break
                } catch (e: DriveUpdate.WrongFileException) {
                    problem = e.message
                    if (pasted == null && id == saved) store.edit().remove(KEY_MANIFEST_ID).apply()
                } catch (e: IllegalArgumentException) {
                    problem = e.message
                    break
                } catch (e: Exception) {
                    problem = e.message ?: "Немає зв'язку з Google Диском."
                    break
                }
            }
            Updater.busy = false
            val found = manifest
            if (quiet) {
                if (found != null && DriveUpdate.compare(found, pkg, BuildConfig.VERSION_CODE.toLong()) == DriveUpdate.Verdict.NEWER) {
                    Updater.onMain { UpdateUi(it).showVerdict(found) }
                }
                return@Thread
            }
            Updater.onMain { h ->
                if (found == null) UpdateUi(h).showProblem(problem ?: "Невідома помилка.", offerLink = true)
                else UpdateUi(h).showVerdict(found)
            }
        }, "update-check").start()
    }

    private fun askForLink() {
        val body = host.overlay.dialog(
            title = "Звідки брати оновлення?",
            message = "Потрібне посилання на файл оновлень на Google Диску автора. Скопіюй його (автор надсилає посилання) і натисни «Вставити посилання».",
            actions = listOf("Вставити посилання" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET),
            onAction = { i ->
                host.overlay.dismiss()
                if (i == 0) checkPastedLink()
            },
        )
        host.overlay.show(KEY_DIALOG_LINK, body)
    }

    fun showProblem(message: String, offerLink: Boolean) {
        Updater.problem = message
        Updater.problemOffersLink = offerLink
        val actions = if (offerLink) listOf("Інше посилання" to Ui.Kind.NORMAL, "Закрити" to Ui.Kind.QUIET)
        else listOf("Закрити" to Ui.Kind.QUIET)
        val body = host.overlay.dialog(
            title = "Оновлення: не вдалося",
            message = message + if (offerLink) "\n\nБез інтернету перевірка не працює." else "",
            actions = actions,
            onAction = { i ->
                host.overlay.dismiss()
                Updater.problem = null
                if (offerLink && i == 0) checkPastedLink()
            },
        )
        host.overlay.show(KEY_DIALOG_PROBLEM, body)
    }

    fun showVerdict(m: DriveUpdate.Manifest) {
        Updater.verdict = m
        val mine = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val theirs = "${m.versionName} (${m.versionCode})"
        val (title, message, actions) = when (DriveUpdate.compare(m, host.packageName, BuildConfig.VERSION_CODE.toLong())) {
            DriveUpdate.Verdict.NEWER -> Triple(
                "Є нова версія",
                "На Диску: $theirs\nУ тебе: $mine\n\nРозмір: ${DriveUpdate.formatMb(m.apkSize)}. Застосунок завантажить файл, " +
                    "перевірить, що він справжній, і відкриє вікно Android «Оновити». Записи, історія й налаштування лишаються.",
                listOf("Завантажити й встановити" to Ui.Kind.PRIMARY, "Не зараз" to Ui.Kind.QUIET),
            )
            DriveUpdate.Verdict.SAME -> Triple(
                "У тебе остання версія",
                "Встановлено $mine — така сама, як на Диску.$OTHER_LINK_HINT",
                listOf("Інше посилання" to Ui.Kind.NORMAL, "Закрити" to Ui.Kind.QUIET),
            )
            DriveUpdate.Verdict.OLDER -> Triple(
                "У тебе новіша версія",
                "Встановлено $mine, на Диску старіша: $theirs.$OTHER_LINK_HINT",
                listOf("Інше посилання" to Ui.Kind.NORMAL, "Закрити" to Ui.Kind.QUIET),
            )
            DriveUpdate.Verdict.OTHER_PACKAGE -> Triple(
                "Інший варіант застосунку",
                "На Диску версія для ${m.packageName}, а в тебе ${host.packageName}.",
                listOf("Інше посилання" to Ui.Kind.NORMAL, "Закрити" to Ui.Kind.QUIET),
            )
        }
        val newer = DriveUpdate.compare(m, host.packageName, BuildConfig.VERSION_CODE.toLong()) == DriveUpdate.Verdict.NEWER
        val body = host.overlay.dialog(title, message, actions, onAction = { i ->
            host.overlay.dismiss()
            Updater.verdict = null
            // T54: «Інше посилання» — і коли перевірка вдала (раніше лише після помилки — старий файл версії був тупиком).
            if (newer && i == 0) download(m) else if (!newer && i == 0) checkPastedLink()
        })
        host.overlay.show(KEY_DIALOG_VERDICT, body)
    }

    private fun download(m: DriveUpdate.Manifest) {
        if (Updater.busy) return
        val dir = File(host.cacheDir, "updates")
        val pm = host.packageManager
        val installedCert = installedCertSha256()
        startProgress("Завантажую оновлення", "Починаю…")
        Thread({
            val file = File(dir, "track-catcher-update.apk")
            val problem: String? = try {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val result = FileOutputStream(file).use { out ->
                    DriveHttp.download(m.apkId, out, MAX_APK_BYTES, expectApk = true) { done, total ->
                        Updater.setProgress("Завантажено ${DriveUpdate.formatMb(done)} з ${DriveUpdate.formatMb(if (total > 0) total else m.apkSize)}")
                    }
                }
                Updater.setProgress("Перевіряю файл…")
                val archive = archiveInfo(pm, file)
                DriveUpdate.checkDownloaded(
                    m, result.sha256, result.size,
                    archive?.packageName, archive?.longVersionCode ?: -1L,
                    archive?.let { certSha256(it) }, installedCert,
                )
            } catch (e: Exception) {
                e.message ?: "Завантаження обірвалось."
            }
            Updater.busy = false
            if (problem != null) {
                file.delete()
                Updater.setProgress(problem, finished = true)
                Updater.onMain { h -> if (h.overlay.key != KEY_DIALOG_PROGRESS) UpdateUi(h).showProblem(problem, offerLink = false) }
            } else {
                Updater.setProgress("Файл перевірено: розмір, контрольна сума, версія й ключ підпису збіглися.", finished = true)
                Updater.pendingApk = file
                Updater.onMain { h ->
                    if (h.overlay.key == KEY_DIALOG_PROGRESS) h.overlay.dismiss()
                    UpdateUi(h).install(file)
                }
            }
        }, "update-download").start()
    }

    fun install(file: File) {
        if (!file.isFile) {
            Updater.pendingApk = null
            return
        }
        if (!host.packageManager.canRequestPackageInstalls()) {
            Updater.pendingApk = file
            val body = host.overlay.dialog(
                title = "Один дозвіл — один раз",
                message = "Файл перевірено. Щоб Android дозволив Track Catcher встановити оновлення, на наступному екрані увімкни " +
                    "«Дозволити з цього джерела» й натисни «Назад» — встановлення почнеться саме.",
                actions = listOf("Відкрити дозвіл" to Ui.Kind.PRIMARY, "Скасувати" to Ui.Kind.QUIET),
                onAction = { i ->
                    host.overlay.dismiss()
                    if (i == 0) openInstallPermission() else Updater.pendingApk = null
                },
            )
            host.overlay.show(KEY_DIALOG_PERMISSION, body)
            return
        }
        Updater.pendingApk = null
        try {
            val uri = FileProvider.getUriForFile(host, host.packageName + ".updates", file)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            host.startActivity(i)
        } catch (e: Exception) {
            showProblem("Не вдалося відкрити встановлення: " + (e.message ?: "невідома помилка"), offerLink = false)
        }
    }

    private fun openInstallPermission() {
        try {
            host.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + host.packageName)))
        } catch (e: Exception) {
            runCatching {
                host.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + host.packageName)))
            }
        }
    }

    /** Повернулись з «Дозволити з цього джерела» — встановлення продовжується саме. */
    fun resumePendingInstall() {
        val file = Updater.pendingApk ?: return
        if (host.overlay.key == KEY_DIALOG_PERMISSION) return // вікно ще відкрите — чекаємо «Відкрити дозвіл»
        if (!file.isFile) {
            Updater.pendingApk = null
            return
        }
        if (host.packageManager.canRequestPackageInstalls()) install(file)
    }

    private fun startProgress(title: String, text: String) {
        Updater.busy = true
        Updater.progressTitle = title
        Updater.progressText = text
        Updater.progressFinished = false
        showProgress()
    }

    private fun showProgress() {
        val box = ui.column(14)
        box.addView(ui.title(Updater.progressTitle, 19f))
        val status = ui.text(Updater.progressText, 15f, Palette.muted)
        box.addView(status)
        val close = ui.button(if (Updater.progressFinished) "Закрити" else "Сховати (робота йде далі)", Ui.Kind.QUIET) {
            Updater.progressView = null
            Updater.progressButton = null
            host.overlay.dismiss()
        }
        // «Закрити» — у правий кут вікна, ближче до правої руки (faric 16.10, T53).
        box.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
        })
        Updater.progressView = status
        Updater.progressButton = close
        host.overlay.show(KEY_DIALOG_PROGRESS, box, dismissOnOutside = false)
    }

    /** Відновити вікно після повороту. */
    fun restore(key: String): Boolean {
        when (key) {
            KEY_DIALOG_PUBLISH -> showPublishSheet()
            KEY_DIALOG_RESET -> confirmReset()
            KEY_DIALOG_LINK -> askForLink()
            KEY_DIALOG_PROGRESS -> showProgress()
            KEY_DIALOG_VERDICT -> Updater.verdict?.let { showVerdict(it) } ?: return false
            KEY_DIALOG_PROBLEM -> Updater.problem?.let { showProblem(it, Updater.problemOffersLink) } ?: return false
            KEY_DIALOG_PERMISSION -> Updater.pendingApk?.let { install(it) } ?: return false
            else -> return false
        }
        return true
    }

    private fun installedCertSha256(): String? = runCatching {
        certSha256(host.packageManager.getPackageInfo(host.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
    }.getOrNull()

    companion object {
        /**
         * Вбудоване посилання на файл версії на Диску автора — щоб інші телефони перевіряли без вставляння.
         * Файл `track-catcher-onovlennia.json` на Диску faric (створено 14.10.2026 22:48, доступ «усі з посиланням»).
         * T47: перший файл (15QLo…, 21:55) лишився на 0.1.15 — 22:48 телефон автора створив файл версії заново,
         * і «Опублікувати зараз» пише вже в новий. Якщо автор створить файл ще раз — вікно публікації попередить.
         */
        /** Підказка у вікні «остання / новіша версія» (T54). */
        private const val OTHER_LINK_HINT =
            "\n\nАвтор казав, що є новіша? Скопіюй посилання від нього й натисни «Інше посилання»."
        const val BUILT_IN_MANIFEST_ID = "1y8g6jMvN68x8aGN1HbuE4EoJCEtIJFqr"

        const val REQ_APK = 40
        const val REQ_JSON = 41
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val AUTO_CHECK_MS = 24L * 60 * 60 * 1000

        private const val KEY_APK_URI = "apk_uri"
        private const val KEY_APK_NAME = "apk_name"
        private const val KEY_JSON_URI = "json_uri"
        private const val KEY_JSON_NAME = "json_name"
        private const val KEY_APK_ID = "apk_id"
        private const val KEY_JSON_ID = "json_id"
        private const val KEY_MANIFEST_ID = "manifest_id"
        private const val KEY_LAST_RESULT = "last_publish_result"
        private const val KEY_AUTO = "auto_check"
        private const val KEY_LAST_AUTO = "last_auto_check"

        const val KEY_DIALOG_PUBLISH = "update:publish"
        const val KEY_DIALOG_RESET = "update:reset"
        const val KEY_DIALOG_LINK = "update:link"
        const val KEY_DIALOG_PROGRESS = "update:progress"
        const val KEY_DIALOG_VERDICT = "update:verdict"
        const val KEY_DIALOG_PROBLEM = "update:problem"
        const val KEY_DIALOG_PERMISSION = "update:permission"

        fun writeText(resolver: android.content.ContentResolver, uri: Uri, text: String) {
            val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "w")
                ?: throw IOException("файл версії на Диску не відкрився для запису")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }

        @Suppress("DEPRECATION")
        private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
            runCatching { pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()

        private fun certSha256(info: PackageInfo): String? = runCatching {
            val signers = info.signingInfo?.apkContentsSigners
            if (signers.isNullOrEmpty()) null
            else DriveUpdate.sha256Hex(DriveUpdate.newSha256().digest(signers[0].toByteArray()))
        }.getOrNull()
    }
}
