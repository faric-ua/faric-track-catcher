package ua.faric.trackcatcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import ua.faric.trackcatcher.capture.NowPlayingTracker

/** Що треба дозволити, щоб записувати. */
object Perms {
    fun recordAudio(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun nowPlaying(ctx: Context) = NowPlayingTracker.hasAccess(ctx)

    fun battery(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun allReady(ctx: Context) = recordAudio(ctx) && notifications(ctx) && nowPlaying(ctx) && battery(ctx)

    fun runtimeToRequest(ctx: Context): Array<String> = buildList {
        if (!recordAudio(ctx)) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !notifications(ctx)) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
