package com.technoblade.jarviscompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val trustedHost = "jarvis-ai-assistant-aulnd6.v2.appdeploy.ai"
    private val permissionMap = mapOf(
        "microphone" to Manifest.permission.RECORD_AUDIO,
        "location" to Manifest.permission.ACCESS_FINE_LOCATION,
        "contacts" to Manifest.permission.READ_CONTACTS,
        "calendar" to Manifest.permission.READ_CALENDAR,
        "notifications" to Manifest.permission.POST_NOTIFICATIONS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host
                if (host == trustedHost) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }
        web.addJavascriptInterface(JarvisBridge(), "JARVIS_NATIVE")
        setContentView(web)
        web.loadUrl("https://$trustedHost/")
    }

    inner class JarvisBridge {
        @JavascriptInterface fun version(): String = "android-companion/0.1.0"
        @JavascriptInterface fun openUrl(url: String) {
            runOnUiThread {
                val uri = Uri.parse(url)
                if (uri.scheme != "http" && uri.scheme != "https") return@runOnUiThread
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }

        @JavascriptInterface fun requestPermission(name: String) {
            val permission = permissionMap[name] ?: return
            if (ContextCompat.checkSelfPermission(this@MainActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(permission), permissionCode(name))
            }
        }

        @JavascriptInterface fun openAccessibilitySettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        @JavascriptInterface fun openApp(packageName: String) {
            runOnUiThread {
                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
            }
        }

        @JavascriptInterface fun pickFile() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, 3001)
            }
        }

        @JavascriptInterface fun status(): String {
            val json = JSONObject()
            permissionMap.forEach { (name, permission) ->
                json.put(name, ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED)
            }
            json.put("deviceControl", JarvisAccessibilityService.instance != null)
            return json.toString()
        }

        private fun permissionCode(name: String): Int = 1000 + name.hashCode().and(0x3FF)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "JARVIS permission enabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
