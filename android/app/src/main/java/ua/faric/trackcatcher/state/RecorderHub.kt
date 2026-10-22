package ua.faric.trackcatcher.state

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import ua.faric.trackcatcher.core.RecStatus
import ua.faric.trackcatcher.core.TrackMeta

/** Стан запису для екранів. Живе окремо від Activity — поворот екрана нічого не перериває. */
object RecorderHub {
    /** Відкрито «Нічний екран» (T85): рівні звуку не малюються — запис бере менше процесора. */
    @Volatile var quietUi: Boolean = false

    enum class Phase { IDLE, RECORDING, FINISHING }

    data class Result(val name: String, val status: RecStatus, val reason: String)

    data class State(
        val phase: Phase = Phase.IDLE,
        val sessionId: Long = 0,
        val startedAt: Long = 0,
        val meta: TrackMeta? = null,
        val playing: Boolean = false,
        val levelL: Float = 0f,
        val levelR: Float = 0f,
        val tracks: Int = 0,
        val complete: Int = 0,
        val partial: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val queued: Int = 0,
        val warning: String? = null,
        /** Діагностика звуку: чи надходить і якого рівня (видно під індикатором). */
        val diag: String? = null,
        val error: String? = null,
        val recent: List<Result> = emptyList(),
        /** «Зупинитись після N пісень»: N (0 — без обмеження) і скільки пісень уже почалось. */
        val limit: Int = 0,
        val songs: Int = 0,
        /** «Зупинитись через N хвилин» (T60): N (0 — без обмеження) і скільки звуку вже записано, мс. */
        val limitMin: Int = 0,
        val recordedMs: Long = 0L,
        /** Спокійне повідомлення після запису (напр. «Записано 26 пісень — зупинено»). */
        val notice: String? = null,
        /** T98: «Дочекатись кінця пісні» — запис зупиниться, щойно почнеться наступна (або плеєр стане). */
        val stopAfterSong: Boolean = false,
    ) {
        val saved: Int get() = complete + partial
    }

    @Volatile
    var state = State()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Synchronized
    fun update(change: (State) -> State) {
        state = change(state)
        val snapshot = state
        main.post { listeners.forEach { it(snapshot) } }
    }

    fun addResult(result: Result) = update { s ->
        s.copy(
            queued = maxOf(0, s.queued - 1),
            complete = s.complete + if (result.status == RecStatus.COMPLETE) 1 else 0,
            partial = s.partial + if (result.status == RecStatus.PARTIAL) 1 else 0,
            failed = s.failed + if (result.status == RecStatus.FAILED) 1 else 0,
            skipped = s.skipped + if (result.status == RecStatus.SKIPPED) 1 else 0,
            recent = (listOf(result) + s.recent).take(8),
        )
    }

    /** Підписка (головний потік). Одразу віддає поточний стан. Повертає «відписатись». */
    fun observe(listener: (State) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners -= listener }
    }
}
