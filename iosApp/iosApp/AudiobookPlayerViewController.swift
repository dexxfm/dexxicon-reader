import AVKit
import UIKit

/// The audiobook reader screen (issue #114) — a thin UI over `AudiobookPlaybackController`
/// (the process-lifetime engine; see its own doc comment for why the split exists). Matches
/// Android's real `PlayerScreen`/`AudiobookPlayer` feature set: play/pause/seek/skip/chapters/
/// speed/sleep timer, plus (per the user's explicit direction) resume-position sync,
/// smart-rewind, and Bluetooth-output-follow, all of which live in the controller since they
/// need to keep working whether or not this screen is on screen. AirPlay is the one real
/// scope difference from Android's Google-Cast support — an `AVRoutePickerView` button is the
/// entire UI for it; no custom session-swap plumbing is needed at all (see issue #114's own
/// research for why).
///
/// Not yet verified beyond compiling — no Mac available locally; the real test is a triggered
/// `ios-ci` run, and eventual real listening once a device is available.
final class AudiobookPlayerViewController: UIViewController {
    private let serverId: String
    private let bookId: String
    private let authHeader: String?
    private let book: AudiobookPlaybackController.Book

    private let coverView = UIImageView()
    private let titleLabel = UILabel()
    private let authorLabel = UILabel()
    private let chapterLabel = UILabel()
    private let elapsedLabel = UILabel()
    private let remainingLabel = UILabel()
    private let scrubber = UISlider()
    private let playPauseButton = UIButton(type: .system)
    private let speedButton = UIButton(type: .system)
    private let sleepTimerButton = UIButton(type: .system)
    private let routePickerView = AVRoutePickerView()

    /// Scrubbing suppresses the slider's own position updates until the user lets go —
    /// otherwise a live position tick fights the user's own drag.
    private var isScrubbing = false

    init(
        serverId: String,
        bookId: String,
        url: URL,
        authHeader: String?,
        title: String,
        author: String?,
        coverUrl: String?,
        durationMs: Int64,
        chapters: [AudiobookPlaybackController.ChapterInfo]
    ) {
        self.serverId = serverId
        self.bookId = bookId
        self.authHeader = authHeader
        self.book = AudiobookPlaybackController.Book(
            serverId: serverId,
            bookId: bookId,
            title: title,
            author: author,
            coverUrl: coverUrl,
            durationMs: durationMs,
            chapters: chapters,
            digestUrl: url.absoluteString
        )
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        buildLayout()

        titleLabel.text = book.title
        authorLabel.text = book.author
        authorLabel.isHidden = book.author?.isEmpty ?? true
        loadCover()

        let controller = AudiobookPlaybackController.shared
        if !controller.isLoaded(serverId: serverId, bookId: bookId) {
            controller.start(book: book, authHeader: authHeader)
        }
        controller.onUpdate = { [weak self] state in self?.render(state) }
        render(controller.state)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Detaching only stops *this screen* from redrawing — playback, the lock-screen
        // controls, and position saving all keep going in AudiobookPlaybackController, same
        // as dismissing Apple Music's now-playing screen doesn't stop the song.
        AudiobookPlaybackController.shared.onUpdate = nil
    }

    // MARK: layout

    private func buildLayout() {
        coverView.contentMode = .scaleAspectFit
        coverView.layer.cornerRadius = 12
        coverView.clipsToBounds = true
        coverView.backgroundColor = .secondarySystemBackground

        titleLabel.font = .preferredFont(forTextStyle: .title2)
        titleLabel.numberOfLines = 2
        titleLabel.textAlignment = .center

        authorLabel.font = .preferredFont(forTextStyle: .subheadline)
        authorLabel.textColor = .secondaryLabel
        authorLabel.textAlignment = .center

        chapterLabel.font = .preferredFont(forTextStyle: .footnote)
        chapterLabel.textColor = .secondaryLabel
        chapterLabel.textAlignment = .center
        chapterLabel.numberOfLines = 1

        elapsedLabel.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        elapsedLabel.textColor = .secondaryLabel
        remainingLabel.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        remainingLabel.textColor = .secondaryLabel
        remainingLabel.textAlignment = .right

        scrubber.addTarget(self, action: #selector(scrubberTouchDown), for: .touchDown)
        scrubber.addTarget(self, action: #selector(scrubberValueChanged), for: .valueChanged)
        scrubber.addTarget(self, action: #selector(scrubberTouchUp), for: [.touchUpInside, .touchUpOutside])

        let timeRow = UIStackView(arrangedSubviews: [elapsedLabel, remainingLabel])
        timeRow.distribution = .equalSpacing

        let skipBackButton = transportButton(systemName: "gobackward.15", action: #selector(skipBack))
        let skipForwardButton = transportButton(systemName: "goforward.30", action: #selector(skipForward))
        playPauseButton.setPreferredSymbolConfiguration(
            UIImage.SymbolConfiguration(pointSize: 44), forImageIn: .normal
        )
        playPauseButton.addTarget(self, action: #selector(togglePlayPause), for: .touchUpInside)

        let transportRow = UIStackView(arrangedSubviews: [skipBackButton, playPauseButton, skipForwardButton])
        transportRow.axis = .horizontal
        transportRow.alignment = .center
        transportRow.distribution = .equalCentering

        let prevChapterButton = transportButton(systemName: "backward.end", action: #selector(previousChapter))
        let nextChapterButton = transportButton(systemName: "forward.end", action: #selector(nextChapter))
        let chapterRow = UIStackView(arrangedSubviews: [prevChapterButton, chapterLabel, nextChapterButton])
        chapterRow.axis = .horizontal
        chapterRow.alignment = .center
        chapterRow.spacing = 12

        speedButton.addTarget(self, action: #selector(showSpeedMenu), for: .touchUpInside)
        sleepTimerButton.setImage(UIImage(systemName: "moon.zzz"), for: .normal)
        sleepTimerButton.addTarget(self, action: #selector(showSleepTimerMenu), for: .touchUpInside)
        routePickerView.tintColor = .label

        let toolRow = UIStackView(arrangedSubviews: [speedButton, UIView(), sleepTimerButton, routePickerView])
        toolRow.axis = .horizontal
        toolRow.alignment = .center
        toolRow.spacing = 20

        let stack = UIStackView(arrangedSubviews: [
            coverView, titleLabel, authorLabel, chapterRow, scrubber, timeRow, transportRow, toolRow,
        ])
        stack.axis = .vertical
        stack.spacing = 16
        stack.setCustomSpacing(4, after: titleLabel)
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            coverView.widthAnchor.constraint(equalToConstant: 220),
            coverView.heightAnchor.constraint(equalToConstant: 220),
            stack.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            stack.topAnchor.constraint(greaterThanOrEqualTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
        ])
    }

    private func transportButton(systemName: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: systemName), for: .normal)
        button.setPreferredSymbolConfiguration(UIImage.SymbolConfiguration(pointSize: 22), forImageIn: .normal)
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }

    private func loadCover() {
        guard let coverUrl = book.coverUrl, let url = URL(string: coverUrl) else { return }
        var request = URLRequest(url: url)
        if let authHeader { request.setValue(authHeader, forHTTPHeaderField: "Authorization") }
        URLSession.shared.dataTask(with: request) { [weak self] data, _, _ in
            guard let self, let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async { self.coverView.image = image }
        }.resume()
    }

    // MARK: state -> UI

    private func render(_ state: AudiobookPlaybackController.State) {
        playPauseButton.setImage(
            UIImage(systemName: state.isPlaying ? "pause.circle.fill" : "play.circle.fill"), for: .normal
        )
        chapterLabel.text = state.currentChapterTitle
        chapterLabel.isHidden = state.currentChapterTitle == nil
        speedButton.setTitle(speedLabel(state.speed), for: .normal)
        sleepTimerButton.tintColor = (state.sleepTimerEndsAt != nil || state.sleepAtChapterEnd) ? .systemOrange : .label

        if !isScrubbing {
            scrubber.maximumValue = Float(max(state.durationMs, 1))
            scrubber.value = Float(state.positionMs)
        }
        elapsedLabel.text = formatMs(state.positionMs)
        remainingLabel.text = "-" + formatMs(max(0, state.durationMs - state.positionMs))
    }

    /// `String(format: "%.2gx", ...)` looks tempting but is wrong here — `%g`'s precision
    /// counts *significant digits*, not decimal places, so 1.25 would round to "1.2×". This
    /// shows exactly the digits each speed actually needs: whole numbers with none, everything
    /// else with as many decimal places as it has (never more than 2, since every speed this
    /// screen offers only ever needs at most 2).
    private func speedLabel(_ speed: Float) -> String {
        let hundredths = (speed * 100).rounded()
        if hundredths.truncatingRemainder(dividingBy: 100) == 0 {
            return String(format: "%.0fx", hundredths / 100)
        } else if hundredths.truncatingRemainder(dividingBy: 10) == 0 {
            return String(format: "%.1fx", hundredths / 100)
        } else {
            return String(format: "%.2fx", hundredths / 100)
        }
    }

    private func formatMs(_ ms: Int64) -> String {
        let totalSeconds = Int(ms / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%d:%02d", minutes, seconds)
    }

    // MARK: actions

    @objc private func togglePlayPause() { AudiobookPlaybackController.shared.playPause() }
    @objc private func skipForward() { AudiobookPlaybackController.shared.skipForward() }
    @objc private func skipBack() { AudiobookPlaybackController.shared.skipBack() }
    @objc private func nextChapter() { AudiobookPlaybackController.shared.nextChapter() }
    @objc private func previousChapter() { AudiobookPlaybackController.shared.previousChapter() }

    @objc private func scrubberTouchDown() { isScrubbing = true }

    @objc private func scrubberValueChanged() {
        elapsedLabel.text = formatMs(Int64(scrubber.value))
    }

    @objc private func scrubberTouchUp() {
        isScrubbing = false
        AudiobookPlaybackController.shared.seek(toMs: Int64(scrubber.value))
    }

    @objc private func showSpeedMenu() {
        let sheet = UIAlertController(title: "Playback speed", message: nil, preferredStyle: .actionSheet)
        for speed: Float in [0.75, 1.0, 1.25, 1.5, 1.75, 2.0] {
            sheet.addAction(UIAlertAction(title: speedLabel(speed), style: .default) { _ in
                AudiobookPlaybackController.shared.setSpeed(speed)
            })
        }
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        sheet.popoverPresentationController?.sourceView = speedButton
        present(sheet, animated: true)
    }

    @objc private func showSleepTimerMenu() {
        let sheet = UIAlertController(title: "Sleep timer", message: nil, preferredStyle: .actionSheet)
        for minutes in [5, 15, 30, 45, 60] {
            sheet.addAction(UIAlertAction(title: "\(minutes) minutes", style: .default) { _ in
                AudiobookPlaybackController.shared.setSleepTimer(minutes: minutes)
            })
        }
        sheet.addAction(UIAlertAction(title: "End of chapter", style: .default) { _ in
            AudiobookPlaybackController.shared.setSleepTimerEndOfChapter()
        })
        sheet.addAction(UIAlertAction(title: "Off", style: .destructive) { _ in
            AudiobookPlaybackController.shared.clearSleepTimer()
        })
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        sheet.popoverPresentationController?.sourceView = sleepTimerButton
        present(sheet, animated: true)
    }
}

extension AudiobookPlayerViewController {
    /// Wraps the player in its own `UINavigationController` with a "Done" button — same
    /// pattern as `EpubReaderViewController.presentable`/`PdfReaderViewController.presentable`.
    /// Dismissing this does **not** stop playback — see this class's own doc comment.
    static func presentable(
        serverId: String,
        bookId: String,
        url: URL,
        authHeader: String?,
        title: String,
        author: String?,
        coverUrl: String?,
        durationMs: Int64,
        chapters: [AudiobookPlaybackController.ChapterInfo]
    ) -> UIViewController {
        let player = AudiobookPlayerViewController(
            serverId: serverId, bookId: bookId, url: url, authHeader: authHeader,
            title: title, author: author, coverUrl: coverUrl, durationMs: durationMs, chapters: chapters
        )
        player.title = "Now Playing"
        player.navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done,
            target: player,
            action: #selector(AudiobookPlayerViewController.close)
        )
        return UINavigationController(rootViewController: player)
    }

    @objc private func close() {
        dismiss(animated: true)
    }
}
