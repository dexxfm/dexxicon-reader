package net.dexxicon.reader.shared

import net.dexxicon.reader.core.model.ContentFormat

/**
 * Hand-off point from `:shared`'s Compose UI to a genuinely native, per-platform reading
 * screen (issue #99 — see the "iOS Reading Support" proposal for why: no KMP reading engine
 * fits what this app already depends on, so reading is native Swift on iOS, using Readium's
 * own Swift Toolkit, while Android keeps its existing, untouched native reader stack).
 *
 * Plain data crosses this boundary — [url] and [authHeader] are already resolved (via
 * [net.dexxicon.reader.shared.di.AppContainer.authHeaderProvider]) by the caller, so the
 * platform side never needs to reach back into `:shared`'s auth/network layer just to open a
 * book. [authHeader] is null when the acquisition needs no `Authorization` header at all (an
 * open-access acquisition, or a server type
 * [net.dexxicon.reader.core.network.AuthHeaderProvider] doesn't recognize).
 *
 * This is a plain Kotlin function type, not an interop object, and deliberately so: Kotlin/
 * Native can't call arbitrary third-party Swift code directly — only Objective-C/C, per
 * Kotlin's own interop docs — so a Swift-side reader can't be instantiated *from* Kotlin
 * without a real `@objc` + cinterop bridge. The natural, already-supported direction is the
 * other way: Swift implements this closure when it constructs `MainViewController`, exactly
 * like every other callback (`onOpenBook`, `onBack`, …) already crossing into this shared UI
 * — and, once invoked, Swift's own `UINavigationController` pushes a fully native screen,
 * entirely outside Compose's render tree.
 */
typealias OnOpenReader = (
    serverId: String,
    bookId: String,
    format: ContentFormat,
    url: String,
    authHeader: String?,
) -> Unit
