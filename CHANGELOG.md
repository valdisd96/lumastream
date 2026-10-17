# Changelog

## Unreleased

- Documented LumaStream's purpose, target device, technical direction, and pull-request workflow.
- Standardized project documentation and code comments on English; translated the existing README and changelog.
- Added an installable ARMv7 Android TV debug APK with an embedded libtorrent4j engine and Media3 playback.
- Added magnet, HTTPS torrent, local document, and external share inputs; video file selection; download status; and visible errors.
- Added verified-piece streaming with demand-based priorities, cancellable buffering, seeking, storage-reserve checks, unsafe-path rejection, and session cleanup.
- Added remote pause/resume, backward/forward seeking, audio/settings access, and automatic file focus; fixed keyboard dismissal and native-player key routing on the projector.
- Verified real 1080p playback before full download, seeking to missing pieces, magnet metadata acquisition, audio-track switching, invalid-input handling, and background cache cleanup on WK03K. Physical speaker audibility still needs owner confirmation.
- Added a pinned Gradle wrapper, Android build/lint CI with APK artifacts, and bundled native dependency notices.
- Catalog search, history, external subtitle loading, and the proposed rolling disk cache remain planned product work, not part of this playback milestone.
