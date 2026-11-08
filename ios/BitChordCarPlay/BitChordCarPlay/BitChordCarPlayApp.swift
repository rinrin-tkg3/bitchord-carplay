import SwiftUI
import CarPlay

@main
struct BitChordCarPlayApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            NowPlayingView()
        }
    }
}

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if connectingSceneSession.role == .carTemplateApplication {
            let scene = UISceneConfiguration(name: "CarPlay", sessionRole: connectingSceneSession.role)
            scene.delegateClass = CarPlaySceneDelegate.self
            return scene
        } else {
            let scene = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
            return scene
        }
    }
}
