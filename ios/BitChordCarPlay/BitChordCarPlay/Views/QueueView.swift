import SwiftUI

public struct QueueView: View {
    @ObservedObject var playback = PlaybackService.shared
    @Environment(\.presentationMode) var presentationMode

    public init() {}

    public var body: some View {
        NavigationView {
            ZStack {
                Color(red: 0.08, green: 0.09, blue: 0.11)
                    .ignoresSafeArea()

                if playback.queue.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "music.note.list")
                            .font(.system(size: 48))
                            .foregroundColor(.gray.opacity(0.5))
                        Text("再生キューはありません")
                            .font(.headline)
                            .foregroundColor(.gray)
                    }
                } else {
                    List {
                        ForEach(playback.queue) { item in
                            let isCurrent = (item.videoId == playback.state?.mediaId)
                            Button(action: {
                                playback.playQueueIndex(item.index)
                                presentationMode.wrappedValue.dismiss()
                            }) {
                                HStack(spacing: 12) {
                                    // Index or Playing Icon
                                    if isCurrent {
                                        Image(systemName: "speaker.wave.3.fill")
                                            .font(.system(size: 14, weight: .bold))
                                            .foregroundColor(.green)
                                            .frame(width: 20)
                                    } else {
                                        Text("\(item.index + 1)")
                                            .font(.system(size: 12, weight: .medium))
                                            .foregroundColor(.gray.opacity(0.6))
                                            .frame(width: 20)
                                    }

                                    // Thumbnail
                                    AsyncArtworkView(url: artworkUrl(for: item.thumbnailUrl))
                                        .frame(width: 44, height: 44)
                                        .cornerRadius(6)

                                    // Title & Artist (2 lines to prevent clipping)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(item.title)
                                            .font(.system(size: 14, weight: isCurrent ? .bold : .medium))
                                            .foregroundColor(isCurrent ? .green : .white)
                                            .lineLimit(2)
                                            .fixedSize(horizontal: false, vertical: true)

                                        Text(item.artist)
                                            .font(.system(size: 12))
                                            .foregroundColor(.gray)
                                            .lineLimit(1)
                                    }

                                    Spacer()
                                }
                                .padding(.vertical, 3)
                            }
                            .listRowBackground(Color.white.opacity(isCurrent ? 0.09 : 0.02))
                        }
                    }
                    .listStyle(PlainListStyle())
                }
            }
            .navigationBarTitle("再生キュー (\(playback.queue.count))", displayMode: .inline)
            .navigationBarItems(trailing: Button("閉じる") {
                presentationMode.wrappedValue.dismiss()
            })
        }
    }

    private func artworkUrl(for rawUrl: String?) -> URL? {
        guard let rawUrl = rawUrl, !rawUrl.isEmpty else { return nil }
        let clean = rawUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? rawUrl
        return URL(string: "\(playback.baseUrl)/api/artwork?url=\(clean)")
    }
}
