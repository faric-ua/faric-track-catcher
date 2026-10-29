package ua.faric.trackcatcher.capture

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import ua.faric.trackcatcher.R
import ua.faric.trackcatcher.TrackCatcherApp
import ua.faric.trackcatcher.core.LogText
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.Settings
import ua.faric.trackcatcher.state.WorkLog
import ua.faric.trackcatcher.ui.MainActivity

/**
 * Фонова служба для [FlacBatch]: працює, навіть коли екран повернули чи застосунок згорнули; у шторці — поступ
 * і «Зупинити». Зупинка — після поточної пісні (FLAC не лишається недописаним).
 */
class FlacBatchService : Service() {
    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WorkLog.i("робота", "натиснули «Зупинити» (потік ${if (worker?.isAlive == true) "працює" else "не працює"}, останній крок: ${WorkLog.stepWhat})")
            FlacBatch.stop()
            return START_NOT_STICKY
        }
        val ids = intent?.getLongArrayExtra(EXTRA_IDS)
        enter(notification("Готуюсь…", "", 0, ongoing = true))
        if (ids == null || worker != null) {
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        val mode = intent.getStringExtra(EXTRA_MODE)
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        val tail = intent.getStringExtra(EXTRA_TAIL).orEmpty()
        val what = mode ?: FlacBatch.Mode.FLAC.name
        WorkLog.step("робота", "почалось: $what · записів ${ids.size}" + if (auto) " · сам після запису" else "")
        worker = Thread({
            try {
                when (mode) {
                    FlacBatch.Mode.RENUMBER.name -> renumber(ids, auto)
                    FlacBatch.Mode.TITLES.name -> titles(ids, tail)
                    FlacBatch.Mode.EDIT.name -> editMeta(ids, specOf(intent))
                    FlacBatch.Mode.TRIM.name -> trim(ids, planOf(intent))
                    FlacBatch.Mode.HOLES.name -> holes(ids)
                    FlacBatch.Mode.RETITLE.name -> retitle(ids, intent.getStringArrayExtra(EXTRA_TITLES)?.map { it ?: "" }.orEmpty())
                    else -> run(ids)
                }
                val s = FlacBatch.state
                WorkLog.i(
                    "робота",
                    "кінець: $what · ${s.index} з ${s.total} · зроблено ${s.made}" + (if (s.cancelled) " · зупинено" else "") +
                        (if (s.skipped > 0) " · пропущено ${s.skipped}" else "") +
                        s.problems.joinToString("") { "\nне вийшло: " + it.replace('\t', ' ') },
                )
            } catch (t: Throwable) {
                WorkLog.e("робота", "збій посеред «$what», останній крок: ${WorkLog.stepWhat}", t)
                throw t
            } finally {
                WorkLog.idle()
            }
        }, "wav-to-flac").also { it.start() }
        watch(worker!!, what)
        return START_NOT_STICKY
    }

    /**
     * Сторож (T108, faric 29.10 16:50: «Перевіряю номери: 1 з 8» стоїть 5 хв, застосунок живий): робота хвилину без
     * жодного кроку — у журнал іде, де саме стоїть потік, і автокопія з журналом іде на Диск. Сам нічого не перериває.
     */
    private fun watch(w: Thread, what: String) {
        Thread({
            var warnedAt = 0L
            while (w.isAlive) {
                try {
                    Thread.sleep(10_000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val now = System.currentTimeMillis()
                val at = WorkLog.stepAt
                if (w.isAlive && LogText.stuck(now, at, warnedAt)) {
                    warnedAt = now
                    WorkLog.w(
                        "сторож",
                        "«$what» без руху ${(now - at) / 1000} с; останній крок: ${WorkLog.stepWhat}; потік: ${w.state}\n" +
                            LogText.threadStack(w.stackTrace),
                    )
                }
            }
        }, "work-watchdog").apply { isDaemon = true }.start()
    }

    private fun run(ids: LongArray) {
        val app = applicationContext
        val db = HistoryDb.get(app)
        val settings = Settings(app).snapshot()
        val problems = ArrayList<String>()
        var made = 0
        var cancelled = false
        for ((i, id) in ids.withIndex()) {
            if (FlacBatch.cancel) {
                cancelled = true
                break
            }
            val r = db.get(id) ?: continue
            if (!FlacBatch.isWav(r)) { made++; continue } // уже зроблено (повторний запуск)
            val label = r.title.ifBlank { r.displayName }
            var shown = -1
            fun say(p: Float) {
                val pct = (p * 100).toInt()
                if (pct == shown) return
                shown = pct
                FlacBatch.update { it.copy(index = i + 1, label = label, percent = pct) }
                WorkLog.alive("FLAC ${i + 1} з ${ids.size}: $label · $pct %")
                notify("${i + 1} з ${ids.size}: $label", "$pct %", ((i + p) * 100 / ids.size).toInt())
            }
            WorkLog.step("flac", "${i + 1} з ${ids.size}: $label · ${r.wavName}")
            say(0f)
            try {
                FlacBatch.makeOne(app, r, settings) { say(it) }
                made++
                FlacBatch.update { it.copy(made = made) }
            } catch (e: Exception) {
                WorkLog.e("flac", "не вийшло: $label", e)
                problems += "$label\t${ua.faric.trackcatcher.core.ErrorText.human(e)}"
                FlacBatch.update { it.copy(problems = problems.toList()) }
            }
        }
        FlacBatch.update { it.copy(running = false, finished = true, made = made, problems = problems.toList(), cancelled = cancelled) }
        val title = if (cancelled) "Зупинено: зроблено $made з ${ids.size}" else "Готово: " + TimeText.plural(made, "FLAC", "FLAC", "FLAC") + " з обкладинками"
        val done = notification(title, if (problems.isEmpty()) "WAV лишились на місці" else "Не вийшло: ${problems.size} — подробиці в застосунку", -1, ongoing = false)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, done)
        worker = null
        stopSelf()
    }

    /** T70: перевірити номери в альбомах цих записів; виправити лише теги там, де номер не той. */
    private fun renumber(ids: LongArray, auto: Boolean) {
        val app = applicationContext
        val db = HistoryDb.get(app)
        val starts = ids.toList().mapNotNull { db.get(it) }
        val seen = HashSet<String>()
        // Усі файли всіх зачеплених альбомів, з правильним номером кожного.
        val work = ArrayList<Triple<ua.faric.trackcatcher.data.RecordingRow, Pair<String, String>, Int>>()
        val problems = ArrayList<String>()
        val store = MediaStoreWriter(app)
        var skipped = 0
        for (r in starts) {
            if (!seen.add(Renumber.scopeKey(r))) continue
            val all = Renumber.scope(db, r)
            // Спершу — чи файли на місці; перенесені не рахуються й у номерах (faric 18.10 15:38).
            val rows = Renumber.present(app, all)
            if (rows == null) {
                problems += "${r.album.ifBlank { "без альбому" }}\tне видно жодного файла альбому — карта пам'яті вставлена? Нічого не змінено"
                continue
            }
            skipped += all.size - rows.size
            val nums = Renumber.numbers(rows)
            for (row in rows) for (f in Renumber.files(row)) {
                if (!store.stillThere(f.first)) { skipped++; continue }
                work += Triple(row, f, nums[row.id] ?: continue)
            }
        }
        FlacBatch.update { it.copy(skipped = skipped, problems = problems.toList()) }
        FlacBatch.update { it.copy(total = work.size) }
        WorkLog.step("номери", "файлів до перевірки: ${work.size}, пропущено: $skipped" + problems.joinToString("") { "\n" + it.replace('\t', ' ') })
        var fixed = 0
        var cancelled = false
        for ((i, w) in work.withIndex()) {
            if (FlacBatch.cancel) { cancelled = true; break }
            val (row, file, n) = w
            val label = "№$n · " + row.title.ifBlank { row.displayName }
            FlacBatch.update { it.copy(index = i + 1, label = label, percent = (i * 100) / maxOf(1, work.size)) }
            notify("Перевіряю номери: ${i + 1} з ${work.size}", label, (i * 100) / maxOf(1, work.size))
            WorkLog.step("номери", "${i + 1} з ${work.size}: $label · ${file.second}")
            try {
                if (Renumber.fixFile(app, row, file.first, file.second, n)) {
                    fixed++
                    FlacBatch.update { it.copy(made = fixed) }
                }
            } catch (e: Exception) {
                WorkLog.e("номери", "не вийшло: ${file.second}", e)
                problems += "${file.second}\t${ua.faric.trackcatcher.core.ErrorText.human(e)}"
                FlacBatch.update { it.copy(problems = problems.toList()) }
            }
        }
        FlacBatch.update { it.copy(running = false, finished = true, made = fixed, problems = problems.toList(), cancelled = cancelled, index = work.size, skipped = skipped) }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        val nm = getSystemService(NotificationManager::class.java)
        if (auto && fixed == 0 && problems.isEmpty()) {
            nm.cancel(NOTIF_ID) // після запису й усе гаразд — не заважаємо
        } else {
            val title = if (fixed == 0) "Номери пісень правильні (${work.size})" else "Номери виправлено: $fixed з ${work.size}"
            nm.notify(NOTIF_ID, notification(title, if (problems.isEmpty()) "Звук не змінювався — лише теги" else "Не вийшло: ${problems.size} — подробиці в застосунку", -1, ongoing = false))
        }
        worker = null
        stopSelf()
    }

    /** T71: прибрати хвіст з назв — теги, імена файлів, історія. */
    private fun titles(ids: LongArray, tail: String) {
        val app = applicationContext
        val db = HistoryDb.get(app)
        TitleFix.learn(app, tail) // нові записи з цим хвостом очищаються самі
        val problems = ArrayList<String>()
        var skipped = 0
        var fixed = 0
        var cancelled = false
        for ((i, id) in ids.withIndex()) {
            if (FlacBatch.cancel) { cancelled = true; break }
            val r = db.get(id) ?: continue
            val label = TitleFix.newTitle(r, tail) ?: r.title
            FlacBatch.update { it.copy(index = i + 1, label = label, percent = (i * 100) / ids.size) }
            notify("Прибираю зайве з назв: ${i + 1} з ${ids.size}", label, (i * 100) / ids.size)
            WorkLog.step("робота", "Прибираю зайве з назв: ${i + 1} з ${ids.size}: $label")
            if (TitleFix.newTitle(r, tail) == null) continue
            if (r.hasFile && Renumber.present(app, listOf(r)).isNullOrEmpty()) {
                skipped++ // файла нема на місці — не чіпаємо (faric 18.10 15:38)
                FlacBatch.update { it.copy(skipped = skipped) }
                continue
            }
            val bad = runCatching { TitleFix.fix(app, r, tail) }.getOrElse { listOf("${r.title}\t${ua.faric.trackcatcher.core.ErrorText.human(it)}") }
            if (bad.isEmpty()) {
                fixed++
                FlacBatch.update { it.copy(made = fixed) }
            } else {
                problems += bad
                FlacBatch.update { it.copy(problems = problems.toList()) }
            }
        }
        FlacBatch.update { it.copy(running = false, finished = true, made = fixed, problems = problems.toList(), cancelled = cancelled, index = ids.size) }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        val title = if (cancelled) "Зупинено: назв виправлено $fixed" else "Назви виправлено: $fixed з ${ids.size}"
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            notification(title, if (problems.isEmpty()) "Звук не змінювався — лише назви" else "Не вийшло: ${problems.size} — подробиці в застосунку", -1, ongoing = false),
        )
        worker = null
        stopSelf()
    }

    /** T77: назви з нових імен файлів — тег TITLE і історія (ім'я файлу вже нове). */
    private fun retitle(ids: LongArray, titles: List<String>) {
        val app = applicationContext
        val db = HistoryDb.get(app)
        val problems = ArrayList<String>()
        var fixed = 0
        var cancelled = false
        for ((i, id) in ids.withIndex()) {
            if (FlacBatch.cancel) { cancelled = true; break }
            val r = db.get(id) ?: continue
            val title = titles.getOrNull(i) ?: continue
            FlacBatch.update { it.copy(index = i + 1, label = title, percent = (i * 100) / ids.size) }
            notify("Назви з імен файлів: ${i + 1} з ${ids.size}", title, (i * 100) / ids.size)
            WorkLog.step("робота", "Назви з імен файлів: ${i + 1} з ${ids.size}: $title")
            val bad = runCatching { TitleFix.fixTo(app, r, title, "взято з імені файлу") }.getOrElse { listOf("${r.title}\t${ua.faric.trackcatcher.core.ErrorText.human(it)}") }
            if (bad.isEmpty()) { fixed++; FlacBatch.update { it.copy(made = fixed) } } else { problems += bad; FlacBatch.update { it.copy(problems = problems.toList()) } }
        }
        FlacBatch.update { it.copy(running = false, finished = true, made = fixed, problems = problems.toList(), cancelled = cancelled, index = ids.size) }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            notification("Назви з імен файлів: $fixed з ${ids.size}", if (problems.isEmpty()) "Звук не змінювався — лише назви" else "Не вийшло: ${problems.size} — подробиці в застосунку", -1, ongoing = false),
        )
        worker = null
        stopSelf()
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun specOf(intent: Intent): ua.faric.trackcatcher.core.MetaEdit.Spec =
        ua.faric.trackcatcher.core.MetaEdit.Spec.fromMap((intent.getSerializableExtra(EXTRA_SPEC) as? HashMap<String, String>).orEmpty())

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun planOf(intent: Intent): ua.faric.trackcatcher.core.TrimFade.Plan =
        ua.faric.trackcatcher.core.TrimFade.Plan.fromMap((intent.getSerializableExtra(EXTRA_SPEC) as? HashMap<String, String>).orEmpty())

    /** T79: обрізати / наростання / затухання. */
    private fun trim(ids: LongArray, plan: ua.faric.trackcatcher.core.TrimFade.Plan) {
        val app = applicationContext
        val db = HistoryDb.get(app)
        val problems = ArrayList<String>()
        var fixed = 0
        var skipped = 0
        var cancelled = false
        for ((i, id) in ids.withIndex()) {
            if (FlacBatch.cancel) { cancelled = true; break }
            val r = db.get(id) ?: continue
            val label = r.title.ifBlank { r.displayName }
            FlacBatch.update { it.copy(index = i + 1, label = label, percent = (i * 100) / ids.size) }
            notify("Обрізаю: ${i + 1} з ${ids.size}", label, (i * 100) / ids.size)
            WorkLog.step("робота", "Обрізаю: ${i + 1} з ${ids.size}: $label")
            if (Renumber.present(app, listOf(r)).isNullOrEmpty()) { skipped++; FlacBatch.update { it.copy(skipped = skipped) }; continue }
            TrimApply.progress = { f -> filePart(i, ids.size, "Обрізаю", label, f) }
            val bad = runCatching { TrimApply.apply(app, r, plan) }.getOrElse { listOf("${r.title}\t${ua.faric.trackcatcher.core.ErrorText.human(it)}") }
            TrimApply.progress = null
            if (bad.isEmpty()) { fixed++; FlacBatch.update { it.copy(made = fixed) } } else { problems += bad; FlacBatch.update { it.copy(problems = problems.toList()) } }
        }
        FlacBatch.update { it.copy(running = false, finished = true, made = fixed, problems = problems.toList(), cancelled = cancelled, index = ids.size, skipped = skipped) }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            notification("Обрізано: $fixed з ${ids.size}", if (problems.isEmpty()) TrimApply.describe(plan) else "Не вийшло: ${problems.size} — подробиці в застосунку", -1, ongoing = false),
        )
        worker = null
        stopSelf()
    }

    /** T88: перевірити на тріски — лише читає файли; підсумок — список «що перезаписати». */
    private fun holes(ids: LongArray) {
        val app = applicationContext
        val db = HistoryDb.get(app)
        val problems = ArrayList<String>()
        val report = ArrayList<String>()
        var checked = 0
        var skipped = 0
        var cancelled = false
        for ((i, id) in ids.withIndex()) {
            if (FlacBatch.cancel) { cancelled = true; break }
            val r = db.get(id) ?: continue
            val label = r.title.ifBlank { r.displayName }
            FlacBatch.update { it.copy(index = i + 1, label = label, percent = (i * 100) / ids.size) }
            notify("Шукаю тріски: ${i + 1} з ${ids.size}", label, (i * 100) / ids.size)
            WorkLog.step("робота", "Шукаю тріски: ${i + 1} з ${ids.size}: $label")
            if (!HoleScan.canCheck(r)) { problems += "$label\t${HoleScan.OnlyMp3().message}"; continue }
            if (Renumber.present(app, listOf(r)).isNullOrEmpty()) { skipped++; FlacBatch.update { it.copy(skipped = skipped) }; continue }
            try {
                TrimApply.progress = { f -> filePart(i, ids.size, "Шукаю тріски", label, f) }
                val places = try { HoleScan.check(app, r) } finally { TrimApply.progress = null }
                checked++
                if (places.isNotEmpty()) report += HoleScan.line(r, places)
                FlacBatch.update { it.copy(made = report.size, report = report.toList()) }
            } catch (e: Exception) {
                problems += "$label\t${ua.faric.trackcatcher.core.ErrorText.human(e)}"
            }
            FlacBatch.update { it.copy(problems = problems.toList()) }
        }
        FlacBatch.update { it.copy(running = false, finished = true, made = report.size, report = report.toList(), problems = problems.toList(), cancelled = cancelled, index = checked, skipped = skipped) }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        val title = if (report.isEmpty()) "Тріск нема ($checked перевірено)" else "Тріски: ${report.size} з $checked — перезаписати"
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            notification(title, "Файли не змінювались — лише перевірка", -1, ongoing = false),
        )
        worker = null
        stopSelf()
    }

    /** T78: теги й імена файлів. */
    private fun editMeta(ids: LongArray, spec: ua.faric.trackcatcher.core.MetaEdit.Spec) {
        val app = applicationContext
        val db = HistoryDb.get(app)
        val plain = Settings(app).snapshot().plainNames
        val problems = ArrayList<String>()
        var fixed = 0
        var skipped = 0
        var cancelled = false
        for ((i, id) in ids.withIndex()) {
            if (FlacBatch.cancel) { cancelled = true; break }
            val r = db.get(id) ?: continue
            val label = r.title.ifBlank { r.displayName }
            FlacBatch.update { it.copy(index = i + 1, label = label, percent = (i * 100) / ids.size) }
            notify("Змінюю теги: ${i + 1} з ${ids.size}", label, (i * 100) / ids.size)
            WorkLog.step("робота", "Змінюю теги: ${i + 1} з ${ids.size}: $label")
            if (Renumber.present(app, listOf(r)).isNullOrEmpty()) { skipped++; FlacBatch.update { it.copy(skipped = skipped) }; continue }
            val bad = runCatching { MetaApply.apply(app, r, spec, plain) }.getOrElse { listOf("${r.title}\t${ua.faric.trackcatcher.core.ErrorText.human(it)}") }
            if (bad.isEmpty()) { fixed++; FlacBatch.update { it.copy(made = fixed) } } else { problems += bad; FlacBatch.update { it.copy(problems = problems.toList()) } }
        }
        FlacBatch.update { it.copy(running = false, finished = true, made = fixed, problems = problems.toList(), cancelled = cancelled, index = ids.size, skipped = skipped) }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            notification("Теги змінено: $fixed з ${ids.size}", if (problems.isEmpty()) "Звук не змінювався — лише теги й імена" else "Не вийшло: ${problems.size} — подробиці в застосунку", -1, ongoing = false),
        )
        worker = null
        stopSelf()
    }

    /** T93: поступ усередині пісні — у вікні (смужка) і в шторці (загальний, з урахуванням частини пісні). */
    private fun filePart(i: Int, total: Int, what: String, label: String, f: Float) {
        val pct = (f * 100).toInt()
        FlacBatch.update { it.copy(filePercent = pct) }
        notify("$what: ${i + 1} з $total · $pct %", label, ((i + f) * 100 / maxOf(1, total)).toInt())
    }

        private fun enter(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, n)
    }

    private var lastShown = 0L

    private fun notify(title: String, text: String, percent: Int) {
        WorkLog.alive("$title · $text")
        val now = System.currentTimeMillis()
        if (now - lastShown < 700) return
        lastShown = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title, text, percent, ongoing = true))
    }

    private fun notification(title: String, text: String, percent: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 31, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_TAB, "HISTORY"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, TrackCatcherApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (ongoing) {
            if (percent >= 0) b.setProgress(100, percent, false)
            val stop = PendingIntent.getService(
                this, 32, Intent(this, FlacBatchService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_stop), "Зупинити", stop).build())
        }
        return b.build()
    }

    override fun onDestroy() {
        // Служба закривається, а робота не дійшла до кінця — не лишати в шторці «вічне» «Перевіряю…».
        if (worker != null) {
            WorkLog.w("робота", "служба закривається, а робота не скінчилась; останній крок: ${WorkLog.stepWhat}")
            runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        }
        super.onDestroy()
    }

    companion object {
        /** Після старту процесу: незакінчене (ongoing) повідомлення роботи — від минулого, уже вбитого процесу. */
        fun clearStale(context: android.content.Context) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.activeNotifications.firstOrNull { it.id == NOTIF_ID && (it.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 }?.let {
                    WorkLog.w("старт", "у шторці лишилось від минулого процесу: «${it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)}» — прибрано")
                    nm.cancel(NOTIF_ID)
                }
            }
        }

        const val EXTRA_IDS = "ids"
        const val EXTRA_MODE = "mode"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_TAIL = "tail"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_SPEC = "spec"
        private const val ACTION_STOP = "ua.faric.trackcatcher.flac.STOP"
        private const val NOTIF_ID = 3
    }
}
