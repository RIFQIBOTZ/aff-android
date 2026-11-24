package id.rifqibotz.sharelink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var first = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF050D0B.toInt()
        window.navigationBarColor = 0xFF050D0B.toInt()

        web = WebView(this)
        web.setBackgroundColor(0xFF050D0B.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.host == "rifqibotz.github.io") return false
                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams!!.createIntent(), 42)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }

        setContentView(web)
        web.clearCache(true) // cache HTTP saja; localStorage (data sesi) tidak tersentuh
        web.loadUrl(APP_URL)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 42) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onResume() {
        super.onResume()
        BubbleService.instance?.onAppForeground(true)
        if (!first && BubbleService.dirty) {
            BubbleService.dirty = false
            web.reload() // ambil data terbaru hasil tap dari gelembung
        }
        first = false
    }

    override fun onPause() {
        super.onPause()
        BubbleService.instance?.onAppForeground(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun isBubbleEnabled(): Boolean = BubbleService.instance != null

        /** Muat ulang tampilan ke versi terbaru tanpa menghapus data (localStorage tidak disentuh). */
        @JavascriptInterface
        fun refreshApp() {
            runOnUiThread {
                web.clearCache(true)
                BubbleService.instance?.reloadEngine()
                web.loadUrl(APP_URL)
                Toast.makeText(this@MainActivity, "Memuat versi terbaru. Data tidak dihapus.", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this@MainActivity)

        @JavascriptInterface
        fun setBubble(on: Boolean): String {
            if (!on) {
                BubbleService.stop(this@MainActivity)
                return "off"
            }
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                runOnUiThread {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                return "need_permission"
            }
            runOnUiThread { BubbleService.start(this@MainActivity) }
            return "on"
        }

        @JavascriptInterface
        fun saveFile(name: String, content: String): Boolean = try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Tersimpan di folder Download: $name", Toast.LENGTH_LONG).show()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
