package net.dexxicon.reader.shared.sso

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Hosts the SSO WebView flow's browser step. The WebView widget itself is inherently
 * platform-specific (`android.webkit.WebView` vs `platform.WebKit.WKWebView`) — everything
 * that isn't ("does this URL look like the redirect", building the authorize URL, reading
 * `code`/`state`/`error` off it) is real, shared logic in [buildAuthorizeUrl]/
 * [readOidcRedirect] (commonMain, `OidcRedirect.kt`), so each `actual` only has to:
 *  1. load [url];
 *  2. watch every URL the WebView navigates or attempts to navigate to;
 *  3. the moment one starts with [redirectPrefix], cancel that navigation (the redirect
 *     target usually isn't a real page — BookOrbit/Grimmory's own callback path — and even
 *     when it is, the code shouldn't be spent by actually loading it) and report the full
 *     URL via [onRedirect] exactly once.
 *
 * [onProgress] (0–100, or values outside that range to mean "no update") is best-effort —
 * the iOS actual may not wire it up; it's cosmetic (a progress bar), never load-bearing.
 */
@Composable
expect fun SsoWebView(
    url: String,
    redirectPrefix: String,
    modifier: Modifier,
    onProgress: (Int) -> Unit,
    onRedirect: (capturedUrl: String) -> Unit,
)
