package com.bajar.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

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
            emit("error", "e_init_fail|${e.message}")
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
            if (show) emit("info", "i_updated")
        } catch (e: Exception) {
            if (show) emit("error", "e_upd_fail|${e.message}")
        }
    }

    private fun download(url: String, mode: String, o: JSONObject) {
        if (cancelled) { emit("cancelled"); return }
        if (!ready) { emit("error", "e_init_wait"); return }
        runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val work = File(cacheDir, "w${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // Opciones elegidas en la pantalla de descarga (solo valores conocidos)
            val vq = o.optString("vq").takeIf { it in setOf("max", "1080", "720", "480", "360") } ?: "720"
            val af = o.optString("af").takeIf { it in setOf("mp3", "m4a", "opus", "flac", "wav") } ?: "mp3"
            val aq = o.optString("aq").takeIf { it in setOf("best", "med", "low") } ?: "best"
            val en = o.optString("lang") == "en"
            val isList = o.optBoolean("list")
            val thumb = o.optBoolean("thumb", true) // guardar también la miniatura como imagen aparte
            val video = mode == "video"
            val dir = work.absolutePath

            val r = YoutubeDLRequest(url)
            r.addOption("--windows-filenames")
            r.addOption("--no-mtime")
            r.addOption("--convert-thumbnails", "jpg")
            if (thumb) r.addOption("--write-thumbnail")                  // imagen aparte
            if (video || af != "wav") r.addOption("--embed-thumbnail")   // portada dentro del archivo
            if (isList) {
                r.addOption("--yes-playlist")
                r.addOption("--ignore-errors")
                r.addOption("-o", "$dir/%(playlist_title).80s/%(playlist_index)02d - %(title).100s.%(ext)s")
            } else {
                r.addOption("--no-playlist")
                r.addOption("-o", "$dir/%(title).100s.%(ext)s")
            }
            if (video) {
                r.addOption("-f", "bv*+ba/b")
                r.addOption("-S", if (vq == "max") "res,ext" else "res:$vq,ext")
                r.addOption("--merge-output-format", "mp4")
            } else {
                val cap = when (aq) { "med" -> "[abr<=140]"; "low" -> "[abr<=75]"; else -> "" }
                r.addOption("-f", "ba$cap/ba/b")
                r.addOption("-x")
                r.addOption("--audio-format", af)
                if (af == "mp3") r.addOption("--audio-quality", when (aq) { "med" -> "128K"; "low" -> "64K"; else -> "0" })
            }

            // Todo va a Download/ZIO, con audios y videos en subcarpetas separadas
            val folder = if (video) "Download/ZIO/Videos" else "Download/ZIO/Audios"
            val media = setOf("mp3", "m4a", "opus", "flac", "wav", "mp4", "mkv", "webm")
            val imgs = setOf("jpg", "jpeg", "png", "webp")
            val tmpName = Regex("\\.f\\d+\\.[a-z0-9]+$|\\.temp\\.[a-z0-9]+$") // restos intermedios de yt-dlp
            val sent = HashSet<String>()
            var savedCount = 0
            var totalItems = 0
            var playlistName = ""

            // Listas: cada canción/video se guarda en cuanto termina, para no llenar la memoria del teléfono
            fun flush(prefix: String?) {
                val d = work.listFiles { f -> f.isDirectory }?.firstOrNull() ?: return
                playlistName = d.name
                val dest = "$folder/${d.name}"
                val fs = d.listFiles { f -> f.isFile && (prefix == null || f.name.startsWith(prefix)) }
                    .orEmpty().sortedBy { it.name }
                for (f in fs) {
                    if (tmpName.containsMatchIn(f.name)) continue
                    val ext = f.extension.lowercase()
                    val isMedia = ext in media
                    val isImg = ext in imgs
                    if (!isMedia && !(isImg && thumb)) { if (isImg) f.delete(); continue }
                    if (isMedia) emit("progress", "[saving]", 100f)
                    save(f, mimeOf(f), dest)
                    if (isMedia) savedCount++
                    f.delete()
                }
            }

            var err: String? = null
            try {
                YoutubeDL.getInstance().execute(r, "dl") { p, _, line ->
                    val ln = "$line"
                    if (isList) {
                        val m = Regex("Downloading item (\\d+) of (\\d+)").find(ln)
                        if (m != null) {
                            val n = m.groupValues[1].toInt()
                            totalItems = m.groupValues[2].toInt()
                            if (n > 1) flush("%02d - ".format(n - 1))
                        }
                    }
                    // al empezar cada descarga se envía la miniatura y el título a la ventana de progreso
                    if (ln.startsWith("[download] Destination:")) meta(work, ln, sent)
                    emit("progress", ln, p)
                }
            } catch (e: Exception) {
                err = e.message
            }
            if (cancelled) { emit("cancelled"); return }

            val coverFail = err?.let { it.contains("thumbnail", true) || it.contains("mutagen", true) || it.contains("embed", true) } == true
            val noCover = if (coverFail) (if (en) " · cover not embedded" else " · portada no incrustada") else ""

            if (isList) {
                flush(null)
                if (savedCount == 0) { emit("error", nice(err)); return }
                val what = if (video) "videos" else (if (en) "songs" else "canciones")
                val failed = if (totalItems > savedCount) (if (en) ", ${totalItems - savedCount} failed" else ", ${totalItems - savedCount} fallaron") else ""
                emit("done", "$folder/$playlistName", n = "$playlistName ($savedCount $what$failed)$noCover")
            } else {
                val all = work.walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()
                val files = all.filter { it.extension.lowercase() in media && !tmpName.containsMatchIn(it.name) }
                val thumbs = if (thumb) all.filter { it.extension.lowercase() in imgs } else emptyList()
                if (files.isEmpty()) { emit("error", nice(err)); return }
                val f = files.first()
                save(f, mimeOf(f), folder)
                thumbs.forEach { save(it, mimeOf(it), folder) }
                val extra = if (thumbs.isEmpty()) "" else (if (en) " + thumbnail" else " + miniatura")
                emit("done", folder, n = f.name + extra + noCover)
            }
        } catch (e: Exception) {
            emit("error", nice(e.message))
        } finally {
            work.deleteRecursively()
            runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    private fun meta(work: File, line: String, sent: MutableSet<String>) {
        try {
            val path = line.substringAfter("Destination:").trim()
            val title = File(path).nameWithoutExtension
                .replace(Regex("\\.f\\d+$"), "").replace(Regex("^\\d+ - "), "")
            val img = work.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") && it.path !in sent }
                .maxByOrNull { it.lastModified() }
            var data = ""
            if (img != null && img.length() in 1..2_500_000) {
                sent.add(img.path)
                val mime = when (img.extension.lowercase()) { "webp" -> "image/webp"; "png" -> "image/png"; else -> "image/jpeg" }
                data = "data:$mime;base64," + Base64.encodeToString(img.readBytes(), Base64.NO_WRAP)
            }
            emit("meta", title, 0f, data)
        } catch (e: Exception) {
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
            ?: throw IllegalStateException("e_create")
        contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        v.clear()
        v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(u, v, null, null)
        return dir
    }

    private fun nice(m: String?): String {
        val lines = m.orEmpty().lines()
        val l = lines.lastOrNull { it.contains("ERROR") } ?: lines.lastOrNull { it.isNotBlank() } ?: "e_unknown"
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
