import UIKit
import ReadiumZIPFoundation

/// Native, dedicated comic-page pager (issue #179).
///
/// Comics used to reuse `EpubReaderViewController`'s `EPUBNavigatorViewController`, per
/// Readium's own changelog (3.8.0), which documents fixed-layout/Divina support for CBZ as a
/// reason to deprecate the separate `CBZNavigatorViewController`. On a real device, though,
/// that path never painted anything: the page image decoded successfully (confirmed via
/// WebKit's own JPEG-decode logging) and the navigator's view had a correct, non-zero frame,
/// yet the screen stayed blank/white with no further signal to diagnose. Notably, Android's own
/// comic reader never reused its EPUB reader either — it's a from-scratch Compose pager
/// (`ComicPanelDetector`/`PanelSteppingNavigator`) — so this mirrors that same architecture
/// decision on iOS instead of continuing to chase Readium's fixed-layout internals.
///
/// Reads page images directly from the local CBZ file that `ComicArchiveNormalizer` always
/// produces (it downloads the comic in full and verifies its real magic bytes, unpacking+
/// repacking as CBZ only when the source turns out to be RAR) via `ReadiumZIPFoundation`, the
/// same ZIP reader/writer already used to build that file — no new dependency needed.
final class ComicPagerViewController: UIPageViewController {
    private let url: URL
    private let authHeader: String?
    private let isManga: Bool

    private var archive: ReadiumZIPFoundation.Archive?
    private var pageEntries: [ReadiumZIPFoundation.Entry] = []

    private let loadingIndicator = UIActivityIndicatorView(style: .large)

    private static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "gif", "webp", "bmp",
    ]

    init(url: URL, authHeader: String?, isManga: Bool) {
        self.url = url
        self.authHeader = authHeader
        self.isManga = isManga
        super.init(transitionStyle: .scroll, navigationOrientation: .horizontal, options: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        dataSource = self

        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(loadingIndicator)
        NSLayoutConstraint.activate([
            loadingIndicator.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            loadingIndicator.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
        loadingIndicator.startAnimating()

        Task { await loadPages() }
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

        var sorted = entries
            .filter { Self.imageExtensions.contains(($0.path as NSString).pathExtension.lowercased()) }
            .sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
        // issue #108: same reasoning as EpubReaderViewController's edge-tap inversion — a manga
        // reads its pages in the opposite physical order, so reverse the reading-order array
        // itself rather than special-casing navigation everywhere it's used.
        if isManga {
            sorted.reverse()
        }

        guard !sorted.isEmpty else {
            NSLog("[ComicPager] no image entries found in \(cbzURL)")
            showError("This comic has no readable pages.")
            return
        }

        self.archive = archive
        pageEntries = sorted

        loadingIndicator.stopAnimating()
        loadingIndicator.removeFromSuperview()

        if let first = makePage(at: 0) {
            setViewControllers([first], direction: .forward, animated: false)
        }
    }

    private func makePage(at index: Int) -> ComicPageViewController? {
        guard pageEntries.indices.contains(index) else { return nil }
        return ComicPageViewController(index: index, pager: self) { [weak self] in
            await self?.loadImage(at: index)
        }
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

    /// Called by a page's edge-tap gesture (see `ComicPageViewController`) — the pager owns
    /// navigation since it alone knows the current index and the reading-order array.
    fileprivate func turnPage(physicallyForward: Bool) {
        guard let current = (viewControllers?.first as? ComicPageViewController)?.index else { return }
        // Same inversion as EpubReaderViewController's EdgeTapNavigator: the array is already
        // reversed for manga, so "reading forward" here always just means the next array index.
        let nextIndex = physicallyForward != isManga ? current + 1 : current - 1
        guard let next = makePage(at: nextIndex) else { return }
        setViewControllers([next], direction: nextIndex > current ? .forward : .reverse, animated: true)
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

extension ComicPagerViewController: UIPageViewControllerDataSource {
    func pageViewController(_ pageViewController: UIPageViewController, viewControllerBefore viewController: UIViewController) -> UIViewController? {
        guard let current = (viewController as? ComicPageViewController)?.index else { return nil }
        return makePage(at: current - 1)
    }

    func pageViewController(_ pageViewController: UIPageViewController, viewControllerAfter viewController: UIViewController) -> UIViewController? {
        guard let current = (viewController as? ComicPageViewController)?.index else { return nil }
        return makePage(at: current + 1)
    }
}

extension ComicPagerViewController {
    /// Presents the pager full-screen, dismissed by the standard edge-swipe gesture (issue
    /// #176) — same shape as `EpubReaderViewController.presentable`.
    static func presentable(url: URL, authHeader: String?, isManga: Bool) -> UIViewController {
        let pager = ComicPagerViewController(url: url, authHeader: authHeader, isManga: isManga)
        pager.title = "Reading"
        return FullScreenReaderPresentation.wrap(pager)
    }
}

/// One page of a `ComicPagerViewController` — a pinch-zoomable image loaded lazily from the
/// parent's already-open CBZ archive. `UIPageViewController` (with `.scroll` transition style)
/// keeps at most the current page plus its immediate neighbors alive at once, which bounds
/// memory without needing an explicit image cache here.
private final class ComicPageViewController: UIViewController, UIScrollViewDelegate {
    let index: Int
    private weak var pager: ComicPagerViewController?
    private let loadImage: () async -> UIImage?

    private let scrollView = UIScrollView()
    private let imageView = UIImageView()
    private let loadingIndicator = UIActivityIndicatorView(style: .medium)
    private var hasSetInitialZoom = false

    init(index: Int, pager: ComicPagerViewController, loadImage: @escaping () async -> UIImage?) {
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
        // A page can be laid out before it has a real, non-zero frame (e.g. `UIPageViewController`
        // preparing an off-screen neighbor) — bail out rather than compute a bogus zero/NaN
        // scale and latch `hasSetInitialZoom` on it; `viewDidLayoutSubviews` runs again once
        // this page gets a real frame.
        guard scrollView.bounds.width > 0, scrollView.bounds.height > 0 else { return }

        imageView.frame = CGRect(origin: .zero, size: image.size)
        scrollView.contentSize = image.size

        let scale = min(scrollView.bounds.width / image.size.width, scrollView.bounds.height / image.size.height)
        scrollView.minimumZoomScale = scale
        scrollView.maximumZoomScale = max(scale * 4, 1)
        // Only force-fit on the very first layout — `zoomScale` starts at UIScrollView's own
        // default of 1.0, which is *larger* than a typical fit-to-screen `scale` (well under 1
        // for any page bigger than the screen), so a naive "raise zoomScale up to scale" check
        // never fires and the page renders at native pixel size instead of fit-to-screen. Once
        // set, later layout passes (e.g. from `viewDidLayoutSubviews`) must leave zoomScale
        // alone or they'd keep resetting the user's own pinch-zoom back to fit.
        if !hasSetInitialZoom {
            scrollView.zoomScale = scale
            hasSetInitialZoom = true
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
        // instead of accidentally turning the page.
        guard scrollView.zoomScale <= scrollView.minimumZoomScale else { return }
        let width = view.bounds.width
        let x = gesture.location(in: view).x
        let edgeFraction: CGFloat = 0.28
        if x < width * edgeFraction {
            pager?.turnPage(physicallyForward: false)
        } else if x > width * (1 - edgeFraction) {
            pager?.turnPage(physicallyForward: true)
        }
        // Centre tap: no reader chrome to toggle yet, matching EpubReaderViewController.
    }
}
