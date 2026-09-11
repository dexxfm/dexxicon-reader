package net.dexxicon.reader.shared.sso

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * iOS [actual]: `WKWebView` via Compose Multiplatform's `UIKitView`, with a
 * `WKNavigationDelegateProtocol` implementation intercepting `decidePolicyForNavigationAction`
 * — the WKWebView equivalent of the Android actual's `shouldOverrideUrlLoading`. This is real
 * Objective-C protocol conformance in Kotlin/Native, unverifiable on this Windows dev machine
 * (no local iOS toolchain) and only checkable via Codemagic's `ios-ci` — see [SsoWebView]'s
 * doc comment and issue #70 for the wider caveat.
 *
 * Deliberately does NOT implement `onProgress` — `WKWebView`'s loading progress is a
 * KVO-observable property (`estimatedProgress`), not a delegate callback, and bridging KVO
 * into Kotlin/Native correctly is its own real piece of interop this flow doesn't need
 * ([onProgress] is documented as best-effort/cosmetic for exactly this reason). The redirect
 * interception below is the one piece of behavior this screen actually depends on.
 */
@Composable
actual fun SsoWebView(
    url: String,
    redirectPrefix: String,
    modifier: Modifier,
    onProgress: (Int) -> Unit,
    onRedirect: (capturedUrl: String) -> Unit,
) {
    val delegate = remember(redirectPrefix) { RedirectInterceptingDelegate(redirectPrefix) }
    delegate.onRedirect = onRedirect

    UIKitView(
        factory = {
            WKWebView().apply {
                navigationDelegate = delegate
                loadRequest(NSURLRequest(URL = NSURL(string = url)))
            }
        },
        modifier = modifier,
    )
}

private class RedirectInterceptingDelegate(
    private val redirectPrefix: String,
) : NSObject(), WKNavigationDelegateProtocol {

    var onRedirect: ((String) -> Unit)? = null
    private var finished = false

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val targetUrl = decidePolicyForNavigationAction.request.URL?.absoluteString
        if (!finished && targetUrl != null && targetUrl.startsWith(redirectPrefix)) {
            finished = true
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            onRedirect?.invoke(targetUrl)
            return
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
    }
}
