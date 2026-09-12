import UIKit
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

/// Real Readium Swift Toolkit EPUB reader (issue #101, following #99's plumbing) — also the
/// reader for MOBI/AZW3/FB2 (server-converted to EPUB before either client requests bytes,
/// see `ReaderLaunch.kt`) and for CBZ/CBR comics (issue #106: Readium's own changelog, 3.8.0,
/// deprecated a separate `CBZNavigatorViewController` in favor of this exact reuse — kept
/// this class's name regardless, matching Readium's own precedent of keeping
/// `EPUBNavigatorViewController`'s name even once it also opens comics). CBR specifically
/// (issue #107) is normalized to a real ZIP/CBZ by `ComicArchiveNormalizer` before it ever
/// reaches Readium — see `openPublication()`.
///
/// Opens `url` — already an absolute, resolved acquisition URL — with `authHeader` — already
/// resolved by `:shared`'s `AppContainer.authHeaderProvider` — attached to every request.
/// Mirrors what Android's `core/reader/PublicationStreamer` does with its authenticated
/// OkHttp client: neither platform reader needs its own path back into the auth layer.
///
/// `isManga` (issue #108) drives two things, both real Readium Swift extension points, not
/// custom rendering: the `ReadingProgression` preference (right-to-left page order) and
/// which edge of [EdgeTapNavigator]'s tap zones means "next page". Deliberately **not**
/// included this pass — see the "iOS Reading Support" proposal's Comics feature-parity
/// section: Android's `ComicPanelDetector`/`PanelSteppingNavigator` "smart zoom" (a from-
/// scratch pixel-analysis algorithm with zero Readium involvement even on Android — real new
/// work, deferred until there's a real device to judge its output on) and its custom
/// drag-page-turn-with-live-peel-preview gesture (Readium's own built-in paginated-mode swipe
/// already turns pages for free, same as it already does for EPUB — the custom peel visual on
/// top of that is a cosmetic addition, not a functional gap, and wasn't sized accurately in
/// the original feasibility report).
///
/// Not yet verified beyond compiling — no Mac available locally; the real test is a
/// triggered `ios-ci` run.
final class EpubReaderViewController: UIViewController {
    private let url: URL
    private let authHeader: String?
    private let isManga: Bool

    private let loadingIndicator = UIActivityIndicatorView(style: .large)
    private var navigatorViewController: EPUBNavigatorViewController?

    init(url: URL, authHeader: String?, isManga: Bool) {
        self.url = url
        self.authHeader = authHeader
        self.isManga = isManga
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(loadingIndicator)
        NSLayoutConstraint.activate([
            loadingIndicator.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            loadingIndicator.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
        loadingIndicator.startAnimating()

        Task { await openPublication() }
    }

    @MainActor
    private func openPublication() async {
        // issue #107: a CBR needs unpacking + repacking as a real ZIP before Readium's format
        // sniffing (ZIP-only, same as the Kotlin toolkit) can do anything with it at all — the
        // normalizer downloads it in full (RAR can't be range-streamed) and hands back a local
        // file:// URL to the resulting CBZ. Every other format (including CBZ itself) is
        // untouched by this check and keeps streaming straight from the server.
        let resolvedURL: any AbsoluteURL
        if ComicArchiveNormalizer.looksLikeRar(url: url, mediaType: nil) {
            guard let cbzURL = try? await ComicArchiveNormalizer.normalize(url: url, authHeader: authHeader),
                  let fileURL = FileURL(url: cbzURL) else {
                showError("Couldn't open this CBR comic.")
                return
            }
            resolvedURL = fileURL
        } else {
            // AssetRetriever.retrieve(url:) takes Readium's own AbsoluteURL protocol, not
            // Foundation's URL — HTTPURL(string:) is Readium's real, documented constructor
            // for a remote http(s) URL (confirmed against ios-ci's actual compiler error, not
            // guessed a second time: `argument type 'URL' does not conform to expected type
            // 'AbsoluteURL'`).
            guard let httpURL = HTTPURL(string: url.absoluteString) else {
                showError("Couldn't open this book.")
                return
            }
            resolvedURL = httpURL
        }

        // additionalHeaders applies to every request this client makes — the same one-time
        // attachment Android's authenticated OkHttp client does, just via Readium Swift's own
        // HTTP client instead of Ktor. A normalized CBR is read from a local file, so it never
        // makes another authenticated request — the header was already spent downloading it.
        let httpClient = DefaultHTTPClient(
            additionalHeaders: authHeader.map { ["Authorization": $0] }
        )
        let assetRetriever = AssetRetriever(httpClient: httpClient)
        let publicationOpener = PublicationOpener(
            parser: DefaultPublicationParser(
                httpClient: httpClient,
                assetRetriever: assetRetriever,
                pdfFactory: DefaultPDFDocumentFactory()
            )
        )

        guard case let .success(asset) = await assetRetriever.retrieve(url: resolvedURL) else {
            showError("Couldn't open this book.")
            return
        }

        guard case let .success(publication) = await publicationOpener.open(
            asset: asset,
            allowUserInteraction: true
        ) else {
            showError("Couldn't open this book.")
            return
        }

        do {
            // issue #108: manga reads right-to-left — nil (not .ltr) lets a non-manga book
            // fall back to its own publication metadata instead of forcing left-to-right on
            // something that might disagree (e.g. an Arabic EPUB).
            let config = EPUBNavigatorViewController.Configuration(
                preferences: EPUBPreferences(readingProgression: isManga ? .rtl : nil)
            )
            let navigator = try EPUBNavigatorViewController(
                publication: publication,
                initialLocation: nil,
                config: config
            )
            navigator.delegate = self
            embed(navigator)
        } catch {
            showError("Couldn't open this book.")
        }
    }

    private func embed(_ navigator: EPUBNavigatorViewController) {
        loadingIndicator.stopAnimating()
        loadingIndicator.removeFromSuperview()

        navigatorViewController = navigator
        addChild(navigator)
        navigator.view.frame = view.bounds
        navigator.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(navigator.view)
        navigator.didMove(toParent: self)
    }

    private func showError(_ message: String) {
        loadingIndicator.stopAnimating()
        loadingIndicator.removeFromSuperview()

        let label = UILabel()
        label.text = message
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),
        ])
    }
}

extension EpubReaderViewController {
    /// Wraps the reader in its own `UINavigationController` with a "Done" button, ready to
    /// present modally from any view controller — no dependency on the app having its own
    /// root navigation controller (it doesn't, today).
    static func presentable(url: URL, authHeader: String?, isManga: Bool) -> UIViewController {
        let reader = EpubReaderViewController(url: url, authHeader: authHeader, isManga: isManga)
        reader.title = "Reading"
        reader.navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done,
            target: reader,
            action: #selector(EpubReaderViewController.close)
        )
        return UINavigationController(rootViewController: reader)
    }

    @objc private func close() {
        // Calling dismiss on any view controller in a presented stack forwards to whichever
        // ancestor actually did the presenting — standard UIKit behavior, not specific to
        // this being wrapped in its own UINavigationController.
        dismiss(animated: true)
    }
}

/// Edge-tap page turning (issue #108) — the Swift equivalent of Android's
/// `core/reader/EdgeTapNavigator`: tap the left/right `edgeFraction` of the screen to turn a
/// page, RTL-aware (same reasoning as Android's own comment — a manga read right-to-left still
/// needs the *physical* left edge to mean "next", not whichever `goForward`/`goBackward`
/// happens to mean by default). A middle tap is a no-op for now — Android's version toggles
/// reader chrome, which this minimal reader doesn't have yet.
///
/// This hooks Readium's own extension point rather than adding a competing gesture
/// recognizer: `EPUBNavigatorViewController` already forwards unhandled taps to
/// `delegate?.navigator(self, didTapAt:)` internally (confirmed against its real source), so
/// conforming to `EPUBNavigatorDelegate` and implementing that one method is all that's
/// needed — no fighting with the navigator's own WKWebView-based tap handling.
extension EpubReaderViewController: EPUBNavigatorDelegate {
    private static let edgeFraction: CGFloat = 0.28

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        let width = navigator.view.bounds.width
        guard width > 0 else { return }

        if point.x < width * Self.edgeFraction {
            turnPage(navigator, physicallyForward: false)
        } else if point.x > width * (1 - Self.edgeFraction) {
            turnPage(navigator, physicallyForward: true)
        }
        // Centre tap: no reader chrome to toggle yet, so intentionally a no-op.
    }

    private func turnPage(_ navigator: VisualNavigator, physicallyForward: Bool) {
        // Same inversion as Android's EdgeTapNavigator: a manga's *reading* forward is the
        // physical left edge, not the right, so flip which Navigator call the tapped edge
        // maps to rather than reinterpreting the tap location itself.
        let readingForward = physicallyForward != isManga
        Task {
            if readingForward {
                _ = await navigator.goForward(options: .animated)
            } else {
                _ = await navigator.goBackward(options: .animated)
            }
        }
    }

    /// `NavigatorDelegate`'s one method with no default implementation (every other method
    /// on `EPUBNavigatorDelegate`'s whole protocol chain — `VisualNavigatorDelegate`,
    /// `SelectableNavigatorDelegate`, `ViewportObservingNavigatorDelegate` — has one,
    /// confirmed against their real source before relying on it). Non-fatal (e.g. a
    /// DRM copy-forbidden action) — nothing to surface yet in this minimal reader; the
    /// full-screen `showError(_:)` path is reserved for actual open failures.
    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {}
}
