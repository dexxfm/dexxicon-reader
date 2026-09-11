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
        hostVC = MainViewControllerKt.MainViewController(onOpenReader: { serverId, bookId, format, url, authHeader in
            guard let bookUrl = URL(string: url) else { return }
            switch format {
            case .epub, .mobi, .azw3, .fb2:
                // issue #101: these four formats all open through the same EPUB Navigator —
                // the server already converts MOBI/AZW3/FB2 to EPUB before either client
                // requests bytes (see ReaderLaunch.kt's doc comment), so there's no
                // format-specific branching needed here.
                let reader = EpubReaderViewController.presentable(url: bookUrl, authHeader: authHeader)
                hostVC.present(reader, animated: true)
            default:
                // PDF/comics/audiobooks aren't built yet (see the "iOS Reading Support"
                // proposal's sequencing) — same placeholder #99 proved the plumbing with.
                let alert = UIAlertController(
                    title: "Not yet supported",
                    message: "Reading \(format.name) books on iOS isn't built yet.",
                    preferredStyle: .alert
                )
                alert.addAction(UIAlertAction(title: "OK", style: .default))
                hostVC.present(alert, animated: true)
            }
        })
        return hostVC
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
