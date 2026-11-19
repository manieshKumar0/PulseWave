package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
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

private const val YOUTUBE_SIGNIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInDialog(
    onDismiss: () -> Unit,
    onSessionCaptured: (cookie: String, visitorData: String, accountLabel: String) -> Unit
) {
    var loadProgress by remember { mutableIntStateOf(10) }
    var pageTitle by remember { mutableStateOf("Sign in to YouTube") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var capturedOnce by remember { mutableStateOf(false) }

    fun tryExtractYouTubeAuth(view: WebView?, url: String?) {
        if (capturedOnce) return
        val currentUrl = url.orEmpty()
        val isYouTubeHost = currentUrl.contains("music.youtube.com", ignoreCase = true) ||
            currentUrl.contains("www.youtube.com", ignoreCase = true) ||
            currentUrl.contains("m.youtube.com", ignoreCase = true)
        if (!isYouTubeHost) return

        val cm = CookieManager.getInstance()
        val musicCookie = cm.getCookie("https://music.youtube.com").orEmpty()
        val rootCookie = cm.getCookie("https://www.youtube.com").orEmpty()
        val mergedCookie = when {
            musicCookie.contains("SAPISID=") || musicCookie.contains("__Secure-3PAPISID=") -> musicCookie
            rootCookie.contains("SAPISID=") || rootCookie.contains("__Secure-3PAPISID=") -> rootCookie
            else -> ""
        }
        if (mergedCookie.isBlank()) return

        capturedOnce = true
        cm.flush()

        val js = "(function(){try{var v=(window.ytcfg&&ytcfg.get)?ytcfg.get('VISITOR_DATA'):'';return v||'';}catch(e){return '';}})()"
        if (view != null) {
            view.evaluateJavascript(js) { rawVisitor ->
                val visitor = rawVisitor
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.takeUnless { it == "null" || it == "undefined" }
                    .orEmpty()
                onSessionCaptured(mergedCookie, visitor, "YouTube Music Connected")
            }
        } else {
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
                        onClick = { tryExtractYouTubeAuth(webViewRef, webViewRef?.url) },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Done")
                    }
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
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        tryExtractYouTubeAuth(view, url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        tryExtractYouTubeAuth(view, url)
                                    }
                                }

                                webViewRef = this
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
