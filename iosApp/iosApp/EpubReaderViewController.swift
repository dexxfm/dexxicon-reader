import UIKit
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

/// Real Readium Swift Toolkit EPUB reader (issue #101, following #99's plumbing). Opens
/// `url` — already an absolute, resolved acquisition URL — with `authHeader` — already
/// resolved by `:shared`'s `AppContainer.authHeaderProvider`, the same provider its own
/// authenticated Ktor client uses — attached to every request. This mirrors what Android's
/// `core/reader/PublicationStreamer` does with its authenticated OkHttp client: neither
/// platform reader needs its own path back into the auth layer, both just get a
/// ready-to-use URL + header.
///
/// Also the reader for MOBI/AZW3/FB2 (see `ReaderLaunch.kt`'s doc comment) — the server
/// already converts those three to EPUB before either client ever requests bytes, so they
/// open through this exact same path, no format-specific handling needed here.
///
/// Not yet verified beyond compiling — no Mac available locally; the real test is a
/// triggered `ios-ci` run. `EPUBNavigatorDelegate` is left unset (every one of its own
/// methods has a default empty implementation) to keep this first real version minimal.
final class EpubReaderViewController: UIViewController {
    private let url: URL
    private let authHeader: String?

    private let loadingIndicator = UIActivityIndicatorView(style: .large)
    private var navigatorViewController: EPUBNavigatorViewController?

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
        // AssetRetriever.retrieve(url:) takes Readium's own AbsoluteURL protocol, not
        // Foundation's URL — HTTPURL(string:) is Readium's real, documented constructor for
        // a remote http(s) URL (confirmed against ios-ci's actual compiler error, not
        // guessed a second time: `argument type 'URL' does not conform to expected type
        // 'AbsoluteURL'`).
        guard let httpURL = HTTPURL(string: url.absoluteString) else {
            showError("Couldn't open this book.")
            return
        }

        // additionalHeaders applies to every request this client makes — the same one-time
        // attachment Android's authenticated OkHttp client does, just via Readium Swift's own
        // HTTP client instead of Ktor.
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
            let navigator = try EPUBNavigatorViewController(
                publication: publication,
                initialLocation: nil
            )
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
    static func presentable(url: URL, authHeader: String?) -> UIViewController {
        let reader = EpubReaderViewController(url: url, authHeader: authHeader)
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
