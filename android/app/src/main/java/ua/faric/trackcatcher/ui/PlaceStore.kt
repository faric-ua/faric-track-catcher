package ua.faric.trackcatcher.ui

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.util.Base64
import ua.faric.trackcatcher.BuildConfig

/**
 * Місце в застосунку на диску (faric 21.10 01:17: «кешування — щоб, коли між програмами переходиш, повертатися на те
 * місце, звідки пішов»). Поворот екрана Android переживає сам, а от коли він закриває застосунок у фоні (на M30s —
 * часто), новий запуск починався з «Запису». Тепер — та сама вкладка, група, пошук, прокрутка й відкрите вікно.
 * Лише на 12 год і лише для тієї самої збірки (інакше — як нове відкриття).
 */
object PlaceStore {
    private const val PREFS = "place"
    private const val KEY_DATA = "data"
    private const val KEY_AT = "at"
    private const val KEY_BUILD = "build"
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    fun save(context: Context, place: Bundle) {
        val p = Parcel.obtain()
        try {
            p.writeBundle(place)
            val data = Base64.encodeToString(p.marshall(), Base64.NO_WRAP)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATA, data).putLong(KEY_AT, System.currentTimeMillis()).putInt(KEY_BUILD, BuildConfig.VERSION_CODE).apply()
        } finally {
            p.recycle()
        }
    }

    fun load(context: Context): Bundle? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        if (System.currentTimeMillis() - at > MAX_AGE_MS || prefs.getInt(KEY_BUILD, 0) != BuildConfig.VERSION_CODE) return null
        val data = prefs.getString(KEY_DATA, null) ?: return null
        val p = Parcel.obtain()
        return try {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            p.unmarshall(bytes, 0, bytes.size)
            p.setDataPosition(0)
            p.readBundle(PlaceStore::class.java.classLoader)
        } catch (e: Exception) {
            null
        } finally {
            p.recycle()
        }
    }
}
