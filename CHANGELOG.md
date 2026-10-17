# Changelog

## Unreleased

- Documented LumaStream's purpose, target device, technical direction, and pull-request workflow.
- Standardized project documentation and code comments on English; translated the existing README and changelog.
- Added an installable ARMv7 Android TV debug APK with an embedded libtorrent4j engine and Media3 playback.
- Added magnet, HTTPS torrent, local document, and external share inputs; video file selection; download status; and visible errors.
- Added verified-piece streaming with demand-based priorities, cancellable buffering, seeking, storage-reserve checks, unsafe-path rejection, and session cleanup.
- Added remote pause/resume, backward/forward seeking, audio/settings access, and automatic file focus; fixed keyboard dismissal and native-player key routing on the projector.
- Verified real 1080p playback before full download, seeking to missing pieces, magnet metadata acquisition, audio-track switching, invalid-input handling, and background cache cleanup on WK03K. The owner confirmed audible playback through the built-in speakers on 2026-10-17.
- Added a pinned Gradle wrapper, Android build/lint CI with explicit SDK packages and APK artifacts, and bundled native dependency notices.
- Multi-source search, history, external subtitle loading, and the proposed rolling disk cache remain planned product work.
- Added a new-session agent entry point and a project-local `projector-adb` skill covering device connection, remote controls, playback evidence, and cleanup. Added a screenshot helper verified on WK03K that strips firmware text preceding PNG data; local device details and captures are excluded from Git.
- Added Internet Archive video search with remote query entry, bounded pagination, creator/source/license details, real metadata-to-torrent opening, cancellation, and visible availability/network errors. Added bounded HTTPS JSON reads, literal query escaping, and rate-limit cooldown handling.
- Verified catalog-to-player Sintel playback on WK03K before full download, result pagination, and restricted-item handling. Added explicit remote query editing and prevented stale keyboard state from trapping Back on details or playback.
