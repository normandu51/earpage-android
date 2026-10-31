package com.deeplex.earpage

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.deeplex.earpage.bridge.BridgeHost
import com.deeplex.earpage.recorder.RecorderCapability
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var bridge: BridgeHost
    private lateinit var recorder: RecorderCapability

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (::recorder.isInitialized) recorder.onPermissionResult(granted)
        }

    private var loaded = false
    private var safeAreaJson = ZERO_INSETS

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (isDebuggable()) {
            WebView.setWebContentsDebuggingEnabled(true)
            Log.i(TAG, "WebView inspection enabled (chrome://inspect)")
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = isAppLocal(request.url)
                    if (!allowed) Log.w(TAG, "blocked navigation: ${request.url}")
                    return !allowed
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url
                    if (isAppLocal(url)) return null
                    val accept = request.requestHeaders["Accept"]?.lowercase() ?: ""
                    if (request.isForMainFrame || accept.contains("text/html")) {
                        Log.w(TAG, "blocked remote document: $url")
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            403,
                            "Forbidden",
                            emptyMap(),
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                    return null
                }
            }
        }

        bridge = BridgeHost(webView) { isMainFrame, url ->
            isMainFrame && url != null && url.startsWith(APP_BASE_URL)
        }
        bridge.install()
        recorder = RecorderCapability(this, bridge) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        recorder.register()

        val root = FrameLayout(this).apply {
            addView(
                webView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            onInsets(cssInsets(insets))
            insets
        }
        ViewCompat.requestApplyInsets(root)

        root.post {
            if (!loaded) {
                onInsets(ViewCompat.getRootWindowInsets(root)?.let { cssInsets(it) } ?: ZERO_INSETS)
            }
        }
    }

    /**
     * The first inset report installs the host interface (so its boot value is
     * present before page scripts) and loads the app. Later reports are pushed
     * through the same interface.
     */
    private fun onInsets(json: String) {
        if (!loaded) {
            loaded = true
            safeAreaJson = json
            installHostInterface(json)
            loadApp()
            return
        }
        if (json == safeAreaJson) return
        safeAreaJson = json
        Log.i(TAG, "safe-area event insets=$json")
        bridge.emit("safe-area", JSONObject().put("insets", JSONObject(json)))
    }

    private fun installHostInterface(safeArea: String) {
        val env = """{"safeArea":$safeArea}"""
        val script = """
            (function () {
              var queue = [];
              var handler = null;
              window.earPageBridge = {
                env: $env,
                postMessage: function (message) {
                  window.${BridgeHost.TRANSPORT}.postMessage(String(message));
                },
                onMessage: function (fn) {
                  handler = fn;
                  while (queue.length) { fn(queue.shift()); }
                },
                _dispatch: function (message) {
                  if (handler) { handler(message); } else { queue.push(message); }
                }
              };
              window.__earPageSelfTestRequested = $selfTestRequested;
            })();
        """.trimIndent()

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.e(TAG, "DOCUMENT_START_SCRIPT unsupported; host interface unavailable")
            return
        }
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("file://"))
        } catch (error: IllegalArgumentException) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }
        Log.i(TAG, "host interface installed env=$env")
    }

    private fun loadApp() {
        Log.i(TAG, "loading ${APP_BASE_URL}index.html")
        webView.loadUrl("${APP_BASE_URL}index.html")
        scheduleDebugHooks()
    }

    private fun scheduleDebugHooks() {
        if (!isDebuggable()) return

        if (selfTestRequested) {
            webView.postDelayed({
                webView.evaluateJavascript("window.earPageSelfTest && window.earPageSelfTest();", null)
            }, 1_500)
            webView.postDelayed({
                webView.evaluateJavascript("JSON.stringify(window.__earPageSelfTestResult || null)") { value ->
                    Log.i(TAG, "self-test result=$value")
                }
            }, 10_000)
        }

        if (intent.getBooleanExtra("earPageNavigationTest", false)) {
            webView.postDelayed({
                webView.evaluateJavascript("location.href='https://example.com/'", null)
            }, 2_000)
        }
    }

    private fun cssInsets(insets: WindowInsetsCompat): String {
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val density = resources.displayMetrics.density.toDouble()
        fun css(px: Int): String = String.format(Locale.US, "%.2f", px / density)
        return """{"top":${css(bars.top)},"right":${css(bars.right)},"bottom":${css(bars.bottom)},"left":${css(bars.left)}}"""
    }

    private fun isAppLocal(uri: Uri): Boolean =
        uri.scheme == "file" && uri.toString().startsWith(APP_BASE_URL)

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val selfTestRequested: Boolean
        get() = isDebuggable() && intent.getBooleanExtra("earPageSelfTest", false)

    private companion object {
        const val TAG = "EARPAGE"
        const val APP_BASE_URL = "file:///android_asset/www/"
        const val ZERO_INSETS = """{"top":0,"right":0,"bottom":0,"left":0}"""
    }
}
