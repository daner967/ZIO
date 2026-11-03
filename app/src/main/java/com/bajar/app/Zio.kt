package com.bajar.app

import android.app.Application
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Base64
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Todo el trabajo pesado vive aquí y no en la pantalla (Activity): así una descarga sigue viva
 * aunque la pantalla se cierre o se vuelva a crear, y los eventos siempre llegan a la pantalla actual.
 */
object Zio {
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var app: Application
    private var started = false
    private val MEDIA = setOf("mp3", "m4a", "opus", "flac", "wav", "mp4", "mkv", "webm")
    private val IMG = setOf("jpg", "jpeg", "png", "webp")
    private val TMP = Regex("\\.f\\d+\\.[a-z0-9]+$|\\.temp\\.[a-z0-9]+$") // restos intermedios de yt-dlp

    @Volatile private var ready = false
    @Volatile private var lastMeta = ""
    @Volatile private var lastProg = ""
    @Volatile private var lastNotif = 0L
    @Volatile var running = false
    @Volatile var cancelled = false
    @Volatile var version = ""
    @Volatile var shared = ""
    @Volatile var ui: MainActivity? = null

    fun boot(a: Application) {
        if (started) return
        started = true
        app = a
        pool.execute { startEngine() }
    }

    // ------------------------------------------------------------ comunicación con la pantalla
    fun emit(t: String, m: String = "", p: Float = 0f, n: String = "") {
        val pd = if (p.isNaN() || p.isInfinite()) 0.0 else p.toDouble()
        val j = JSONObject().put("t", t).put("m", m).put("p", pd).put("n", n).toString()
        if (running) {
            if (t == "meta") lastMeta = j
            else if (t == "progress" && m != "[saving]") lastProg = j
        }
        ui?.js("onEvent($j)")
    }

    // Estado de la descarga en curso, para repintar la ventana si la pantalla se vuelve a crear
    fun live(): String {
        if (!running) return ""
        return listOf(lastMeta, lastProg).filter { it.isNotEmpty() }.joinToString(",", "[", "]")
    }

    fun takeShared(): String {
        val s = shared
        shared = ""
        return s
    }

    // ------------------------------------------------------------ motor yt-dlp
    private fun startEngine() {
        try {
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            ready = true
            version = YoutubeDL.getInstance().version(app) ?: ""
            emit("ready", version)
        } catch (e: Exception) {
            emit("error", "e_init_fail|${e.message}")
            return
        }
        refresh(false)
    }

    // yt-dlp se rompe seguido cuando YouTube cambia. Se actualiza al abrir la app, como mucho cada 12 horas
    private fun refresh(show: Boolean) {
        val prefs = app.getSharedPreferences("zio", 0)
        val now = System.currentTimeMillis()
        if (!show && now - prefs.getLong("upd", 0L) < 43_200_000L) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("upd", now).apply()
            version = YoutubeDL.getInstance().version(app) ?: version
            emit("ready", version)
            if (show) emit("info", "i_updated")
        } catch (e: Exception) {
            if (show) emit("error", "e_upd_fail|${e.message}")
        }
    }

    fun update() { pool.execute { refresh(true) } }

    fun cancel() {
        cancelled = true
        try { YoutubeDL.getInstance().destroyProcessById("dl") } catch (e: Exception) { }
    }

    fun start(url: String, mode: String, opts: String) {
        val u = url.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://")) { emit("error", "bad_link"); return }
        val o = try { JSONObject(opts) } catch (e: Exception) { JSONObject() }
        cancelled = false
        running = true
        lastMeta = ""
        lastProg = ""
        pool.execute { download(u, mode, o) }
    }

    // ------------------------------------------------------------ notificaciones
    private fun notifyRun(text: String, pct: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotif < 1000) return
        lastNotif = now
        try {
            app.getSystemService(NotificationManager::class.java).notify(Notif.RUN, Notif.running(app, text, pct))
        } catch (e: Exception) { }
    }

    private fun notifyEnd(text: String) {
        if (ui?.hasWindowFocus() == true) return // si la app está a la vista no hace falta avisar
        try {
            app.getSystemService(NotificationManager::class.java).notify(Notif.END, Notif.done(app, text))
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------ descarga
    private fun download(url: String, mode: String, o: JSONObject) {
        val en = o.optString("lang") == "en"
        val work = File(app.cacheDir, "w${System.currentTimeMillis()}")
        var wl: PowerManager.WakeLock? = null
        try {
            if (cancelled) { emit("cancelled"); return }
            if (!ready) { emit("error", "e_init_wait"); return }
            work.mkdirs()
            ui?.keepOn(true)
            // Servicio en primer plano + CPU despierta: la descarga sigue aunque cambies de app o apagues la pantalla
            try { app.startForegroundService(Intent(app, DownloadService::class.java)) } catch (e: Exception) { }
            try {
                val lock = app.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zio:download")
                lock.acquire(6 * 60 * 60 * 1000L)
                wl = lock
            } catch (e: Exception) { }

            // Opciones elegidas en la pantalla de descarga (solo valores conocidos)
            val vq = o.optString("vq").takeIf { it in setOf("max", "1080", "720", "480", "360") } ?: "720"
            val af = o.optString("af").takeIf { it in setOf("mp3", "m4a", "opus", "flac", "wav") } ?: "mp3"
            val aq = o.optString("aq").takeIf { it in setOf("best", "med", "low") } ?: "best"
            val isList = o.optBoolean("list")
            val thumb = o.optBoolean("thumb", true) // guardar también la miniatura como imagen aparte
            val video = mode == "video"
            val dir = work.absolutePath

            val r = YoutubeDLRequest(url)
            r.addOption("--windows-filenames")
            r.addOption("--no-mtime")
            r.addOption("-N", "4") // fragmentos en paralelo cuando el formato los usa
            r.addOption("--convert-thumbnails", "jpg")
            if (thumb) r.addOption("--write-thumbnail")                // imagen aparte
            if (video || af != "wav") r.addOption("--embed-thumbnail") // portada dentro del archivo
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
            val sent = HashSet<String>()
            var savedCount = 0
            var totalItems = 0
            var curItem = 0
            var curTitle = ""
            var playlistName = ""

            // Listas: cada canción o video se guarda en cuanto termina, para no llenar la memoria del teléfono
            fun flush(prefix: String?) {
                val d = work.listFiles { f -> f.isDirectory }?.firstOrNull() ?: return
                playlistName = d.name
                val dest = "$folder/${d.name}"
                val fs = d.listFiles { f -> f.isFile && (prefix == null || f.name.startsWith(prefix)) }
                    .orEmpty().sortedBy { it.name }
                for (f in fs) {
                    if (TMP.containsMatchIn(f.name)) continue
                    val ext = f.extension.lowercase()
                    val isMedia = ext in MEDIA
                    val isImg = ext in IMG
                    if (!isMedia && !(isImg && thumb)) { if (isImg) f.delete(); continue }
                    if (isMedia) emit("progress", "[saving]", 100f)
                    save(f, mimeOf(f), dest)
                    if (isMedia) savedCount++
                    f.delete()
                }
            }

            var err: String? = null
            var lastEmit = 0L
            try {
                YoutubeDL.getInstance().execute(r, "dl") { p, _, line ->
                    val ln = "$line"
                    if (isList) {
                        val m = Regex("Downloading item (\\d+) of (\\d+)").find(ln)
                        if (m != null) {
                            curItem = m.groupValues[1].toInt()
                            totalItems = m.groupValues[2].toInt()
                            if (curItem > 1) flush("%02d - ".format(curItem - 1))
                        }
                    }
                    // al empezar cada descarga se envía la miniatura y el título a la ventana de progreso
                    if (ln.startsWith("[download] Destination:")) {
                        curTitle = meta(work, ln, sent).ifEmpty { curTitle }
                    }
                    // las líneas de porcentaje llegan muy seguido: se limitan para no saturar la pantalla
                    val isPct = ln.startsWith("[download]") && ln.contains("%")
                    val now = System.currentTimeMillis()
                    if (!isPct || now - lastEmit >= 150) {
                        lastEmit = now
                        emit("progress", ln, p)
                        val pf = if (p < 0f || p.isNaN()) 0f else p
                        val all = if (totalItems > 0) ((curItem - 1) + pf / 100f) / totalItems * 100f else pf
                        notifyRun(if (curTitle.isEmpty()) "…" else curTitle, all.toInt())
                    }
                }
            } catch (e: Exception) {
                err = e.message
            }
            if (cancelled) { emit("cancelled"); return }

            val coverFail = err?.let { it.contains("thumbnail", true) || it.contains("mutagen", true) || it.contains("embed", true) } == true
            val noCover = if (coverFail) (if (en) " · cover not embedded" else " · portada no incrustada") else ""

            if (isList) {
                flush(null)
                if (savedCount == 0) {
                    val msg = nice(err)
                    emit("error", msg)
                    notifyEnd(if (en) "Download failed" else "Falló la descarga")
                    return
                }
                val what = if (video) "videos" else (if (en) "songs" else "canciones")
                val failed = if (totalItems > savedCount) (if (en) ", ${totalItems - savedCount} failed" else ", ${totalItems - savedCount} fallaron") else ""
                val n = "$playlistName ($savedCount $what$failed)$noCover"
                emit("done", "$folder/$playlistName", n = n)
                notifyEnd((if (en) "Done: " else "Listo: ") + n)
            } else {
                val all = work.walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()
                val files = all.filter { it.extension.lowercase() in MEDIA && !TMP.containsMatchIn(it.name) }
                val thumbs = if (thumb) all.filter { it.extension.lowercase() in IMG } else emptyList()
                if (files.isEmpty()) {
                    emit("error", nice(err))
                    notifyEnd(if (en) "Download failed" else "Falló la descarga")
                    return
                }
                val f = files.first()
                save(f, mimeOf(f), folder)
                thumbs.forEach { save(it, mimeOf(it), folder) }
                val extra = if (thumbs.isEmpty()) "" else (if (en) " + thumbnail" else " + miniatura")
                val n = f.name + extra + noCover
                emit("done", folder, n = n)
                notifyEnd((if (en) "Done: " else "Listo: ") + n)
            }
        } catch (e: Exception) {
            emit("error", nice(e.message))
        } finally {
            work.deleteRecursively()
            try { wl?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
            try { app.stopService(Intent(app, DownloadService::class.java)) } catch (e: Exception) { }
            running = false
            ui?.keepOn(false)
        }
    }

    // Envía a la ventana de progreso el título y la miniatura del video que empieza. Devuelve el título.
    private fun meta(work: File, line: String, sent: MutableSet<String>): String {
        return try {
            val path = line.substringAfter("Destination:").trim()
            val title = File(path).nameWithoutExtension
                .replace(Regex("\\.f\\d+$"), "").replace(Regex("^\\d+ - "), "")
            val img = work.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in IMG && it.path !in sent }
                .maxByOrNull { it.lastModified() }
            emit("meta", title, 0f, if (img != null) thumb64(img, sent) else "")
            title
        } catch (e: Exception) {
            ""
        }
    }

    // Miniatura reducida (unos 640 px de ancho): carga rápido y pesa muy poco al pasarla a la pantalla
    private fun thumb64(img: File, sent: MutableSet<String>): String {
        sent.add(img.path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(img.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
        val bmp = BitmapFactory.decodeFile(img.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return ""
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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

    // Copia el archivo a Descargas. Si algo falla a medias, borra el archivo incompleto
    private fun save(f: File, mime: String, dir: String) {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val res = app.contentResolver
        val u = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("e_create")
        try {
            res.openOutputStream(u)!!.use { out -> f.inputStream().use { it.copyTo(out, 256 * 1024) } }
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            res.update(u, v, null, null)
        } catch (e: Exception) {
            try { res.delete(u, null, null) } catch (x: Exception) { }
            throw e
        }
    }

    private fun nice(m: String?): String {
        val lines = m.orEmpty().lines()
        val l = lines.lastOrNull { it.contains("ERROR") } ?: lines.lastOrNull { it.isNotBlank() } ?: "e_unknown"
        return l.removePrefix("ERROR: ").trim().take(220)
    }
}
