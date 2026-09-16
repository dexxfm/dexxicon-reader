import CarPlay

/// CarPlay's own scene delegate (issue #119) — a first, deliberately minimal scene: just
/// `CPNowPlayingTemplate.shared` as the root template, which auto-populates itself from the
/// exact same `MPNowPlayingInfoCenter`/`MPRemoteCommandCenter` state
/// `AudiobookPlaybackController.swift` already publishes for the lock screen/Control Center
/// (issue #114) — no separate data layer needed, and no custom Now Playing buttons added here
/// either: this app's lock-screen surface deliberately exposes only play/pause and
/// skip-forward-30/skip-back-15 (chapter nav stays in-app-only — see
/// `AudiobookPlaybackController.configureRemoteCommands()`'s own comment), and
/// `CPNowPlayingTemplate` inherits that same restraint for free since it reads the identical
/// `MPRemoteCommandCenter` registrations.
///
/// No library-browsing template — scope stayed at "now playing" per issue #119's own title.
/// A `CPListTemplate`-backed browse tree sourced from the catalog (mirroring Android Auto's
/// `MediaLibraryService` browse tree, issue #118) would be a separate, larger follow-up issue.
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        interfaceController.setRootTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        self.interfaceController = nil
    }
}
