package com.deeplex.earpage.bridge

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class BridgeException(val code: String, message: String) : Exception(message)

/**
 * Android side of the bridge contract. Methods are registered by their
 * capability-qualified name; unknown methods answer METHOD_NOT_FOUND so a
 * caller never hangs.
 *
 * Web-facing shape is provided by the injected shim (see MainActivity):
 *   window.earPageBridge.{postMessage,onMessage,_dispatch,env}
 * The transport object below is an implementation detail of this shell.
 */
class BridgeHost(
    private val webView: WebView,
    private val isAllowedSender: (isMainFrame: Boolean, url: String?) -> Boolean,
) {
    private val handlers = mutableMapOf<String, (JSONObject?) -> Any?>()
    private val asyncHandlers =
        mutableMapOf<String, (JSONObject?, (Any?, BridgeException?) -> Unit) -> Unit>()

    fun register(method: String, handler: (JSONObject?) -> Any?) {
        handlers[method] = handler
    }

    fun registerAsync(
        method: String,
        handler: (JSONObject?, (Any?, BridgeException?) -> Unit) -> Unit,
    ) {
        asyncHandlers[method] = handler
    }

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.e(TAG, "WEB_MESSAGE_LISTENER is not supported")
            return
        }
        // The page origin is opaque, so "file://" is the narrowest rule that
        // still matches the app's own document. If a WebView rejects it, fall
        // back to "*" and rely on the HTML-document block in the WebViewClient.
        try {
            installListener(setOf("file://"))
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "file:// origin rule rejected; falling back to *", error)
            installListener(setOf("*"))
        }
    }

    private fun installListener(originRules: Set<String>) {
        WebViewCompat.addWebMessageListener(
            webView,
            TRANSPORT,
            originRules,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: androidx.webkit.JavaScriptReplyProxy,
                ) {
                    if (!isAllowedSender(isMainFrame, view.url)) {
                        Log.w(TAG, "ignored bridge message (main=$isMainFrame url=${view.url})")
                        return
                    }
                    val raw = message.data ?: return
                    handle(raw)
                }
            },
        )
    }

    private fun handle(raw: String) {
        val request = try {
            JSONObject(raw)
        } catch (error: Exception) {
            Log.w(TAG, "ignored malformed bridge request")
            return
        }

        val id = request.optString("id", "")
        if (id.isEmpty()) return
        val method = request.optString("method", "")
        val params = request.optJSONObject("params")

        val asyncHandler = asyncHandlers[method]
        if (asyncHandler != null) {
            var settled = false
            asyncHandler(params) { result, failure ->
                if (settled) return@asyncHandler
                settled = true
                sendOutcome(id, method, result, failure)
            }
            return
        }

        val handler = handlers[method]
        if (handler == null) {
            sendOutcome(
                id,
                method,
                null,
                BridgeException("METHOD_NOT_FOUND", "$method is not implemented"),
            )
            return
        }

        val outcome = try {
            handler(params)
        } catch (error: BridgeException) {
            sendOutcome(id, method, null, error)
            return
        } catch (error: Exception) {
            Log.e(TAG, "bridge handler $method failed", error)
            sendOutcome(
                id,
                method,
                null,
                BridgeException("INTERNAL", error.message ?: "internal error"),
            )
            return
        }
        sendOutcome(id, method, outcome, null)
    }

    private fun sendOutcome(
        id: String,
        method: String,
        result: Any?,
        failure: BridgeException?,
    ) {
        val response = if (failure == null) {
            JSONObject()
                .put("id", id)
                .put("ok", true)
                .put("result", result ?: JSONObject.NULL)
        } else {
            JSONObject()
                .put("id", id)
                .put("ok", false)
                .put("error", JSONObject().put("code", failure.code).put("message", failure.message ?: failure.code))
        }
        sendToPage(response.toString())
        Log.i(TAG, "bridge $method id=$id ok=${response.optBoolean("ok")}")
    }

    fun emit(event: String, data: Any?) {
        val payload = JSONObject().put("event", event).put("data", data ?: JSONObject.NULL)
        sendToPage(payload.toString())
    }

    /** Single native -> web path: the injected shim dispatches (and queues) messages. */
    private fun sendToPage(json: String) {
        val literal = JSONObject.quote(json)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        webView.post {
            webView.evaluateJavascript(
                "window.earPageBridge && window.earPageBridge._dispatch($literal);",
                null,
            )
        }
    }

    companion object {
        const val TRANSPORT = "earPageBridgeTransport"
        private const val TAG = "EARPAGE"
    }
}
