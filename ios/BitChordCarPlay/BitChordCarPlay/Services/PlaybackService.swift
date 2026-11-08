import Foundation
import AVFoundation
import MediaPlayer
import Combine

public class PlaybackService: NSObject, ObservableObject {
    public static let shared = PlaybackService()

    @Published public var state: BitChordState?
    @Published public var isConnected: Bool = false
    @Published public var currentPositionSec: Double = 0.0
    @Published public var durationSec: Double = 0.0
    @Published public var isLocalPlaying: Bool = false
    @Published public var queue: [QueueSongItem] = []
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
    private var cancellables = Set<AnyCancellable>()

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
        super.init()

        setupAudioSession()
        setupRemoteCommands()
        startPolling()
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
            self?.fetchState()
        }
        reportTimer?.invalidate()
        reportTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.reportPlaybackToHost()
        }
        fetchState()
    }

    public func fetchState() {
        guard let url = URL(string: "\(baseUrl)/api/state?cid=ios_carplay") else { return }
        var request = URLRequest(url: url)
        request.timeoutInterval = 2.0
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            DispatchQueue.main.async {
                if let error = error {
                    self.isConnected = false
                    return
                }
                guard let data = data,
                      let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                    self.isConnected = false
                    return
                }
                self.isConnected = true
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

        // Track changed
        if prev?.mediaId != newState.mediaId, let newId = newState.mediaId {
            loadAndPlayTrack(videoId: newId, startPositionMs: newState.positionMs)
        }

        // Remote Seek occurred from Android host
        if let seekSeq = newState.seekSeq, seekSeq != self.lastAppliedSeekSeq {
            self.lastAppliedSeekSeq = seekSeq
            let targetMs = newState.wantedSeekMs ?? newState.positionMs
            seekTo(positionMs: targetMs, notifyHost: false)
        }

        // Play / Pause synchronization
        let wantsPlay = (newState.playWhenReady == true) || (newState.isPlaying == true)
        if wantsPlay && player?.timeControlStatus != .playing {
            player?.play()
            isLocalPlaying = true
        } else if !wantsPlay && player?.timeControlStatus == .playing {
            player?.pause()
            isLocalPlaying = false
        }

        updateNowPlayingInfo()
    }

    // MARK: - Track Loading (Mode 1: Stream from Android vs Mode 2: Direct from iPhone)
    private func loadAndPlayTrack(videoId: String, startPositionMs: Int64) {
        self.currentLoadedMediaId = videoId
        let seekSec = Double(max(0, startPositionMs)) / 1000.0

        switch playbackMode {
        case .streamFromAndroid:
            // Mode 1: Stream M4A via Galaxy's embedded server
            let streamUrlStr = "\(baseUrl)/api/audio/\(videoId)?cid=ios_carplay"
            guard let streamUrl = URL(string: streamUrlStr) else { return }
            setupPlayerWithUrl(streamUrl, startPositionSec: seekSec)

        case .directFromPhone:
            // Mode 2: Request the direct stream URL from Android or direct resolver
            resolveAndPlayDirectStream(videoId: videoId, startPositionSec: seekSec)
        }
    }

    private func resolveAndPlayDirectStream(videoId: String, startPositionSec: Double) {
        guard let url = URL(string: "\(baseUrl)/api/direct-stream/\(videoId)") else {
            // Fallback to mode 1 stream
            let fallbackUrlStr = "\(baseUrl)/api/audio/\(videoId)?cid=ios_carplay"
            if let fallbackUrl = URL(string: fallbackUrlStr) {
                setupPlayerWithUrl(fallbackUrl, startPositionSec: startPositionSec)
            }
            return
        }

        URLSession.shared.dataTask(with: url) { [weak self] data, _, error in
            guard let self = self else { return }
            guard let data = data, error == nil else {
                // Fallback to mode 1 stream on failure
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

    private func setupPlayerWithUrl(_ url: URL, startPositionSec: Double) {
        if let token = timeObserverToken {
            player?.removeTimeObserver(token)
            timeObserverToken = nil
        }

        let asset = AVURLAsset(url: url)
        let playerItem = AVPlayerItem(asset: asset)
        
        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(playerItemDidReachEnd), name: .AVPlayerItemDidPlayToEndTime, object: playerItem)

        let newPlayer = AVPlayer(playerItem: playerItem)
        newPlayer.automaticallyWaitsToMinimizeStalling = false
        self.player = newPlayer

        if startPositionSec > 1.0 {
            let targetTime = CMTime(seconds: startPositionSec, preferredTimescale: 600)
            newPlayer.seek(to: targetTime, toleranceBefore: .zero, toleranceAfter: .zero)
        }

        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserverToken = newPlayer.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self = self, !self.isSeekingLocally else { return }
            self.currentPositionSec = time.seconds
            if let dur = self.state?.durationMs, dur > 0 {
                self.durationSec = Double(dur) / 1000.0
            } else if let itemDur = self.player?.currentItem?.duration.seconds, itemDur > 0, !itemDur.isNaN {
                self.durationSec = itemDur
            }
            self.isLocalPlaying = (self.player?.timeControlStatus == .playing)
            self.updateNowPlayingInfo()
        }

        newPlayer.play()
        isLocalPlaying = true
        updateNowPlayingInfo()
    }

    @objc private func playerItemDidReachEnd() {
        reportPlaybackToHost(ended: true)
        sendAction(["type": "next"])
    }

    // MARK: - Actions
    public func togglePlayPause() {
        if player?.timeControlStatus == .playing {
            player?.pause()
            isLocalPlaying = false
            sendAction(["type": "pause", "positionMs": Int64(currentPositionSec * 1000)])
        } else {
            player?.play()
            isLocalPlaying = true
            sendAction(["type": "play", "positionMs": Int64(currentPositionSec * 1000)])
        }
        updateNowPlayingInfo()
    }

    public func nextTrack() {
        sendAction(["type": "next"])
    }

    public func previousTrack() {
        sendAction(["type": "prev"])
    }

    public func seekTo(positionMs: Int64, notifyHost: Bool = true) {
        let sec = Double(max(0, positionMs)) / 1000.0
        currentPositionSec = sec
        let time = CMTime(seconds: sec, preferredTimescale: 600)
        player?.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
        if notifyHost {
            sendAction(["type": "seek", "positionMs": positionMs])
        }
        updateNowPlayingInfo()
    }

    public func playQueueIndex(_ index: Int) {
        sendAction(["type": "playQueueIndex", "index": index])
    }

    public func sendAction(_ payload: [String: Any]) {
        guard let url = URL(string: "\(baseUrl)/api/action?cid=ios_carplay") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req) { [weak self] _, _, _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                self?.fetchState()
            }
        }.resume()
    }

    private func reportPlaybackToHost(ended: Bool = false) {
        guard let mediaId = currentLoadedMediaId,
              let url = URL(string: "\(baseUrl)/api/report?cid=ios_carplay") else { return }
        let payload: [String: Any] = [
            "mediaId": mediaId,
            "playing": isLocalPlaying && !ended,
            "buffering": player?.currentItem?.isPlaybackLikelyToKeepUp == false && isLocalPlaying,
            "positionMs": Int64(currentPositionSec * 1000),
            "durationMs": Int64(durationSec * 1000),
            "ended": ended
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
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentPositionSec
        info[MPMediaItemPropertyPlaybackDuration] = durationSec
        info[MPNowPlayingInfoPropertyPlaybackRate] = isLocalPlaying ? 1.0 : 0.0

        // Async Artwork loader for lock screen and CarPlay
        if let thumbUrlStr = state?.thumbnailUrl, let url = URL(string: "\(baseUrl)/api/artwork?url=\(thumbUrlStr.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")") {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            URLSession.shared.dataTask(with: url) { data, _, _ in
                if let data = data, let image = UIImage(data: data) {
                    var updated = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                    let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    updated[MPMediaItemPropertyArtwork] = artwork
                    DispatchQueue.main.async {
                        MPNowPlayingInfoCenter.default().nowPlayingInfo = updated
                    }
                }
            }.resume()
        } else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }
}
