import CarPlay
import UIKit

/// Only reason this exists (issue #119): SwiftUI's `App` protocol has no scene-configuration
/// hook of its own, and CarPlay needs one to route a connecting `CPTemplateApplicationScene`
/// to `CarPlaySceneDelegate` instead of the default phone/iPad `UIWindowScene`. Wired into
/// `iOSApp.swift` via `@UIApplicationDelegateAdaptor` — everything else about app startup
/// stays exactly as SwiftUI's own lifecycle already handles it.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if connectingSceneSession.role == .carTemplateApplication {
            let config = UISceneConfiguration(name: "CarPlay", sessionRole: connectingSceneSession.role)
            config.delegateClass = CarPlaySceneDelegate.self
            return config
        }
        return UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    }
}
