package ua.faric.trackcatcher.capture

import android.content.Context
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import ua.faric.trackcatcher.data.Settings
import ua.faric.trackcatcher.state.RecorderHub

/**
 * Черга збереження: по одному шматку, поки запис іде далі.
 * [hold] (T85): шматки лише чекають у черзі, нічого не стискається — поки не [release] (натиснуто «Стоп»).
 * Самі шматки лежать на диску з описом (`.json`), тож якщо застосунок закриють посеред запису — їх збереже відновлення.
 */
class FinisherQueue(context: Context, private val onIdle: () -> Unit) {
    private val app = context.applicationContext
    private val finisher = SegmentFinisher(context)
    private val pending = AtomicInteger(0)
    private val held = ConcurrentLinkedQueue<Pair<SegmentJob, Settings.Values>>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "track-catcher-save").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Чекати зі стисканням до [release] (T85). */
    @Volatile var hold: Boolean = false

    val busy: Boolean get() = pending.get() > 0

    /** Скільки пісень чекає «Стоп» (T85). */
    val waiting: Int get() = held.size

    fun submit(job: SegmentJob, settings: Settings.Values) {
        pending.incrementAndGet()
        RecorderHub.update { it.copy(queued = it.queued + 1) }
        if (hold) held.add(job to settings) else start(job, settings)
        // «Стоп» міг прийти саме зараз — не лишити шматок у черзі назавжди.
        if (!hold) drain()
    }

    /** Почати стискати все, що чекало (T85: після «Стоп»). */
    fun release() {
        hold = false
        drain()
    }

    private fun drain() {
        while (true) {
            val (job, settings) = held.poll() ?: break
            start(job, settings)
        }
    }

    private fun start(job: SegmentJob, settings: Settings.Values) {
        executor.execute {
            val row = try {
                finisher.finish(job, settings)
            } catch (t: Throwable) {
                finisher.failure(job, t)
            }
            RecorderHub.addResult(RecorderHub.Result(row.displayName, row.status, row.reason))
            ua.faric.trackcatcher.data.AutoBackup.request(app) // T67: нічого не робить, якщо автокопію не ввімкнено
            if (pending.decrementAndGet() == 0) onIdle()
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
