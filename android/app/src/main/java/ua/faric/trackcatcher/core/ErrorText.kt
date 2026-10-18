package ua.faric.trackcatcher.core

import java.io.FileNotFoundException

/**
 * Помилка словами для людини (faric 18.10 15:26: «написав дуже багато всього цікавого» — у підсумку «Перевірити номери»
 * були десятки рядків «Failed to determine if … is child of …: java.io.FileNotFoundException: Missing file for …»).
 */
object ErrorText {
    const val MISSING = "файла нема там, де його пам'ятає історія — його перенесли, перейменували чи видалили. Нічого не змінено"

    fun human(e: Throwable): String {
        var c: Throwable? = e
        while (c != null) {
            val m = c.message.orEmpty()
            if (c is FileNotFoundException || m.contains("FileNotFoundException") || m.contains("Missing file") || m.contains("No such file")) return MISSING
            if (c is SecurityException || m.contains("Permission Denial")) return "Android не дав доступу до файлу (своя папка: дозвіл могли забрати — «Налаштування» → «Куди зберігається»). Нічого не змінено"
            c = c.cause
        }
        return e.message?.takeIf { it.isNotBlank() }?.let { if (it.length > 200) it.take(200) + "…" else it } ?: e.javaClass.simpleName
    }
}
