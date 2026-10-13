package ua.faric.trackcatcher.state

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import ua.faric.trackcatcher.core.RecStatus
import ua.faric.trackcatcher.core.TrackMeta

/** Стан запису для екранів. Живе окремо від Activity — поворот екрана нічого не перериває. */
object RecorderHub {
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
        val error: String? = null,
        val recent: List<Result> = emptyList(),
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
