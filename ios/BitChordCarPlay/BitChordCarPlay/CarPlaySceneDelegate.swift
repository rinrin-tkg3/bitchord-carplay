import Foundation
import CarPlay

public class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    public static private(set) var shared: CarPlaySceneDelegate?
    public static var hasConnectedInterface: Bool {
        return shared?.interfaceController != nil
    }

    public var interfaceController: CPInterfaceController?

    public override init() {
        super.init()
        CarPlaySceneDelegate.shared = self
    }

    public func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        CarPlaySceneDelegate.shared = self
        print("[CarPlaySceneDelegate] scene willConnectTo session: \(session.role.rawValue)")
        PlaybackService.shared.updateCarPlayConnectionStatus()
    }

    public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController
        CarPlaySceneDelegate.shared = self
        print("[CarPlaySceneDelegate] didConnect interfaceController")
        PlaybackService.shared.updateCarPlayConnectionStatus()
        WiFiReconnectService.shared.triggerReconnect()
        PlaybackService.shared.triggerAutoDiscovery()

        // Apple standard Now Playing screen on CarPlay display
        let nowPlayingTemplate = CPNowPlayingTemplate.shared
        interfaceController.setRootTemplate(nowPlayingTemplate, animated: true, completion: nil)
    }

    public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        print("[CarPlaySceneDelegate] didDisconnectInterfaceController")
        self.interfaceController = nil
        PlaybackService.shared.updateCarPlayConnectionStatus()
    }

    public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didDisconnect interfaceController: CPInterfaceController, from window: CPWindow) {
        print("[CarPlaySceneDelegate] didDisconnect from window")
        self.interfaceController = nil
        PlaybackService.shared.updateCarPlayConnectionStatus()
    }

    public func sceneDidDisconnect(_ scene: UIScene) {
        print("[CarPlaySceneDelegate] sceneDidDisconnect")
        self.interfaceController = nil
        PlaybackService.shared.updateCarPlayConnectionStatus()
    }

    public func sceneDidEnterBackground(_ scene: UIScene) {
        print("[CarPlaySceneDelegate] sceneDidEnterBackground")
        PlaybackService.shared.updateCarPlayConnectionStatus()
    }

    public func sceneWillEnterForeground(_ scene: UIScene) {
        print("[CarPlaySceneDelegate] sceneWillEnterForeground")
        PlaybackService.shared.updateCarPlayConnectionStatus()
    }
}
