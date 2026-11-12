import Foundation
import AVFoundation
import MediaPlayer
import Combine

public class PlaybackService: NSObject, ObservableObject {
    public static let shared = PlaybackService()

    @Published public var state: BitChordState?
    @Published public var isConnected: Bool = false
    @Published public var isDiscovering: Bool = false
    @Published public var discoveryStatusText: String = ""
    @Published public var currentPositionSec: Double = 0.0
    @Published public var durationSec: Double = 0.0
    @Published public var isLocalPlaying: Bool = false
    @Published public var queue: [QueueSongItem] = []

    // User Intent & Optimistic Transport Lock (prevents ping-pong oscillation)
    @Published public var userWantsPlay: Bool = false
    @Published public var lastUserActionTime: Date = Date.distantPast
    private var lastSkipActionTime: Date = Date.distantPast

    // CarPlay connection state and simulation mode
    @Published public var isCharging: Bool = false
    @Published public var isCarPlayConnected: Bool = false {
        didSet {
            checkCarPlayPlaybackEnforcement()
        }
    }
    @Published public var simulateCarPlayConnected: Bool {
        didSet {
            UserDefaults.standard.set(simulateCarPlayConnected, forKey: "simulate_carplay_connected")
            checkCarPlayPlaybackEnforcement()
        }
    }
    public var isEffectivelyCarPlayConnected: Bool {
        return isCarPlayConnected || simulateCarPlayConnected
    }

    @Published public var playbackMode: AudioPlaybackMode {
        didSet {
            UserDefaults.standard.set(playbackMode.rawValue, forKey: "bitchord_playback_mode")
            // Mode changed: reload track under new mode if playing
            if let mediaId = currentLoadedMediaId {
                loadAndPlayTrack(videoId: mediaId, startPositionMs: Int64(currentPositionSec * 1000))
            }
        }
    }
    @Published public var serverHost: String {
        didSet {
            UserDefaults.standard.set(serverHost, forKey: "bitchord_server_host")
        }
    }
    @Published public var serverPort: Int {
        didSet {
            UserDefaults.standard.set(serverPort, forKey: "bitchord_server_port")
        }
    }

    private var player: AVPlayer?
    private var timeObserverToken: Any?
    private var pollTimer: Timer?
    private var reportTimer: Timer?
    private var currentLoadedMediaId: String?
    private var lastAppliedSeekSeq: Int64 = 0
    private var isSeekingLocally: Bool = false
    private var disconnectCount: Int = 0
    private var cachedArtworkUrl: String? = nil
    private var cachedArtwork: MPMediaItemArtwork? = nil
    private var isDownloadingArtwork: Bool = false

    // Seamless / Gapless next track preloading
    private var preloadedVideoId: String? = nil
    private var preloadedPlayerItem: AVPlayerItem? = nil

    public var baseUrl: String {
        let host = serverHost.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanHost = host.isEmpty ? "10.207.211.165" : host
        return "http://\(cleanHost):\(serverPort)"
    }

    override private init() {
        let savedMode = UserDefaults.standard.string(forKey: "bitchord_playback_mode") ?? ""
        self.playbackMode = AudioPlaybackMode(rawValue: savedMode) ?? .streamFromAndroid
        self.serverHost = UserDefaults.standard.string(forKey: "bitchord_server_host") ?? "10.207.211.165"
        let savedPort = UserDefaults.standard.integer(forKey: "bitchord_server_port")
        self.serverPort = savedPort > 0 ? savedPort : 8765
        self.simulateCarPlayConnected = UserDefaults.standard.bool(forKey: "simulate_carplay_connected")
        super.init()

        setupAudioSession()
        setupRemoteCommands()
        setupCarPlayDetection()
        startPolling()
        // Auto discover on app launch if not connected
        triggerAutoDiscovery()
    }

    // MARK: - CarPlay Connection Detection & Observers
    private func setupCarPlayDetection() {
        // Enable battery monitoring to detect in-car charging state
        UIDevice.current.isBatteryMonitoringEnabled = true
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: UIDevice.batteryStateDidChangeNotification,
            object: nil
        )

        // 1. Audio Route Changes (cable plug/unplug, wireless CarPlay connection)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )

        // 2. Screen Connect / Disconnect (CarPlay external display detection)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: UIScreen.didConnectNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: UIScreen.didDisconnectNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: UIScreen.modeDidChangeNotification,
            object: nil
        )

        // 3. App Lifecycle & Scene Notifications
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: UIScene.willConnectNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: UIScene.didActivateNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSystemStateNotification),
            name: UIScene.didDisconnectNotification,
            object: nil
        )

        updateCarPlayConnectionStatus()
    }

    @objc private func handleSystemStateNotification() {
        updateCarPlayConnectionStatus()
    }

    public func updateCarPlayConnectionStatus() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            // 0. Check Device Battery State: Charging or Full = connected to in-car power!
            UIDevice.current.isBatteryMonitoringEnabled = true
            let bState = UIDevice.current.batteryState
            let isChargingNow = (bState == .charging || bState == .full)
            self.isCharging = isChargingNow

            // 1. Check UIScreen for connected CarPlay display (Active even before audio plays!)
            let hasCarPlayScreen = UIScreen.screens.contains { screen in
                screen.traitCollection.userInterfaceIdiom == .carPlay ||
                (screen != UIScreen.main && screen.mirrored == nil)
            }

            // 2. Check CarPlaySceneDelegate interfaceController presence
            let hasCarPlayInterface = CarPlaySceneDelegate.hasConnectedInterface

            // 3. Check UIApplication connectedScenes for carTemplateApplication session
            let scenes = UIApplication.shared.connectedScenes
            let hasCarPlayScene = scenes.contains { scene in
                scene.session.role == .carTemplateApplication
            }

            // 4. Check AVAudioSession output route for carAudio / usbAudio / CarPlay head unit
            let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
            let hasCarAudioPort = outputs.contains { output in
                output.portType == .carAudio ||
                output.portType == .usbAudio ||
                output.portName.localizedCaseInsensitiveContains("CarPlay") ||
                output.portName.localizedCaseInsensitiveContains("Car Audio") ||
                output.portName.localizedCaseInsensitiveContains("Head Unit")
            }

            // Overall determination: In-car Charging/Power, or any of the 4 CarPlay indicators!
            let isConnected = isChargingNow || hasCarPlayScreen || hasCarPlayInterface || hasCarPlayScene || hasCarAudioPort

            if self.isCarPlayConnected != isConnected {
                print("[PlaybackService] CarPlay connection status changed: \(self.isCarPlayConnected) -> \(isConnected) (charging: \(isChargingNow), screen: \(hasCarPlayScreen), interface: \(hasCarPlayInterface), scene: \(hasCarPlayScene), audio: \(hasCarAudioPort))")
                self.isCarPlayConnected = isConnected
            }
        }
    }
    // MARK: - CarPlay Playback Enforcement
    private func checkCarPlayPlaybackEnforcement() {
        if !isEffectivelyCarPlayConnected {
            if player?.timeControlStatus == .playing || isLocalPlaying {
                player?.pause()
                isLocalPlaying = false
                print("[PlaybackService] CarPlay disconnected -> paused playback")
            }
        }
    }

    // MARK: - Audio Session
    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.allowBluetooth, .allowBluetoothA2DP])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("Failed to set audio session category: \(error)")
        }
    }

    // MARK: - Polling & Synchronization
    public func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.updateCarPlayConnectionStatus()
            self?.fetchState()
        }
        reportTimer?.invalidate()
        reportTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.reportPlaybackToHost()
        }
        fetchState()
        WiFiReconnectService.shared.triggerReconnect()
    }

    public func fetchState() {
        guard let url = URL(string: "\(baseUrl)/api/state?cid=ios_carplay") else { return }
        var request = URLRequest(url: url)
        request.timeoutInterval = 1.5
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            DispatchQueue.main.async {
                if error != nil {
                    self.isConnected = false
                    self.disconnectCount += 1

                    // Attempt aggressive Wi-Fi reconnection every ~4s while disconnected
                    if self.disconnectCount % 8 == 2 {
                        WiFiReconnectService.shared.triggerReconnect()
                    }
                    // After 4 missed polls (~2s), start network auto-discovery
                    if self.disconnectCount >= 4 && !self.isDiscovering {
                        self.triggerAutoDiscovery()
                    }
                    return
                }
                guard let data = data,
                      let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                    self.isConnected = false
                    return
                }
                self.isConnected = true
                self.disconnectCount = 0
                do {
                    let decoder = JSONDecoder()
                    let newState = try decoder.decode(BitChordState.self, from: data)
                    self.applyState(newState)
                } catch {
                    print("Decode error: \(error)")
                }
            }
        }.resume()
    }

    private func applyState(_ newState: BitChordState) {
        let prev = self.state
        self.state = newState
        if let q = newState.queue {
            self.queue = q
        }

        let isRoutedToIphone = newState.activeOnIphone == true

        // User intent grace period: If user recently tapped play/pause (< 1.5s ago),
        // prioritize user's immediate intent over network poll to prevent ping-pong oscillation
        let inGracePeriod = Date().timeIntervalSince(self.lastUserActionTime) < 1.5
        let effectiveWantsPlay: Bool
        if inGracePeriod {
            effectiveWantsPlay = self.userWantsPlay
        } else {
            // Android MediaSession's playWhenReady is the authoritative intent
            if let pwr = newState.playWhenReady {
                effectiveWantsPlay = pwr
            } else {
                effectiveWantsPlay = newState.isPlaying ?? false
            }
        }

        // Track changed or routing switched to iPhone while player not ready
        let routingSwitchedToIphone = (prev?.activeOnIphone != true && isRoutedToIphone)
        let trackChanged = (prev?.mediaId != newState.mediaId)
        let needsLoad = trackChanged || (isRoutedToIphone && (player == nil || player?.currentItem == nil || currentLoadedMediaId != newState.mediaId))
        if needsLoad, let newId = newState.mediaId {
            if isRoutedToIphone {
                loadAndPlayTrack(videoId: newId, startPositionMs: newState.positionMs)
            } else {
                self.currentLoadedMediaId = newId
            }
        }

        // Remote Seek occurred from Android host
        if let seekSeq = newState.seekSeq, seekSeq > self.lastAppliedSeekSeq {
            let wasRecentUserSeek = Date().timeIntervalSince(self.lastUserActionTime) < 1.5
            self.lastAppliedSeekSeq = seekSeq
            if !wasRecentUserSeek {
                let targetMs = newState.wantedSeekMs ?? newState.positionMs
                if isRoutedToIphone {
                    seekTo(positionMs: targetMs, notifyHost: false)
                    reportPlaybackToHost()
                } else {
                    self.currentPositionSec = Double(max(0, targetMs)) / 1000.0
                }
            }
        }

        // Strict Mutual Exclusion for Sound Output:
        // Sound is emitted on iPhone ONLY IF:
        // 1. Android host has routed audio to iPhone (activeOnIphone == true)
        // 2. AND effectiveWantsPlay == true
        // 3. AND device is effectively connected to CarPlay (or debug simulation)
        if isRoutedToIphone && effectiveWantsPlay && isEffectivelyCarPlayConnected {
            if player?.timeControlStatus != .playing {
                player?.play()
                isLocalPlaying = true
            }
        } else {
            // Under ALL other conditions (e.g. Android output active, paused, or CarPlay disconnected),
            // iPhone player MUST be paused immediately to guarantee no dual audio occurs!
            if player?.timeControlStatus == .playing || isLocalPlaying {
                player?.pause()
                isLocalPlaying = false
            }
        }

        // When Android speaker is playing, synchronize UI progress from host state
        if !isRoutedToIphone && !self.isSeekingLocally {
            self.currentPositionSec = Double(max(0, newState.positionMs)) / 1000.0
            if newState.durationMs > 0 {
                self.durationSec = Double(newState.durationMs) / 1000.0
            }
        } else if isRoutedToIphone && !self.isSeekingLocally {
            if newState.durationMs > 0 {
                let hostDurSec = Double(newState.durationMs) / 1000.0
                if self.durationSec <= 0 || abs(self.durationSec - 2.0 * hostDurSec) < 3.0 {
                    self.durationSec = hostDurSec
                }
            }
        }

        updateNowPlayingInfo()
    }

    private func getNextTrackVideoId() -> String? {
        guard !queue.isEmpty else { return nil }
        let q = queue
        let currentIdx = state?.currentIndex ?? 0
        if let next = q.first(where: { $0.index == currentIdx + 1 }) {
            return next.videoId
        }
        if let currentLoaded = currentLoadedMediaId,
           let pos = q.firstIndex(where: { $0.videoId == currentLoaded }),
           pos + 1 < q.count {
            return q[pos + 1].videoId
        }
        return nil
    }

    private func preloadNextTrack(videoId: String) {
        guard preloadedVideoId != videoId else { return }
        preloadedVideoId = videoId
        let streamUrlStr = "\(baseUrl)/api/audio/\(videoId)?cid=ios_carplay"
        guard let url = URL(string: streamUrlStr) else { return }
        let asset = AVURLAsset(url: url)
        let item = AVPlayerItem(asset: asset)
        asset.loadValuesAsynchronously(forKeys: ["playable", "duration"]) { [weak self] in
            DispatchQueue.main.async {
                if self?.preloadedVideoId == videoId {
                    self?.preloadedPlayerItem = item
                }
            }
        }
    }

    // MARK: - Track Loading (Mode 1: Stream from Android vs Mode 2: Direct from iPhone)
    private func loadAndPlayTrack(videoId: String, startPositionMs: Int64) {
        self.currentLoadedMediaId = videoId
        let seekSec = Double(max(0, startPositionMs)) / 1000.0

        if startPositionMs <= 0, preloadedVideoId == videoId, let item = preloadedPlayerItem {
            preloadedVideoId = nil
            preloadedPlayerItem = nil
            setupPlayerWithItem(item, startPositionSec: 0)
            if let nextId = getNextTrackVideoId() {
                preloadNextTrack(videoId: nextId)
            }
            return
        }

        switch playbackMode {
        case .streamFromAndroid:
            let streamUrlStr = "\(baseUrl)/api/audio/\(videoId)?cid=ios_carplay"
            guard let streamUrl = URL(string: streamUrlStr) else { return }
            setupPlayerWithUrl(streamUrl, startPositionSec: seekSec)

        case .directFromPhone:
            resolveAndPlayDirectStream(videoId: videoId, startPositionSec: seekSec)
        }

        if let nextId = getNextTrackVideoId() {
            preloadNextTrack(videoId: nextId)
        }
    }

    private func resolveAndPlayDirectStream(videoId: String, startPositionSec: Double) {
        guard let url = URL(string: "\(baseUrl)/api/direct-stream/\(videoId)") else {
            let fallbackUrlStr = "\(baseUrl)/api/audio/\(videoId)?cid=ios_carplay"
            if let fallbackUrl = URL(string: fallbackUrlStr) {
                setupPlayerWithUrl(fallbackUrl, startPositionSec: startPositionSec)
            }
            return
        }

        URLSession.shared.dataTask(with: url) { [weak self] data, _, error in
            guard let self = self else { return }
            guard let data = data, error == nil else {
                DispatchQueue.main.async {
                    let fallbackUrlStr = "\(self.baseUrl)/api/audio/\(videoId)?cid=ios_carplay"
                    if let fallbackUrl = URL(string: fallbackUrlStr) {
                        self.setupPlayerWithUrl(fallbackUrl, startPositionSec: startPositionSec)
                    }
                }
                return
            }

            if let info = try? JSONDecoder().decode(DirectStreamInfo.self, from: data),
               let directUrl = URL(string: info.url) {
                DispatchQueue.main.async {
                    self.setupPlayerWithUrl(directUrl, startPositionSec: startPositionSec)
                }
            } else {
                DispatchQueue.main.async {
                    let fallbackUrlStr = "\(self.baseUrl)/api/audio/\(videoId)?cid=ios_carplay"
                    if let fallbackUrl = URL(string: fallbackUrlStr) {
                        self.setupPlayerWithUrl(fallbackUrl, startPositionSec: startPositionSec)
                    }
                }
            }
        }.resume()
    }

    private func setupPlayerWithItem(_ playerItem: AVPlayerItem, startPositionSec: Double) {
        if let token = timeObserverToken {
            player?.removeTimeObserver(token)
            timeObserverToken = nil
        }

        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(playerItemDidReachEnd), name: .AVPlayerItemDidPlayToEndTime, object: playerItem)

        if player == nil {
            player = AVPlayer(playerItem: playerItem)
        } else {
            player?.replaceCurrentItem(with: playerItem)
        }

        if startPositionSec > 0 {
            let time = CMTime(seconds: startPositionSec, preferredTimescale: 600)
            player?.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
        }

        let isRoutedToIphone = state?.activeOnIphone == true
        let inGracePeriod = Date().timeIntervalSince(self.lastUserActionTime) < 1.5
        let wantsPlay = inGracePeriod ? self.userWantsPlay : ((state?.playWhenReady == true) || (state?.isPlaying == true))

        if isRoutedToIphone && wantsPlay && isEffectivelyCarPlayConnected {
            player?.play()
            isLocalPlaying = true
        } else {
            player?.pause()
            isLocalPlaying = false
        }

        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserverToken = player?.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self = self, !self.isSeekingLocally else { return }
            // Only update position from local player if routed to iPhone
            if self.state?.activeOnIphone == true {
                let sec = CMTimeGetSeconds(time)
                if !sec.isNaN && !sec.isInfinite {
                    self.currentPositionSec = sec
                }
                if let dur = self.player?.currentItem?.duration {
                    let dSec = CMTimeGetSeconds(dur)
                    if !dSec.isNaN && !dSec.isInfinite && dSec > 0 {
                        let hostDurSec = Double(self.state?.durationMs ?? 0) / 1000.0
                        if hostDurSec > 0 && abs(dSec - 2.0 * hostDurSec) < 3.0 {
                            // CoreMedia doubled the fragmented MP4 duration! Discard doubled value.
                            self.durationSec = hostDurSec
                        } else if hostDurSec > 0 {
                            self.durationSec = hostDurSec
                        } else {
                            self.durationSec = dSec
                        }
                    }
                }
                // Preload next track when within 25 seconds of song end
                if self.preloadedPlayerItem == nil, self.durationSec > 30, self.currentPositionSec >= self.durationSec - 25 {
                    if let nextId = self.getNextTrackVideoId() {
                        self.preloadNextTrack(videoId: nextId)
                    }
                }
            }
        }

        // Immediate playback report after track setup to unlock Android loading spinner
        reportPlaybackToHost()
    }

    private func setupPlayerWithUrl(_ url: URL, startPositionSec: Double) {
        let asset = AVURLAsset(url: url)
        let playerItem = AVPlayerItem(asset: asset)
        setupPlayerWithItem(playerItem, startPositionSec: startPositionSec)
    }

    @objc private func playerItemDidReachEnd() {
        if state?.activeOnIphone == true {
            reportPlaybackToHost(ended: true)
        }
        nextTrack()
    }

    // MARK: - Auto-Discovery of Galaxy BitChord Host (mDNS & Multi-Subnet Probing)
    public func triggerAutoDiscovery() {
        guard !isDiscovering else { return }
        isDiscovering = true
        discoveryStatusText = "Galaxy探索中..."

        var candidates: [String] = []
        if !serverHost.isEmpty {
            candidates.append(serverHost)
            let parts = serverHost.split(separator: ".")
            if parts.count == 4 {
                let prefix = "\(parts[0]).\(parts[1]).\(parts[2])"
                candidates.append("\(prefix).1")
                candidates.append("\(prefix).194")
                candidates.append("\(prefix).2")
                candidates.append("\(prefix).100")
            }
        }
        // mDNS (bitchord.local) & User's known IPs
        candidates.append("bitchord.local")
        candidates.append("10.117.79.194")
        candidates.append("10.117.79.1")
        candidates.append("192.168.43.1")
        candidates.append("10.207.211.165")
        candidates.append("10.207.211.1")
        candidates.append("192.168.49.1")
        candidates.append("172.20.10.1")
        candidates.append("172.20.10.2")
        candidates.append("192.168.1.1")
        candidates.append("192.168.11.1")
        candidates.append("192.168.11.15")

        let uniqueCandidates = Array(NSOrderedSet(array: candidates)) as! [String]

        // Scan candidate ports (8765, 8766, 8767)
        var candidatePorts = [8765, 8766, 8767]
        if !candidatePorts.contains(serverPort) {
            candidatePorts.insert(serverPort, at: 0)
        }

        let group = DispatchGroup()
        var foundIp: String? = nil
        var foundPort: Int? = nil

        for ip in uniqueCandidates {
            for port in candidatePorts {
                guard let url = URL(string: "http://\(ip):\(port)/api/state?cid=autodiscover") else { continue }
                var req = URLRequest(url: url)
                req.timeoutInterval = 1.2
                req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData

                group.enter()
                URLSession.shared.dataTask(with: req) { data, response, error in
                    defer { group.leave() }
                    if let http = response as? HTTPURLResponse, http.statusCode == 200, data != nil {
                        if foundIp == nil {
                            foundIp = ip
                            foundPort = port
                        }
                    }
                }.resume()
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self = self else { return }
            self.isDiscovering = false
            if let ip = foundIp, let port = foundPort {
                self.serverHost = ip
                self.serverPort = port
                self.isConnected = true
                self.discoveryStatusText = "Galaxy発見 (\(ip):\(port))"
                self.fetchState()
                print("[PlaybackService] Auto-discovery succeeded: \(ip):\(port)")
            } else {
                self.discoveryStatusText = "Galaxy未検出"
            }
        }
    }

    // MARK: - User Playback Controls
    public func togglePlayPause() {
        guard isEffectivelyCarPlayConnected else {
            print("[PlaybackService] Playback blocked: CarPlay is not connected")
            return
        }

        let isRouted = state?.activeOnIphone == true
        let currentlyPlaying: Bool
        if isRouted {
            currentlyPlaying = isLocalPlaying
        } else {
            currentlyPlaying = (state?.playWhenReady == true) || (state?.isPlaying == true)
        }
        let willPlay = !currentlyPlaying

        // Record immediate user intent to lock out conflicting network responses
        self.userWantsPlay = willPlay
        self.lastUserActionTime = Date()

        if isRouted {
            if willPlay {
                player?.play()
                isLocalPlaying = true
                sendAction("play", params: ["positionMs": Int64(currentPositionSec * 1000)])
            } else {
                player?.pause()
                isLocalPlaying = false
                sendAction("pause", params: ["positionMs": Int64(currentPositionSec * 1000)])
            }
            // Immediately report to host to flush state without waiting for 0.5s timer
            reportPlaybackToHost()
        } else {
            // Android is playing sound locally: directly send play or pause action to Android host
            sendAction(willPlay ? "play" : "pause")
        }
    }

    public func nextTrack() {
        let now = Date()
        guard now.timeIntervalSince(lastSkipActionTime) >= 0.35 else { return }
        lastSkipActionTime = now
        self.lastUserActionTime = now
        sendAction("next")
    }

    public func previousTrack() {
        let now = Date()
        guard now.timeIntervalSince(lastSkipActionTime) >= 0.35 else { return }
        lastSkipActionTime = now
        self.lastUserActionTime = now
        sendAction("prev")
    }

    public func seekTo(positionMs: Int64, notifyHost: Bool = true) {
        let sec = Double(positionMs) / 1000.0
        self.currentPositionSec = sec
        self.isSeekingLocally = true
        let time = CMTime(seconds: sec, preferredTimescale: 600)
        if let p = player, p.currentItem != nil {
            p.seek(to: time) { [weak self] _ in
                self?.isSeekingLocally = false
            }
        } else {
            self.isSeekingLocally = false
        }
        if notifyHost {
            self.lastUserActionTime = Date()
            sendAction("seek", params: ["positionMs": positionMs])
            reportPlaybackToHost()
        }
    }

    public func playQueueIndex(_ index: Int) {
        self.lastUserActionTime = Date()
        sendAction("playQueueIndex", params: ["index": index])
    }

    // MARK: - API Action to Android Host (POST /api/action)
    public func sendAction(_ type: String, params: [String: Any]? = nil) {
        guard let url = URL(string: "\(baseUrl)/api/action") else { return }
        var payload: [String: Any] = [
            "type": type,
            "cid": "ios_carplay"
        ]
        if let p = params {
            for (k, v) in p {
                payload[k] = v
            }
        }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req).resume()
    }

    public func reportPlaybackToHost(ended: Bool = false) {
        // Only report clock if iPhone is the active routed audio speaker!
        guard state?.activeOnIphone == true else { return }
        guard let mediaId = currentLoadedMediaId ?? state?.mediaId,
              let url = URL(string: "\(baseUrl)/api/report?cid=ios_carplay") else { return }
        let inGracePeriod = Date().timeIntervalSince(self.lastUserActionTime) < 1.5
        let isPlayingNow: Bool
        if ended {
            isPlayingNow = false
        } else if inGracePeriod {
            isPlayingNow = self.userWantsPlay
        } else {
            isPlayingNow = (player?.timeControlStatus == .playing || (player?.rate ?? 0) > 0)
        }
        let isBuffering = player?.currentItem?.isPlaybackLikelyToKeepUp == false && isPlayingNow
        let payload: [String: Any] = [
            "mediaId": mediaId,
            "playing": isPlayingNow,
            "buffering": isBuffering,
            "positionMs": Int64(currentPositionSec * 1000),
            "durationMs": Int64(durationSec * 1000),
            "ended": ended,
            "seekSeq": self.lastAppliedSeekSeq
        ]
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req).resume()
    }

    // MARK: - CarPlay & Lock Screen Remote Commands
    private func setupRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.nextTrack()
            return .success
        }
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.previousTrack()
            return .success
        }
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self = self, let posEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            self.seekTo(positionMs: Int64(posEvent.positionTime * 1000))
            return .success
        }
    }

    private func updateNowPlayingInfo() {
        var info = [String: Any]()
        if let s = state {
            info[MPMediaItemPropertyTitle] = s.title
            info[MPMediaItemPropertyArtist] = s.artist
            if let alb = s.album, !alb.isEmpty {
                info[MPMediaItemPropertyAlbumTitle] = alb
            }
        }
        let isRouted = state?.activeOnIphone == true
        let isPlaying = isRouted ? isLocalPlaying : ((state?.isPlaying == true) || (state?.playWhenReady == true))

        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentPositionSec
        info[MPMediaItemPropertyPlaybackDuration] = durationSec
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0

        let thumbUrlStr = state?.thumbnailUrl

        // If artwork is already cached for this thumbnail URL, use it directly without re-fetching
        if let artwork = cachedArtwork, thumbUrlStr == cachedArtworkUrl {
            info[MPMediaItemPropertyArtwork] = artwork
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            return
        }

        // Keep current artwork attached to info while loading the new one to avoid visual flicker
        if let artwork = cachedArtwork {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info

        guard let thumb = thumbUrlStr, !thumb.isEmpty, thumb != cachedArtworkUrl, !isDownloadingArtwork else {
            return
        }

        guard let url = URL(string: "\(baseUrl)/api/artwork?url=\(thumb.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")") else {
            return
        }

        isDownloadingArtwork = true
        URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let self = self else { return }
            self.isDownloadingArtwork = false
            if let data = data, let image = UIImage(data: data) {
                let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                DispatchQueue.main.async {
                    self.cachedArtworkUrl = thumb
                    self.cachedArtwork = artwork
                    var updated = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                    updated[MPMediaItemPropertyArtwork] = artwork
                    MPNowPlayingInfoCenter.default().nowPlayingInfo = updated
                }
            }
        }.resume()
    }
}
