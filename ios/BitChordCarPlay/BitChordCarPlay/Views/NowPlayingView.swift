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
            ZStack {
                // Background dark theme
                Color(red: 0.06, green: 0.07, blue: 0.09)
                    .ignoresSafeArea()

                VStack(spacing: 16) {
                    // Header Bar (Status & Settings, with top padding to clear battery / status bar)
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
                                    .frame(width: 8, height: 8)
                                Text(playback.isConnected ? "Galaxy接続中" : (wifiService.isReconnecting ? "Wi-Fi再接続中..." : (playback.isDiscovering ? "Galaxy探索中..." : "未接続 (タップで再接続)")))
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundColor(.white.opacity(0.85))
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Color.white.opacity(0.08))
                            .cornerRadius(14)
                        }

                        Spacer()

                        // Settings Button (moved down to prevent overlapping battery indicator)
                        Button(action: { showingSettings = true }) {
                            Image(systemName: "gearshape.fill")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(width: 38, height: 38)
                                .background(Color.white.opacity(0.08))
                                .clipShape(Circle())
                        }
                    }
                    .padding(.horizontal, 22)
                    .padding(.top, 36) // Generous top padding for iPhone 7 status bar & battery

                    Spacer(minLength: 6)

                    // Artwork View
                    AsyncArtworkView(url: artworkUrl)
                        .frame(width: 260, height: 260)
                        .cornerRadius(22)
                        .shadow(color: Color.black.opacity(0.45), radius: 18, x: 0, y: 8)

                    Spacer(minLength: 6)

                    // Metadata
                    VStack(spacing: 5) {
                        Text(playback.state?.title.isEmpty == false ? playback.state!.title : "曲が選択されていません")
                            .font(.system(size: 21, weight: .bold))
                            .foregroundColor(.white)
                            .lineLimit(1)
                            .multilineTextAlignment(.center)

                        Text(playback.state?.artist.isEmpty == false ? playback.state!.artist : "BitChord Host")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(.gray)
                            .lineLimit(1)

                        if let album = playback.state?.album, !album.isEmpty {
                            Text(album)
                                .font(.system(size: 12, weight: .regular))
                                .foregroundColor(.gray.opacity(0.7))
                                .lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 30)

                    // Scrubber Bar
                    VStack(spacing: 6) {
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
                                .font(.system(size: 12))
                                .monospacedDigit()
                                .foregroundColor(.gray)
                            Spacer()
                            Text(formatTime(playback.durationSec))
                                .font(.system(size: 12))
                                .monospacedDigit()
                                .foregroundColor(.gray)
                        }
                    }
                    .padding(.horizontal, 28)

                    // Transport Controls
                    HStack(spacing: 44) {
                        Button(action: { playback.previousTrack() }) {
                            Image(systemName: "backward.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.white)
                        }

                        Button(action: { playback.togglePlayPause() }) {
                            Image(systemName: isCurrentlyPlaying ? "pause.circle.fill" : "play.circle.fill")
                                .font(.system(size: 64))
                                .foregroundColor(playback.isEffectivelyCarPlayConnected ? .white : .white.opacity(0.4))
                        }

                        Button(action: { playback.nextTrack() }) {
                            Image(systemName: "forward.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.white)
                        }
                    }
                    .padding(.vertical, 4)

                    // Bottom Row: CarPlay Indicator & Queue Button (Natural Music App Placement)
                    HStack {
                        // CarPlay status indicator
                        HStack(spacing: 6) {
                            Image(systemName: "car.fill")
                                .font(.system(size: 13))
                            if playback.isCarPlayConnected {
                                Text("CarPlay接続中")
                                    .font(.system(size: 12, weight: .semibold))
                            } else if playback.simulateCarPlayConnected {
                                Text("擬似CarPlayモード")
                                    .font(.system(size: 12, weight: .semibold))
                            } else {
                                Text("CarPlay接続待ち")
                                    .font(.system(size: 12, weight: .regular))
                            }
                        }
                        .foregroundColor(playback.isEffectivelyCarPlayConnected ? .green : .gray)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.white.opacity(0.06))
                        .cornerRadius(12)

                        Spacer()

                        // Queue Button (Standard placement at bottom right)
                        Button(action: { showingQueue = true }) {
                            HStack(spacing: 6) {
                                Image(systemName: "list.bullet")
                                    .font(.system(size: 16, weight: .semibold))
                                Text("キュー")
                                    .font(.system(size: 13, weight: .medium))
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Color.white.opacity(0.09))
                            .cornerRadius(14)
                        }
                    }
                    .padding(.horizontal, 28)
                    .padding(.bottom, 24)
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
                .font(.system(size: 64))
                .foregroundColor(.white.opacity(0.3))
        }
    }
}
