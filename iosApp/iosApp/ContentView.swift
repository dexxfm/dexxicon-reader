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
        // issue #114: adding `audiobook` (a nullable Kotlin data class) as this closure's 7th
        // parameter changed how Kotlin/Native bridges the whole block signature to Swift —
        // `isManga` (a primitive Kotlin Boolean) now arrives boxed as `KotlinBoolean` instead
        // of a native `Bool`, confirmed via a real ios-ci compile error, not guessed. `.boolValue`
        // unwraps it at each use below.
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
                // issue #183: serverId/bookId (position save/restore, bookmarks/highlights)
                // and digestUrl (the acquisition URL itself, same "remote file reference"
                // Android's readers keep) are new — no iOS reader recorded any of this before.
                let reader = EpubReaderViewController.presentable(
                    url: bookUrl, authHeader: authHeader, isManga: isManga.boolValue,
                    serverId: serverId, bookId: bookId, digestUrl: url
                )
                hostVC.present(reader, animated: true)
            case .comic:
                // issue #106/#108/#107/#179: CBZ and CBR both open through
                // ComicPagerViewController, a dedicated native pager — Readium's own
                // fixed-layout/Divina reuse of EPUBNavigatorViewController (per its 3.8.0
                // changelog) never actually painted a comic page on a real device, so comics
                // get their own reader instead, mirroring Android's own from-scratch comic
                // reader. CBR needs one extra step first — ComicArchiveNormalizer unpacks +
                // repacks it as a real ZIP, since format sniffing is ZIP-only — but that
                // happens inside the pager's own async load, transparently to this switch.
                let reader = ComicPagerViewController.presentable(url: bookUrl, authHeader: authHeader, isManga: isManga.boolValue)
                hostVC.present(reader, animated: true)
            case .pdf:
                // issue #112: backed by Apple's own PDFKit via Readium's
                // PDFNavigatorViewController — no bundled native PDF library needed, unlike
                // Android's PDFium-based reader.
                // issue #183: serverId/bookId/digestUrl mirror the EPUB case above — position
                // save/restore and bookmarks are new for this reader too.
                let reader = PdfReaderViewController.presentable(
                    url: bookUrl, authHeader: authHeader,
                    serverId: serverId, bookId: bookId, digestUrl: url
                )
                hostVC.present(reader, animated: true)
            case .audiobook:
                // issue #114/#183: no Readium involvement at all — Android's own player bypasses
                // Readium for audio too (a plain stream URL + ExoPlayer), so this mirrors that
                // with AVPlayer instead of adopting Readium Swift's AudioNavigator, which
                // expects a packaged Readium/ZAB manifest this app's servers don't produce. The
                // screen itself is the shared Compose `PlayerScreen` (issue #183) — this closure
                // only needs to start the real engine and present it.
                // `audiobook` carries the metadata (title/author/narrator/cover/duration/
                // chapters) no other reader needs — BookDetailScreen only builds it for this
                // format, so it should never actually be nil here, but the player needs *some*
                // title even in that unexpected case.
                let book = AudiobookPlaybackController.Book(
                    serverId: serverId,
                    bookId: bookId,
                    title: audiobook?.title ?? "Audiobook",
                    author: audiobook?.author,
                    narrator: audiobook?.narrator,
                    coverUrl: audiobook?.coverUrl,
                    durationMs: audiobook?.durationMs ?? 0,
                    chapters: (audiobook?.chapters ?? []).map {
                        AudiobookPlaybackController.ChapterInfo(title: $0.title, startMs: $0.startMs)
                    },
                    digestUrl: url
                )
                presentPlayer(from: hostVC, book: book, authHeader: authHeader)
            default:
                // .unknown — includes CB7 (issue #110: dropped rather than supported) and any
                // genuinely unrecognized format. Same placeholder #99 proved the plumbing with.
                notYetSupported("Reading \(format.name) books on iOS isn't built yet.")
            }
        })

        // issue #146: points :shared's MiniPlayer (docked in App.kt, rendered by the very
        // Compose tree `hostVC` above hosts) at the real native player singleton — same
        // "wire the container's actions to the real engine" step Android's
        // `DexxiconApplication.wireMiniPlayer()` does at app startup.
        MainViewControllerKt.setMiniPlayerActions(
            playPause: { AudiobookPlaybackController.shared.playPause() },
            dismiss: { AudiobookPlaybackController.shared.stop() },
            reopen: {
                guard let book = AudiobookPlaybackController.shared.state.book else { return }
                presentPlayer(from: hostVC, book: book, authHeader: nil)
            }
        )

        // issue #183: the full player screen's own bridge, alongside setMiniPlayerActions'
        // narrower slice — every command the shared PlayerScreen can send, pointed at the same
        // real engine. Every primitive parameter here arrives boxed (KotlinLong/KotlinInt/
        // KotlinFloat, not a native Int64/Int/Float) — same interop quirk as `isManga` above:
        // a Kotlin function type Swift *implements* (Kotlin invokes it later) always boxes its
        // primitive parameters, confirmed via a real ios-ci compile error on that closure, not
        // guessed a second time here.
        MainViewControllerKt.setPlayerActions(
            playPause: { AudiobookPlaybackController.shared.playPause() },
            skipForward: { AudiobookPlaybackController.shared.skipForward() },
            skipBack: { AudiobookPlaybackController.shared.skipBack() },
            nextChapter: { AudiobookPlaybackController.shared.nextChapter() },
            previousChapter: { AudiobookPlaybackController.shared.previousChapter() },
            seekTo: { AudiobookPlaybackController.shared.seek(toMs: $0.int64Value) },
            seekToChapter: { index in
                let controller = AudiobookPlaybackController.shared
                let i = Int(index.int32Value)
                guard let book = controller.state.book, book.chapters.indices.contains(i) else { return }
                controller.seek(toMs: book.chapters[i].startMs)
            },
            setSpeed: { AudiobookPlaybackController.shared.setSpeed($0.floatValue) },
            setSleepTimer: { durationMs in
                guard let durationMs else {
                    AudiobookPlaybackController.shared.clearSleepTimer()
                    return
                }
                AudiobookPlaybackController.shared.setSleepTimer(minutes: Int(durationMs.int64Value / 60_000))
            },
            setSleepTimerEndOfChapter: { AudiobookPlaybackController.shared.setSleepTimerEndOfChapter() }
        )

        return hostVC
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Starts the real engine (if not already loaded) and presents the shared Compose `PlayerScreen`
/// (issue #183) — shared between the initial `onOpenReader` hand-off and the mini-player's
/// "reopen" tap, which re-presents over an already-running engine instead of restarting it.
private func presentPlayer(from hostVC: UIViewController, book: AudiobookPlaybackController.Book, authHeader: String?) {
    let controller = AudiobookPlaybackController.shared
    if !controller.isLoaded(serverId: book.serverId, bookId: book.bookId) {
        controller.start(book: book, authHeader: authHeader)
    }
    let player = MainViewControllerKt.PlayerViewController(onBack: {
        hostVC.dismiss(animated: true)
    })
    // hidesNavigationBar: true — PlayerScreen draws its own BackPill as part of its Compose
    // content, so the native bar's back button here would just be a redundant second one.
    hostVC.present(FullScreenReaderPresentation.wrap(player, hidesNavigationBar: true), animated: true)
}
