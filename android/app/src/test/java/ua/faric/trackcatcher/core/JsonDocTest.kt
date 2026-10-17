package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class JsonDocTest {
    @Test fun roundTripWithUkrainianAndEmoji() {
        val v = linkedMapOf(
            "title" to "🎵 TRACK 02 — VELVET TEMPLE. 🏛",
            "artist" to "Saney \"лапки\" \\ слеш\nрядок",
            "created" to 1792101646746L,
            "size" to 0L,
            "gain" to -3.25,
            "ok" to true,
            "art" to null,
            "list" to listOf(1L, "два", false),
        )
        assertEquals(v, JsonDoc.parse(JsonDoc.write(v)))
    }

    @Test fun readsSpacesAndUnicodeEscapes() {
        val v = JsonDoc.parse(" { \"a\" : [ 1 , 2.5 , \"\\u0456\\ud83c\\udfb5\" ] } ") as Map<*, *>
        assertEquals(listOf(1L, 2.5, "і🎵"), v["a"])
    }

    @Test fun brokenJsonExplains() {
        for (bad in listOf("{\"a\":", "[1,2", "{\"a\" 1}", "tru", "{} x")) {
            try {
                JsonDoc.parse(bad)
                fail("мало впасти: $bad")
            } catch (e: JsonDoc.ParseException) {
                // добре
            }
        }
    }
}
