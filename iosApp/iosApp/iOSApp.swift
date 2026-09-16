import SwiftUI

@main
struct iOSApp: App {
    // issue #119: routes a connecting CarPlay scene to CarPlaySceneDelegate — see
    // AppDelegate's own doc comment for why SwiftUI's App protocol needs this adaptor at all.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
