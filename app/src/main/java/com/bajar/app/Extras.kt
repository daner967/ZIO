package com.bajar.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** Música de fondo (assets/bgm.mp3) a volumen bajo. Solo suena mientras la app está a la vista. */
object Bgm {
    private var mp: MediaPlayer? = null
    @Volatile private var wanted = false
    private const val VOL = 0.22f

    fun set(ctx: Context, on: Boolean) {
        wanted = on
        if (on) start(ctx) else stop()
    }

    fun resume(ctx: Context) { if (wanted) start(ctx) }

    fun pause() {
        try { mp?.let { if (it.isPlaying) it.pause() } } catch (e: Exception) { }
    }

    fun stop() {
        try { mp?.release() } catch (e: Exception) { }
        mp = null
    }

    private fun build(ctx: Context, fromFile: Boolean): MediaPlayer {
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        if (fromFile) {
            // plan B: se copia una vez a la memoria de la app
            val f = File(ctx.cacheDir, "bgm.mp3")
            if (!f.exists()) ctx.assets.open("bgm.mp3").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            p.setDataSource(f.absolutePath)
        } else {
            ctx.assets.openFd("bgm.mp3").use { fd -> p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        }
        return p
    }

    private fun start(ctx: Context) {
        val cur = mp
        if (cur != null) {
            try { if (!cur.isPlaying) cur.start() } catch (e: Exception) { }
            return
        }
        try {
            val p = try { build(ctx, false) } catch (e: Exception) { build(ctx, true) }
            p.isLooping = true
            p.setVolume(VOL, VOL)
            p.setOnPreparedListener { if (wanted) it.start() }
            p.setOnErrorListener { m, _, _ ->
                try { m.release() } catch (e: Exception) { }
                if (mp === m) mp = null
                true
            }
            p.prepareAsync()
            mp = p
        } catch (e: Exception) { }
    }
}

/** Cambia el icono de la app en el cajón de aplicaciones según el tema (usa "alias" del manifiesto). */
object Icons {
    private val ALIAS = mapOf(
        "purple" to "IconPurple", "blackpurple" to "IconBp", "black" to "IconBlack",
        "white" to "IconWhite", "green" to "IconGreen", "red" to "IconRed", "xmas" to "IconXmas"
    )

    fun set(ctx: Context, theme: String) {
        val pm = ctx.packageManager
        val target = ALIAS[theme] ?: "IconPurple"
        fun cn(n: String) = ComponentName(ctx.packageName, "com.bajar.app.$n")
        // primero se activa el nuevo y después se apagan los demás, para que nunca falte un icono
        pm.setComponentEnabledSetting(cn(target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (n in ALIAS.values) {
            if (n != target) {
                pm.setComponentEnabledSetting(cn(n), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
