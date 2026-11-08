import Foundation
import CarPlay

public class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    public var interfaceController: CPInterfaceController?

    public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController
        PlaybackService.shared.isCarPlayConnected = true
        PlaybackService.shared.triggerAutoDiscovery()

        // Apple standard Now Playing screen on CarPlay display
        let nowPlayingTemplate = CPNowPlayingTemplate.shared
        interfaceController.setRootTemplate(nowPlayingTemplate, animated: true, completion: nil)
    }

    public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        self.interfaceController = nil
        PlaybackService.shared.isCarPlayConnected = false
    }
}
