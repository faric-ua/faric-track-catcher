package ua.faric.trackcatcher.capture

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import ua.faric.trackcatcher.data.Settings
import ua.faric.trackcatcher.state.RecorderHub

/** Черга збереження: по одному шматку, поки запис іде далі. */
class FinisherQueue(context: Context, private val onIdle: () -> Unit) {
    private val app = context.applicationContext
    private val finisher = SegmentFinisher(context)
    private val pending = AtomicInteger(0)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "track-catcher-save").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    val busy: Boolean get() = pending.get() > 0

    fun submit(job: SegmentJob, settings: Settings.Values) {
        pending.incrementAndGet()
        RecorderHub.update { it.copy(queued = it.queued + 1) }
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
