package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupsTest {
    @Test fun albumsInOrderOfNewest() {
        // Історія — новіші згори: Neon 3, Lanes 2, Neon 1.
        val rows = listOf("Neon" to 3, "Lanes" to 2, "neon " to 1)
        val g = Playlist.groupInOrder(rows) { Playlist.groupKey(it.first) }
        assertEquals(listOf("neon", "lanes"), g.map { it.first })
        assertEquals(listOf(3, 1), g[0].second.map { it.second })
    }

    @Test fun emptyNameIsOwnGroup() {
        val g = Playlist.groupInOrder(listOf("", "A", " ")) { Playlist.groupKey(it) }
        assertEquals(listOf("", "a"), g.map { it.first })
        assertEquals(2, g[0].second.size)
    }
}
