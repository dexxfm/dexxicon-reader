import CarPlay
import SharedKit

/// CarPlay's scene delegate (issue #119, browse tree added issue #240). Root template is a
/// library browse list — Continue listening / Downloaded / All audiobooks, mirroring Android
/// Auto's own browse tree (issue #118) — selecting a book starts playback and pushes
/// `CPNowPlayingTemplate.shared`, which auto-populates itself from the exact same
/// `MPNowPlayingInfoCenter`/`MPRemoteCommandCenter` state `AudiobookPlaybackController.swift`
/// already publishes for the lock screen/Control Center (issue #114) — no separate data layer
/// needed for that part, and no custom Now Playing buttons here either: this app's lock-screen
/// surface deliberately exposes only play/pause and skip-forward-30/skip-back-15 (chapter nav
/// stays in-app-only — see `AudiobookPlaybackController.configureRemoteCommands()`'s own
/// comment), and `CPNowPlayingTemplate` inherits that same restraint for free.
///
/// The browse tree itself (issue #240) is a real, new Swift UI built on `CarPlayLibraryBridge`
/// (`:shared`) — see that class's own doc comment for the query logic and the deliberate,
/// scoped-down differences from Android Auto's tree.
@objc(CarPlaySceneDelegate)
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private lazy var libraryBridge = MainViewControllerKt.carPlayLibraryBridge()
    /// Keeps each row's `UIImage` cache alive for the lifetime of the list — `CPListItem`
    /// itself has no image cache of its own, and covers are re-fetched on every reload
    /// otherwise (e.g. reconnecting the CarPlay scene).
    private var coverCache: [String: UIImage] = [:]

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        loadLibrary()
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        self.interfaceController = nil
    }

    // MARK: browse tree

    private func loadLibrary() {
        // A visible root immediately, even before the three lists resolve — CarPlay has no
        // built-in loading state for CPListTemplate the way CPNowPlayingTemplate has for
        // playback, so an empty-sections template is the closest equivalent.
        interfaceController?.setRootTemplate(
            CPListTemplate(title: "Dexxicon Reader", sections: []),
            animated: false,
            completion: nil
        )

        let group = DispatchGroup()
        var continueListening: [CarPlayAudiobookCard] = []
        var downloaded: [CarPlayAudiobookCard] = []
        var all: [CarPlayAudiobookCard] = []

        group.enter()
        libraryBridge.continueListening { cards in
            continueListening = cards
            group.leave()
        }
        group.enter()
        libraryBridge.downloaded { cards in
            downloaded = cards
            group.leave()
        }
        group.enter()
        libraryBridge.allAudiobooks { cards in
            all = cards
            group.leave()
        }

        group.notify(queue: .main) { [weak self] in
            self?.presentLibrary(continueListening: continueListening, downloaded: downloaded, all: all)
        }
    }

    /// issue #240 (requested live): one tab per list, instead of three stacked sections in a
    /// single scrolling list — easier to parse at a glance while driving, and each tab keeps
    /// its own scroll position when switching back to it, unlike sections in one long list.
    private func presentLibrary(
        continueListening: [CarPlayAudiobookCard],
        downloaded: [CarPlayAudiobookCard],
        all: [CarPlayAudiobookCard]
    ) {
        var tabs: [CPListTemplate] = []
        if !continueListening.isEmpty {
            tabs.append(tab(title: "Continue listening", cards: continueListening))
        }
        if !downloaded.isEmpty {
            tabs.append(tab(title: "Downloaded", cards: downloaded))
        }
        if !all.isEmpty {
            tabs.append(tab(title: "All audiobooks", cards: all))
        }
        if tabs.isEmpty {
            // No content anywhere — a tab bar needs at least one tab, so fall back to a plain
            // empty list rather than an invalid zero-tab CPTabBarTemplate.
            interfaceController?.setRootTemplate(
                CPListTemplate(title: "Dexxicon Reader", sections: []),
                animated: true,
                completion: nil
            )
            return
        }
        interfaceController?.setRootTemplate(
            CPTabBarTemplate(templates: tabs),
            animated: true,
            completion: nil
        )
    }

    private func tab(title: String, cards: [CarPlayAudiobookCard]) -> CPListTemplate {
        let template = CPListTemplate(
            title: title,
            sections: [CPListSection(items: cards.map(listItem), header: nil, sectionIndexTitle: nil)]
        )
        template.tabTitle = title
        return template
    }

    private func listItem(for card: CarPlayAudiobookCard) -> CPListItem {
        let item = CPListItem(text: card.title, detailText: card.author)
        if let progress = card.progress?.doubleValue {
            item.playbackProgress = CGFloat(progress)
        }
        item.handler = { [weak self] _, completion in
            self?.play(serverId: card.serverId, bookId: card.bookId, completion: completion)
        }
        if let cached = coverCache[card.coverUrl ?? ""] {
            item.setImage(cached)
        } else if let urlString = card.coverUrl, let url = URL(string: urlString) {
            loadCover(url: url) { [weak self, weak item] image in
                guard let self, let item, let image else { return }
                self.coverCache[urlString] = image
                item.setImage(image)
            }
        }
        return item
    }

    private func loadCover(url: URL, completion: @escaping (UIImage?) -> Void) {
        URLSession.shared.dataTask(with: url) { data, _, _ in
            // issue #240: CPListItem.maximumImageSize is the documented target — CarPlay
            // renders whatever's provided at whatever size it already is, undersized or
            // oversized, so downscaling here (cheap, cover art is never that large to begin
            // with) avoids handing CarPlay a full-resolution image for a thumbnail-sized cell.
            let image = data.flatMap { UIImage(data: $0) }
                .map { CarPlaySceneDelegate.resized($0, to: CPListItem.maximumImageSize) }
            DispatchQueue.main.async { completion(image) }
        }.resume()
    }

    private static func resized(_ image: UIImage, to targetSize: CGSize) -> UIImage {
        let scale = min(targetSize.width / image.size.width, targetSize.height / image.size.height, 1)
        guard scale < 1 else { return image }
        let newSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: newSize)) }
    }

    // MARK: playback

    private func play(serverId: String, bookId: String, completion: @escaping () -> Void) {
        libraryBridge.resolve(serverId: serverId, bookId: bookId) { [weak self] playable in
            defer { completion() }
            guard let self, let playable else { return }
            let book = AudiobookPlaybackController.Book(
                serverId: playable.serverId,
                bookId: playable.bookId,
                title: playable.title,
                author: playable.author,
                narrator: playable.narrator,
                coverUrl: playable.coverUrl,
                durationMs: playable.durationMs,
                chapters: playable.chapters.map {
                    AudiobookPlaybackController.ChapterInfo(title: $0.title, startMs: $0.startMs)
                },
                digestUrl: playable.digestUrl
            )
            AudiobookPlaybackController.shared.start(book: book, authHeader: playable.authHeader)
            self.showNowPlaying()
        }
    }

    /// issue #240: real crash confirmed via crash log — `CPNowPlayingTemplate.shared` is a
    /// singleton, so playing a second book after already having pushed it once threw
    /// `"Pushing the same template instance more than once is not supported."` `pushTemplate`
    /// is only safe the first time; once it's already somewhere in the stack (the user picked
    /// a book, went back to the browse tabs, then picked another), `popToTemplate` brings the
    /// same instance back to the front instead of pushing a duplicate.
    private func showNowPlaying() {
        guard let interfaceController else { return }
        let alreadyPushed = interfaceController.templates.contains { $0 === CPNowPlayingTemplate.shared }
        if alreadyPushed {
            interfaceController.popToTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
        } else {
            interfaceController.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
        }
    }
}
