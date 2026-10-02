package com.technoblade.jarviscompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val trustedHost = "jarvis-ai-assistant-aulnd6.v2.appdeploy.ai"
    private var speechRecognizer: SpeechRecognizer? = null
    private var handsFreeEnabled = true
    private lateinit var tts: TextToSpeech

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
        tts = TextToSpeech(this) { tts.language = Locale.UK }
        web.loadUrl("https://$trustedHost/")
        web.postDelayed({ startHandsFreeIfPermitted() }, 1200)
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
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), permissionCode(name))
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

        @JavascriptInterface fun setHandsFree(enabled: Boolean) { handsFreeEnabled = enabled; if (enabled) startHandsFreeIfPermitted() else stopHandsFree() }

        @JavascriptInterface fun speakNative(text: String) { runOnUiThread { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis") } }

        @JavascriptInterface fun status(): String {
            val json = JSONObject()
            permissionMap.forEach { (name, permission) ->
                json.put(name, checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
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
            if (permissions.contains(Manifest.permission.RECORD_AUDIO)) startHandsFreeIfPermitted()
        }
    }

    private fun startHandsFreeIfPermitted() {
        if (!handsFreeEnabled || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !SpeechRecognizer.isRecognitionAvailable(this)) return
        if (speechRecognizer == null) speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { if (handsFreeEnabled) web.postDelayed({ startListeningOnce() }, 700) }
                override fun onResults(results: Bundle?) { handleVoiceResults(results); if (handsFreeEnabled) web.postDelayed({ startListeningOnce() }, 400) }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        startListeningOnce()
    }

    private fun startListeningOnce() {
        if (!handsFreeEnabled) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { speechRecognizer?.startListening(intent) } catch (_: Exception) {}
    }

    private fun handleVoiceResults(results: Bundle?) {
        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: return
        val match = Regex("\\bjarvis\\b[,:\\s]*(.*)", RegexOption.IGNORE_CASE).find(spoken) ?: return
        val command = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (command.isEmpty()) { tts.speak("Yes, Sir?", TextToSpeech.QUEUE_FLUSH, null, "jarvis") ; return }
        val safe = JSONObject.quote(command)
        runOnUiThread { web.evaluateJavascript("window.JARVIS_NATIVE_COMMAND($safe)", null) }
    }

    private fun stopHandsFree() { try { speechRecognizer?.cancel() } catch (_: Exception) {} }

    override fun onDestroy() { stopHandsFree(); speechRecognizer?.destroy(); tts.shutdown(); super.onDestroy() }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
