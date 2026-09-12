import UIKit
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

/// Real Readium Swift Toolkit PDF reader (issue #112, next in the "iOS Reading Support"
/// proposal's build order after EPUB/#101 and comics/#106/#107). Backed by Apple's own
/// PDFKit — Readium Swift's `PDFNavigatorViewController` wraps it directly, so unlike
/// Android's PDFium-based reader, no bundled native PDF library is needed at all.
///
/// Deliberately minimal, matching `EpubReaderViewController`'s own bar rather than Android's
/// richer `PdfReaderViewModel` (which persists bookmarks and a resumable reading-progress
/// locator, synced against the server): no iOS reader has that yet, for any format, so PDF
/// isn't the place to introduce the asymmetry of building it for one format only. Just opens
/// `url` — already an absolute, resolved acquisition URL — with `authHeader` attached, and
/// renders it. Self-contained rather than sharing `EpubReaderViewController`'s asset-retrieve-
/// then-open boilerplate, to keep this change's diff focused; factoring the two readers'
/// common setup into one helper is a possible later cleanup, not attempted here.
///
/// No delegate is supplied to the navigator — `PDFNavigatorDelegate` carries the same one
/// non-defaulted `NavigatorDelegate.presentError` requirement `EPUBNavigatorDelegate` does
/// (confirmed against real source: both declare the identical `VisualNavigatorDelegate,
/// SelectableNavigatorDelegate, ViewportObservingNavigatorDelegate` conformance list), but
/// `delegate` defaults to `nil` on `PDFNavigatorViewController`'s initializer and this pass
/// isn't porting any comic-specific behavior (edge-tap paging, manga direction) — neither
/// applies to a PDF document — so there's nothing to implement it for yet.
///
/// Not yet verified beyond compiling — no Mac available locally; the real test is a
/// triggered `ios-ci` run.
final class PdfReaderViewController: UIViewController {
    private let url: URL
    private let authHeader: String?

    private let loadingIndicator = UIActivityIndicatorView(style: .large)
    private var navigatorViewController: PDFNavigatorViewController?

    init(url: URL, authHeader: String?) {
        self.url = url
        self.authHeader = authHeader
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

        do {
            let navigator = try PDFNavigatorViewController(
                publication: publication,
                initialLocation: nil
            )
            embed(navigator)
        } catch {
            showError("Couldn't open this PDF.")
        }
    }

    private func embed(_ navigator: PDFNavigatorViewController) {
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

extension PdfReaderViewController {
    /// Wraps the reader in its own `UINavigationController` with a "Done" button, ready to
    /// present modally — same pattern as `EpubReaderViewController.presentable`.
    static func presentable(url: URL, authHeader: String?) -> UIViewController {
        let reader = PdfReaderViewController(url: url, authHeader: authHeader)
        reader.title = "Reading"
        reader.navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done,
            target: reader,
            action: #selector(PdfReaderViewController.close)
        )
        return UINavigationController(rootViewController: reader)
    }

    @objc private func close() {
        dismiss(animated: true)
    }
}
