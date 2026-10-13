package ua.faric.trackcatcher.capture

import java.io.File
import org.json.JSONObject
import ua.faric.trackcatcher.core.EndReason
import ua.faric.trackcatcher.core.TrackMeta

/** Готовий шматок звуку, що чекає збереження у файл. */
data class SegmentJob(
    val pcmFile: File,
    val sidecar: File,
    val sessionId: Long,
    val index: Int,
    val meta: TrackMeta,
    val startPositionMs: Long?,
    val endReason: EndReason,
    val cutBySilence: Boolean,
    val closedAt: Long,
    val recovered: Boolean = false,
) {
    companion object {
        fun sidecarJson(sessionId: Long, index: Int, meta: TrackMeta, positionMs: Long?, endReason: EndReason?, cutBySilence: Boolean): String =
            JSONObject().apply {
                put("sessionId", sessionId)
                put("index", index)
                put("artist", meta.artist)
                put("title", meta.title)
                put("album", meta.album)
                put("durationMs", meta.durationMs)
                put("sourcePackage", meta.sourcePackage)
                put("artworkPath", meta.artworkPath ?: "")
                put("positionMs", positionMs ?: -1L)
                put("closed", endReason != null)
                put("endReason", endReason?.name ?: "")
                put("cutBySilence", cutBySilence)
            }.toString()

        /** Відновлення після закриття застосунку посеред запису. */
        fun fromSidecar(sidecar: File): SegmentJob? = try {
            val j = JSONObject(sidecar.readText())
            val pcm = File(sidecar.parentFile, sidecar.name.removeSuffix(".json") + ".pcm")
            if (!pcm.isFile) {
                null
            } else {
                val meta = TrackMeta(
                    artist = j.optString("artist"),
                    title = j.optString("title"),
                    album = j.optString("album"),
                    durationMs = j.optLong("durationMs"),
                    sourcePackage = j.optString("sourcePackage"),
                    artworkPath = j.optString("artworkPath").ifBlank { null },
                )
                val closed = j.optBoolean("closed")
                SegmentJob(
                    pcmFile = pcm,
                    sidecar = sidecar,
                    sessionId = j.optLong("sessionId"),
                    index = j.optInt("index"),
                    meta = meta,
                    startPositionMs = j.optLong("positionMs", -1L).takeIf { it >= 0 },
                    endReason = if (closed) EndReason.valueOf(j.optString("endReason", "STOP").ifBlank { "STOP" }) else EndReason.STOP,
                    cutBySilence = j.optBoolean("cutBySilence"),
                    closedAt = pcm.lastModified(),
                    recovered = !closed,
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
