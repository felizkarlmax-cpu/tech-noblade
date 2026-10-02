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
import android.speech.tts.UtteranceProgressListener
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
    private var ttsReady = false
    private var recognitionActive = false
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
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts.language = Locale.UK
                ttsReady = true
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (handsFreeEnabled) web.postDelayed({ startListeningOnce() }, 350)
            }
            override fun onError(utteranceId: String?) {
                if (handsFreeEnabled) web.postDelayed({ startListeningOnce() }, 350)
            }
        })
        web.loadUrl("https://$trustedHost/")
        web.postDelayed({
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 9001)
            } else {
                startHandsFreeIfPermitted()
            }
        }, 1500)
    }

    inner class JarvisBridge {
        @JavascriptInterface fun version(): String = "android-companion/0.2.0"
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

        @JavascriptInterface fun setHandsFree(enabled: Boolean) {
            handsFreeEnabled = enabled
            if (enabled) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 9001)
                } else startHandsFreeIfPermitted()
            } else stopHandsFree()
        }

        @JavascriptInterface fun speakNative(text: String) {
            runOnUiThread {
                stopHandsFree()
                if (!ttsReady || text.isBlank()) {
                    if (handsFreeEnabled) web.postDelayed({ startListeningOnce() }, 350)
                    return@runOnUiThread
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
            }
        }

        @JavascriptInterface fun status(): String {
            val json = JSONObject()
            permissionMap.forEach { (name, permission) ->
                json.put(name, checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
            }
            json.put("deviceControl", JarvisAccessibilityService.instance != null)
            json.put("handsFree", handsFreeEnabled)
            json.put("ttsReady", ttsReady)
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
                override fun onEndOfSpeech() { recognitionActive = false }
                override fun onError(error: Int) {
                    recognitionActive = false
                    if (handsFreeEnabled) web.postDelayed({ startListeningOnce() }, 900)
                }
                override fun onResults(results: Bundle?) {
                    recognitionActive = false
                    handleVoiceResults(results)
                    if (handsFreeEnabled && !tts.isSpeaking) web.postDelayed({ startListeningOnce() }, 500)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        startListeningOnce()
    }

    private fun startListeningOnce() {
        if (!handsFreeEnabled || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || tts.isSpeaking || recognitionActive) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        try {
            recognitionActive = true
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            recognitionActive = false
            if (handsFreeEnabled) web.postDelayed({ startListeningOnce() }, 1200)
        }
    }

    private fun handleVoiceResults(results: Bundle?) {
        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: return
        val match = Regex("\\bjarvis\\b[,:\\s]*(.*)", RegexOption.IGNORE_CASE).find(spoken) ?: return
        val command = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (command.isEmpty()) { speakAndResume("Yes, Sir?"); return }
        val safe = JSONObject.quote(command)
        runOnUiThread { web.evaluateJavascript("window.JARVIS_NATIVE_COMMAND($safe)", null) }
    }

    private fun speakAndResume(text: String) {
        stopHandsFree()
        if (!ttsReady || text.isBlank()) {
            if (handsFreeEnabled) web.postDelayed({ startListeningOnce() }, 350)
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun stopHandsFree() { recognitionActive = false; try { speechRecognizer?.cancel() } catch (_: Exception) {} }

    override fun onDestroy() { stopHandsFree(); speechRecognizer?.destroy(); tts.shutdown(); super.onDestroy() }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
