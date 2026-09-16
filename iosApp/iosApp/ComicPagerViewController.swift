import UIKit
import ReadiumZIPFoundation
import SharedKit

/// The comic reader's outer owner (Phase 4 of #183) — same role as `EpubReaderViewController`/
/// `PdfReaderViewController`: opens the book, resolves/saves reading position via
/// `ComicProgressBridge`, and embeds `:shared`'s `ComicReaderScreen` for the chrome (top bar,
/// page slider, settings sheet). Unlike those two, there's no separate Readium navigator object
/// to embed as the chrome's `readerContent` — page rendering was always from-scratch Swift here
/// (see the original doc comment below), so `ComicPageContentViewController` now plays that
/// role instead, exactly the way `EPUBNavigatorViewController`/`PDFNavigatorViewController` do
/// for the other two readers.
///
/// Reads page images directly from the local CBZ file that `ComicArchiveNormalizer` always
/// produces (it downloads the comic in full and verifies its real magic bytes, unpacking+
/// repacking as CBZ only when the source turns out to be RAR) via `ReadiumZIPFoundation`, the
/// same ZIP reader/writer already used to build that file — no new dependency needed. Comics
/// used to reuse `EpubReaderViewController`'s `EPUBNavigatorViewController`, per Readium's own
/// changelog (3.8.0), which documents fixed-layout/Divina support for CBZ as a reason to
/// deprecate the separate `CBZNavigatorViewController`. On a real device, though, that path
/// never painted anything — Android's own comic reader never reused its EPUB reader either
/// (`ComicPanelDetector`/`PanelSteppingNavigator`, a from-scratch Compose pager), so this
/// mirrors that same architecture decision on iOS instead of continuing to chase Readium's
/// fixed-layout internals.
final class ComicPagerViewController: UIViewController {
    private let url: URL
    private let authHeader: String?
    private let title_: String
    private let serverId: String
    private let bookId: String
    private let digestUrl: String?
    private let initialRightToLeft: Bool

    private var archive: ReadiumZIPFoundation.Archive?
    /// Always in natural (ascending filename) reading order — unlike the pre-Phase-4 version,
    /// this is never reversed for manga. Reading direction is a live, toggleable concern (the
    /// shared chrome's own "Right-to-left" switch), so it has to be interpreted at gesture/
    /// navigation time instead, exactly the way Android's `EdgeTapNavigator`/`pageTurnGesture`
    /// already do via their own `rightToLeft`/`forward` inversion.
    private var pageEntries: [ReadiumZIPFoundation.Entry] = []

    private let loadingIndicator = UIActivityIndicatorView(style: .large)
    private var content: ComicPageContentViewController?
    private var saveTask: Task<Void, Never>?
    /// issue #188 — the last preferences Swift received, cached so [updateIsDoubleSpread] can
    /// re-derive the resolved double-spread state on a layout pass alone (rotation, Split View
    /// resize) without waiting on a fresh preferences push, since [ReaderPageLayout.AUTO]
    /// depends on the viewport too, not just the setting itself.
    private var lastPreferences: DatastoreReaderDisplayPreferences?

    private static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "gif", "webp", "bmp",
    ]

    init(url: URL, authHeader: String?, isManga: Bool, title: String, serverId: String, bookId: String, digestUrl: String?) {
        self.url = url
        self.authHeader = authHeader
        self.initialRightToLeft = isManga
        self.title_ = title
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
        view.backgroundColor = .black

        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(loadingIndicator)
        NSLayoutConstraint.activate([
            loadingIndicator.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            loadingIndicator.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
        loadingIndicator.startAnimating()

        Task { await loadPages() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateIsDoubleSpread()
    }

    @MainActor
    private func loadPages() async {
        guard let cbzURL = try? await ComicArchiveNormalizer.normalize(url: url, authHeader: authHeader) else {
            NSLog("[ComicPager] normalize failed for \(url)")
            showError("Couldn't open this comic.")
            return
        }

        guard
            let archive = try? await ReadiumZIPFoundation.Archive(url: cbzURL, accessMode: .read),
            let entries = try? await archive.entries()
        else {
            NSLog("[ComicPager] failed to open archive at \(cbzURL)")
            showError("Couldn't open this comic.")
            return
        }

        let sorted = entries
            .filter { Self.imageExtensions.contains(($0.path as NSString).pathExtension.lowercased()) }
            .sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }

        guard !sorted.isEmpty else {
            NSLog("[ComicPager] no image entries found in \(cbzURL)")
            showError("This comic has no readable pages.")
            return
        }

        self.archive = archive
        pageEntries = sorted

        // issue #183: ComicProgressBridge already resolved the local-vs-server position — see
        // PdfProgressBridge's own reasoning, the same shape one level simpler (a plain page,
        // not a locator JSON, since there's no real Readium Locator here at all).
        let initialPage = await withCheckedContinuation { (continuation: CheckedContinuation<Int?, Never>) in
            MainViewControllerKt.comicProgressBridge().initialPage(
                serverId: serverId, bookId: bookId, digestUrl: digestUrl
            ) { page in
                continuation.resume(returning: (page?.int32Value).map(Int.init))
            }
        }
        let initialIndex = ((initialPage ?? 1) - 1).clamped(to: 0...(pageEntries.count - 1))

        embedChrome(initialIndex: initialIndex)
    }

    /// Wires this reader's own bridge (issue #183) and embeds `:shared`'s `ComicReaderScreen`
    /// (which itself embeds a `ComicPageContentViewController` via `UIKitViewController`) as
    /// this view controller's sole child — replacing the loading indicator.
    private func embedChrome(initialIndex: Int) {
        let content = ComicPageContentViewController(
            pageCount: pageEntries.count,
            rightToLeft: initialRightToLeft,
            loadImage: { [weak self] index in await self?.loadImage(at: index) }
        )
        content.onPageChanged = { [weak self] index in self?.handlePageChanged(index) }
        content.onCenterTap = { MainViewControllerKt.comicReaderToggleChrome() }
        content.jump(to: initialIndex, animated: false)
        self.content = content

        loadingIndicator.stopAnimating()
        loadingIndicator.removeFromSuperview()

        pushState()
        MainViewControllerKt.setComicReaderActions(
            goToPage: { [weak self] page in
                self?.content?.jump(to: Int(page.int32Value) - 1, animated: false)
            },
            submitPreferences: { [weak self] prefs in self?.applyPreferences(prefs) }
        )

        let chrome = MainViewControllerKt.ComicReaderViewController(
            onBack: { [weak self] in self?.dismiss(animated: true) },
            navigatorViewController: content,
            serverId: serverId,
            bookId: bookId
        )
        addChild(chrome)
        chrome.view.frame = view.bounds
        chrome.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(chrome.view)
        chrome.didMove(toParent: self)
    }

    /// Pushes the current screen/page state — called once right after the content controller
    /// is ready, and again from [handlePageChanged] on every page change.
    private func pushState() {
        MainViewControllerKt.updateComicReaderState(
            state: ComicReaderNativeState(
                screenState: ComicReaderUiStateReady(title: title_, pageCount: Int32(pageEntries.count)),
                currentPage: Int32((content?.currentIndex ?? 0) + 1)
            )
        )
    }

    /// Called by `ComicPageContentViewController` on every page change — a settled drag, an
    /// edge tap, or the shared chrome's own page slider. Pushes state immediately and schedules
    /// a debounced (~1s, same cadence as Android's/PDF's own) position save.
    private func handlePageChanged(_ index: Int) {
        pushState()

        saveTask?.cancel()
        saveTask = Task { [serverId, bookId, pageEntries] in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            guard !Task.isCancelled else { return }
            let page = index + 1
            let percent = Double(page) / Double(max(pageEntries.count, 1))
            MainViewControllerKt.comicProgressBridge().save(
                serverId: serverId, bookId: bookId, page: Int32(page), percent: percent
            )
        }
    }

    private func applyPreferences(_ prefs: DatastoreReaderDisplayPreferences) {
        lastPreferences = prefs
        content?.rightToLeft = prefs.comicRightToLeft
        content?.tapNavigationEnabled = prefs.tapNavigation
        content?.commitFraction = CGFloat(prefs.swipeSensitivity.commitFraction)
        content?.fitWidth = prefs.fitMode == DatastoreReaderFitMode.pageWidth
        updateIsDoubleSpread()
    }

    /// issue #188: two-page spread. `Auto` follows the same `>= 720pt` viewport threshold
    /// EPUB's own "Page layout" setting already uses (see `EpubReaderViewController`'s own
    /// `applyPreferences` and its comment on the `auto_`/`double_` Kotlin enum export names).
    /// Re-derived on every layout pass, not just a fresh preferences push — only the viewport
    /// can change afterward (rotation, Split View resize) with the preferences themselves
    /// unchanged, and `Auto`'s resolved value depends on both.
    private func updateIsDoubleSpread() {
        guard let prefs = lastPreferences else { return }
        let wideViewport = view.bounds.width >= 720
        // issue #204 — DOUBLE falls back to the same wideViewport check AUTO uses, not an
        // unconditional true: the settings sheet disables "Two-page" below 720pt (see
        // canUseDoubleSpread below), but the viewport can still narrow *while* DOUBLE is
        // already the saved preference (e.g. Split View resize), so a stale preference alone
        // must never force an unusably cramped spread on a phone-sized window. Same fix
        // already applied to the Android/shared ComicReaderScreen — see that file's own
        // comment on this exact scenario.
        let isDoubleSpread: Bool
        if prefs.pageLayout == DatastoreReaderPageLayout.auto_ {
            isDoubleSpread = wideViewport
        } else if prefs.pageLayout == DatastoreReaderPageLayout.double_ {
            isDoubleSpread = wideViewport
        } else {
            isDoubleSpread = false
        }
        // issue #204 — pushed unconditionally (not gated on isDoubleSpread's own change check
        // below): the viewport can narrow below 720pt while a SINGLE-layout book is open,
        // which never changes isDoubleSpread (already false) but must still grey out "Two-page"
        // in the settings sheet so a phone-sized window can't select it.
        MainViewControllerKt.updateComicCanUseDoubleSpread(canUse: wideViewport)
        guard content?.isDoubleSpread != isDoubleSpread else { return }
        content?.isDoubleSpread = isDoubleSpread
        MainViewControllerKt.updateComicIsDoubleSpread(isDoubleSpread: isDoubleSpread)
    }

    private func loadImage(at index: Int) async -> UIImage? {
        guard let archive, pageEntries.indices.contains(index) else { return nil }
        var data = Data()
        guard (try? await archive.extract(pageEntries[index], consumer: { chunk in
            data.append(chunk)
        })) != nil else {
            NSLog("[ComicPager] failed to extract page \(index)")
            return nil
        }
        return UIImage(data: data)
    }

    private func showError(_ message: String) {
        loadingIndicator.stopAnimating()
        loadingIndicator.removeFromSuperview()

        let label = UILabel()
        label.text = message
        label.textColor = .white
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

extension ComicPagerViewController {
    /// Presents the reader full-screen, dismissed by the standard edge-swipe gesture (issue
    /// #176) — same shape as `EpubReaderViewController.presentable`. `hidesNavigationBar: true`
    /// (issue #183) — the shared chrome draws its own `BackPill`.
    static func presentable(
        url: URL, authHeader: String?, isManga: Bool, title: String,
        serverId: String, bookId: String, digestUrl: String?
    ) -> UIViewController {
        let pager = ComicPagerViewController(
            url: url, authHeader: authHeader, isManga: isManga, title: title,
            serverId: serverId, bookId: bookId, digestUrl: digestUrl
        )
        return FullScreenReaderPresentation.wrap(pager, hidesNavigationBar: true)
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        // Fully qualified — an unqualified `min`/`max` inside an extension on a Comparable
        // type resolves to that type's own `.min`/`.max` static members (e.g. `Int.min`)
        // ahead of the global `min(_:_:)`/`max(_:_:)` functions this actually wants.
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}

/// The comic reader's real "navigator" (issue #183) — everything genuinely native about
/// rendering and turning pages lives here: the per-page zoomable image views, edge-tap
/// navigation, and a from-scratch drag-to-turn-page gesture mirroring Android's own
/// `pageTurnGesture` (see that Composable's doc comment for the algorithm this ports): a
/// horizontal drag past touch-slop snapshots the current page, moves straight to the target
/// page underneath (so lifting the snapshot reveals the *real* next page, not a placeholder),
/// and tracks the snapshot's position with the finger. Releasing past [commitFraction] of the
/// width — or a quick flick — lets the turn stand (the snapshot slides fully off); a shorter,
/// slower drag springs the snapshot back over the screen and quietly undoes the turn
/// underneath. A drag at the first/last page never turns at all — it's rendered as a damped
/// rubber-band pull on the live page itself, always springing back regardless of distance.
///
/// `UIPanGestureRecognizer` replaces `UIPageViewController`'s own built-in paging gesture
/// entirely (issue #183 Phase 4) — that gesture has no public API for a tunable commit
/// threshold, so matching Android's Low/Medium/High swipe-sensitivity setting means owning
/// the gesture outright rather than trying to tune a system one.
///
/// issue #188: two-page spread — [isDoubleSpread] switches this between one page
/// ([currentPageVC], unchanged from before #188) and two ([leadingPageVC]/[trailingPageVC],
/// side by side in [spreadContainer]), mirroring Android's `SingleSpreadReader`/
/// `DoubleSpreadReader` split. Unlike Compose's declarative recomposition, there's no way to
/// swap the `UIViewController` Compose Multiplatform's `UIKitViewController` embeds after
/// first creating it, so both modes live inside this one persistent controller instead of two
/// separate classes swapped at the call site.
final class ComicPageContentViewController: UIViewController, UIGestureRecognizerDelegate {
    private let pageCount: Int
    private let loadImage: (Int) async -> UIImage?

    var rightToLeft: Bool
    var tapNavigationEnabled: Bool = true
    var commitFraction: CGFloat = 0.33
    /// Mirrors PDF's own reduced "Fit"/"Width" setting — false fits the whole page (the
    /// original, only behavior), true fits to width and lets the scroll view pan vertically
    /// for the rest. Propagated straight to the current page on change (see `didSet`) — a new
    /// page picks it up itself, from this same property, when `showCurrentPage()` creates it.
    /// Ignored in double-spread — see [isDoubleSpread]'s own doc comment.
    var fitWidth: Bool = false {
        didSet { currentPageVC?.fitWidth = fitWidth }
    }

    /// issue #188 — "Page fit" is moot once double-spread is active: each container is sized
    /// to its own page's real aspect ratio (see [layoutSpread]), so Readium's — well, this
    /// reader's own — default whole-page fit already fills it edge-to-edge with no letterbox
    /// to "Width" away. The shared chrome greys that setting out for the same reason (see
    /// `ComicSettings`'s own `isDoubleSpread` param) once this is wired up there.
    var isDoubleSpread: Bool = false {
        didSet {
            guard oldValue != isDoubleSpread else { return }
            currentIndex = leadingIndex(for: currentIndex)
            showCurrentPage()
        }
    }

    /// The step a page-turn (swipe or edge-tap) advances by — a whole spread in double-spread,
    /// same as Android's own `advanceSpread`.
    private var stepSize: Int { isDoubleSpread ? 2 : 1 }

    /// In double-spread, [currentIndex] always holds the *leading* (lower-numbered) page of the
    /// currently visible pair — pages pair up sequentially, (0,1), (2,3), … (0-based; Android's
    /// own (1,2), (3,4), … one-based), no cover-offset special case. [trailingIndex] shows
    /// [currentIndex] again — rather than a stale page, or going blank — on a trailing book
    /// with an odd page count, where the final pair has no second page.
    private func leadingIndex(for index: Int) -> Int { (index / 2) * 2 }
    private var trailingIndex: Int { min(currentIndex + 1, pageCount - 1) }

    private(set) var currentIndex: Int = 0
    private var currentPageVC: ComicPageViewController?

    /// issue #188 — the two-page-spread pair, only non-nil while [isDoubleSpread] is true.
    /// [spreadContainer] holds both, sized/positioned by [layoutSpread] — never autoresized,
    /// since centring a pair whose combined width may be less than the full screen (margin on
    /// the *outer* edges only, never a gap between the two pages) needs an exact frame, not
    /// Auto Layout/autoresizing.
    private var leadingPageVC: ComicPageViewController?
    private var trailingPageVC: ComicPageViewController?
    private var spreadContainer: UIView?
    /// Each page's own width/height ratio, read off its real decoded image the moment it loads
    /// (see `ComicPageViewController.onAspectKnown`) — seeded to a typical portrait comic/manga
    /// page ratio so the very first layout pass isn't zero-width, corrected (and
    /// [layoutSpread] re-run) the moment each side's real image is known. Same reasoning as
    /// Android's own `pageAspectA`/`pageAspectB`.
    private var leadingAspect: CGFloat = 0.7071
    private var trailingAspect: CGFloat = 0.7071

    private var panGesture: UIPanGestureRecognizer!
    private var dragClaimed = false
    /// nil while idle or mid-elastic-pull; set the moment a real turn happens underneath the
    /// snapshot, to whichever direction (in reading-order terms) it turned.
    private var dragTurnedForward: Bool?
    private var dragOverlay: UIView?

    var onPageChanged: ((Int) -> Void)?
    var onCenterTap: (() -> Void)?

    init(pageCount: Int, rightToLeft: Bool, loadImage: @escaping (Int) async -> UIImage?) {
        self.pageCount = pageCount
        self.rightToLeft = rightToLeft
        self.loadImage = loadImage
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.clipsToBounds = true

        panGesture = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        panGesture.delegate = self
        // A pinch reads to a plain UIPanGestureRecognizer as a (small, symmetric) pan too —
        // without this, a two-finger pinch-to-zoom could spuriously claim a page-turn mid-
        // pinch, tearing out the zoomed page's scroll view while the system's own pinch
        // recognizer is still actively driving it. That's the likely cause of the reported
        // lockup: UIKit's gesture state machine left tracking a view that no longer exists.
        panGesture.maximumNumberOfTouches = 1
        view.addGestureRecognizer(panGesture)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if isDoubleSpread {
            layoutSpread()
        } else {
            currentPageVC?.view.frame = view.bounds
        }
    }

    /// Jumps straight to [index] with no drag/animation — the shared chrome's page slider and
    /// the initial-position resolve both go through this.
    func jump(to index: Int, animated: Bool) {
        let clamped = index.clamped(to: 0...max(pageCount - 1, 0))
        let target = isDoubleSpread ? leadingIndex(for: clamped) : clamped
        guard target != currentIndex || (currentPageVC == nil && leadingPageVC == nil) else { return }
        currentIndex = target
        showCurrentPage()
        onPageChanged?(currentIndex)
    }

    private func showCurrentPage() {
        removeCurrentChildren()
        if isDoubleSpread {
            showSpread()
        } else {
            showSinglePage()
        }
    }

    private func removeCurrentChildren() {
        for vc in [currentPageVC, leadingPageVC, trailingPageVC].compactMap({ $0 }) {
            vc.willMove(toParent: nil)
            vc.view.removeFromSuperview()
            vc.removeFromParent()
        }
        currentPageVC = nil
        leadingPageVC = nil
        trailingPageVC = nil
        spreadContainer?.removeFromSuperview()
        spreadContainer = nil
    }

    private func showSinglePage() {
        // Captured eagerly rather than re-reading `self.currentIndex` inside the closure — a
        // page turn that lands before this async load finishes must not retarget it at
        // whatever the *new* current page happens to be.
        let index = currentIndex
        let vc = ComicPageViewController(index: index, pager: self) { [weak self] in
            await self?.loadImage(index)
        }
        vc.fitWidth = fitWidth
        addChild(vc)
        vc.view.frame = view.bounds
        vc.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(vc.view)
        vc.didMove(toParent: self)
        currentPageVC = vc
    }

    private func showSpread() {
        let leadingIdx = currentIndex
        let trailingIdx = trailingIndex

        let container = UIView()
        view.addSubview(container)
        spreadContainer = container

        let leading = ComicPageViewController(index: leadingIdx, pager: self) { [weak self] in
            await self?.loadImage(leadingIdx)
        }
        leading.fitWidth = false
        leading.onAspectKnown = { [weak self] aspect in
            self?.leadingAspect = aspect
            self?.layoutSpread()
        }
        addChild(leading)
        container.addSubview(leading.view)
        leading.didMove(toParent: self)
        leadingPageVC = leading

        let trailing = ComicPageViewController(index: trailingIdx, pager: self) { [weak self] in
            await self?.loadImage(trailingIdx)
        }
        trailing.fitWidth = false
        trailing.onAspectKnown = { [weak self] aspect in
            self?.trailingAspect = aspect
            self?.layoutSpread()
        }
        addChild(trailing)
        container.addSubview(trailing.view)
        trailing.didMove(toParent: self)
        trailingPageVC = trailing

        layoutSpread()
    }

    /// Sizes/positions [spreadContainer] and its two page views: fills the available height if
    /// the pair then fits the width, otherwise shrinks to fit width instead (still no seam,
    /// just letterboxed top/bottom) — same algorithm as Android's `DoubleSpreadReader`. The
    /// leading page sits on the reading direction's own leading side — left for LTR, right for
    /// RTL/manga.
    private func layoutSpread() {
        guard let container = spreadContainer, let leading = leadingPageVC, let trailing = trailingPageVC else { return }
        let boxW = view.bounds.width
        let boxH = view.bounds.height
        guard boxW > 0, boxH > 0 else { return }

        let combinedAspect = max(leadingAspect + trailingAspect, 0.01)
        let spreadHeight = min(boxH, boxW / combinedAspect)
        let leadingWidth = spreadHeight * leadingAspect
        let trailingWidth = spreadHeight * trailingAspect
        let totalWidth = leadingWidth + trailingWidth

        container.frame = CGRect(
            x: (boxW - totalWidth) / 2,
            y: (boxH - spreadHeight) / 2,
            width: totalWidth,
            height: spreadHeight
        )

        if rightToLeft {
            trailing.view.frame = CGRect(x: 0, y: 0, width: trailingWidth, height: spreadHeight)
            leading.view.frame = CGRect(x: trailingWidth, y: 0, width: leadingWidth, height: spreadHeight)
        } else {
            leading.view.frame = CGRect(x: 0, y: 0, width: leadingWidth, height: spreadHeight)
            trailing.view.frame = CGRect(x: leadingWidth, y: 0, width: trailingWidth, height: spreadHeight)
        }
    }

    /// Called by a page's edge-tap gesture (see `ComicPageViewController`) — this content
    /// controller owns navigation since it alone knows the current index and page count.
    /// issue #188: never called with a meaningful edge while double-spread is active —
    /// [ComicPageViewController.handleSingleTap] checks [effectiveTapNavigationEnabled] itself
    /// and routes straight to [centerTapped] instead, the same "any tap toggles chrome"
    /// fallback Android's `EdgeTapNavigator` gets from `enabled: { false }`.
    fileprivate func turnPage(physicallyForward: Bool) {
        guard tapNavigationEnabled else { return }
        let actuallyForward = physicallyForward != rightToLeft
        let nextIndex = actuallyForward ? currentIndex + stepSize : currentIndex - stepSize
        guard (0..<pageCount).contains(nextIndex) else { return }
        currentIndex = nextIndex
        showCurrentPage()
        onPageChanged?(currentIndex)
    }

    /// Off in double-spread regardless of the user's own toggle — edge taps don't cleanly map
    /// onto two independent page view controllers' touch targets, same reasoning as Android's
    /// `EdgeTapNavigator(enabled: { false })`. Checked by `ComicPageViewController` itself.
    fileprivate var effectiveTapNavigationEnabled: Bool { tapNavigationEnabled && !isDoubleSpread }

    fileprivate func centerTapped() {
        onCenterTap?()
    }

    private var isZoomedIn: Bool {
        if isDoubleSpread {
            let leadingZoomed = leadingPageVC.map {
                $0.scrollView.zoomScale > $0.scrollView.minimumZoomScale + 0.001
            } ?? false
            let trailingZoomed = trailingPageVC.map {
                $0.scrollView.zoomScale > $0.scrollView.minimumZoomScale + 0.001
            } ?? false
            return leadingZoomed || trailingZoomed
        }
        guard let current = currentPageVC else { return false }
        return current.scrollView.zoomScale > current.scrollView.minimumZoomScale + 0.001
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard currentPageVC != nil || (leadingPageVC != nil && trailingPageVC != nil) else { return }
        let width = max(view.bounds.width, 1)
        let translation = gesture.translation(in: view)
        let velocity = gesture.velocity(in: view)

        switch gesture.state {
        case .began:
            dragClaimed = false
        case .changed:
            if !dragClaimed {
                // Same slop + "clearly horizontal" gate as Android's own claim check — a
                // shorter or more-vertical drag is left alone (the page's own pinch-zoom pan,
                // or nothing at all).
                guard abs(translation.x) > 6, abs(translation.x) > abs(translation.y) * 1.4 else { return }
                guard !isZoomedIn else { return }
                dragClaimed = true
                beginDrag(physicallyForward: translation.x < 0)
            }
            updateDrag(tx: translation.x, width: width)
        case .ended, .cancelled:
            if dragClaimed {
                endDrag(tx: translation.x, velocityX: velocity.x, width: width)
            }
            dragClaimed = false
        default:
            break
        }
    }

    /// Whether another turn in [actuallyForward]'s direction — already stepping by [stepSize]
    /// — would go past the book's own bounds. Same shape as Android's own `atBoundary` check
    /// (`pageTurnGesture`'s own parameter) in both modes.
    private func isAtBoundary(actuallyForward: Bool) -> Bool {
        if isDoubleSpread {
            return actuallyForward ? currentIndex > pageCount - 3 : currentIndex <= 0
        }
        return actuallyForward ? currentIndex >= pageCount - 1 : currentIndex <= 0
    }

    private func beginDrag(physicallyForward: Bool) {
        let actuallyForward = physicallyForward != rightToLeft

        if isAtBoundary(actuallyForward: actuallyForward) {
            dragTurnedForward = nil
            dragOverlay = nil
            return
        }

        // Snapshot the current (outgoing) page(s) and cover the screen with it *before*
        // swapping in the real next page(s) underneath — so the reveal, as the snapshot lifts
        // away, shows the actual next page from the very first frame, not a placeholder.
        let snapshot: UIView?
        if isDoubleSpread, let container = spreadContainer {
            snapshot = container.snapshotView(afterScreenUpdates: false)
            snapshot?.frame = container.frame
        } else {
            snapshot = currentPageVC?.view.snapshotView(afterScreenUpdates: false)
            snapshot?.frame = view.bounds
        }
        if let snapshot {
            view.addSubview(snapshot)
        }
        dragOverlay = snapshot

        currentIndex = actuallyForward ? currentIndex + stepSize : currentIndex - stepSize
        showCurrentPage()
        onPageChanged?(currentIndex)
        dragTurnedForward = actuallyForward
    }

    private func updateDrag(tx: CGFloat, width: CGFloat) {
        if let overlay = dragOverlay {
            let offset = tx.clamped(to: -width...width)
            overlay.transform = CGAffineTransform(translationX: offset, y: 0)
        } else {
            let damped = Self.rubberBand(tx, max: 72)
            let target: UIView? = isDoubleSpread ? spreadContainer : currentPageVC?.view
            target?.transform = CGAffineTransform(translationX: damped, y: 0)
        }
    }

    private func endDrag(tx: CGFloat, velocityX: CGFloat, width: CGFloat) {
        let flick = abs(velocityX) >= 450 && abs(tx) >= 16

        guard let overlay = dragOverlay, let forward = dragTurnedForward else {
            // Elastic pull at a boundary — always springs back, nothing to undo.
            let target: UIView? = isDoubleSpread ? spreadContainer : currentPageVC?.view
            UIView.animate(withDuration: 0.2) {
                target?.transform = .identity
            }
            return
        }

        let stands = forward
            ? (tx <= -width * commitFraction || (flick && tx < 0))
            : (tx >= width * commitFraction || (flick && tx > 0))

        if stands {
            UIView.animate(withDuration: 0.2, animations: {
                overlay.transform = CGAffineTransform(translationX: forward ? -width : width, y: 0)
            }, completion: { _ in
                overlay.removeFromSuperview()
            })
        } else {
            UIView.animate(withDuration: 0.2, animations: {
                overlay.transform = .identity
            }, completion: { _ in
                overlay.removeFromSuperview()
                self.currentIndex = forward ? self.currentIndex - self.stepSize : self.currentIndex + self.stepSize
                self.showCurrentPage()
                self.onPageChanged?(self.currentIndex)
            })
        }
        dragOverlay = nil
        dragTurnedForward = nil
    }

    /// The classic UIScrollView rubber-band curve — see `PageTurnGesture.kt`'s own
    /// `rubberBand()` for the identical formula this mirrors.
    private static func rubberBand(_ rawDelta: CGFloat, max: CGFloat, resistance: CGFloat = 0.55) -> CGFloat {
        let x = abs(rawDelta)
        let damped = (x * max * resistance) / (max + resistance * x)
        return rawDelta < 0 ? -damped : damped
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        // Lets the current page's own pinch/pan (for zoom) keep working — handlePan bails out
        // itself via the zoomScale check above whenever the page is actually zoomed in.
        true
    }
}

/// One page of a `ComicPageContentViewController` — a pinch-zoomable image loaded lazily from
/// the parent's already-open CBZ archive. Unchanged from before Phase 4 except its `pager`
/// type, the single-tap handler's centre-tap wiring (issue #183 — comics now hide/show chrome
/// on tap, same as EPUB's reader), and [onAspectKnown] (issue #188 — lets
/// `ComicPageContentViewController` size a two-page spread to each page's own real shape).
private final class ComicPageViewController: UIViewController, UIScrollViewDelegate {
    let index: Int
    private weak var pager: ComicPageContentViewController?
    private let loadImage: () async -> UIImage?

    let scrollView = UIScrollView()
    private let imageView = UIImageView()
    private let loadingIndicator = UIActivityIndicatorView(style: .medium)
    /// Set by `ComicPageContentViewController` right after creation (and live-updated on the
    /// current page via its own `didSet`) — false fits the whole page, true fits to width and
    /// lets the scroll view pan vertically for the rest.
    var fitWidth: Bool = false {
        didSet {
            guard oldValue != fitWidth else { return }
            // The fit *mode* changed, not the viewport size lastFitSize normally guards
            // against re-touching — force a real recompute regardless.
            lastFitSize = .zero
            layoutImage()
        }
    }
    /// issue #188 — fires once, the moment this page's real image finishes loading, with its
    /// own width/height ratio. `ComicPageContentViewController` uses this to size a two-page
    /// spread's containers to each page's real shape rather than a flat 50/50 split — see
    /// that type's own `layoutSpread`.
    var onAspectKnown: ((CGFloat) -> Void)?
    /// The scroll view's own bounds size the last time `layoutImage()` actually fit the image
    /// to it, or `.zero` before the first real layout. Compared against *size*, never against
    /// the scroll view's live `zoomScale` — comparing zoom risked a feedback loop (setting
    /// `zoomScale` can itself provoke another layout pass, which would re-enter this exact
    /// check) that could lock the app up solid; comparing size cannot re-enter itself, since
    /// nothing here changes the scroll view's *size*. It still self-corrects a transitional
    /// size reported mid-embedding — issue #183 Phase 4 nests this three levels deep in
    /// Compose's `UIKitViewController`, unlike the old direct full-screen presentation — by
    /// re-fitting on the next, truly-final layout pass; once the size stops changing, later
    /// passes never touch zoom again, so a manual pinch afterward is left alone.
    private var lastFitSize: CGSize = .zero
    /// True once `imageView.frame`/`scrollView.contentSize` have been set to the image's own
    /// native size — done exactly once. `layoutImage()` runs multiple times per page (once
    /// from `viewDidLayoutSubviews`, again once the image finishes loading, and again if the
    /// viewport's size settles late — see `lastFitSize`'s own comment); resetting the zoomed
    /// view's frame back to its *native*, unscaled size on a later call, while `zoomScale` is
    /// already non-1.0 from an earlier one, desyncs UIScrollView's own zoom bookkeeping from
    /// the view's actual frame — the likely cause of pages rendering zoomed in after the very
    /// first layout. The image's own intrinsic size never changes, so there's nothing to redo.
    private var hasConfiguredContent = false

    init(index: Int, pager: ComicPageContentViewController, loadImage: @escaping () async -> UIImage?) {
        self.index = index
        self.pager = pager
        self.loadImage = loadImage
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        scrollView.frame = view.bounds
        scrollView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scrollView.delegate = self
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 4
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        view.addSubview(scrollView)

        imageView.contentMode = .scaleAspectFit
        scrollView.addSubview(imageView)

        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(loadingIndicator)
        NSLayoutConstraint.activate([
            loadingIndicator.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            loadingIndicator.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
        loadingIndicator.startAnimating()

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)

        let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleSingleTap(_:)))
        singleTap.require(toFail: doubleTap)
        scrollView.addGestureRecognizer(singleTap)

        Task {
            guard let image = await loadImage() else { return }
            imageView.image = image
            loadingIndicator.stopAnimating()
            loadingIndicator.removeFromSuperview()
            if image.size.width > 0, image.size.height > 0 {
                onAspectKnown?(image.size.width / image.size.height)
            }
            layoutImage()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        scrollView.frame = view.bounds
        layoutImage()
    }

    private func layoutImage() {
        guard let image = imageView.image, image.size.width > 0, image.size.height > 0 else { return }
        guard scrollView.bounds.width > 0, scrollView.bounds.height > 0 else { return }

        if !hasConfiguredContent {
            imageView.frame = CGRect(origin: .zero, size: image.size)
            scrollView.contentSize = image.size
            hasConfiguredContent = true
        }

        let scale = fitWidth
            ? scrollView.bounds.width / image.size.width
            : min(scrollView.bounds.width / image.size.width, scrollView.bounds.height / image.size.height)
        scrollView.minimumZoomScale = scale
        scrollView.maximumZoomScale = max(scale * 4, 1)
        // Only re-fit when the viewport's own size has actually changed since the last fit —
        // see `lastFitSize`'s own doc comment for why this compares size, not zoom.
        if scrollView.bounds.size != lastFitSize {
            lastFitSize = scrollView.bounds.size
            // Deferred a tick — setting zoomScale synchronously from inside
            // viewDidLayoutSubviews (mid-layout-pass) updates the property but the visual
            // transform doesn't reliably follow, leaving the page rendered near its native
            // size instead of the computed fit — a known UIScrollView quirk. Running it after
            // this layout pass has fully settled applies it correctly.
            DispatchQueue.main.async { [weak self] in
                guard let self, self.scrollView.bounds.size == self.lastFitSize else { return }
                self.scrollView.setZoomScale(scale, animated: false)
                self.centerImage()
            }
        }
        centerImage()
    }

    private func centerImage() {
        let boundsSize = scrollView.bounds.size
        var frame = imageView.frame
        frame.origin.x = frame.width < boundsSize.width ? (boundsSize.width - frame.width) / 2 : 0
        frame.origin.y = frame.height < boundsSize.height ? (boundsSize.height - frame.height) / 2 : 0
        imageView.frame = frame
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
    func scrollViewDidZoom(_ scrollView: UIScrollView) { centerImage() }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        if scrollView.zoomScale > scrollView.minimumZoomScale {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
        } else {
            let point = gesture.location(in: imageView)
            let zoomRect = CGRect(
                x: point.x - scrollView.bounds.width / 4,
                y: point.y - scrollView.bounds.height / 4,
                width: scrollView.bounds.width / 2,
                height: scrollView.bounds.height / 2
            )
            scrollView.zoom(to: zoomRect, animated: true)
        }
    }

    @objc private func handleSingleTap(_ gesture: UITapGestureRecognizer) {
        // Edge-tap page turning (issue #108), same shape as EpubReaderViewController's own —
        // only while not zoomed in, so a zoomed reader can still tap near an edge to pan there
        // instead of accidentally turning the page. issue #183: a centre tap now toggles the
        // shared chrome, same as EPUB's reader. issue #188: any tap at all falls through to a
        // centre tap once tap-navigation is off — double-spread's own reason, or (same as
        // always) the user's own toggle — matching Android's `EdgeTapNavigator(enabled: false)`
        // fallback rather than going dead.
        guard scrollView.zoomScale <= scrollView.minimumZoomScale else { return }
        guard pager?.effectiveTapNavigationEnabled == true else {
            pager?.centerTapped()
            return
        }
        let width = view.bounds.width
        let x = gesture.location(in: view).x
        let edgeFraction: CGFloat = 0.28
        if x < width * edgeFraction {
            pager?.turnPage(physicallyForward: false)
        } else if x > width * (1 - edgeFraction) {
            pager?.turnPage(physicallyForward: true)
        } else {
            pager?.centerTapped()
        }
    }
}
