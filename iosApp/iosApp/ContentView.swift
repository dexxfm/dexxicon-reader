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
                // issue #106/#108: CBZ opens through the same EPUB Navigator too — Readium's
                // own changelog (3.8.0) deprecated CBZNavigatorViewController in favor of this
                // exact reuse — and it's the same reader that now also carries manga reading
                // direction + edge-tap page turning. CBR isn't supported yet (issue #107 —
                // the RAR-extraction library choice for iOS has a real wrinkle, tracked
                // separately) — a quick extension check gives a clear message instead of a
                // confusing generic failure from Readium trying and failing to open a raw
                // RAR as a ZIP.
                if bookUrl.pathExtension.lowercased() == "cbr" {
                    notYetSupported("Reading .cbr comics on iOS isn't built yet.")
                } else {
                    let reader = EpubReaderViewController.presentable(url: bookUrl, authHeader: authHeader, isManga: isManga)
                    hostVC.present(reader, animated: true)
                }
            default:
                // PDF/audiobooks aren't built yet (see the "iOS Reading Support" proposal's
                // sequencing) — same placeholder #99 proved the plumbing with.
                notYetSupported("Reading \(format.name) books on iOS isn't built yet.")
            }
        })
        return hostVC
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
