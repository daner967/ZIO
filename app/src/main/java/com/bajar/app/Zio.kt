package com.bajar.app

import android.app.Application
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import android.system.Os
import android.util.Base64
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Todo el trabajo pesado vive aquí y no en la pantalla (Activity): así las descargas siguen vivas
 * aunque la pantalla se cierre o se vuelva a crear, y los eventos siempre llegan a la pantalla actual.
 *
 * Cola: cada descarga es un "trabajo". Se hacen de una en una, en orden. Si una se detiene (cancelada
 * o con error) conserva lo ya bajado, y al reanudarla continúa desde ahí.
 */
object Zio {
    private val pool = Executors.newSingleThreadExecutor()      // descargas, una a la vez
    private val infoPool = Executors.newSingleThreadExecutor()  // consultas de listas
    private lateinit var app: Application
    private var started = false
    private val MEDIA = setOf("mp3", "m4a", "opus", "flac", "wav", "mp4", "mkv", "webm")
    private val IMG = setOf("jpg", "jpeg", "png", "webp")
    private val TMP = Regex("\\.f\\d+\\.[a-z0-9]+$|\\.temp\\.[a-z0-9]+$") // restos intermedios de yt-dlp

    private class Job(val id: Int, val key: String, val url: String, val mode: String, val opts: JSONObject) {
        @Volatile var state = "queued" // queued | running | stopped
        @Volatile var title = ""
        @Volatile var msg = ""         // por qué se detuvo
        @Volatile var gen = 0          // cambia cuando se cancela o se vuelve a programar
    }

    private val jobs = CopyOnWriteArrayList<Job>()
    private var nextId = 1

    @Volatile private var ready = false
    @Volatile private var curId = 0
    @Volatile private var stopCur = false
    @Volatile private var blocked = false
    @Volatile private var lastMeta = ""
    @Volatile private var lastProg = ""
    @Volatile private var lastNotif = 0L
    @Volatile var running = false
    @Volatile var version = ""
    @Volatile var shared = ""
    @Volatile var ui: MainActivity? = null
    @Volatile var cancelLabel = "Cancelar"

    fun boot(a: Application) {
        if (started) return
        started = true
        app = a
        // Carpeta temporal propia: el Python de yt-dlp no trae una en Android y fallaba con "Read-only file system"
        try {
            val tmp = File(app.cacheDir, "ytmp").apply { mkdirs() }
            tmp.listFiles()?.forEach { it.deleteRecursively() }
            for (k in listOf("TMPDIR", "TEMP", "TMP")) Os.setenv(k, tmp.absolutePath, true)
        } catch (e: Exception) { }
        // Limpieza: descargas detenidas de hace más de 7 días y restos de versiones anteriores
        try {
            val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            File(app.cacheDir, "jobs").listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
            app.cacheDir.listFiles()?.forEach { if (it.isDirectory && Regex("^w\\d+$").matches(it.name)) it.deleteRecursively() }
        } catch (e: Exception) { }
        pool.execute { startEngine() }
    }

    // ------------------------------------------------------------ comunicación con la pantalla
    fun emit(t: String, m: String = "", p: Float = 0f, n: String = "", x: Any? = null) {
        val pd = if (p.isNaN() || p.isInfinite()) 0.0 else p.toDouble()
        val o = JSONObject().put("t", t).put("m", m).put("p", pd).put("n", n).put("id", curId)
        if (x != null) o.put("x", x)
        val j = o.toString()
        if (running) {
            if (t == "meta") lastMeta = j
            else if (t == "progress" && m != "[saving]") lastProg = j
        }
        ui?.js("onEvent($j)")
    }

    private fun snapshot(): JSONArray {
        val a = JSONArray()
        for (j in jobs) {
            a.put(JSONObject().put("id", j.id).put("url", j.url).put("mode", j.mode)
                .put("state", j.state).put("title", j.title).put("msg", j.msg))
        }
        return a
    }

    private fun pushQueue() { emit("queue", x = snapshot()) }

    // Estado actual, para repintar la pantalla si Android la vuelve a crear con descargas en curso
    fun live(): String {
        if (jobs.isEmpty()) return ""
        val o = JSONObject().put("q", snapshot())
        if (running) {
            if (lastMeta.isNotEmpty()) o.put("meta", JSONObject(lastMeta))
            if (lastProg.isNotEmpty()) o.put("prog", JSONObject(lastProg))
        }
        return o.toString()
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

    // Opciones comunes a todo lo que se le pide a yt-dlp
    private fun common(r: YoutubeDLRequest) {
        r.addOption("-P", "temp:" + File(app.cacheDir, "ytmp").apply { mkdirs() }.absolutePath)
        r.addOption("--cache-dir", File(app.cacheDir, "ytcache").apply { mkdirs() }.absolutePath)
    }

    // ------------------------------------------------------------ cola de descargas
    private fun keyOf(u: String, mode: String, o: JSONObject): String {
        val parts = listOf("vq", "af", "aq", "thumb", "list", "sub", "idx", "items", "subs", "from", "to").joinToString("|") { o.optString(it) }
        return Integer.toHexString("$u|$mode|$parts".hashCode())
    }

    @Synchronized
    fun start(url: String, mode: String, opts: String): Int {
        val u = url.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://")) { emit("error", "bad_link"); return -1 }
        val o = try { JSONObject(opts) } catch (e: Exception) { JSONObject() }
        val key = keyOf(u, mode, o)
        val old = jobs.firstOrNull { it.key == key }
        if (old != null) {
            if (old.state == "stopped") { retry(old.id); return old.id } // misma descarga detenida: se reanuda
            emit("info", "e_dup")
            return -1
        }
        val j = Job(nextId++, key, u, mode, o)
        jobs.add(j)
        pushQueue()
        val g = j.gen
        pool.execute { runJob(j, g) }
        return j.id
    }

    @Synchronized
    fun retry(id: Int) {
        val j = jobs.firstOrNull { it.id == id && it.state == "stopped" } ?: return
        j.state = "queued"
        j.msg = ""
        val g = ++j.gen
        pushQueue()
        pool.execute { runJob(j, g) }
    }

    @Synchronized
    fun cancel(id: Int) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        if (j.state == "running") {
            stopRunning()
        } else if (j.state == "queued") {
            j.gen++ // el turno ya programado quedará sin efecto
            j.state = "stopped"
            j.msg = "cancelled"
            pushQueue()
        }
    }

    // Lo usa el botón "Cancelar" de la notificación
    fun cancelRunning() {
        val id = jobs.firstOrNull { it.state == "running" }?.id ?: return
        cancel(id)
    }

    fun freeBytes(): Long = try { StatFs(app.cacheDir.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }

    @Synchronized
    fun remove(id: Int) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        if (j.state == "running") return
        j.gen++
        jobs.remove(j)
        deleteWork(j)
        pushQueue()
    }

    private fun stopRunning() {
        stopCur = true
        try { YoutubeDL.getInstance().destroyProcessById("dl") } catch (e: Exception) { }
    }

    private fun deleteWork(j: Job) {
        try { File(app.cacheDir, "jobs/${j.key}").deleteRecursively() } catch (e: Exception) { }
    }

    private fun startService() {
        try { app.startForegroundService(Intent(app, DownloadService::class.java)) } catch (e: Exception) { }
    }

    private fun stopService() {
        try { app.stopService(Intent(app, DownloadService::class.java)) } catch (e: Exception) { }
    }

    private fun runJob(j: Job, g: Int) {
        if (g != j.gen || j.state != "queued") return
        val en = j.opts.optString("lang") == "en"
        cancelLabel = if (en) "Cancel" else "Cancelar"
        curId = j.id
        j.state = "running"
        j.msg = ""
        j.title = ""
        stopCur = false
        blocked = false
        lastMeta = ""
        lastProg = ""
        running = true
        pushQueue()
        startService()
        val result: String? = try { download(j) } catch (e: Exception) { friendly(e.message) }
        running = false
        if (result == null) {
            jobs.remove(j)
            deleteWork(j)
        } else {
            j.state = "stopped"
            j.msg = result
        }
        if (jobs.none { it.state == "queued" || it.state == "running" }) {
            stopService()
            ui?.keepOn(false)
        }
        pushQueue()
        if (result == "cancelled") {
            emit("cancelled")
        } else if (result != null) {
            emit("error", result)
            notifyEnd(if (en) "Download failed" else "Falló la descarga")
        }
    }

    // ------------------------------------------------------------ listas y búsquedas
    // Pide a yt-dlp solo la lista de elementos (sin descargar nada)
    private fun flat(u: String): JSONObject {
        val r = YoutubeDLRequest(u)
        common(r)
        r.addOption("--flat-playlist")
        r.addOption("-J")
        r.addOption("--no-warnings")
        return JSONObject(YoutubeDL.getInstance().execute(r).out)
    }

    private fun entriesOf(root: JSONObject): JSONArray {
        val entries = root.optJSONArray("entries") ?: JSONArray()
        val arr = JSONArray()
        for (k in 0 until entries.length()) {
            val e = entries.optJSONObject(k) ?: continue
            val str = { key: String -> if (e.isNull(key)) "" else e.optString(key) }
            arr.put(
                JSONObject().put("i", k + 1).put("id", str("id")).put("t", str("title"))
                    .put("c", str("channel").ifEmpty { str("uploader") })
                    .put("d", e.optInt("duration", 0))
            )
        }
        return arr
    }

    // Elegir elementos de una lista
    fun listInfo(url: String, rid: Int) {
        val u = url.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://")) { emit("list_err", "bad_link"); return }
        infoPool.execute {
            try {
                if (!ready) { emit("list_err", "e_init_wait"); return@execute }
                val root = flat(u)
                val title = if (root.isNull("title")) "" else root.optString("title")
                emit("list", x = JSONObject().put("rid", rid).put("title", title).put("items", entriesOf(root)))
            } catch (e: Exception) {
                emit("list_err", friendly(e.message))
            }
        }
    }

    // Buscar por nombre (sin pegar el enlace)
    fun search(q: String, rid: Int) {
        val query = q.trim().take(120)
        if (query.isEmpty()) { emit("search_err", "e_url"); return }
        infoPool.execute {
            try {
                if (!ready) { emit("search_err", "e_init_wait"); return@execute }
                val root = flat("ytsearch12:$query")
                emit("search", x = JSONObject().put("rid", rid).put("items", entriesOf(root)))
            } catch (e: Exception) {
                emit("search_err", friendly(e.message))
            }
        }
    }

    // ------------------------------------------------------------ Wi-Fi / datos móviles
    // ¿La conexión actual son solo datos móviles? (Wi-Fi o cable cuentan como conexión normal)
    private fun onMobile(): Boolean {
        return try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            false
        }
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

    // ------------------------------------------------------------ descarga de un trabajo
    // Devuelve null si todo salió bien, "cancelled" si se canceló, o un código de error.
    private fun download(j: Job): String? {
        val url = j.url
        val mode = j.mode
        val o = j.opts
        val en = o.optString("lang") == "en"
        // La carpeta de trabajo se conserva si la descarga se detiene: así se puede reanudar
        val work = File(app.cacheDir, "jobs/${j.key}")
        var wl: PowerManager.WakeLock? = null
        try {
            if (!ready) return "e_init_wait"
            val mobileOk = o.optBoolean("mobile", true) // false = solo Wi-Fi
            if (!mobileOk && onMobile()) return "e_mobile"
            if (freeBytes() < 150L * 1024 * 1024) return "e_space"
            work.mkdirs()
            ui?.keepOn(true)
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
            // Elementos elegidos de una lista, por ejemplo "1-3,5,8-9"
            val pick = if (isList) o.optString("items").replace(Regex("[^0-9,\\-]"), "") else ""
            val pickIdx = expand(pick)

            // Nombre de archivo: "Canal - Título" (en bytes, para no pasar el límite de Android).
            // Al bajar de nuevo un elemento de una lista, vuelve a su carpeta y a su número.
            val unk = if (en) "Unknown" else "Canal"
            val who = "%(channel,uploader|$unk).60B"
            val ttl = "%(title).110B"
            val sub = o.optString("sub").replace(Regex("[\\\\/]"), "_").replace("..", "_").trim().take(120)
            val idx = o.optInt("idx", 0)
            val pre = if (!isList && idx > 0) "%02d - ".format(idx) else ""
            val saveOpts = JSONObject().put("vq", vq).put("af", af).put("aq", aq).put("thumb", thumb)
            if (sub.isNotEmpty()) saveOpts.put("sub", sub)
            if (idx > 0) saveOpts.put("idx", idx)
            if (pick.isNotEmpty()) saveOpts.put("items", pick)
            val subs = o.optString("subs").takeIf { it in setOf("es", "en", "es,en") } ?: ""
            val tre = Regex("^\\d{1,3}(:[0-5]?\\d){0,2}$")
            val from = o.optString("from").trim().takeIf { !isList && tre.matches(it) } ?: ""
            val to = o.optString("to").trim().takeIf { !isList && tre.matches(it) } ?: ""
            if (subs.isNotEmpty()) saveOpts.put("subs", subs)
            if (from.isNotEmpty()) saveOpts.put("from", from)
            if (to.isNotEmpty()) saveOpts.put("to", to)
            val req = JSONObject().put("url", url).put("mode", mode).put("list", isList).put("opts", saveOpts)

            val r = YoutubeDLRequest(url)
            common(r)
            r.addOption("--windows-filenames")
            r.addOption("--no-mtime")
            r.addOption("--embed-metadata")                 // canal, título, etc. dentro del archivo
            r.addOption("--write-info-json")                // datos del video para el historial (no se guardan en Descargas)
            r.addOption("--no-write-playlist-metafiles")
            r.addOption("-N", "4")                          // fragmentos en paralelo cuando el formato los usa
            r.addOption("--convert-thumbnails", "jpg")
            if (thumb) r.addOption("--write-thumbnail")                // imagen aparte
            if (video || af != "wav") r.addOption("--embed-thumbnail") // portada dentro del archivo
            if (subs.isNotEmpty()) {
                r.addOption("--write-subs")
                r.addOption("--write-auto-subs")
                r.addOption("--sub-langs", subs.split(",").joinToString(",") { "$it.*" })
                r.addOption("--convert-subs", "srt")
                // YouTube limita seguido los subtítulos (error 429): se espera entre pedidos y, si fallan,
                // el audio o video se baja igual en vez de cancelarse todo
                r.addOption("--sleep-subtitles", "2")
                r.addOption("--ignore-errors")
            }
            if (from.isNotEmpty() || to.isNotEmpty()) {
                r.addOption("--download-sections", "*${from.ifEmpty { "0" }}-${to.ifEmpty { "inf" }}")
            }
            if (isList) {
                r.addOption("--yes-playlist")
                r.addOption("--ignore-errors")
                if (pick.isNotEmpty()) r.addOption("--playlist-items", pick)
                // lo ya bajado queda anotado: al reanudar una lista no se repite
                r.addOption("--download-archive", File(work, "archive.txt").absolutePath)
                r.addOption("-o", "$dir/%(playlist_title).80B/%(playlist_index)02d - $who - $ttl.%(ext)s")
            } else {
                r.addOption("--no-playlist")
                r.addOption("-o", "$dir/$pre$who - $ttl.%(ext)s")
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
            val base = o.optString("folder").replace(Regex("[\\\\/:*?\"<>|]"), "_").replace("..", "_").trim().take(40).ifEmpty { "ZIO" }
            val folder = "Download/$base/" + (if (video) "Videos" else "Audios")
            val saveDir = if (sub.isNotEmpty()) "$folder/$sub" else folder
            val items = JSONArray()
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
                    val isSub = ext == "srt"
                    if (!isMedia && !(isImg && thumb) && !(isSub && subs.isNotEmpty())) { if (isImg || isSub) f.delete(); continue }
                    if (isMedia) emit("progress", "[saving]", 100f)
                    val uri = save(f, mimeOf(f), dest)
                    if (isMedia) {
                        savedCount++
                        val info = readItem(File(d, f.nameWithoutExtension + ".info.json"))
                        if (info != null) items.put(info.put("v", uri).put("m", mimeOf(f)))
                    }
                    f.delete()
                }
            }

            var err: String? = null
            var lastEmit = 0L
            var lastNet = 0L
            try {
                YoutubeDL.getInstance().execute(r, "dl") { p, _, line ->
                    val ln = "$line"
                    if (isList) {
                        val m = Regex("Downloading item (\\d+) of (\\d+)").find(ln)
                        if (m != null) {
                            curItem = m.groupValues[1].toInt()
                            totalItems = m.groupValues[2].toInt()
                            if (curItem > 1) {
                                // el elemento anterior ya terminó: se guarda y se libera espacio
                                val prev = if (pickIdx.isNotEmpty()) pickIdx.getOrNull(curItem - 2) else curItem - 1
                                if (prev != null) flush("%02d - ".format(prev))
                            }
                        }
                    }
                    // al empezar cada descarga se envía la miniatura y el título a la ventana de progreso
                    if (ln.startsWith("[download] Destination:")) {
                        val title = meta(work, ln, sent)
                        if (title.isNotEmpty() && title != j.title) {
                            j.title = title
                            curTitle = title
                            pushQueue()
                        }
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
                    // si la descarga con datos está desactivada y el teléfono cambia a datos móviles, se detiene
                    if (!mobileOk && now - lastNet > 3000) {
                        lastNet = now
                        if (onMobile()) { blocked = true; stopRunning() }
                    }
                }
            } catch (e: Exception) {
                err = e.message
            }
            if (stopCur) return if (blocked) "e_mobile" else "cancelled"

            val coverFail = err?.let { it.contains("thumbnail", true) || it.contains("mutagen", true) || it.contains("embed", true) } == true
            val noCover = if (coverFail) (if (en) " · cover not embedded" else " · portada no incrustada") else ""
            val subsFail = subs.isNotEmpty() && err?.contains("subtitles", true) == true
            val noSubs = if (subsFail) (if (en) " · some subtitles failed (YouTube limited requests)" else " · algunos subtítulos no se pudieron bajar (YouTube limitó las solicitudes)") else ""

            if (isList) {
                flush(null)
                if (savedCount == 0) return friendly(err)
                val what = if (video) "videos" else (if (en) "songs" else "canciones")
                val failed = if (totalItems > savedCount) (if (en) ", ${totalItems - savedCount} failed" else ", ${totalItems - savedCount} fallaron") else ""
                val n = "$playlistName ($savedCount $what$failed)$noCover$noSubs"
                val x = JSONObject().put("req", req).put("pl", playlistName).put("items", items)
                emit("done", "$folder/$playlistName", n = n, x = x)
                notifyEnd((if (en) "Done: " else "Listo: ") + n)
            } else {
                val all = work.walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()
                val files = all.filter { it.extension.lowercase() in MEDIA && !TMP.containsMatchIn(it.name) }
                val thumbs = if (thumb) all.filter { it.extension.lowercase() in IMG } else emptyList()
                val subFiles = if (subs.isNotEmpty()) all.filter { it.extension.lowercase() == "srt" } else emptyList()
                if (files.isEmpty()) return friendly(err)
                val f = files.first()
                val uri = save(f, mimeOf(f), saveDir)
                thumbs.forEach { save(it, mimeOf(it), saveDir) }
                subFiles.forEach { save(it, mimeOf(it), saveDir) }
                val info = readItem(File(f.parentFile, f.nameWithoutExtension + ".info.json"))
                val extra = (if (thumbs.isEmpty()) "" else (if (en) " + thumbnail" else " + miniatura")) +
                    (if (subFiles.isEmpty()) "" else (if (en) " + subtitles" else " + subtítulos"))
                val n = f.name + extra + noCover + noSubs
                val x = JSONObject().put("req", req).put("ch", info?.optString("c") ?: "").put("v", uri).put("m", mimeOf(f))
                emit("done", saveDir, n = n, x = x)
                notifyEnd((if (en) "Done: " else "Listo: ") + n)
            }
            return null
        } catch (e: Exception) {
            return friendly(e.message)
        } finally {
            try { wl?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        }
    }

    // "1-3,5" -> [1, 2, 3, 5]
    private fun expand(s: String): List<Int> {
        val out = ArrayList<Int>()
        for (p in s.split(",")) {
            if (p.isEmpty()) continue
            val ab = p.split("-")
            val a = ab[0].toIntOrNull() ?: continue
            val b = if (ab.size > 1) (ab[1].toIntOrNull() ?: a) else a
            if (b < a || b - a > 5000) continue
            for (k in a..b) out.add(k)
        }
        return out
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
        "srt" -> "application/x-subrip"
        else -> "application/octet-stream"
    }

    // Copia el archivo a Descargas y devuelve su dirección (para abrirlo o compartirlo desde el historial).
    // Si algo falla a medias, borra el archivo incompleto.
    private fun save(f: File, mime: String, dir: String): String {
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
        return u.toString()
    }

    // Lee los datos de un video que yt-dlp dejó en su archivo .info.json: enlace, título, canal y posición en la lista
    private fun readItem(f: File): JSONObject? {
        return try {
            if (!f.exists()) return null
            val j = JSONObject(f.readText())
            val s = { k: String -> if (j.isNull(k)) "" else j.optString(k) }
            JSONObject()
                .put("u", s("webpage_url").ifEmpty { s("original_url") })
                .put("id", s("id"))
                .put("t", s("title"))
                .put("c", s("channel").ifEmpty { s("uploader") })
                .put("i", j.optInt("playlist_index", 0))
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ errores explicados
    private fun lastError(m: String?): String {
        val lines = m.orEmpty().lines()
        val l = lines.lastOrNull { it.contains("ERROR") } ?: lines.lastOrNull { it.isNotBlank() } ?: ""
        return l.removePrefix("ERROR: ").trim().take(220)
    }

    // Convierte el mensaje de yt-dlp en un código que la pantalla traduce y explica
    private fun friendly(m: String?): String {
        val l = m.orEmpty().lowercase()
        fun has(vararg k: String) = k.any { l.contains(it) }
        return when {
            has("e_create") -> "e_create"
            has("confirm your age", "age-restricted", "age restricted", "inappropriate for some users") -> "e_age"
            has("not a bot") -> "e_bot"
            has("private video", "this video is private") -> "e_private"
            has("members-only", "members only", "join this channel", "channel's members") -> "e_members"
            has("not available in your country", "blocked it in your country", "geo-restrict", "geo restrict") -> "e_geo"
            has("video unavailable", "this video is not available", "has been removed", "no longer available",
                "account associated with this video has been terminated", "copyright", "does not exist") -> "e_unavail"
            has("premieres in", "live event will begin", "this live event", "is offline") -> "e_live"
            has("http error 429", "too many requests") -> "e_429"
            has("no space left") -> "e_space"
            has("read-only file system") -> "e_ro"
            has("unsupported url") -> "e_url"
            has("requested format is not available", "no video formats found") -> "e_format"
            has("getaddrinfo", "name resolution", "network is unreachable", "timed out", "connection reset",
                "connection refused", "unable to download webpage", "unable to download api page",
                "remote end closed", "urlopen error", "no route to host") -> "e_net"
            has("unable to extract", "failed to extract", "nsig", "signature extraction", "player response", "challenge") -> "e_extract"
            else -> {
                val d = lastError(m)
                if (d.isEmpty()) "e_unknown" else "e_other|$d"
            }
        }
    }
}
