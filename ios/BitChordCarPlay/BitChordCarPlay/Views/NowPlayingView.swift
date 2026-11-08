import SwiftUI

public struct NowPlayingView: View {
    @ObservedObject var playback = PlaybackService.shared
    @State private var showingQueue = false
    @State private var showingSettings = false
    @State private var sliderValue: Double = 0.0
    @State private var isDraggingSlider = false

    public init() {}

    public var body: some View {
        NavigationView {
            ZStack {
                // Background dark theme
                Color(red: 0.06, green: 0.07, blue: 0.09)
                    .ignoresSafeArea()

                VStack(spacing: 24) {
                    // Header Status
                    HStack {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(playback.isConnected ? Color.green : Color.red)
                                .frame(width: 8, height: 8)
                            Text(playback.isConnected ? "Galaxy接続中" : "未接続")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(.gray)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Color.white.opacity(0.06))
                        .cornerRadius(12)

                        Spacer()

                        Button(action: { showingQueue = true }) {
                            Image(systemName: "list.bullet")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(width: 40, height: 40)
                                .background(Color.white.opacity(0.08))
                                .clipShape(Circle())
                        }

                        Button(action: { showingSettings = true }) {
                            Image(systemName: "gearshape.fill")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(width: 40, height: 40)
                                .background(Color.white.opacity(0.08))
                                .clipShape(Circle())
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 10)

                    Spacer(minLength: 10)

                    // Artwork View
                    AsyncArtworkView(url: artworkUrl)
                        .frame(width: 280, height: 280)
                        .cornerRadius(24)
                        .shadow(color: Color.black.opacity(0.4), radius: 20, x: 0, y: 10)

                    Spacer(minLength: 10)

                    // Metadata
                    VStack(spacing: 6) {
                        Text(playback.state?.title.isEmpty == false ? playback.state!.title : "曲が選択されていません")
                            .font(.system(size: 22, weight: .bold))
                            .foregroundColor(.white)
                            .lineLimit(1)
                            .multilineTextAlignment(.center)

                        Text(playback.state?.artist.isEmpty == false ? playback.state!.artist : "BitChord Host")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(.gray)
                            .lineLimit(1)

                        if let album = playback.state?.album, !album.isEmpty {
                            Text(album)
                                .font(.system(size: 13, weight: .regular))
                                .foregroundColor(.gray.opacity(0.7))
                                .lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 30)

                    // Scrubber Bar
                    VStack(spacing: 8) {
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
                            Image(systemName: playback.isLocalPlaying ? "pause.circle.fill" : "play.circle.fill")
                                .font(.system(size: 68))
                                .foregroundColor(.white)
                        }

                        Button(action: { playback.nextTrack() }) {
                            Image(systemName: "forward.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.white)
                        }
                    }
                    .padding(.bottom, 30)
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
