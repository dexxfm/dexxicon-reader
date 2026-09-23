import UIKit
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator
import SharedKit

/// Real Readium Swift Toolkit EPUB reader (issue #101, following #99's plumbing) — also the
/// reader for MOBI/AZW3/FB2 (server-converted to EPUB before either client requests bytes,
/// see `ReaderLaunch.kt`).
///
/// Phase 2 of the shared-reader-chrome redesign (issue #183): this class now only owns what's
/// genuinely native — downloading/opening the publication, the real `EPUBNavigatorViewController`,
/// decoration rendering, edge-tap + centre-tap (chrome toggle), the text-selection "Highlight"
/// menu item, and translating a portable `TocEntry`/`Bookmark`/`Highlight` into a real
/// `Locator`/`Link` jump. The chrome itself (top bar, TOC/bookmarks/highlights/display-settings
/// sheets) is `:shared`'s `EpubReaderScreen`, embedded via `MainViewControllerKt
/// .EpubReaderViewController(...)` once the navigator is ready — see `embedChrome(_:toc:)`.
///
/// Comics (CBZ/CBR) do **not** go through this class (issue #179) — see `ComicPagerViewController`.
///
/// Opens `url` — already an absolute, resolved acquisition URL — with `authHeader` — already
/// resolved by `:shared`'s `AppContainer.authHeaderProvider` — attached to every request.
///
/// `isManga` (issue #108) drives the `ReadingProgression` preference (right-to-left page order)
/// and which edge of the tap zones means "next page".
///
/// `serverId`/`bookId` (issue #183) let this reader save/restore reading position and record
/// bookmarks/highlights against the same portable repositories Android already shares — no iOS
/// reader recorded any of this before this change. `digestUrl` is the acquisition URL, doubling
/// as the same "remote file reference" Android's readers keep even when reading a local copy.
final class EpubReaderViewController: UIViewController {
    private let url: URL
    private let authHeader: String?
    private let isManga: Bool
    private let serverId: String
    private let bookId: String
    private let digestUrl: String?

    private let loadingIndicator = UIActivityIndicatorView(style: .large)
    private var navigator: EPUBNavigatorViewController?
    private var publication: Publication?
    /// Flattened table of contents — index in this array is the `TocEntry.ref` string the
    /// shared chrome hands back to `goToToc(_:)`, mirroring Android's own `flatToc` local.
    private var flatToc: [(depth: Int, link: Link)] = []
    private var remoteResumeLocator: Locator?
    private var currentLocator: Locator?
    private var tocEntries: [TocEntry] = []
    private var remoteResumePercentValue: Double?
    private var saveTask: Task<Void, Never>?
    /// issue #279: whether the page is dark (highlights then use `.strongHighlight`), and the
    /// last highlights applied, to re-apply them when the theme changes.
    private var darkPage = false
    private var lastHighlights: [ModelHighlight] = []
    /// issue #278: bumped on every `applyDecorations`, and part of each decoration's id — see
    /// there. `decoratedHref` is the resource the highlights were last re-applied for.
    private var decorationGeneration = 0
    private var decoratedHref: String?

    init(url: URL, authHeader: String?, isManga: Bool, serverId: String, bookId: String, digestUrl: String?) {
        self.url = url
        self.authHeader = authHeader
        self.isManga = isManga
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
        // issue #170/#171: EPUB (and MOBI/AZW3/FB2, server-converted to EPUB) is a ZIP
        // container, and Readium Swift Toolkit 3.11.0's ZIP-over-HTTP range streaming requests
        // a fixed ~6 MiB look-ahead window without ever clamping it to the file's real length —
        // see RemoteFileCache's doc comment for the confirmed root cause. Downloading the whole
        // file up front sidesteps ranged reads entirely.
        guard let localURL = try? await RemoteFileCache.download(url: url, authHeader: authHeader, extension: "epub"),
              let resolvedURL = FileURL(url: localURL) else {
            showError("Couldn't open this book.")
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

        let assetResult = await assetRetriever.retrieve(url: resolvedURL, mediaType: .epub)
        guard case let .success(asset) = assetResult else {
            if case let .failure(retrieveError) = assetResult {
                NSLog("[EpubReader] assetRetriever.retrieve failed: \(retrieveError)")
            }
            showError("Couldn't open this book.")
            return
        }

        let openResult = await publicationOpener.open(asset: asset, allowUserInteraction: true)
        guard case let .success(publication) = openResult else {
            if case let .failure(openError) = openResult {
                NSLog("[EpubReader] publicationOpener.open failed: \(openError)")
            }
            showError("Couldn't open this book.")
            return
        }
        self.publication = publication

        // issue #183: same flattened order as Android's own `flatten(publication
        // .tableOfContents)` — `manifest.tableOfContents`, not `publication.tableOfContents`
        // (Publication doesn't forward that one property, confirmed against its real source).
        flatToc = Self.flatten(publication.manifest.tableOfContents)
        tocEntries = flatToc.enumerated().map { index, entry in
            TocEntry(depth: Int32(entry.depth), title: entry.link.title ?? entry.link.href, ref: String(index))
        }

        // issue #183: restore the saved position (if any), and check whether the server has a
        // meaningfully newer one — same two steps Android's EpubReaderViewModel.load() does,
        // via EpubProgressBridge (closure-based; see its own doc comment for why).
        // issues #266/#274/#275: where to open — a tapped highlight's jump, else the resume point
        // — resolved here against the publication in the same order Android's
        // EpubReaderViewModel uses (see EpubProgressBridge.initialStart).
        let start = await withCheckedContinuation { (continuation: CheckedContinuation<EpubStart, Never>) in
            MainViewControllerKt.epubProgressBridge().initialStart(serverId: serverId, bookId: bookId) { start in
                continuation.resume(returning: start)
            }
        }
        let initialLocator = await Self.locate(start, in: publication)
        let localPercent = initialLocator?.locations.totalProgression

        // A jump is a spot the user asked for — no "Continue from NN%" offer over it.
        let remotePercent: Double? = start.isJump ? nil : await withCheckedContinuation { (continuation: CheckedContinuation<Double?, Never>) in
            MainViewControllerKt.epubProgressBridge().remoteResumePercent(
                serverId: serverId,
                bookId: bookId,
                digestUrl: digestUrl,
                localPercent: localPercent.map { KotlinDouble(double: $0) }
            ) { percent in
                continuation.resume(returning: percent?.doubleValue)
            }
        }
        remoteResumePercentValue = remotePercent
        if let target = remotePercent, case let .success(positions) = await publication.positions() {
            remoteResumeLocator = positions.min { lhs, rhs in
                abs((lhs.locations.totalProgression ?? 0) - target) < abs((rhs.locations.totalProgression ?? 0) - target)
            }
        }

        do {
            // issue #108: manga reads right-to-left — nil (not .ltr) lets a non-manga book
            // fall back to its own publication metadata instead of forcing left-to-right on
            // something that might disagree (e.g. an Arabic EPUB).
            // issue #183: the custom "Highlight" text-selection menu item — its handler
            // (`highlight(_:)` below) reads `navigator.currentSelection` on tap, the pull-API
            // shape confirmed for Readium Swift (no selection-created event exists).
            var config = EPUBNavigatorViewController.Configuration(
                preferences: EPUBPreferences(readingProgression: isManga ? .rtl : nil),
                editingActions: EditingAction.defaultActions + [
                    EditingAction(title: "Highlight", action: #selector(highlight(_:))),
                ]
            )
            // issue #279: Readium's own highlight template again at a stronger tint, for dark
            // pages (see `.strongHighlight`).
            config.decorationTemplates[.strongHighlight] = HTMLDecorationTemplate.defaultTemplates(alpha: 0.5)[.highlight]
            let nav = try EPUBNavigatorViewController(
                publication: publication,
                initialLocation: initialLocator,
                config: config
            )
            nav.delegate = self
            navigator = nav
            currentLocator = initialLocator
            embedChrome(nav)
        } catch {
            NSLog("[EpubReader] EPUBNavigatorViewController init threw: \(error)")
            showError("Couldn't open this book.")
        }
    }

    /// Wires this reader's own bridge (issue #183) and embeds `:shared`'s `EpubReaderScreen`
    /// (which itself embeds `navigator` via `UIKitViewController`) as this view controller's
    /// sole child — replacing the loading indicator, the same "swap in the real content" shape
    /// the pre-#183 `embed(_:)` used for the bare navigator.
    private func embedChrome(_ nav: EPUBNavigatorViewController) {
        loadingIndicator.stopAnimating()
        loadingIndicator.removeFromSuperview()

        pushState()
        MainViewControllerKt.setEpubReaderActions(
            goToBookmark: { [weak self] bookmark in self?.goToBookmark(bookmark) },
            goToHighlight: { [weak self] highlight in self?.goToHighlight(highlight) },
            goToToc: { [weak self] entry in self?.goToToc(entry) },
            jumpToRemoteResume: { [weak self] in
                guard let self, let target = self.remoteResumeLocator else { return }
                Task { _ = await self.navigator?.go(to: target, options: .init()) }
            },
            submitPreferences: { [weak self] prefs in self?.submitPreferences(prefs) },
            applyHighlights: { [weak self] highlights in self?.applyDecorations(for: highlights) }
        )
        // issue #183: registered once here (not inside applyDecorations, which can run many
        // times) — the tap listener itself doesn't depend on the current highlights list.
        nav.observeDecorationInteractions(inGroup: "highlights") { event in
            MainViewControllerKt.epubReaderSetActiveHighlight(id: Self.highlightId(fromDecorationId: event.decoration.id))
        }

        let chrome = MainViewControllerKt.EpubReaderViewController(
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
        MainViewControllerKt.updateEpubReaderState(
            state: EpubReaderNativeState(
                screenState: EpubReaderUiStateReady(
                    title: "",
                    toc: tocEntries,
                    remoteResumePercent: remoteResumePercentValue.map { KotlinDouble(double: $0) },
                    hasRemoteResume: remoteResumePercentValue != nil
                ),
                currentLocatorJson: try? currentLocator?.jsonString()
            )
        )
    }

    private func goToBookmark(_ bookmark: ModelBookmark) {
        if bookmark.isForeign {
            // Chapter-level jump: match the label to a table-of-contents entry — same fallback
            // Android's own `goToBookmark()` uses for a bookmark made in a server's web reader.
            if let match = flatToc.first(where: { $0.link.title?.caseInsensitiveCompare(bookmark.title) == .orderedSame }) {
                Task { _ = await navigator?.go(to: match.link, options: .init()) }
            }
        } else {
            goToLocatorJson(bookmark.locatorJson)
        }
    }

    private func goToToc(_ entry: TocEntry) {
        guard let index = Int(entry.ref), flatToc.indices.contains(index) else { return }
        Task { _ = await navigator?.go(to: flatToc[index].link, options: .init()) }
    }

    /// issue #278: a BookOrbit (CFI-only) highlight has no locator of its own — see `locator(for:)`.
    private func goToHighlight(_ highlight: ModelHighlight) {
        Task {
            guard let locator = await locator(for: highlight) else { return }
            _ = await navigator?.go(to: locator, options: .init())
        }
    }

    private func goToLocatorJson(_ json: String) {
        guard let locator = try? Locator(jsonString: json) else { return }
        Task { _ = await navigator?.go(to: locator, options: .init()) }
    }

    /// The Swift-side equivalent of Android's `EpubPreferencesMapping.kt` — Readium Swift's
    /// own `EPUBPreferences` isn't a KMP type, so this mapping can't live in `:shared`.
    private func submitPreferences(_ prefs: DatastoreReaderDisplayPreferences) {
        // issue #183: Kotlin enums bridge to Swift as a KotlinEnum-conforming class hierarchy,
        // not a true Swift enum — `switch`'s exhaustiveness/`@unknown default` checking doesn't
        // apply the same way, so this compares against the bridged static cases with `==`
        // instead (confirmed working shape via a real ios-ci compile error, not guessed).
        let resolvedTheme: Theme
        if prefs.theme == DatastoreReaderTheme.light {
            resolvedTheme = Theme.light
        } else if prefs.theme == DatastoreReaderTheme.sepia {
            resolvedTheme = Theme.sepia
        } else if prefs.theme == DatastoreReaderTheme.grey {
            resolvedTheme = Theme.light
        } else if prefs.theme == DatastoreReaderTheme.dark {
            resolvedTheme = Theme.dark
        } else {
            resolvedTheme = traitCollection.userInterfaceStyle == .dark ? Theme.dark : Theme.light
        }
        // issue #279: a stronger highlight tint on a dark page — re-applied when that changes.
        if (resolvedTheme == Theme.dark) != darkPage {
            darkPage = resolvedTheme == Theme.dark
            applyDecorations(for: lastHighlights)
        }
        var epubPrefs = EPUBPreferences(
            fontSize: prefs.fontScale,
            scroll: prefs.scroll,
            theme: resolvedTheme
        )
        // Grey isn't one of Readium's built-in themes; paint it on with explicit colours —
        // same packed-ARGB-int values Android's ThemeSwatch/EpubPreferencesMapping use, reused
        // as-is since Readium Swift's `Color(rawValue:)` is the same packed representation.
        if prefs.theme == DatastoreReaderTheme.grey {
            epubPrefs.backgroundColor = ReadiumNavigator.Color(rawValue: 0xFFC8CBCF)
            epubPrefs.textColor = ReadiumNavigator.Color(rawValue: 0xFF17181A)
        }
        // issue #183: Kotlin's `auto`/`double` enum cases are exported as `auto_`/`double_` —
        // both are Objective-C/C keywords, confirmed via a real ios-ci compile error.
        if prefs.pageLayout == DatastoreReaderPageLayout.auto_ {
            epubPrefs.columnCount = view.bounds.width >= 720 ? ColumnCount.two : ColumnCount.auto
        } else if prefs.pageLayout == DatastoreReaderPageLayout.single {
            epubPrefs.columnCount = ColumnCount.one
        } else if prefs.pageLayout == DatastoreReaderPageLayout.double_ {
            epubPrefs.columnCount = ColumnCount.two
        }
        if prefs.fitMode == DatastoreReaderFitMode.pageWidth {
            epubPrefs.pageMargins = 0.5
        } else if prefs.fitMode == DatastoreReaderFitMode.pageFit {
            epubPrefs.pageMargins = 1.0
        } else if prefs.fitMode == DatastoreReaderFitMode.pageHeight {
            epubPrefs.pageMargins = 1.0
        } else if prefs.fitMode == DatastoreReaderFitMode.actualSize {
            epubPrefs.pageMargins = 1.6
        }
        navigator?.submitPreferences(epubPrefs)
    }

    /// Renders highlight decorations — the Swift equivalent of Android's own
    /// `LaunchedEffect(navigator, highlights) { nav.applyDecorations(...) }`; called from
    /// `:shared`'s Compose code whenever the collected highlights list changes (issue #183 —
    /// Swift can't collect a Kotlin `Flow` directly, so this is a push, not a pull).
    ///
    /// issue #278: Readium Swift 3.11 can drop decorations applied while the navigator's first
    /// resource is still loading — the resource reads the (still empty) decoration list as it
    /// loads, but isn't marked loaded yet when `apply` looks for it — and its diffing then never
    /// re-sends an identical list, so highlights never showed at all. Each call therefore stamps
    /// a new generation into the decoration ids (Readium sees a fresh set and adds them to every
    /// loaded resource), and `locationDidChange` re-applies once a new resource is on screen.
    /// The generation also lets a slower, older call (its locators are resolved async) bail out.
    private func applyDecorations(for highlights: [ModelHighlight]) {
        lastHighlights = highlights
        guard let navigator else { return }
        decorationGeneration += 1
        let generation = decorationGeneration
        let darkPage = darkPage
        Task {
            var decorations: [Decoration] = []
            for h in highlights {
                guard let locator = await locator(for: h) else { continue }
                decorations.append(Decoration(
                    id: "\(h.id)#\(generation)",
                    locator: locator,
                    style: Self.highlightStyle(tint: UIColor(argb: h.color.argb), darkPage: darkPage)
                ))
            }
            guard generation == decorationGeneration else { return }
            navigator.apply(decorations: decorations, in: "highlights")
        }
    }

    /// The highlight id inside a decoration id stamped by `applyDecorations`.
    private static func highlightId(fromDecorationId id: String) -> String {
        String(id[..<(id.lastIndex(of: "#") ?? id.endIndex)])
    }

    /// issue #278: a highlight's locator, to paint it or go to it. One made in BookOrbit's web
    /// reader has only an EPUB CFI (its stored locator is `{}`), so it gets its chapter's locator
    /// carrying the highlighted text — Readium finds a locator's exact range from that quote.
    private func locator(for highlight: ModelHighlight) async -> Locator? {
        if let locator = try? Locator(jsonString: highlight.locatorJson) {
            return locator
        }
        guard let publication, let index = EpubReaderBridgeKt.cfiChapterIndex(highlight: highlight)?.intValue else {
            return nil
        }
        return await Self.chapterLocator(index, text: highlight.text, in: publication)
    }

    /// issues #274/#278: the reading-order item at `index`, pinned to `text` when there is one.
    private static func chapterLocator(_ index: Int, text: String?, in publication: Publication) async -> Locator? {
        guard publication.readingOrder.indices.contains(index),
              let chapter = await publication.locate(publication.readingOrder[index]) else {
            return nil
        }
        guard let text, !text.isEmpty else { return chapter }
        return chapter.copy(text: { $0.highlight = text })
    }

    /// Adds a "Highlight" item to the text-selection menu (see `Configuration.editingActions`
    /// above) — on tap, reads the current selection (a pull API; Readium Swift has no
    /// selection-created event) and hands it straight to the already-portable
    /// `HighlightRepository` via `epubReaderAddHighlight` — entirely native-triggered, so it
    /// never touches the shared chrome at all, mirroring Android's `HighlightSelectionCallback`.
    @objc private func highlight(_ sender: Any?) {
        guard let navigator, let selection = navigator.currentSelection,
              let text = selection.locator.text.highlight, !text.isEmpty,
              let json = try? selection.locator.jsonString() else { return }
        MainViewControllerKt.epubReaderAddHighlight(
            serverId: serverId,
            bookId: bookId,
            locatorJson: json,
            progression: selection.locator.locations.totalProgression ?? 0,
            text: text,
            chapterTitle: selection.locator.title
        )
        navigator.clearSelection()
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

extension EpubReaderViewController {
    /// Presents the reader full-screen, dismissed by the standard edge-swipe gesture (issue
    /// #176). `hidesNavigationBar: true` (issue #183) — the shared chrome draws its own
    /// `BackPill` as part of its Compose content, same reason the player screen passes it.
    /// issues #274/#275: the first usable of the start's locator, reading-order item (a
    /// BookOrbit CFI highlight's chapter) and whole-book progression; nil opens the book's start.
    private static func locate(_ start: EpubStart, in publication: Publication) async -> Locator? {
        if let json = start.locatorJson, let locator = try? Locator(jsonString: json) {
            return locator
        }
        if let index = start.spineIndex?.intValue,
           let locator = await chapterLocator(index, text: start.text, in: publication) {
            return locator
        }
        if let progression = start.progression?.doubleValue {
            return await publication.locate(progression: progression)
        }
        return nil
    }

    static func presentable(url: URL, authHeader: String?, isManga: Bool, serverId: String, bookId: String, digestUrl: String?) -> UIViewController {
        let reader = EpubReaderViewController(
            url: url, authHeader: authHeader, isManga: isManga,
            serverId: serverId, bookId: bookId, digestUrl: digestUrl
        )
        return FullScreenReaderPresentation.wrap(reader, hidesNavigationBar: true)
    }
}

/// Edge-tap page turning (issue #108) plus, as of issue #183, a centre tap toggling the shared
/// chrome's visibility — the Swift equivalent of Android's own `EdgeTapNavigator(onCenterTap =
/// ...)`. This hooks Readium's own extension point rather than adding a competing gesture
/// recognizer: `EPUBNavigatorViewController` already forwards unhandled taps to
/// `delegate?.navigator(self, didTapAt:)` internally.
extension EpubReaderViewController: EPUBNavigatorDelegate {
    private static let edgeFraction: CGFloat = 0.28

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        let width = navigator.view.bounds.width
        guard width > 0 else { return }

        if point.x < width * Self.edgeFraction {
            turnPage(navigator, physicallyForward: false)
        } else if point.x > width * (1 - Self.edgeFraction) {
            turnPage(navigator, physicallyForward: true)
        } else {
            MainViewControllerKt.epubReaderToggleChrome()
        }
    }

    private func turnPage(_ navigator: VisualNavigator, physicallyForward: Bool) {
        let readingForward = physicallyForward != isManga
        Task {
            if readingForward {
                _ = await navigator.goForward(options: .animated)
            } else {
                _ = await navigator.goBackward(options: .animated)
            }
        }
    }

    /// Pushes the new position into the shared chrome and schedules a debounced (~1.5s, same
    /// cadence as Android's `locatorUpdates.debounce(1_500)`) save via `EpubProgressBridge`.
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        currentLocator = locator
        pushState()

        // issue #278: a newly displayed resource gets the highlights re-applied (see
        // `applyDecorations` for the Readium race this works around).
        let href = locator.href.string
        if href != decoratedHref {
            decoratedHref = href
            applyDecorations(for: lastHighlights)
        }

        saveTask?.cancel()
        saveTask = Task { [serverId, bookId] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled, let json = try? locator.jsonString() else { return }
            MainViewControllerKt.epubProgressBridge().save(
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

private extension UIColor {
    /// Same packed-ARGB-`Int` representation `HighlightColor.argb` and Compose's own
    /// `Color(Int)` constructor use — see `HighlightColor`'s own doc comment.
    convenience init(argb: Int32) {
        let value = UInt32(bitPattern: argb)
        let a = CGFloat((value >> 24) & 0xFF) / 255
        let r = CGFloat((value >> 16) & 0xFF) / 255
        let g = CGFloat((value >> 8) & 0xFF) / 255
        let b = CGFloat(value & 0xFF) / 255
        self.init(red: r, green: g, blue: b, alpha: a)
    }
}

/// issue #279: a highlight painted at a stronger tint (Readium's highlight template at 50%
/// alpha), for dark pages — at the default 30% a yellow highlight read as dim olive on black.
/// A style of its own because the navigator's templates are fixed once it's created, while the
/// reader theme can change mid-book.
private extension Decoration.Style.Id {
    static let strongHighlight: Decoration.Style.Id = "strongHighlight"
}

private extension EpubReaderViewController {
    static func highlightStyle(tint: UIColor, darkPage: Bool) -> Decoration.Style {
        darkPage
            ? Decoration.Style(id: .strongHighlight, config: Decoration.Style.HighlightConfig(tint: tint, isActive: false))
            : .highlight(tint: tint, isActive: false)
    }
}
