package ua.faric.trackcatcher.capture

import android.app.ActivityManager
import android.content.Context
import ua.faric.trackcatcher.core.EncodeTiming

/** Скільки пам'яті в телефоні — для «Коли стискати» (T85). */
object DeviceRam {
    fun total(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    } catch (e: Exception) {
        0L
    }

    fun lowRam(context: Context): Boolean = try {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    } catch (e: Exception) {
        false
    }

    fun deferEncoding(context: Context, timing: EncodeTiming): Boolean = EncodeTiming.defer(timing, total(context), lowRam(context))
}
