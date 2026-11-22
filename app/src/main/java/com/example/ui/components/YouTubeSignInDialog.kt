package com.example.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.remote.YouTubeMusicClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val YOUTUBE_SIGNIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F"
private const val YT_DIAG_TAG = "PULSE_YT_RESOLVER"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInDialog(
    onDismiss: () -> Unit,
    onSessionCaptured: (cookie: String, visitorData: String, accountLabel: String) -> Unit
) {
    var loadProgress by remember { mutableIntStateOf(10) }
    var pageTitle by remember { mutableStateOf("Sign in to YouTube") }
    var statusNotice by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var capturedOnce by remember { mutableStateOf(false) }

    fun tryExtractYouTubeAuth(view: WebView?, url: String?, isManualTrigger: Boolean) {
        if (capturedOnce) return
        val currentUrl = url.orEmpty()
        val host = currentUrl.toHttpUrlOrNull()?.host.orEmpty().lowercase()
        val isYouTubeHost = host == "music.youtube.com" ||
            host == "www.youtube.com" ||
            host == "m.youtube.com" ||
            host == "youtube.com"

        val cm = CookieManager.getInstance()
        val musicCookie = cm.getCookie("https://music.youtube.com").orEmpty()
        val rootCookie = cm.getCookie("https://www.youtube.com").orEmpty()
        val mergedCookie = YouTubeMusicClient.mergeCookieHeaders(rootCookie, musicCookie)
        val components = YouTubeMusicClient.inspectCookieComponents(mergedCookie)

        if (!isYouTubeHost && !isManualTrigger) return
        if (!components.isAuthenticated) {
            if (isManualTrigger || isYouTubeHost) {
                Log.d(
                    YT_DIAG_TAG,
                    "auth_dialog_not_ready manual=$isManualTrigger host=$host cookieCount=${components.cookieCount} hasSapisid=${components.hasSapisid} hasSid=${components.hasSid} hasHsidOrSsid=${components.hasHsidOrSsid}"
                )
            }
            if (isManualTrigger) {
                statusNotice = "Sign-in is not finished yet. Complete Google sign-in on YouTube first."
            }
            return
        }

        val js = "(function(){try{var g=window.ytcfg&&ytcfg.get?ytcfg.get.bind(ytcfg):null;var v=g?(g('VISITOR_DATA')||''):'';var l=g?(g('LOGGED_IN')===true?'1':(g('LOGGED_IN')===false?'0':'')):'';return JSON.stringify({v:v,l:l});}catch(e){return '';}})()"
        if (view != null) {
            view.evaluateJavascript(js) { rawJson ->
                if (capturedOnce) return@evaluateJavascript
                val parsedObj = rawJson
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                val loggedInFlag = parsedObj?.optString("l").orEmpty()
                if (loggedInFlag == "0" && !isManualTrigger) {
                    Log.w(YT_DIAG_TAG, "auth_dialog_ytcfg_not_logged_in host=$host cookieCount=${components.cookieCount}")
                    return@evaluateJavascript
                }
                val visitor = parsedObj?.optString("v")
                    ?.takeUnless { it == "null" || it == "undefined" }
                    .orEmpty()
                capturedOnce = true
                cm.flush()
                Log.i(
                    YT_DIAG_TAG,
                    "auth_dialog_captured manual=$isManualTrigger host=$host cookieCount=${components.cookieCount} hasSapisid=${components.hasSapisid} hasSid=${components.hasSid} hasHsidOrSsid=${components.hasHsidOrSsid} hasVisitorData=${visitor.isNotBlank()}"
                )
                onSessionCaptured(mergedCookie, visitor, "YouTube Music Connected")
            }
        } else {
            capturedOnce = true
            cm.flush()
            Log.i(
                YT_DIAG_TAG,
                "auth_dialog_captured_no_view manual=$isManualTrigger cookieCount=${components.cookieCount} hasSapisid=${components.hasSapisid} hasSid=${components.hasSid} hasVisitorData=false"
            )
            onSessionCaptured(mergedCookie, "", "YouTube Music Connected")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("youtube_signin_close_button")
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close YouTube sign-in")
                        }
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Connect YouTube",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = pageTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    TextButton(
                        onClick = { tryExtractYouTubeAuth(webViewRef, webViewRef?.url, isManualTrigger = true) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("youtube_signin_done_button")
                    ) {
                        Text("Done")
                    }
                }

                statusNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                if (loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)

                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank()) {
                                            pageTitle = title
                                        }
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        statusNotice = null
                                        tryExtractYouTubeAuth(view, url, isManualTrigger = false)
                                    }
                                }

                                webViewRef = this
                                Log.i(YT_DIAG_TAG, "auth_dialog_opened")
                                loadUrl(YOUTUBE_SIGNIN_URL)
                            }
                        }
                    )
                }
            }

            BackHandler {
                val wv = webViewRef
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    onDismiss()
                }
            }
        }
    }
}
