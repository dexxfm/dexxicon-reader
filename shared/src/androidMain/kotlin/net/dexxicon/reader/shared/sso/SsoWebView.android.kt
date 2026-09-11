package net.dexxicon.reader.shared.sso

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Android [actual]: ported near-verbatim from the native `feature/servers`
 * `OidcWebViewScreen`'s `CallbackInterceptingClient` — same dual-hook interception
 * (`shouldOverrideUrlLoading` for real link/redirect navigations, `onPageStarted` as a
 * fallback for redirects some servers issue as an HTTP 30x rather than a client-side one). */
@Composable
actual fun SsoWebView(
    url: String,
    redirectPrefix: String,
    modifier: Modifier,
    onProgress: (Int) -> Unit,
    onRedirect: (capturedUrl: String) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.databaseEnabled = true
                webChromeClient = WebChromeClient()
                webViewClient = RedirectInterceptingClient(redirectPrefix, onRedirect, onProgress)
                loadUrl(url)
            }
        },
    )
}

private class RedirectInterceptingClient(
    private val redirectPrefix: String,
    private val onRedirect: (String) -> Unit,
    private val onProgress: (Int) -> Unit,
) : WebViewClient() {

    private var finished = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        maybeIntercept(request.url.toString())

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        onProgress(10)
        if (url != null && maybeIntercept(url)) view?.stopLoading()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onProgress(100)
    }

    private fun maybeIntercept(url: String): Boolean {
        if (finished) return true
        if (!url.startsWith(redirectPrefix)) return false
        finished = true
        onRedirect(url)
        return true
    }
}
