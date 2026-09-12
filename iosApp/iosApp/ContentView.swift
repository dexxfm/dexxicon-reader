import SwiftUI
import SharedKit

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard) // let Compose own the rest of the safe area
    }
}

/// Hosts the shared Compose Multiplatform UI inside SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // issue #99: `onOpenReader` is the hand-off from :shared's Compose UI to a real,
        // native reader screen — `hostVC` is the same view controller Compose renders into,
        // captured so there's something to present from.
        var hostVC: UIViewController!
        hostVC = MainViewControllerKt.MainViewController(onOpenReader: { serverId, bookId, format, url, authHeader, isManga in
            guard let bookUrl = URL(string: url) else { return }
            let notYetSupported = { (message: String) in
                let alert = UIAlertController(title: "Not yet supported", message: message, preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "OK", style: .default))
                hostVC.present(alert, animated: true)
            }
            switch format {
            case .epub, .mobi, .azw3, .fb2:
                // issue #101: these four formats all open through the same EPUB Navigator —
                // the server already converts MOBI/AZW3/FB2 to EPUB before either client
                // requests bytes (see ReaderLaunch.kt's doc comment), so there's no
                // format-specific branching needed here.
                let reader = EpubReaderViewController.presentable(url: bookUrl, authHeader: authHeader, isManga: isManga)
                hostVC.present(reader, animated: true)
            case .comic:
                // issue #106/#108/#107: CBZ and CBR both open through the same EPUB Navigator
                // — Readium's own changelog (3.8.0) deprecated CBZNavigatorViewController in
                // favor of this exact reuse, and it's the same reader that also carries manga
                // reading direction + edge-tap page turning. CBR needs one extra step first —
                // ComicArchiveNormalizer unpacks + repacks it as a real ZIP, since Readium's
                // format sniffing is ZIP-only — but that happens inside
                // EpubReaderViewController's own async open, transparently to this switch;
                // there's nothing format-specific left to do here.
                let reader = EpubReaderViewController.presentable(url: bookUrl, authHeader: authHeader, isManga: isManga)
                hostVC.present(reader, animated: true)
            case .pdf:
                // issue #112: backed by Apple's own PDFKit via Readium's
                // PDFNavigatorViewController — no bundled native PDF library needed, unlike
                // Android's PDFium-based reader.
                let reader = PdfReaderViewController.presentable(url: bookUrl, authHeader: authHeader)
                hostVC.present(reader, animated: true)
            default:
                // Audiobooks aren't built yet (see the "iOS Reading Support" proposal's
                // sequencing) — same placeholder #99 proved the plumbing with.
                notYetSupported("Reading \(format.name) books on iOS isn't built yet.")
            }
        })
        return hostVC
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
