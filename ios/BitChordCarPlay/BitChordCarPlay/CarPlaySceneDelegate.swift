import Foundation
import CarPlay

public class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    public var interfaceController: CPInterfaceController?

    public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController

        // Enable standard car head-unit Now Playing template
        let nowPlayingTemplate = CPNowPlayingTemplate.shared
        
        // Add Bar Button to open Queue on CarPlay Screen
        let queueButton = CPBarButton(type: .text) { [weak self] _ in
            self?.presentQueueTemplate()
        }
        queueButton.title = "キュー"
        nowPlayingTemplate.trailingNavigationBarButtons = [queueButton]

        interfaceController.setRootTemplate(nowPlayingTemplate, animated: true, completion: nil)
    }

    public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        self.interfaceController = nil
    }

    private func presentQueueTemplate() {
        let items = PlaybackService.shared.queue.map { song -> CPListItem in
            let item = CPListItem(text: song.title, detailText: song.artist)
            item.handler = { _, completionHandler in
                PlaybackService.shared.playQueueIndex(song.index)
                completionHandler()
                self.interfaceController?.popTemplate(animated: true, completion: nil)
            }
            return item
        }

        let section = CPListSection(items: items)
        let listTemplate = CPListTemplate(title: "再生キュー", sections: [section])
        interfaceController?.pushTemplate(listTemplate, animated: true, completion: nil)
    }
}
