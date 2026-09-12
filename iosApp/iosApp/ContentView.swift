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
        hostVC = MainViewControllerKt.MainViewController(onOpenReader: { serverId, bookId, format, url, authHeader, isManga, audiobook in
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
            case .audiobook:
                // issue #114: no Readium involvement at all — Android's own player bypasses
                // Readium for audio too (a plain stream URL + ExoPlayer), so this mirrors that
                // with AVPlayer instead of adopting Readium Swift's AudioNavigator, which
                // expects a packaged Readium/ZAB manifest this app's servers don't produce.
                // `audiobook` carries the metadata (title/author/cover/duration/chapters) no
                // other reader needs — BookDetailScreen only builds it for this format, so it
                // should never actually be nil here, but the player needs *some* title even in
                // that unexpected case.
                let reader = AudiobookPlayerViewController.presentable(
                    serverId: serverId,
                    bookId: bookId,
                    url: bookUrl,
                    authHeader: authHeader,
                    title: audiobook?.title ?? "Audiobook",
                    author: audiobook?.author,
                    coverUrl: audiobook?.coverUrl,
                    durationMs: audiobook?.durationMs ?? 0,
                    chapters: (audiobook?.chapters ?? []).map {
                        AudiobookPlaybackController.ChapterInfo(title: $0.title, startMs: $0.startMs)
                    }
                )
                hostVC.present(reader, animated: true)
            default:
                // .unknown — includes CB7 (issue #110: dropped rather than supported) and any
                // genuinely unrecognized format. Same placeholder #99 proved the plumbing with.
                notYetSupported("Reading \(format.name) books on iOS isn't built yet.")
            }
        })
        return hostVC
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
