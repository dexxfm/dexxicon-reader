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
        // native reader screen. `hostVC` is the same view controller Compose renders into —
        // captured so the placeholder below has something to present from. A real Readium
        // Swift Toolkit-backed reader (per format) replaces this placeholder in a follow-up;
        // this proves the Kotlin -> Swift closure hand-off itself carries the right data
        // (resolved acquisition URL + a fresh auth header, already computed on the Kotlin
        // side — see OnOpenReader's doc comment for why nothing crosses back the other way).
        var hostVC: UIViewController!
        hostVC = MainViewControllerKt.MainViewController(onOpenReader: { serverId, bookId, format, url, authHeader in
            let message = "server: \(serverId)\nbook: \(bookId)\nformat: \(format.name)\nurl: \(url)\nauth header: \(authHeader != nil ? "present" : "none")"
            let alert = UIAlertController(
                title: "Open reader (not yet implemented)",
                message: message,
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            hostVC.present(alert, animated: true)
        })
        return hostVC
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
