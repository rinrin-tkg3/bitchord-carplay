import Foundation

public struct BitChordState: Codable {
    public var activeOnIphone: Boolean?
    public var mediaId: String?
    public var title: String
    public var artist: String
    public var album: String?
    public var thumbnailUrl: String?
    public var isPlaying: Boolean?
    public var playWhenReady: Boolean?
    public var buffering: Boolean?
    public var positionMs: Int64
    public var durationMs: Int64
    public var commandSeq: Int64?
    public var seekSeq: Int64?
    public var wantedSeekMs: Int64?
    public var queue: [QueueSongItem]?
    public var currentIndex: Int?
    public var isAutoDownloaded: Boolean?

    enum CodingKeys: String, CodingKey {
        case activeOnIphone
        case mediaId
        case title
        case artist
        case album
        case thumbnailUrl
        case isPlaying
        case playWhenReady
        case buffering
        case positionMs
        case durationMs
        case commandSeq
        case seekSeq
        case wantedSeekMs
        case queue
        case currentIndex
        case isAutoDownloaded
    }
}

public typealias Boolean = Bool

public struct QueueSongItem: Codable, Identifiable {
    public var id: String { "\(index)_\(videoId)" }
    public var index: Int
    public var videoId: String
    public var title: String
    public var artist: String
    public var album: String?
    public var thumbnailUrl: String?
    public var durationMs: Int64
}

public struct DirectStreamInfo: Codable {
    public var videoId: String
    public var url: String
    public var durationMs: Int64
    public var mimeType: String
}

public enum AudioPlaybackMode: String, CaseIterable, Identifiable {
    case streamFromAndroid = "Androidからリアルタイム受信"
    case directFromPhone = "曲情報を基にiPhoneで直接取得"

    public var id: String { rawValue }
    
    public var subtitle: String {
        switch self {
        case .streamFromAndroid:
            return "Galaxyが配信する音声をiPhoneがリアルタイム受信して流します"
        case .directFromPhone:
            return "Galaxyからメタ情報のみを受信し、iPhone自身が直接音楽データを取得して流します"
        }
    }
}
