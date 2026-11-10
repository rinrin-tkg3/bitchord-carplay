import SwiftUI

public struct NowPlayingView: View {
    @ObservedObject var playback = PlaybackService.shared
    @ObservedObject var wifiService = WiFiReconnectService.shared
    @State private var showingQueue = false
    @State private var showingSettings = false
    @State private var sliderValue: Double = 0.0
    @State private var isDraggingSlider = false

    public init() {}

    private var isCurrentlyPlaying: Bool {
        if Date().timeIntervalSince(playback.lastUserActionTime) < 1.5 {
            return playback.userWantsPlay
        }
        let isRouted = playback.state?.activeOnIphone == true
        if isRouted {
            return playback.isLocalPlaying
        } else {
            return (playback.state?.playWhenReady == true) || (playback.state?.isPlaying == true)
        }
    }

    public var body: some View {
        NavigationView {
            GeometryReader { geometry in
                let screenHeight = geometry.size.height
                let screenWidth = geometry.size.width
                // Dynamic compact scaling for iPhone 7 (height ~667) and smaller devices
                let isCompact = screenHeight < 720
                let artworkSize = isCompact ? min(screenWidth * 0.52, 205.0) : min(screenWidth * 0.65, 260.0)
                let headerTopPad = isCompact ? 10.0 : 20.0
                let vSpacing = isCompact ? 10.0 : 16.0
                let bottomPad = isCompact ? 14.0 : 24.0

                ZStack {
                    // Background dark theme
                    Color(red: 0.06, green: 0.07, blue: 0.09)
                        .ignoresSafeArea()

                    VStack(spacing: vSpacing) {
                        // Header Bar (Status & Settings, perfectly positioned for iPhone 7 status bar)
                        HStack {
                            // Galaxy Server status pill (Tappable to reconnect Wi-Fi if disconnected)
                            Button(action: {
                                if !playback.isConnected {
                                    wifiService.triggerReconnect(force: true)
                                    playback.triggerAutoDiscovery()
                                }
                            }) {
                                HStack(spacing: 6) {
                                    Circle()
                                        .fill(playback.isConnected ? Color.green : (playback.isDiscovering || wifiService.isReconnecting ? Color.orange : Color.red))
                                        .frame(width: 7, height: 7)
                                    Text(playback.isConnected ? "Galaxy接続中" : (wifiService.isReconnecting ? "Wi-Fi再接続中..." : (playback.isDiscovering ? "Galaxy探索中..." : "未接続 (再接続)")))
                                        .font(.system(size: isCompact ? 11 : 13, weight: .medium))
                                        .foregroundColor(.white.opacity(0.85))
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(Color.white.opacity(0.08))
                                .cornerRadius(12)
                            }

                            Spacer()

                            // Settings Button
                            Button(action: { showingSettings = true }) {
                                Image(systemName: "gearshape.fill")
                                    .font(.system(size: isCompact ? 15 : 18, weight: .semibold))
                                    .foregroundColor(.white)
                                    .frame(width: isCompact ? 32 : 38, height: isCompact ? 32 : 38)
                                    .background(Color.white.opacity(0.08))
                                    .clipShape(Circle())
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, headerTopPad)

                        Spacer(minLength: 2)

                        // Artwork View (Auto scaled to never push bottom controls offscreen)
                        AsyncArtworkView(url: artworkUrl)
                            .frame(width: artworkSize, height: artworkSize)
                            .cornerRadius(isCompact ? 16 : 22)
                            .shadow(color: Color.black.opacity(0.45), radius: isCompact ? 12 : 18, x: 0, y: 6)

                        Spacer(minLength: 2)

                        // Metadata (Title, Artist, Album)
                        VStack(spacing: 3) {
                            Text(playback.state?.title.isEmpty == false ? playback.state!.title : "曲が選択されていません")
                                .font(.system(size: isCompact ? 18 : 21, weight: .bold))
                                .foregroundColor(.white)
                                .lineLimit(1)
                                .multilineTextAlignment(.center)

                            HStack(spacing: 6) {
                                Text(playback.state?.artist.isEmpty == false ? playback.state!.artist : "BitChord Host")
                                    .font(.system(size: isCompact ? 13 : 15, weight: .medium))
                                    .foregroundColor(.gray)
                                    .lineLimit(1)

                                if playback.state?.isAutoDownloaded == true {
                                    HStack(spacing: 2) {
                                        Image(systemName: "bolt.fill")
                                            .font(.system(size: 8, weight: .bold))
                                        Text("Auto")
                                            .font(.system(size: 9, weight: .bold))
                                    }
                                    .foregroundColor(.green)
                                    .padding(.horizontal, 5)
                                    .padding(.vertical, 2)
                                    .background(Color.green.opacity(0.18))
                                    .cornerRadius(4)
                                }
                            }

                            if let album = playback.state?.album, !album.isEmpty {
                                Text(album)
                                    .font(.system(size: isCompact ? 11 : 12, weight: .regular))
                                    .foregroundColor(.gray.opacity(0.7))
                                    .lineLimit(1)
                            }
                        }
                        .padding(.horizontal, 24)

                        // Scrubber Bar
                        VStack(spacing: 4) {
                            Slider(
                                value: Binding(
                                    get: { isDraggingSlider ? sliderValue : playback.currentPositionSec },
                                    set: { newValue in
                                        isDraggingSlider = true
                                        sliderValue = newValue
                                    }
                                ),
                                in: 0...max(1.0, playback.durationSec),
                                onEditingChanged: { editing in
                                    if !editing {
                                        playback.seekTo(positionMs: Int64(sliderValue * 1000))
                                        isDraggingSlider = false
                                    }
                                }
                            )
                            .accentColor(.white)

                            HStack {
                                Text(formatTime(isDraggingSlider ? sliderValue : playback.currentPositionSec))
                                    .font(.system(size: 11))
                                    .monospacedDigit()
                                    .foregroundColor(.gray)
                                Spacer()
                                Text(formatTime(playback.durationSec))
                                    .font(.system(size: 11))
                                    .monospacedDigit()
                                    .foregroundColor(.gray)
                                }
                        }
                        .padding(.horizontal, 24)

                        // Transport Controls
                        HStack(spacing: isCompact ? 36 : 44) {
                            Button(action: { playback.previousTrack() }) {
                                Image(systemName: "backward.fill")
                                    .font(.system(size: isCompact ? 22 : 28))
                                    .foregroundColor(.white)
                            }

                            Button(action: { playback.togglePlayPause() }) {
                                Image(systemName: isCurrentlyPlaying ? "pause.circle.fill" : "play.circle.fill")
                                    .font(.system(size: isCompact ? 54 : 64))
                                    .foregroundColor(playback.isEffectivelyCarPlayConnected ? .white : .white.opacity(0.4))
                            }

                            Button(action: { playback.nextTrack() }) {
                                Image(systemName: "forward.fill")
                                    .font(.system(size: isCompact ? 22 : 28))
                                    .foregroundColor(.white)
                            }
                        }
                        .padding(.vertical, 2)

                        // Bottom Row: CarPlay Indicator & Queue Button (Always completely visible!)
                        HStack {
                            // CarPlay status indicator (Charging = In-car connected)
                            HStack(spacing: 5) {
                                Image(systemName: playback.isCharging ? "bolt.fill" : "car.fill")
                                    .font(.system(size: isCompact ? 11 : 13))
                                if playback.isCarPlayConnected {
                                    Text(playback.isCharging ? "CarPlay (充電中)" : "CarPlay接続中")
                                        .font(.system(size: isCompact ? 11 : 12, weight: .semibold))
                                } else if playback.simulateCarPlayConnected {
                                    Text("擬似CarPlay")
                                        .font(.system(size: isCompact ? 11 : 12, weight: .semibold))
                                } else {
                                    Text("CarPlay待機中")
                                        .font(.system(size: isCompact ? 11 : 12, weight: .regular))
                                }
                            }
                            .foregroundColor(playback.isEffectivelyCarPlayConnected ? .green : .gray)
                            .padding(.horizontal, isCompact ? 10 : 12)
                            .padding(.vertical, isCompact ? 5 : 6)
                            .background(Color.white.opacity(0.06))
                            .cornerRadius(12)

                            Spacer()

                            // Queue Button (Standard placement at bottom right)
                            Button(action: { showingQueue = true }) {
                                HStack(spacing: 5) {
                                    Image(systemName: "list.bullet")
                                        .font(.system(size: isCompact ? 14 : 16, weight: .semibold))
                                    Text("キュー")
                                        .font(.system(size: isCompact ? 12 : 13, weight: .medium))
                                    if !playback.queue.isEmpty {
                                        Text("(\(playback.queue.count))")
                                            .font(.system(size: isCompact ? 10 : 11, weight: .bold))
                                            .foregroundColor(.white.opacity(0.7))
                                    }
                                }
                                .foregroundColor(.white)
                                .padding(.horizontal, isCompact ? 12 : 14)
                                .padding(.vertical, isCompact ? 6 : 8)
                                .background(Color.white.opacity(0.09))
                                .cornerRadius(14)
                            }
                        }
                        .padding(.horizontal, 24)
                        .padding(.bottom, bottomPad)
                    }
                }
            }
            .navigationBarHidden(true)
            .sheet(isPresented: $showingQueue) {
                QueueView()
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView()
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    private var artworkUrl: URL? {
        guard let thumb = playback.state?.thumbnailUrl, !thumb.isEmpty else { return nil }
        let clean = thumb.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? thumb
        return URL(string: "\(playback.baseUrl)/api/artwork?url=\(clean)")
    }

    private func formatTime(_ sec: Double) -> String {
        guard !sec.isNaN && !sec.isInfinite && sec >= 0 else { return "0:00" }
        let total = Int(sec)
        let m = total / 60
        let s = total % 60
        return String(format: "%d:%02d", m, s)
    }
}

public struct AsyncArtworkView: View {
    public let url: URL?

    public init(url: URL?) {
        self.url = url
    }

    public var body: some View {
        Group {
            if let url = url {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    case .failure(_), .empty:
                        defaultArtwork
                    @unknown default:
                        defaultArtwork
                    }
                }
            } else {
                defaultArtwork
            }
        }
    }

    private var defaultArtwork: some View {
        ZStack {
            Color.white.opacity(0.08)
            Image(systemName: "music.note")
                .font(.system(size: 48))
                .foregroundColor(.white.opacity(0.35))
        }
    }
}
