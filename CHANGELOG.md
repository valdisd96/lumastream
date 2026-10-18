# Changelog

## Unreleased

- Added bounded rolling torrent storage (up to 4 GiB with a 1 GiB free-space reserve), conservative accounting of incomplete requests, safe sparse-block eviction after native IO shutdown, and re-download of evicted pieces on backward seeks. Videos no longer require free space for their full contents.
- Added ARMv7 sparse-storage JNI with a filesystem capability/integrity probe, cache budget display, nine window/mapping regression tests, and pinned NDK/CMake build dependencies.
- Serialized cache-directory ownership across engine instances so a finishing Activity's asynchronous cleanup cannot remove a newly opened session.
- Added persistent catalog watch history and Home cards for watched movies and the latest episode of each series, with saved position, duration, progress and completed status.
- Added direct resume of the saved torrent/file, restart and exact-title/episode source replacement with explicit position transfer; history survives app recreation without retaining video cache.
- Added a persisted catalog-language selector with English as the default, including migration of existing settings; all TMDB search/detail/episode requests use the selected language independently of query text.
- Replaced the manual torrent-input screen with a search-first Home, clearer search/filter controls and consistent poster cards. Removed the Sintel demo button and editable torrent URLs while retaining external file-manager intents.
- Fixed Back navigation: nested screens return through the catalog, playback Back stops the session, and Back on Home closes the activity/task instead of opening the old input screen.
- Added a remote subtitle menu (Up), supported-track selection and Off, plus bounded UTF-8 SRT/WebVTT import from documents or direct HTTPS URLs. Imports preserve position and pause state and use Media3's extraction-time subtitle decoding.
- Fixed remote access to the visible CC button: Down focuses the bottom action row, Left/Right select CC/settings, and OK opens the focused menu. Up/Back leave the row without seeking or stopping playback. CC remains usable for external imports even without embedded subtitle tracks.
- Added a visual TMDB movie/series catalog, separate Stremio torrent-source flow, private configuration, bounded artwork caching and TMDB attribution. Verified credentialed search, artwork, episodes, source resolution and catalog-selected Sintel playback before full download on WK03K.
- Added strict selected-file checks and seven regression tests for episode identity, ambiguous files, conflicting provider metadata, remake years and non-contiguous torrent indexes.
- Fixed poster-grid height so titles/year/type remain visible, and made the movie source action reachable by vertical D-pad navigation; verified both on the projector.
- Fixed peer discovery for catalog providers returning hashes without trackers: use two public trackers only when the provider supplies none. Preserved explicit tracker lists and conventional readable magnet hashes; diagnosed DHT-only discovery failure with real native evidence.
- Documented LumaStream's purpose, target device, technical direction, and pull-request workflow.
- Standardized project documentation and code comments on English; translated the existing README and changelog.
- Added an installable ARMv7 Android TV debug APK with an embedded libtorrent4j engine and Media3 playback.
- Added magnet, HTTPS torrent, local document, and external share inputs; video file selection; download status; and visible errors.
- Added verified-piece streaming with demand-based priorities, cancellable buffering, seeking, storage-reserve checks, unsafe-path rejection, and session cleanup.
- Added remote pause/resume, backward/forward seeking, audio/settings access, and automatic file focus; fixed keyboard dismissal and native-player key routing on the projector.
- Verified real 1080p playback before full download, seeking to missing pieces, magnet metadata acquisition, audio-track switching, invalid-input handling, and background cache cleanup on WK03K. The owner confirmed audible playback through the built-in speakers on 2026-10-17.
- Added a pinned Gradle wrapper, Android build/lint CI with explicit SDK packages and APK artifacts, and bundled native dependency notices.
- Multi-source search and automatic subtitle discovery remain planned product work.
- Added a new-session agent entry point and a project-local `projector-adb` skill covering device connection, remote controls, playback evidence, and cleanup. Added a screenshot helper verified on WK03K that strips firmware text preceding PNG data; local device details and captures are excluded from Git.
- Added Internet Archive video search with remote query entry, bounded pagination, creator/source/license details, real metadata-to-torrent opening, cancellation, and visible availability/network errors. Added bounded HTTPS JSON reads, literal query escaping, and rate-limit cooldown handling.
- Verified catalog-to-player Sintel playback on WK03K before full download, result pagination, and restricted-item handling. Added explicit remote query editing and prevented stale keyboard state from trapping Back on details or playback.
