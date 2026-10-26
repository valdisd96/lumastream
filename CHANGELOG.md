# Changelog

## 0.9.1 — 2026-10-26 (pre-release)

- Make enabled prefetch continue during playback and buffering as well as pause, within the existing disk-cache budget. Retain saved preferences, demand-read priority and session-only cleanup; stop speculative scheduling on player error or inactive playback.
- Rename the setting to **Continuous prefetch** and explain cache-full/refill behavior. Debug/release builds, lint and all 162 release JVM tests passed; real ARM64 emulator playback showed 111.5 MiB ahead of the reader at 01:00 while still downloading at 3.4 MiB/s.
- Publish the signed universal APK with version code 12; signature, four-ABI packaging, 16 KB alignment and private-credential scan passed. Install over 0.9.0 on WK03K without uninstalling: package UID/first-install time and enabled prefetch/4 GiB settings retained; installed APK matches the release SHA-256. Actual playing Sintel grew the forward reserve from 13.7 to 22.0 MiB, followed by pause/resume and normal native shutdown. Full-cache runtime and audible output were not reverified.

## 0.9.0 — 2026-10-26 (pre-release)

- Make TMDB catalog and OpenSubtitles search optional and off on fresh installations; retain configured legacy integrations and preserve credentials when disabled. Disabled TMDB makes no metadata or artwork requests, including saved library cards.
- Restore direct magnet/HTTPS torrent input and document selection from Home without accounts. Keep Archive, saved titles and history accessible; cached history sources can resume without TMDB. Hide online subtitle search when disabled while retaining embedded, torrent and imported subtitles.
- Prepare the universal signed preview with version code 11 and the existing release certificate for in-place updates.
- Release build, all 162 release JVM tests, release lint, signature, four-ABI native packaging and 16 KB alignment checks passed; private credential scanning found no bundled owner keys.
- Installed the exact signed APK over 0.8.0 on WK03K without uninstalling; package UID, first-install time and visible history were retained, and the configured catalog remained available. Installed APK checksum matches the release artifact. Playback verification was stopped when the device switched to other content; no new projector playback or audible-output claim.

- Refresh the English README with a user-friendly introduction, setup guidance and an attributed screenshot gallery; separate detailed engineering and verification records into the maintainer guide.
- Add reader-focused 0.8.0 release notes and concise conventions for future release descriptions. This is a documentation-only refresh; published APKs, checksums and signing are unchanged.

## 0.8.0 — 2026-10-26 (pre-release)

- Add one adaptive UI for Android TV, phones and tablets: touch controls, ordinary launcher entry, portrait/landscape rotation, resizable layouts and system-bar/keyboard insets. Keep Android 9 / API 28 as the minimum and TV remote controls intact.
- Adapt catalog search, discovery, Watch later, History, details, episode/source selection, Settings, Archive and file selection to touch and narrow windows. Keep long content scrollable and preserve result viewport state.
- Use native Media3 touch playback controls and scrollable subtitle dialogs; rotation keeps the active player and torrent session. Background playback/downloads and picture-in-picture remain unsupported.
- Bundle ARMv7, ARM64, x86 and x86-64 in one APK; validate ELF class/machine for every packaged native library and 16 KB alignment for both 64-bit ABIs. Set version code 10.
- Correct TV focused-row scaling and initial result visibility while retaining saved viewport restoration.
- Debug/release builds, all 158 release JVM tests, release lint and native packaging checks passed. Real ARM64 phone playback on 16 KB pages exercised rotation, touch menus, seeking and normal shutdown/cache cleanup; narrow large-text and keyboard layouts were inspected. TV emulator checks covered D-pad focus and result restoration.
- Verify the signed APK upgrading signed 0.7.0 on an isolated tablet: retained app identity and saved pause-prefetch preference, real first frame before full download, advancing video and normal native shutdown. No new physical-phone/tablet, x86 runtime or audible-output claim.

## 0.7.0 — 2026-10-26 (pre-release)

- Build a single universal Android TV / Google TV APK with ARMv7 and ARM64 native libraries, retaining Android 9 / API 28 as the minimum. Phone/tablet UI and x86 are not included.
- Enable NDK flexible-page-size support for the storage JNI library; retain unmodified libtorrent4j 2.1.0-39 binaries for both ABIs.
- Add a CI packaging gate for the exact ABI set, required torrent/storage libraries, every ARM64 ELF LOAD segment's 16 KB alignment, and 16 KB APK ZIP alignment.
- Bump version code to 9 and prepare a locally signed universal APK using the existing release certificate. Debug/release builds, all 158 release unit tests, release lint, signature verification and native packaging checks passed.
- Verified isolated ARMv7 playback on WK03K: first frame before full download, visible advancing video, pause prefetch, missing-piece seek recovery, retained settings on reinstall, and normal native shutdown/cache cleanup. The installed release and its data were untouched.
- Fix cache-size and language-dialog focus restoration when the Settings lazy list has disposed the opener: restore its list item before requesting focus, rather than crashing on dialog dismissal.
- Verified the signed APK on ARM64-only Google TV Android 16 with actual 16 KB pages: real first frame before full download, pause/resume, pause prefetch, missing-piece seek recovery and native shutdown. The corrected final APK passed cache/language dialog selection and cancellation, retained saved settings on reinstall, and rendered video again. ARM64 evidence is emulator-based; sound and private-cache deletion were not verified there.

## 0.6.0 — 2026-10-26 (pre-release)

- Prepared the signed ARMv7 preview with version code 8 and the existing release certificate for in-place upgrades.
- Integrated release build, all 158 release JVM tests and release lint passed. The non-debuggable ARMv7 APK passed alignment/signature checks, matched the installed 0.5.1 certificate, and contained no copy of the owner's private TMDB token.
- Group History into one card per movie or series, using the most recently played episode and its exact resume position rather than displaying every episode separately. Stored episode progress and the existing 100-record bound are unchanged.
- Keep history focus keyed by movie/series identity when the representative episode changes. Exact-episode history actions and Choose episode remain available.
- Verified grouped cards, latest-episode dialog, retained episode data and focus restoration in an isolated WK03K app with synthetic history. Debug build, lint and all 149 JVM tests passed; no playback was started and the installed release/data were unchanged.
- Added opt-in **Prefetch while paused**: selected-video data accumulates in bounded low-priority batches until the disk-cache budget is full or the video ends, without increasing Media3's RAM-buffer target.
- Preserve the forward reserve on resume and nearby seeks; distant seeks redirect downloads. Required video and auxiliary reads retain priority, with whole-piece accounting and one piece of auxiliary headroom.
- Added persisted **1 / 2 / 4 / 8 GiB** torrent-cache choices, defaulting to 4 GiB. Older settings keep their existing preferences and prefetch starts disabled; actual limits remain constrained by free space and the 1 GiB reserve.
- Added paused prefetch status and contiguous verified **Ahead of reader** bytes, separate from cumulative downloads and playback-buffer seconds. Session cache still clears on exit.
- Debug build, lint and all 156 JVM tests passed. An isolated WK03K native run filled a temporary 40 MiB cache with 33,218,868 bytes ahead of the reader, stopped at capacity, retained bytes after disabling prefetch, and verified a distant seek/backseek byte-for-byte without exceeding the limit.
- Verified visible Sintel playback on WK03K: remote pause accumulated additional forward data, resume and a seek into missing data returned to advancing video, and normal stop removed the session cache. Remote settings verified the toggle, cache-size chooser and Save persistence in the isolated app; the installed signed release/data were unchanged.

## 0.5.1 — 2026-10-26 (pre-release)

- Reworked source selection into independently scrolling provider/context and source columns, so notices and provider failures no longer consume the source list's height.
- Added a source-count heading, compact release labels and inset list padding for focused cards. Source quality, reported metadata, compatibility warnings and manual/automatic actions remain available.
- Prepared a signed ARMv7 preview (version code 7) using the existing release certificate for in-place updates preserving settings and history.
- Verified the real Dexter S1E4 source list on WK03K: complete focused cards, source scrolling, independent provider/disclosure scrolling, manual position-transfer confirmation and automatic quality groups. Existing history and configured providers remained usable; no video was started or history edited. Debug/release builds and lint, APK signature and alignment checks passed.
- Release build, all 147 JVM tests and release lint passed. The ARMv7 APK passed signature/alignment checks and a scan for the owner's private TMDB/OpenSubtitles credentials.

## 0.5.0 — 2026-10-19 (pre-release)

- Prepared an ARMv7 release with version code 6 for same-certificate in-place updates from earlier signed previews.
- Added Home discovery with TMDB popular/top-rated movie and series collections, media-specific localized genres, bounded pagination and explicit separation from source availability.
- Added a persistent, source-independent Watch later list with detail-screen add/remove actions, a 100-title limit, atomic storage and visible load/save failures.
- Added exact movie/episode history removal and watched/unwatched actions; unwatched resets the saved position while other entries and recency order remain unchanged. History now exposes older episodes individually.
- Restore each search/discovery/saved-list viewport and focused card on return from details, and preserve history focus with removal fallback.
- Verified real collections, genres, pagination, saved-list persistence across relaunch, exact-episode history edits and scrolled search/discovery focus restoration on WK03K in an isolated app. All 147 JVM tests, debug build and lint passed; the installed signed release and its data were unchanged.

## 0.4.0 — 2026-10-19 (pre-release)

- Prepared a signed ARMv7 preview with version code 5 for same-certificate in-place updates from earlier releases.
- Added independently enabled TorrentsDB and MediaFusion Midnight providers beside the existing configurable source provider; legacy settings preserve their provider and keep additional services disabled until explicitly saved.
- Fetch source lists concurrently with progressive per-provider status, isolated failures, bounded deadlines and short-lived per-provider caches; exact duplicate URLs are queried only once.
- Merge duplicate torrent/file provenance without inventing conflicting metadata, preserve ambiguous unindexed filenames, and diversify automatic candidates within existing quality/language and seed-health boundaries.
- Verified both remote settings toggles, real three-provider Sintel results, cache reuse and automatic playback before full download on WK03K in an isolated app; all 129 JVM tests, debug build and lint passed.

## 0.3.0 — 2026-10-19 (pre-release)

- Bumped the application to version code 4 for an in-place, same-certificate upgrade from 0.2.1.
- Added explicit quality/language-group automatic source selection with compatibility filtering, bounded metadata-based ranking and preserved manual source cards.
- Try up to four sources sequentially through real torrent/Media3 preparation, with 15-second metadata, 35-second attempt and 120-second overall startup budgets; commit at the first rendered frame without switching releases during playback.
- Added visible startup progress, cancel/manual recovery, explicit history-position transfer and serialized teardown with stale-player callback protection.
- Verified real catalog-selected Sintel startup and unreachable-source fallback on WK03K in an isolated app; existing torrent download-window and cache limits remain unchanged.

## 0.2.1 — 2026-10-18 (pre-release)

- Added advisory source-card warnings for stereoscopic labels, unavailable advertised decoders and dimensions beyond device decoder capabilities; ambiguous provider metadata remains unknown and sources stay selectable.
- Added actionable playback error categories and available renderer format details while preserving underlying data-read errors.
- Verified 15 compatibility regression tests and isolated projector warning/error screens without replacing the installed signed release.

## 0.2.0 — 2026-10-18 (pre-release)

- Published a signed ARMv7 update with version code 2, the original release certificate and SHA-256 checksum.
- Verified release build, 78 release unit tests, lint, signature/alignment, non-debuggable packaging and absence of private TMDB/OpenSubtitles credentials. An isolated signed release-mode 0.1.0 → 0.2.0 upgrade preserved settings/history bytes and resumed Sintel at 123000 ms on WK03K; the owner's debug installation was untouched.
- Added explicit next-episode navigation in series player controls and after completion, refreshing TMDB episode identity across season boundaries without autoplay, with series-end, retry and Back-to-season handling.
- Added focused source recovery after catalog opening/playback failures, preserving the movie/episode context and requested/current position, with explicit transfer or restart on replacement and the existing catalog Back path.

- Added matching torrent SRT/VTT discovery, preferred-language auto-selection and manual candidate selection, with bounded verified auxiliary reads that preserve the active video and rolling-cache budget.
- Added explicit-button OpenSubtitles search/download with video-hash and release matching, strict language/episode checks, quota/rate-limit handling and linked attribution. Developer application-consumer setup is required; public anonymous distribution requires a compliant provider plan.
- Added persisted subtitle-language and original-audio preferences, preserving TMDB original-language metadata through watch history.
- Persisted subtitle correction by exact torrent/video and embedded-track or external-content identity, with bounded atomic storage and reset removal.
- Fixed imported-subtitle switching/seeking during an already-active cue by retaining the complete bounded text timeline without changing the audio/video clock.
- Separated actual torrent download speed, unique verified-video progress, required-piece loading stages and Media3 buffered time from rolling-cache usage; playback status hides with the controls.
- Made demanded-piece waits progress-aware with a finite hard cap, serialized overlapping readers' demand/wait/read operations, and preserved underlying network/disk source errors.
- Added Choose episode to series history cards, with season/episode/source navigation and Back returning to the originating Home card.
- Restored playing-state control auto-hide after opening/dismissing subtitle and settings menus.
- Added remote subtitle timing adjustment for supported embedded and imported subtitles: ±0.1/1-second steps, ±30-second range, reset, and media-clock timing across seeks. Applying retains position/play state but may briefly buffer; constant offsets do not fix different edits or progressive drift.

## 0.1.0 — 2026-10-18 (pre-release)

- Published the first signed ARMv7 APK for Android TV 9+, with a SHA-256 checksum and a persistent release certificate; documented local signing, installation, debug-to-release migration, and key-backup requirements.
- Verified release build, 24 release unit tests, lint, APK signature/alignment, non-debuggable packaging, and absence of the owner's private TMDB token. A separately identified release-mode app signed with the release key rendered Sintel on WK03K before full download and stopped normally; the existing debug app was preserved.
- Reviewed the rendered Internet Archive terms, privacy and copyright policies and documented the scholarship/research-only access restriction; this is not legal clearance.

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
