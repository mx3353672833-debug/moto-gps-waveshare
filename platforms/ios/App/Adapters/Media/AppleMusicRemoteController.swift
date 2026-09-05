import Foundation
import MediaPlayer

/// The media projection sent to the round display.  It intentionally models
/// only state that Apple's public system-music API can read reliably.
struct PhoneMediaState: Equatable, Sendable {
    var connected = false
    var playing = false
    var trackToken: UInt32 = 0
    var positionSeconds: UInt16 = 0
    var durationSeconds: UInt16 = 0
    var sourceName = "APPLE MUSIC"
    var trackTitle = "尚未播放"
    var artistName = ""

    // Apple does not expose a public API for changing the Apple Music
    // favourite state of the Music app's current item.
    var likeAvailable = false
    var liked = false
}

/// Controls only the built-in Music app through
/// MPMusicPlayerController.systemMusicPlayer.
///
/// This is deliberately not presented as a global iOS media remote:
/// MPRemoteCommandCenter receives commands for an app's own player; it cannot
/// inject commands into NetEase Cloud Music or another third-party player.
@MainActor
final class AppleMusicRemoteController: NSObject {
    var onStateChange: ((PhoneMediaState) -> Void)?

    private let player = MPMusicPlayerController.systemMusicPlayer
    private var started = false
    private var progressTask: Task<Void, Never>?

    override init() {
        super.init()
    }

    deinit {
        progressTask?.cancel()
        NotificationCenter.default.removeObserver(self)
        if started {
            player.endGeneratingPlaybackNotifications()
        }
    }

    /// Begins observing Apple Music and requests media-library access once.
    /// The required explanation is supplied by NSAppleMusicUsageDescription.
    func start() {
        guard !started else {
            publishCurrentState()
            return
        }
        started = true
        player.beginGeneratingPlaybackNotifications()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerStateDidChange),
            name: .MPMusicPlayerControllerPlaybackStateDidChange,
            object: player
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerStateDidChange),
            name: .MPMusicPlayerControllerNowPlayingItemDidChange,
            object: player
        )

        switch MPMediaLibrary.authorizationStatus() {
        case .authorized:
            publishCurrentState()
        case .notDetermined:
            MPMediaLibrary.requestAuthorization { [weak self] _ in
                Task { @MainActor in
                    self?.publishCurrentState()
                }
            }
        case .denied, .restricted:
            publishCurrentState()
        @unknown default:
            publishCurrentState()
        }
    }

    /// Handles the DeviceCommand raw kinds defined by BLE protocol v1.
    /// Like is intentionally unsupported rather than showing a false success.
    func handleDeviceCommand(kind: UInt8) -> BLECommandDisposition {
        guard MPMediaLibrary.authorizationStatus() == .authorized else {
            publishCurrentState()
            return .invalidState
        }

        switch kind {
        case 16:
            player.skipToPreviousItem()
        case 17:
            if isActivelyPlaying(player.playbackState) {
                player.pause()
            } else {
                player.play()
            }
        case 18:
            player.skipToNextItem()
        case 19:
            return .unsupported
        default:
            return .unsupported
        }

        // MPMusicPlayerController applies queue changes asynchronously.  Send
        // one immediate acknowledgement projection and one settled projection.
        publishCurrentState()
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            self?.publishCurrentState()
        }
        return .accepted
    }

    func publishCurrentState() {
        let authorized = MPMediaLibrary.authorizationStatus() == .authorized
        guard authorized else {
            progressTask?.cancel()
            progressTask = nil
            onStateChange?(PhoneMediaState(
                connected: false,
                sourceName: "APPLE MUSIC",
                trackTitle: "需要媒体资料库权限"
            ))
            return
        }

        let item = player.nowPlayingItem
        let playing = isActivelyPlaying(player.playbackState)
        let title = item?.title?.trimmingCharacters(in: .whitespacesAndNewlines)
        let artist = item?.artist?.trimmingCharacters(in: .whitespacesAndNewlines)
        let duration = item?.playbackDuration ?? 0
        onStateChange?(PhoneMediaState(
            connected: true,
            playing: playing,
            trackToken: Self.trackToken(for: item),
            positionSeconds: Self.clampedSeconds(player.currentPlaybackTime),
            durationSeconds: Self.clampedSeconds(duration),
            sourceName: "APPLE MUSIC",
            trackTitle: title?.isEmpty == false ? title! : "尚未播放",
            artistName: artist?.isEmpty == false ? artist! : "",
            likeAvailable: false,
            liked: false
        ))
        configureProgressUpdates(playing: playing)
    }

    @objc private func playerStateDidChange() {
        publishCurrentState()
    }

    private func configureProgressUpdates(playing: Bool) {
        if !playing {
            progressTask?.cancel()
            progressTask = nil
            return
        }
        guard progressTask == nil else { return }
        progressTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self else { return }
                self.publishCurrentState()
            }
        }
    }

    private func isActivelyPlaying(_ state: MPMusicPlaybackState) -> Bool {
        state == .playing || state == .seekingForward || state == .seekingBackward
    }

    private static func clampedSeconds(_ value: TimeInterval) -> UInt16 {
        guard value.isFinite, value > 0 else { return 0 }
        return UInt16(clamping: Int(value.rounded()))
    }

    private static func trackToken(for item: MPMediaItem?) -> UInt32 {
        guard let item else { return 0 }
        if item.persistentID != 0 {
            let id = item.persistentID
            return UInt32(truncatingIfNeeded: id ^ (id >> 32))
        }
        let identity = "\(item.title ?? "")\u{1f}\(item.artist ?? "")"
        var value: UInt32 = 2_166_136_261
        for byte in identity.utf8 {
            value ^= UInt32(byte)
            value = value &* 16_777_619
        }
        return value == 0 ? 1 : value
    }
}
