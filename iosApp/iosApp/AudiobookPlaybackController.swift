import AVFoundation
import MediaPlayer
import SharedKit
import UIKit

/// The audiobook engine itself (issue #114) — a process-lifetime singleton, deliberately
/// decoupled from `AudiobookPlayerViewController`'s own lifecycle. This is the real
/// architectural point of `MPNowPlayingInfoCenter`/background audio: like Android's
/// `PlaybackService` (a genuinely separate `MediaLibraryService`, not tied to any screen),
/// playback has to keep running — lock-screen controls, AirPlay, sleep timer, position
/// saving — after the reader screen is dismissed, or lock-screen controls would be pointless.
/// The view controller is a thin, replaceable UI over whatever this controller is doing; it
/// attaches an observer while visible and detaches it on the way out, never stopping playback
/// itself.
///
/// No Readium involvement at all (see issue #114's own research) — Android's real player
/// bypasses Readium for audio entirely (a plain stream URL + ExoPlayer, chapters from server
/// metadata, not a parsed Readium manifest), so this mirrors that architecture with `AVPlayer`
/// instead, not Readium Swift's `AudioNavigator` (which expects a real multi-resource
/// Readium/ZAB `Publication` this app's servers don't actually produce).
final class AudiobookPlaybackController: NSObject {
    static let shared = AudiobookPlaybackController()

    struct Book {
        let serverId: String
        let bookId: String
        let title: String
        let author: String?
        let coverUrl: String?
        let durationMs: Int64
        let chapters: [ChapterInfo]
        let digestUrl: String
    }

    struct ChapterInfo {
        let title: String
        let startMs: Int64
    }

    struct State {
        var book: Book?
        var isPlaying = false
        var isBuffering = false
        var positionMs: Int64 = 0
        var durationMs: Int64 = 0
        var speed: Float = 1.0
        var sleepTimerEndsAt: Date?
        var sleepAtChapterEnd = false

        var currentChapterIndex: Int {
            guard let book else { return 0 }
            return book.chapters.lastIndex(where: { $0.startMs <= positionMs }) ?? 0
        }

        var currentChapterTitle: String? {
            book?.chapters.indices.contains(currentChapterIndex) == true
                ? book?.chapters[currentChapterIndex].title
                : nil
        }
    }

    private(set) var state = State() {
        didSet { onUpdate?(state) }
    }

    /// Set by whichever `AudiobookPlayerViewController` is currently visible; cleared (not
    /// nilled-out-and-forgotten-elsewhere) when it goes away. Playback itself never depends
    /// on this being set.
    var onUpdate: ((State) -> Void)?

    private var player: AVPlayer?
    private var loader: AudiobookStreamLoader?
    private var timeObserverToken: Any?
    private var sleepWorkItem: DispatchWorkItem?
    private var lastPushedPositionMs: Int64 = -1
    private var coverImage: UIImage?

    /// The `:shared` Kotlin object this whole feature is built around (issue #114) — held
    /// once rather than re-fetched via `MainViewControllerKt.audiobookProgressSync()` on
    /// every call; it's the same one process-lifetime instance either way.
    private lazy var progressSync = MainViewControllerKt.audiobookProgressSync()

    /// How far playback rewinds when resuming from pause — same UX as Android's
    /// `AudiobookPlayer.playPause`'s `smartRewindSeconds`. Fixed here rather than a user
    /// preference (Android's own `PlayerPreferencesStore` setting) — this pass doesn't build
    /// a settings screen for it; 5s matches a common, unobtrusive default.
    private let smartRewindMs: Int64 = 5_000

    /// How often the current position is saved locally + pushed to the server — same cadence
    /// class as Android's `ServicePositionWriter`, which itself ticks every second but only
    /// actually needs to survive an app kill, not update the server every second.
    private let progressPushIntervalMs: Int64 = 15_000

    private override init() {
        super.init()
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleRouteChange),
            name: AVAudioSession.routeChangeNotification, object: nil
        )
        configureRemoteCommands()
    }

    /// True when [book] is already loaded — the caller can just re-attach its UI rather than
    /// restart playback, same as Android's `AudiobookPlayer.isLoaded`.
    func isLoaded(serverId: String, bookId: String) -> Bool {
        state.book?.serverId == serverId && state.book?.bookId == bookId
    }

    func start(book: Book, authHeader: String?) {
        guard let remoteURL = URL(string: book.digestUrl) else { return }

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)

        let newLoader = AudiobookStreamLoader(authHeader: authHeader)
        loader = newLoader
        let loaderURL = AudiobookStreamLoader.loaderURL(for: remoteURL)
        let asset = AVURLAsset(url: loaderURL)
        newLoader.attach(to: asset)
        let item = AVPlayerItem(asset: asset)

        let newPlayer = player ?? AVPlayer()
        player = newPlayer
        newPlayer.replaceCurrentItem(with: item)

        state = State(book: book, durationMs: book.durationMs)
        lastPushedPositionMs = -1
        loadCoverIfNeeded(book.coverUrl, authHeader: authHeader)
        attachTimeObserver()
        updateNowPlayingInfo()

        // issue #114: resume position — the furthest of the local position and whatever the
        // server has, resolved in Kotlin (AudiobookProgressSync.resolveResumeMs). Playback
        // waits for this (same as Android's PlayerViewModel.load(), which resolves the resume
        // position before ever calling player.play()) rather than starting at 0 and correcting
        // a moment later — a brief loading state beats an audible jump.
        progressSync.resolveResumeMs(
            serverId: book.serverId,
            bookId: book.bookId,
            durationMs: book.durationMs,
            // Same interop quirk as `isManga` in ContentView.swift: a primitive Kotlin `Long`
            // crossing as a closure parameter Swift implements (Kotlin invokes this later)
            // arrives boxed as `KotlinLong`, not a native `Int64` — real ios-ci error, not
            // guessed. `.int64Value` unwraps it once, up front, rather than at every use below.
            onResolved: { [weak self] resumeMsBoxed in
                let resumeMs = resumeMsBoxed.int64Value
                guard let self, self.state.book?.bookId == book.bookId else { return }
                if resumeMs > 0 {
                    self.player?.seek(to: self.cmTime(resumeMs)) { _ in
                        self.player?.play()
                    }
                    self.state.positionMs = resumeMs
                } else {
                    self.player?.play()
                }
                self.state.isPlaying = true
                self.updateNowPlayingInfo()
            }
        )
    }

    // MARK: transport

    func playPause() {
        guard let player else { return }
        if state.isPlaying {
            player.pause()
            state.isPlaying = false
        } else {
            // Smart rewind: re-orient the listener a few seconds before where they paused,
            // same UX as Android's AudiobookPlayer.playPause.
            let target = max(0, state.positionMs - smartRewindMs)
            let speed = state.speed
            player.seek(to: cmTime(target)) { _ in
                // Setting `.rate` directly (not `.play()`, which always resumes at 1.0x)
                // resumes at whatever speed was last chosen.
                player.rate = speed
            }
            state.isPlaying = true
        }
        updateNowPlayingInfo()
    }

    func seek(toMs ms: Int64) {
        guard let player else { return }
        let clamped = max(0, ms)
        player.seek(to: cmTime(clamped))
        state.positionMs = clamped
        updateNowPlayingInfo()
    }

    func skipForward() { seek(toMs: state.positionMs + 30_000) }
    func skipBack() { seek(toMs: max(0, state.positionMs - 15_000)) }

    func nextChapter() {
        guard let book = state.book, !book.chapters.isEmpty else { return }
        let target = min(state.currentChapterIndex + 1, book.chapters.count - 1)
        seek(toMs: book.chapters[target].startMs)
    }

    func previousChapter() {
        guard let book = state.book, !book.chapters.isEmpty else { return }
        let idx = state.currentChapterIndex
        // Within the first few seconds of a chapter, "previous" goes to the one before it;
        // otherwise it restarts the current one — same rule as Android's previousChapter().
        let restartCurrent = state.positionMs - book.chapters[idx].startMs > 3_000
        let target = restartCurrent ? idx : max(0, idx - 1)
        seek(toMs: book.chapters[target].startMs)
    }

    func setSpeed(_ speed: Float) {
        state.speed = speed
        // `.rate` only actually applies while playing — setting it while paused would resume
        // playback as a side effect (any nonzero rate does). The chosen speed still takes
        // effect the moment playback resumes, via `playPause()`'s own `player.rate = speed`.
        if state.isPlaying { player?.rate = speed }
        updateNowPlayingInfo()
    }

    // MARK: sleep timer

    func setSleepTimer(minutes: Int) {
        sleepWorkItem?.cancel()
        state.sleepAtChapterEnd = false
        let endsAt = Date().addingTimeInterval(TimeInterval(minutes * 60))
        state.sleepTimerEndsAt = endsAt
        let item = DispatchWorkItem { [weak self] in
            self?.player?.pause()
            self?.state.isPlaying = false
            self?.state.sleepTimerEndsAt = nil
        }
        sleepWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + TimeInterval(minutes * 60), execute: item)
    }

    func setSleepTimerEndOfChapter() {
        sleepWorkItem?.cancel()
        state.sleepTimerEndsAt = nil
        state.sleepAtChapterEnd = true
    }

    func clearSleepTimer() {
        sleepWorkItem?.cancel()
        state.sleepTimerEndsAt = nil
        state.sleepAtChapterEnd = false
    }

    // MARK: position observing + progress push

    private func attachTimeObserver() {
        if let token = timeObserverToken { player?.removeTimeObserver(token) }
        guard let player else { return }
        timeObserverToken = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1, preferredTimescale: 1_000),
            queue: .main
        ) { [weak self] time in
            self?.tick(currentTime: time)
        }
    }

    private func tick(currentTime: CMTime) {
        guard let book = state.book else { return }
        // `.seconds` is NaN for an invalid/indefinite time (e.g. right before the item's
        // timeline is known) — `Int64(Double)` traps on NaN, so this has to be checked before
        // converting, not just clamped to 0 afterward.
        guard currentTime.seconds.isFinite else { return }
        let positionMs = Int64(currentTime.seconds * 1000)
        state.positionMs = max(0, positionMs)
        state.isBuffering = player?.currentItem?.isPlaybackLikelyToKeepUp == false

        // End-of-chapter sleep timer: fire once the current chapter's end is within a second.
        if state.sleepAtChapterEnd {
            let idx = state.currentChapterIndex
            let nextStart = book.chapters.indices.contains(idx + 1) ? book.chapters[idx + 1].startMs : book.durationMs
            if nextStart > 0 && state.positionMs >= nextStart - 1_000 {
                player?.pause()
                state.isPlaying = false
                state.sleepAtChapterEnd = false
            }
        }

        if state.positionMs - lastPushedPositionMs >= progressPushIntervalMs || lastPushedPositionMs < 0 {
            lastPushedPositionMs = state.positionMs
            progressSync.notePosition(
                serverId: book.serverId,
                bookId: book.bookId,
                title: book.title,
                author: book.author,
                coverUrl: book.coverUrl,
                digestUrl: book.digestUrl,
                positionMs: state.positionMs,
                durationMs: state.durationMs
            )
        }

        updateNowPlayingInfo()
    }

    // MARK: route change (Bluetooth/CarPlay follow — issue #114)

    @objc private func handleRouteChange(_ notification: Notification) {
        // iOS already adopts a newly connected Bluetooth/CarPlay output automatically for a
        // `.playback`-category session — there is no app-level "preferred output device" API
        // on iOS the way Android's AudioDeviceCallback/setPreferredAudioDevice pair provides,
        // so there's nothing to do for the "switch onto it" half of Android's behavior. The
        // real, well-established iOS-side half of "follow the output device" is the reverse:
        // pause rather than let audio suddenly continue through the built-in speaker when a
        // Bluetooth/CarPlay/headphone route disconnects.
        guard
            let info = notification.userInfo,
            let reasonValue = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
            let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue),
            reason == .oldDeviceUnavailable
        else { return }
        player?.pause()
        state.isPlaying = false
    }

    // MARK: Now Playing / remote commands

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            guard let self, !self.state.isPlaying else { return .commandFailed }
            self.playPause()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            guard let self, self.state.isPlaying else { return .commandFailed }
            self.playPause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.playPause()
            return .success
        }
        // An audiobook is one long track: only rewind/fast-forward are exposed on the lock
        // screen/Control Center, same reasoning as Android's PlaybackService explicitly
        // hiding COMMAND_SEEK_TO_PREVIOUS/NEXT in favor of these two slots. Chapter nav stays
        // in-app-only, matching Android too (never exposed to its media notification either).
        center.skipForwardCommand.preferredIntervals = [30]
        center.skipForwardCommand.addTarget { [weak self] _ in
            self?.skipForward()
            return .success
        }
        center.skipBackwardCommand.preferredIntervals = [15]
        center.skipBackwardCommand.addTarget { [weak self] _ in
            self?.skipBack()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seek(toMs: Int64(event.positionTime * 1000))
            return .success
        }
        center.nextTrackCommand.isEnabled = false
        center.previousTrackCommand.isEnabled = false
    }

    private func updateNowPlayingInfo() {
        guard let book = state.book else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: state.currentChapterTitle ?? book.title,
            MPMediaItemPropertyArtist: book.author ?? "",
            MPMediaItemPropertyAlbumTitle: book.title,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(state.positionMs) / 1000.0,
            MPMediaItemPropertyPlaybackDuration: Double(state.durationMs) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: state.isPlaying ? Double(state.speed) : 0.0,
        ]
        if let coverImage {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: coverImage.size) { _ in coverImage }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func loadCoverIfNeeded(_ coverUrl: String?, authHeader: String?) {
        coverImage = nil
        guard let coverUrl, let url = URL(string: coverUrl) else { return }
        var request = URLRequest(url: url)
        if let authHeader { request.setValue(authHeader, forHTTPHeaderField: "Authorization") }
        URLSession.shared.dataTask(with: request) { [weak self] data, _, _ in
            guard let self, let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                self.coverImage = image
                if self.state.book != nil { self.updateNowPlayingInfo() }
            }
        }.resume()
    }

    private func cmTime(_ ms: Int64) -> CMTime {
        CMTime(seconds: Double(ms) / 1000.0, preferredTimescale: 1_000)
    }
}
