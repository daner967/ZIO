package com.bajar.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var ready = false
    @Volatile private var cancelled = false
    @Volatile private var version = ""
    @Volatile private var shared = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        takeShare(intent)
        pool.execute { startEngine() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
    }

    // Enlaces compartidos desde la app de YouTube ("Compartir" > Bajar)
    private fun takeShare(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        shared = Regex("https?://\\S+").find(t)?.value ?: return
        if (::web.isInitialized) web.post { web.evaluateJavascript("if(window.takeShared)takeShared()", null) }
    }

    private fun emit(t: String, m: String = "", p: Float = 0f, n: String = "") {
        val o = JSONObject().put("t", t).put("m", m).put("p", p.toDouble()).put("n", n)
        runOnUiThread { web.evaluateJavascript("onEvent($o)", null) }
    }

    private fun startEngine() {
        try {
            YoutubeDL.getInstance().init(application)
            FFmpeg.getInstance().init(application)
            ready = true
            version = YoutubeDL.getInstance().version(application) ?: ""
            emit("ready", version)
        } catch (e: Exception) {
            emit("error", "No se pudo iniciar el motor: ${e.message}")
            return
        }
        refresh(false)
    }

    // yt-dlp se rompe seguido cuando YouTube cambia: se actualiza al abrir la app
    private fun refresh(show: Boolean) {
        try {
            YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.STABLE)
            version = YoutubeDL.getInstance().version(application) ?: version
            emit("ready", version)
            if (show) emit("info", "Motor actualizado")
        } catch (e: Exception) {
            if (show) emit("error", "No se pudo actualizar: ${e.message}")
        }
    }

    private fun download(url: String, mode: String, o: JSONObject) {
        if (cancelled) { emit("cancelled"); return }
        if (!ready) { emit("error", "El motor sigue iniciando. Inténtalo en unos segundos."); return }
        runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val work = File(cacheDir, "w${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // Opciones elegidas en la pantalla de descarga (solo valores conocidos)
            val vq = o.optString("vq").takeIf { it in setOf("max", "1080", "720", "480", "360") } ?: "720"
            val af = o.optString("af").takeIf { it in setOf("mp3", "m4a", "opus", "flac", "wav") } ?: "mp3"
            val aq = o.optString("aq").takeIf { it in setOf("best", "med", "low") } ?: "best"

            val r = YoutubeDLRequest(url)
            r.addOption("--windows-filenames")
            r.addOption("--no-mtime")
            r.addOption("--write-thumbnail") // la miniatura se guarda como imagen .jpg
            r.addOption("--convert-thumbnails", "jpg")
            val dir = work.absolutePath
            if (mode == "video") {
                r.addOption("--no-playlist")
                r.addOption("-f", "bv*+ba/b")
                r.addOption("-S", if (vq == "max") "res,ext" else "res:$vq,ext")
                r.addOption("--merge-output-format", "mp4")
                r.addOption("-o", "$dir/%(title).100s.%(ext)s")
            } else { // audio suelto o lista (solo audio, máximo 25, todo en un ZIP)
                if (mode == "list") {
                    r.addOption("--yes-playlist")
                    r.addOption("--playlist-end", "25")
                    r.addOption("--ignore-errors")
                    r.addOption("-o", "$dir/%(playlist_title).80s/%(playlist_index)02d - %(title).100s.%(ext)s")
                } else {
                    r.addOption("--no-playlist")
                    r.addOption("-o", "$dir/%(title).100s.%(ext)s")
                }
                val cap = when (aq) { "med" -> "[abr<=140]"; "low" -> "[abr<=75]"; else -> "" }
                r.addOption("-f", "ba$cap/ba/b")
                r.addOption("-x")
                r.addOption("--audio-format", af)
                if (af == "mp3") {
                    r.addOption("--audio-quality", when (aq) { "med" -> "128K"; "low" -> "64K"; else -> "0" })
                    r.addOption("--embed-thumbnail") // en MP3 la miniatura también queda como portada
                }
            }

            var err: String? = null
            try {
                YoutubeDL.getInstance().execute(r, "dl") { p, _, line -> emit("progress", "$line", p) }
            } catch (e: Exception) {
                err = e.message
            }
            if (cancelled) { emit("cancelled"); return }

            val media = setOf("mp3", "m4a", "opus", "flac", "wav", "mp4", "mkv", "webm")
            val imgs = setOf("jpg", "jpeg", "png", "webp")
            val all = work.walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()
            val files = all.filter { it.extension.lowercase() in media }
            val thumbs = all.filter { it.extension.lowercase() in imgs }
            if (files.isEmpty()) { emit("error", nice(err)); return }

            // Todo va a Download/ZIO, con audios y videos en subcarpetas separadas
            val folder = if (mode == "video") "Download/ZIO/Videos" else "Download/ZIO/Audios"

            if (mode == "list") {
                val title = work.listFiles { f -> f.isDirectory }?.firstOrNull()?.name ?: "playlist"
                val zip = File(work, "$title.zip")
                ZipOutputStream(zip.outputStream().buffered()).use { z ->
                    z.setLevel(0)
                    (files + thumbs).forEach { f ->
                        z.putNextEntry(ZipEntry("$title/${f.name}"))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
                val extra = if (err != null) ", algunas fallaron" else ""
                emit("done", save(zip, "application/zip", folder), n = "${zip.name} (${files.size} canciones$extra)")
            } else {
                val f = files.first()
                save(f, mimeOf(f), folder)
                thumbs.forEach { save(it, mimeOf(it), folder) }
                emit("done", folder, n = if (thumbs.isEmpty()) f.name else "${f.name} + miniatura")
            }
        } catch (e: Exception) {
            emit("error", nice(e.message))
        } finally {
            work.deleteRecursively()
            runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun save(f: File, mime: String, dir: String): String {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("No se pudo crear el archivo")
        contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        v.clear()
        v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(u, v, null, null)
        return dir
    }

    private fun nice(m: String?): String {
        val lines = m.orEmpty().lines()
        val l = lines.lastOrNull { it.contains("ERROR") } ?: lines.lastOrNull { it.isNotBlank() } ?: "Error desconocido"
        return l.removePrefix("ERROR: ").trim().take(220)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.goBack?goBack():false") { r -> if (r != "true") finish() }
    }

    inner class Bridge {
        @JavascriptInterface fun start(url: String, mode: String, opts: String) {
            cancelled = false
            pool.execute { download(url, mode, JSONObject(opts)) }
        }
        @JavascriptInterface fun cancel() {
            cancelled = true
            YoutubeDL.getInstance().destroyProcessById("dl")
        }
        @JavascriptInterface fun update() { pool.execute { refresh(true) } }
        @JavascriptInterface fun ver(): String = version
        @JavascriptInterface fun pending(): String { val s = shared; shared = ""; return s }
        @JavascriptInterface fun clip(): String {
            val c = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            return c.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }
    }
}
