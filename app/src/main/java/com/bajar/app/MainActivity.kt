package com.bajar.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Zio.boot(application)
        Zio.ui = this
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // La app solo muestra su propia pantalla: cualquier otro enlace se bloquea
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        if (Zio.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        takeShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Bgm.resume(applicationContext)
    }

    override fun onPause() {
        Bgm.pause()
        super.onPause()
    }

    override fun onDestroy() {
        Bgm.stop()
        if (Zio.ui === this) Zio.ui = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.goBack?goBack():false") { r -> if (r != "true") finish() }
    }

    // Enlaces compartidos desde la app de YouTube ("Compartir" > ZIO)
    private fun takeShare(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Zio.shared = Regex("https?://\\S+").find(t)?.value ?: return
        if (::web.isInitialized) js("if(window.takeShared)takeShared()")
    }

    /** Ejecuta código JavaScript en la pantalla (si todavía existe). */
    fun js(code: String) {
        runOnUiThread {
            try {
                if (!isDestroyed) web.evaluateJavascript(code, null)
            } catch (e: Exception) { }
        }
    }

    /** Pinta las barras del teléfono del color del tema. lightIcons = iconos claros (para fondos oscuros). */
    fun applyBars(bg: String, lightIcons: Boolean) {
        runOnUiThread {
            try {
                val c = Color.parseColor(bg)
                window.statusBarColor = c
                window.navigationBarColor = c
                web.setBackgroundColor(c)
                if (Build.VERSION.SDK_INT >= 30) {
                    val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    window.insetsController?.setSystemBarsAppearance(if (lightIcons) 0 else m, m)
                } else {
                    var f = window.decorView.systemUiVisibility
                    val m = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    f = if (lightIcons) f and m.inv() else f or m
                    window.decorView.systemUiVisibility = f
                }
            } catch (e: Exception) { }
        }
    }

    private fun exists(uri: String): Boolean {
        if (uri.isEmpty()) return false
        return try {
            contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Abre o comparte un archivo guardado en Descargas. */
    private fun openFile(uri: String, mime: String, share: Boolean) {
        try {
            val u = Uri.parse(uri)
            val i = if (share) {
                val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
                Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) {
            Zio.emit("info", "e_noapp")
        }
    }

    /** Mantiene la pantalla encendida mientras hay una descarga. */
    fun keepOn(on: Boolean) {
        runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    inner class Bridge {
        @JavascriptInterface fun start(url: String, mode: String, opts: String): Int {
            runOnUiThread { askNotifications() }
            return Zio.start(url, mode, opts)
        }
        @JavascriptInterface fun retry(id: Int) { Zio.retry(id) }
        @JavascriptInterface fun remove(id: Int) { Zio.remove(id) }
        @JavascriptInterface fun listInfo(url: String, rid: Int) { Zio.listInfo(url, rid) }
        @JavascriptInterface fun open(uri: String, mime: String) { runOnUiThread { openFile(uri, mime, false) } }
        @JavascriptInterface fun share(uri: String, mime: String) { runOnUiThread { openFile(uri, mime, true) } }
        // Recibe una lista de direcciones y responde cuáles archivos todavía existen
        @JavascriptInterface fun check(json: String): String {
            val out = JSONArray()
            try {
                val a = JSONArray(json)
                for (i in 0 until a.length()) out.put(exists(a.optString(i)))
            } catch (e: Exception) { }
            return out.toString()
        }
        @JavascriptInterface fun music(on: Boolean) { runOnUiThread { Bgm.set(applicationContext, on) } }
        @JavascriptInterface fun icon(theme: String) {
            try { Icons.set(applicationContext, theme) } catch (e: Exception) { }
        }
        @JavascriptInterface fun cancel(id: Int) { Zio.cancel(id) }
        @JavascriptInterface fun bars(bg: String, lightIcons: Boolean) { applyBars(bg, lightIcons) }
        @JavascriptInterface fun update() { Zio.update() }
        @JavascriptInterface fun ver(): String = Zio.version
        @JavascriptInterface fun live(): String = Zio.live()
        @JavascriptInterface fun pending(): String = Zio.takeShared()
        @JavascriptInterface fun clip(): String {
            val c = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            return c.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }
    }
}
