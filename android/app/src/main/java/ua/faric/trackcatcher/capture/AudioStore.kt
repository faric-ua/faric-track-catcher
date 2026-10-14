package ua.faric.trackcatcher.capture

import java.io.OutputStream

/** Куди класти готові пісні: «Музика» (MediaStore) або своя папка, напр. на карті пам'яті (T29). */
interface AudioStore {
    /** [dir] — шлях як у «Музиці»: «Music/Track Catcher/Альбом/». */
    fun exists(name: String, dir: String): Boolean

    fun write(name: String, mime: String, dir: String, body: (OutputStream) -> Unit): MediaStoreWriter.Saved
}
