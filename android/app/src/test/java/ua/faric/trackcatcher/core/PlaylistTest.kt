package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Плеєр у подробицях: ⏮ / ⏭ між записами (faric 15.10 15:36). */
class PlaylistTest {
    // Як у історії: новіші згори. 30 — BONUS 03 (0:58), 20 — BONUS 03 (0:38), 15 — пропущено (без файлу), 10 — BONUS 02.
    private val list = listOf(30L, 20L, 15L, 10L)
    private val files = setOf(30L, 20L, 10L)
    private fun n(current: Long, next: Boolean) = Playlist.neighbor(list, { it in files }, current, next)

    @Test
    fun nextIsRecordedLater() {
        assertEquals(30L, n(20, next = true))
        assertEquals(20L, n(10, next = true)) // 15 без файлу — перескок
    }

    @Test
    fun previousIsRecordedEarlier() {
        assertEquals(20L, n(30, next = false))
        assertEquals(10L, n(20, next = false)) // перескок через 15
    }

    @Test
    fun edges() {
        assertNull(n(30, next = true)) // найновіший — далі нічого
        assertNull(n(10, next = false)) // найстаріший
        assertNull(n(99, next = true)) // запису нема в списку (інший фільтр)
    }

    @Test
    fun currentWithoutFileStillHasNeighbours() {
        // Відкрито подробиці пропущеного запису: стрілки ведуть до найближчих з файлом.
        assertEquals(20L, n(15, next = true))
        assertEquals(10L, n(15, next = false))
    }

    @Test
    fun emptyAndSingle() {
        assertNull(Playlist.neighbor(emptyList(), { true }, 1, true))
        assertNull(Playlist.neighbor(listOf(1L), { true }, 1, false))
    }
}
