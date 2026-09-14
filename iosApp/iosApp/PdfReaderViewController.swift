import UIKit
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator
import SharedKit

/// Backed by Apple's own PDFKit via Readium's `PDFNavigatorViewController` — no bundled native
/// PDF library needed at all.
///
/// Phase 3 of the shared-reader-chrome redesign (issue #183): this class now only owns what's
/// genuinely native — downloading/opening the publication, the real
/// `PDFNavigatorViewController`, and translating a portable `TocEntry`/page number into a real
/// `Locator`/`Link` jump. The chrome itself (top bar with title, bookmarks/TOC/display-settings
/// sheets, page-number bottom bar) is `:shared`'s `PdfReaderScreen`, embedded via
/// `MainViewControllerKt.PdfReaderViewController(...)` once the navigator is ready.
///
/// Simpler than `EpubReaderViewController`: no highlights/decorations (PDF has bookmarks only),
/// no chrome-hide-on-tap (the bars are always visible — no edge-tap/centre-tap wiring needed;
/// PDFKit's own built-in swipe/scroll navigation handles paging), and no remote-resume banner —
/// `PdfProgressBridge.initialLocatorJson` already resolves the local-vs-server position before
/// this class ever sees it, so there's no separate `positions()` lookup to do here either.
///
/// `serverId`/`bookId`/`digestUrl` mirror `EpubReaderViewController`'s own reasoning — no iOS
/// reader recorded reading position before Phase 2 introduced this shape.
final class PdfReaderViewController: UIViewController {
    private let url: URL
    private let authHeader: String?
    private let serverId: String
    private let bookId: String
    private let digestUrl: String?

    private let loadingIndicator = UIActivityIndicatorView(style: .large)
    private var navigator: PDFNavigatorViewController?
    private var publication: Publication?
    /// Flattened table of contents — index in this array is the `TocEntry.ref` string the
    /// shared chrome hands back to `goToToc(_:)`, mirroring the EPUB reader's own `flatToc`.
    private var flatToc: [(depth: Int, link: Link)] = []
    private var tocEntries: [TocEntry] = []
    private var currentLocator: Locator?
    private var saveTask: Task<Void, Never>?

    init(url: URL, authHeader: String?, serverId: String, bookId: String, digestUrl: String?) {
        self.url = url
        self.authHeader = authHeader
        self.serverId = serverId
        self.bookId = bookId
        self.digestUrl = digestUrl
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
        // Same AbsoluteURL requirement as EpubReaderViewController — Foundation's URL doesn't
        // conform, HTTPURL(string:) is Readium's real constructor for a remote http(s) URL.
        guard let httpURL = HTTPURL(string: url.absoluteString) else {
            showError("Couldn't open this PDF.")
            return
        }

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

        guard case let .success(asset) = await assetRetriever.retrieve(url: httpURL) else {
            showError("Couldn't open this PDF.")
            return
        }

        guard case let .success(publication) = await publicationOpener.open(
            asset: asset,
            allowUserInteraction: true
        ) else {
            showError("Couldn't open this PDF.")
            return
        }
        self.publication = publication

        // issue #183: same flattened order as the EPUB reader's own — `manifest
        // .tableOfContents`, not `publication.tableOfContents` (Publication doesn't forward
        // that one property, confirmed against its real source).
        flatToc = Self.flatten(publication.manifest.tableOfContents)
        tocEntries = flatToc.enumerated().map { index, entry in
            TocEntry(depth: Int32(entry.depth), title: entry.link.title ?? entry.link.href, ref: String(index))
        }

        // issue #183: PdfProgressBridge already resolved the local-vs-server position (PDF has
        // no remote-resume banner, so there's nothing left for this class to decide) — just
        // parse whatever it hands back and open there.
        let initialJson = await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
            MainViewControllerKt.pdfProgressBridge().initialLocatorJson(
                serverId: serverId, bookId: bookId, digestUrl: digestUrl
            ) { json in
                continuation.resume(returning: json)
            }
        }
        let initialLocator = initialJson.flatMap { try? Locator(jsonString: $0) }

        do {
            let nav = try PDFNavigatorViewController(
                publication: publication,
                initialLocation: initialLocator,
                config: .init()
            )
            nav.delegate = self
            navigator = nav
            currentLocator = initialLocator
            embedChrome(nav)
        } catch {
            NSLog("[PdfReader] PDFNavigatorViewController init threw: \(error)")
            showError("Couldn't open this PDF.")
        }
    }

    /// Wires this reader's own bridge (issue #183) and embeds `:shared`'s `PdfReaderScreen`
    /// (which itself embeds `navigator` via `UIKitViewController`) as this view controller's
    /// sole child — replacing the loading indicator.
    private func embedChrome(_ nav: PDFNavigatorViewController) {
        loadingIndicator.stopAnimating()
        loadingIndicator.removeFromSuperview()

        pushState()
        MainViewControllerKt.setPdfReaderActions(
            goToPage: { [weak self] page in self?.goToPage(Int(page.int32Value)) },
            goToLocatorJson: { [weak self] json in self?.goToLocatorJson(json) },
            goToToc: { [weak self] entry in self?.goToToc(entry) },
            submitPreferences: { [weak self] prefs in self?.submitPreferences(prefs) }
        )

        let chrome = MainViewControllerKt.PdfReaderViewController(
            onBack: { [weak self] in self?.dismiss(animated: true) },
            navigatorViewController: nav,
            serverId: serverId,
            bookId: bookId
        )
        addChild(chrome)
        chrome.view.frame = view.bounds
        chrome.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(chrome.view)
        chrome.didMove(toParent: self)
    }

    /// Pushes the current screen/locator state — called once right after the navigator is
    /// ready, and again from `locationDidChange` on every position change.
    private func pushState() {
        let pageCount = max(publication?.metadata.numberOfPages ?? publication?.readingOrder.count ?? 1, 1)
        MainViewControllerKt.updatePdfReaderState(
            state: PdfReaderNativeState(
                screenState: PdfReaderUiStateReady(
                    title: publication?.metadata.title ?? "",
                    toc: tocEntries,
                    pageCount: Int32(pageCount)
                ),
                currentLocatorJson: try? currentLocator?.jsonString()
            )
        )
    }

    /// The page slider (and a bookmark with an extractable page number) resolve to a plain
    /// page — build a locator against the publication's first reading-order resource, the
    /// same base Android's own `goToPage`/`goToBookmark` use for a single-resource PDF.
    private func goToPage(_ page: Int) {
        Task {
            var target = currentLocator
            if target == nil, let firstLink = publication?.readingOrder.first {
                target = await publication?.locate(firstLink)
            }
            target?.locations.position = page
            guard let target else { return }
            _ = await navigator?.go(to: target, options: .init())
        }
    }

    private func goToLocatorJson(_ json: String) {
        guard let locator = try? Locator(jsonString: json) else { return }
        Task { _ = await navigator?.go(to: locator, options: .init()) }
    }

    private func goToToc(_ entry: TocEntry) {
        guard let index = Int(entry.ref), flatToc.indices.contains(index) else { return }
        Task { _ = await navigator?.go(to: flatToc[index].link, options: .init()) }
    }

    /// The Swift-side equivalent of Android's `PdfPreferencesMapping.kt` — deliberately as
    /// narrow as that one: PDFKit/PDFium both only honour fit + scroll axis here, and the page
    /// background comes from the shared chrome's own `Scaffold` colour, not the engine.
    private func submitPreferences(_ prefs: DatastoreReaderDisplayPreferences) {
        let pdfPrefs = PDFPreferences(
            fit: prefs.fitMode == DatastoreReaderFitMode.pageWidth ? .width : .page,
            scroll: prefs.scroll,
            scrollAxis: prefs.scrollMode.scrolling ? .vertical : .horizontal
        )
        navigator?.submitPreferences(pdfPrefs)
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

    private static func flatten(_ links: [Link], depth: Int = 0) -> [(depth: Int, link: Link)] {
        links.flatMap { link in [(depth, link)] + flatten(link.children, depth: depth + 1) }
    }
}

extension PdfReaderViewController {
    /// Presents the reader full-screen, dismissed by the standard edge-swipe gesture (issue
    /// #176). `hidesNavigationBar: true` (issue #183) — the shared chrome draws its own
    /// `BackPill` as part of its Compose content, same reason the EPUB/player screens pass it.
    static func presentable(url: URL, authHeader: String?, serverId: String, bookId: String, digestUrl: String?) -> UIViewController {
        let reader = PdfReaderViewController(
            url: url, authHeader: authHeader, serverId: serverId, bookId: bookId, digestUrl: digestUrl
        )
        return FullScreenReaderPresentation.wrap(reader, hidesNavigationBar: true)
    }
}

extension PdfReaderViewController: PDFNavigatorDelegate {
    /// Pushes the new position into the shared chrome and schedules a debounced (~1s, same
    /// cadence as Android's `locatorUpdates.debounce(1_000)`) save via `PdfProgressBridge`.
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        currentLocator = locator
        pushState()

        saveTask?.cancel()
        saveTask = Task { [serverId, bookId] in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            guard !Task.isCancelled, let json = try? locator.jsonString() else { return }
            MainViewControllerKt.pdfProgressBridge().save(
                serverId: serverId,
                bookId: bookId,
                locatorJson: json,
                percent: locator.locations.totalProgression.map { KotlinDouble(double: $0) }
            )
        }
    }

    /// `NavigatorDelegate`'s one method with no default implementation. Non-fatal — nothing to
    /// surface yet in this reader.
    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {}
}
