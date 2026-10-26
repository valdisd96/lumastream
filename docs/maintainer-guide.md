# LumaStream maintainer guide

This is the technical reference for maintainers and coding agents: implemented behavior, build and signing instructions, architecture, verification evidence, limitations, and development workflow. For the user-facing overview and screenshots, start with the [README](../README.md). For delivered changes, see the [changelog](../CHANGELOG.md); for release presentation, see the [release writing guide](releases.md) and [0.8.0 release notes](releases/0.8.0.md).

The original technical README is preserved below, including its historical verification records. Those records describe checks at the time they were performed, not renewed verification of every release. The separately labeled published-release deployment evidence supplements the 0.8.0 development checks without changing their scope.

## Navigation

- [Download and installation](#download)
- [Current functionality](#current-functionality)
- [Visual movie and series catalog](#visual-movie-and-series-catalog)
- [Target device and stack](#target-device-and-stack)
- [Build and install](#build-and-install), [release signing](#signing-a-release)
- [0.7.0 universal APK verification](#universal-apk-verification-070)
- [0.8.0 adaptive Android verification](#adaptive-android-verification-080)
- [Phones and tablets](#use-on-phones-and-tablets), [remote controls](#use-with-a-remote)
- [Storage and playback boundaries](#storage-and-playback-boundaries)
- [Implementation](#implementation)
- [Projector verification history](#verification-on-the-projector)
- [Development workflow and remaining work](#development-and-remaining-product-work)
- [Resuming in a new agent session](#resuming-in-a-new-agent-session)

---

# LumaStream

An Android application for TVs, phones and tablets that plays torrents before the download completes, in a single APK with an embedded libtorrent engine and no mandatory external server.

## Download

The latest signed preview is [v0.9.1](https://github.com/valdisd96/lumastream/releases/tag/v0.9.1). Download `lumastream-0.9.1-universal.apk` and `SHA256SUMS` from its assets. One APK supports Android TV, phones and tablets on Android 9 or later, with ARMv7, ARM64, x86 and x86-64 native libraries and 16 KB-compatible 64-bit libraries. GitHub Packages is not used.

Verify the download with `shasum -a 256 -c SHA256SUMS`, then sideload with `adb -s DEVICE install -r lumastream-0.9.1-universal.apk`, or transfer it to the device and use an APK installer with installation from that source enabled. No account is needed for direct torrent playback. TMDB and OpenSubtitles are optional and default Off; no token or media is bundled.

The release certificate differs from development debug keys. Android cannot update a debug installation with this APK in place. Uninstalling the debug app removes its settings and watch history; do not uninstall it unless you accept that loss. Future release updates must retain the release signing key and increase `versionCode`.

Version 0.9.1 has `versionCode=12` and uses the same release certificate as earlier signed previews. Install it over an earlier signed release without uninstalling to retain app-private settings and history. This does not make a debug-to-release installation compatible.

This preview extends enabled prefetch to playback and buffering as well as pause, retaining the saved preference and disk-cache limit. It retains credential-free direct input, optional integrations and adaptive phone/tablet layouts from 0.9.0. Earlier mobile and TV regression checks used ARM64 emulators; x86 runtime and physical phone/tablet playback have not been verified. Codec and filesystem support still depend on the device.

Release 0.9.0 verification: `assembleRelease`, all 162 release JVM tests and `lintRelease` passed. The non-debuggable universal APK passed signature, four-ABI native packaging and 16 KB alignment checks; a decompressed-entry scan found no owner TMDB/OpenSubtitles credentials. On WK03K, installing over 0.8.0 without uninstalling retained the package UID, first-install timestamp and visible history; the configured catalog remained available. The pulled installed APK matched the release SHA-256 exactly. Device interaction stopped when other content appeared, so this deployment makes no new projector playback or audible-output claim and does not claim byte-for-byte inspection of private settings/history.

## Current functionality

- Open magnet links, HTTPS torrent URLs or torrent documents from Home without credentials. Optional TMDB search/discovery provides movie/series metadata; choose a title/episode and source to play. External `ACTION_VIEW` and `ACTION_SEND` torrent inputs remain supported.
- Choose a video from a multi-file torrent; only the selected video's requested pieces and a bounded lookahead are prioritized.
- Play through Media3, pause, seek into undownloaded sections, and choose available audio tracks through the player settings.
- Display download progress, peers, buffering, and actionable input/network/storage errors.
- Stop playback and the native session when leaving the application; clean the session cache on stop or when opening another source.
- Search Internet Archive video items, browse bounded result pages, review creator/source/reported license, and open an item's torrent through the same video-selection flow.

The playback milestone, Internet Archive search, visual TMDB catalog and catalog watch history have device verification. Torrent storage uses a bounded rolling cache rather than requiring free space for the full selected video.

## Visual movie and series catalog

The initial screen separates direct playback, optional movie/series metadata and torrent availability. New installations default to **Catalog: Off** and **Online subtitles: Off**. Home exposes **Open magnet / torrent**, Archive, Watch later and History without credentials. The direct-input screen accepts magnet links and HTTPS torrent URLs, or opens the system picker for a `.torrent` document; devices without a picker can use links or a file manager.

To opt into metadata, select **Catalog: TMDB** in Settings, configure a **TMDB API Read Access Token** (not the shorter API key), then Save settings. Obtain it through your [TMDB API account settings](https://www.themoviedb.org/settings/api). Enabled services require their corresponding credential; disabling retains it for later and Back discards unsaved changes. Legacy settings without enable flags infer each service's enabled state independently from its existing credential. Explicit Off always wins, including when a credential remains stored.

Disabled TMDB performs no metadata or artwork requests, including from history and Watch later. Saved metadata snapshots remain readable and removable; history can resume its cached torrent/file without refreshing TMDB. Fresh metadata/episode lookup requires voluntarily connecting the catalog. Do not commit or paste credentials into issues. Settings remain in app-private `files/catalog-config.json`; Android backup is disabled. No credentials are bundled.

Optional-service development verification (2026-10-26) used an isolated application on an ARM64 phone emulator: credential-free Home and settings save, manual HTTPS torrent opening, system `.torrent` document selection, rendered Sintel playback, and the local-only subtitle menu. The first manual-source frame arrived with 2,351,412 of 129,241,752 bytes downloaded. With synthetic saved history/Watch later and both services explicitly Off despite retained test credentials, saved title details remained readable and cached-source resume rendered at 12,000 ms before advancing. Debug build, 162 JVM tests and debug lint passed. This is development evidence, not a newly published APK or physical-device/audio verification.

An ARM64 Google TV emulator also exercised fresh-Home D-pad focus, direct URL editing, invalid-link feedback, keyboard dismissal without losing the draft, and remote submission. Its URL request surfaced a DNS-resolution failure and returned normally to Home; TV playback was not verified in this run. The input screen retains a separate editing Back step on TV so users can reach Open link instead of accidentally returning Home. Physical projector input, new physical phone playback, and service-level packet capture were not exercised in this check.

Search supports All / Movies / Series, poster cards, year and media type. Details show available artwork, descriptions, seasons and episodes. Source requests use the selected title's IMDb ID and, for series, its season and episode. Archive remains a separate navigation destination, not a fallback mixed into TMDB results.

Home also offers **Discover**, **Watch later**, and **History**. Discover separates **Movies / Series**, **Popular / Top rated**, and a genre chooser populated with the selected media type's localized TMDB genres. Pages replace rather than accumulate results, with at most 500 pages. Unfiltered collections use TMDB's popular/top-rated endpoints; genre collections use discovery sorted by popularity or rating, with at least 200 votes for genre-filtered top-rated results. Catalog metadata, including future releases, is not a promise of available sources or permission to obtain a title.

Choose **Add to Watch later** in a movie or series detail screen; the same action becomes **Remove from Watch later** for a saved title. The list stores up to 100 distinct movie/series titles, newest additions first, in atomic app-private `files/watch-later.json`. It contains metadata snapshots, not credentials, torrent sources or downloaded video. At capacity, remove a title before adding another; existing titles are not silently evicted. Saved cards remain available without a catalog request; opening details refreshes metadata in the current catalog language and source lookup still needs the network. Failed writes preserve the previous list; unreadable storage disables changes until loading succeeds.

Returning from details restores the originating search, discovery or Watch later grid's viewport and focused title, including a partially scrolled row. History preserves its own horizontal viewport and movie/series title focus, even when the displayed episode changes. Removing the focused record falls back to a remaining card or the empty-state navigation. Viewports are session-local and reset when catalog configuration changes; saved titles and history survive process restarts.

Home/library verification on WK03K (2026-10-19) used a separately identified debug app without replacing the installed signed release or its data. Real TMDB popular and top-rated movie lists, movie Action and series Action & Adventure genres, series results, and page 2 were exercised. Remote navigation added and removed Watch later titles; a saved title remained after background process termination and relaunch. Scrolled search and discovery grids returned from details with identical visible text coordinates and focused-card bounds. Seeded isolated history records verified watched marking while retaining position, unwatched reset to zero, and exact-episode deletion without changing another episode/movie; deletion restored focus to the remaining card and persisted across relaunch. Screenshots confirmed the Home, genre dialog, grids, saved list and history actions. Debug build, all 147 JVM tests and lint passed. No new playback/audio claim is made; network/storage failure UI was not injected on-device.

Settings include **Catalog language**, defaulting to **English (en-US)** for both new installs and existing configurations without a language field. Choose English, Russian, Ukrainian, German, French, Spanish, Italian, Portuguese (Brazil), Japanese, Korean or Chinese (Simplified), then **Save settings**. This saved choice controls TMDB search results, details, seasons and episode metadata, independently of the search query's language, device locale, audio and subtitles. Changing it clears the current catalog results so languages are not mixed; TMDB translation availability still varies by title.

The selected filter remains active when editing a query. Use All or Movies to find Interstellar (2014); Series deliberately excludes films.

Search-first Home verification on WK03K: build, unit tests and lint passed. An existing configuration without a language field produced English Interstellar results and an English synopsis. Selecting Russian with the remote, saving, and reinstalling/relaunching preserved the language and produced Russian results/overview for the same English query. Token and provider settings were unchanged; the device was returned to English. Screenshots verified Home, focused search, readable language options and complete result-card labels. Back exited Home to the TV launcher; during editing, the projector keyboard consumed the first Back, the next closed editing, and Home Back exited.

The final build also exercised Home search → Sintel details → source selection → real playback. The first frame arrived with 3,063,808 of 143,100,616 bytes downloaded; a screenshot showed video and playback positions advanced. Back stopped the native session and restored catalog source selection; subsequent Back presses returned through details/results and exited to the launcher. No renewed audible-sound claim is made.

Settings retain the configurable HTTPS Stremio `/manifest.json` URL (initially Torrentio) as the primary provider. **TorrentsDB** (`https://torrentsdb.com/manifest.json`) and **MediaFusion Midnight** (`https://mediafusionfortheweebs.midnightignite.me/manifest.json`) are separate opt-in buttons near the top of Settings. Set each to **On**, then **Save settings**; Back discards unsaved changes. Both start disabled, including when loading older settings, and the existing primary URL, credentials and preferences remain unchanged. Each enabled service receives the selected IMDb title/episode ID and network IP; disabled additional services receive no requests. No debrid account is needed for these direct-torrent endpoints. Public-service availability, limits and terms can change; protocol access does not grant permission to download a work.

Source lookup queries enabled, distinct provider URLs concurrently (at most three calls), publishes each completed result immediately, and shows per-provider searching/success/error status. A slow or failed service does not discard another service's results; sources can be selected before all requests finish. A provider has a 30-second deadline covering manifest and stream lookup, with no automatic retries. Existing HTTP cooldowns remain per-provider, and safe network/access/rate-limit failures are shown without exposing credentials or remote error bodies. Each provider retains up to 12 successful title/episode pages for two minutes in memory; returning to a recent list or retrying a failed service reuses successful cached pages, marked **cached**. Configuration changes replace clients and clear this transient cache. Per-provider limits remain 500 stream entries and 2 MiB per JSON response; the combined source bound is 1,500. Only torrent `infoHash` streams are supported; HTTP video, debrid, DRM and external-player streams are not.

Sources display provider-reported quality, languages, size and seeders only when available. Status counts are per-provider, before cross-provider deduplication. Duplicate torrent/file records combine provenance (for example **Torrentio + TorrentsDB**) but retain one complete metadata record instead of inventing a union of conflicting language, quality or size hints. Hash comparison is case-insensitive and file indices must match exactly; without an index, different or missing filenames remain separate because file identity is unproven. Trackers are combined only when filenames agree exactly. Actual torrent metadata is still checked before playback: contradictory filename/year/episode information or ambiguous episode files produce an error rather than selecting the largest video.

Multi-provider verification on WK03K used a separately identified debug app, leaving the installed signed 0.3.0 and its data unchanged. The remote enabled both optional providers and Save persisted their choices while preserving the token; before Save, persisted settings remained unchanged. Real Sintel lookup displayed Torrentio (5 sources), TorrentsDB (5), and MediaFusion Midnight (25), including a merged Torrentio + TorrentsDB card. Reopening within the TTL showed all three cached statuses. The shared automatic 720p picker rendered a first frame after 7.794 seconds with 2,506,752 / 143,100,616 bytes downloaded; visible playback advanced and Back stopped the native session. Debug build, lint and all 129 JVM tests passed, including migration, independent failures, progressive publication, cancellation, deadlines, cache boundaries, duplicate identity and provider-diversity regressions. This does not verify every returned torrent, renewed audible sound, or sustained throughput.

Source cards show advisory warnings for explicit 3D/stereoscopic labels, codecs absent from the device's advertised decoders, and reported dimensions rejected by every candidate decoder. Decoder capabilities are queried on the device, not hardcoded for the projector. Missing/conflicting hints or failed capability queries remain unknown; no warning is not a compatibility guarantee. Resolution-only labels do not invent a width, and warnings never hide or block a source. Actual codec profiles, frame rate, HDR and stream integrity still require playback.

The source screen uses two independently scrolling columns: title/episode context, provider statuses and disclosures on the left; automatic selection and manual source cards on the right. Provider notices no longer reduce the source list's height. Release labels are compact and long labels use ellipses; focused cards have inset space around the list.

Source-layout verification on WK03K (2026-10-26): a locally signed 0.5.1 build (version code 7, not a published release) updated 0.5.0 in place with the existing release certificate, retaining the package UID and original installation time. History remained visible, including Dexter S1E4 at 0:47; configured providers returned 60 deduplicated sources without reconfiguration. Screenshots and remote navigation verified complete first/second focused cards, vertical source scrolling, independent provider/disclosure scrolling, the manual position-transfer confirmation and automatic quality-group dialog. No video was started and no history record was edited. Debug and release builds/lint passed; signature and alignment checks passed. Provider error/empty states and unusually long compatibility warnings were not injected in this run.

Playback errors distinguish unsupported formats, decoder limits, decoder startup/failure, container parsing, media-data reads and audio-output failures. Available renderer MIME/codec/dimensions and underlying causes remain visible with guidance to choose another source.

Compatibility verification on WK03K: debug build, all 93 unit tests (including 15 compatibility regressions), and lint passed. An isolated temporary debug application used real device decoder capabilities with a synthetic `H264 1920x2160 3D` source card; screenshots confirmed readable stereoscopic and decoder-limit warnings with Play still visible. An injected Media3 capability error exercised the production error formatter/screen, and a separate IO error retained its underlying cause without decoder advice. This checks warnings and error presentation, not playback of an incompatible video. The installed signed v0.2.0 and its data were not replaced; temporary verification code/apps were removed.

**Automatic source selection:** choose **Watch automatically…** above the source cards, then explicitly choose a quality/provider-language group. Unknown labels remain a separate group; the picker never silently lowers quality or changes the reported language set. Language labels are not verified audio tracks. Sources with compatibility warnings remain manually selectable but are excluded from automatic selection. Within the chosen group, positive reported seed counts rank first (descending), unknown counts next, zero last; positive reported size breaks ties only and is not treated as an episode bitrate. After the best candidate, the picker prefers previously unrepresented providers within the best remaining seed-health category, without promoting zero-seeder reports over positive/unknown sources. Duplicate hash/file selections are attempted only once and at most four candidates are attempted. Opening the group picker snapshots its candidates so late provider responses do not move choices or change a queued playback attempt.

Automatic startup uses one native session at a time and the real Media3 playback path, not a metadata-only availability probe. It allows up to 15 seconds for metadata and file identification, 35 seconds for the entire attempt, and 120 seconds overall, including teardown between attempts. Mandatory native cleanup can finish after the deadline; no new attempt starts once the overall budget expires. Errors or missing first frames advance to the next candidate only before the first rendered frame. At that frame, the source is committed and automatic switching stops. This improves source selection, not the bandwidth of an unhealthy swarm, and does not guarantee sustained playback speed.

The preparation overlay remains visible over an attached player surface and offers **Choose manually / Cancel**; Back also cancels and returns to sources. Backgrounding cancels startup through normal session shutdown. Every source replacement awaits native teardown; late callbacks from released players cannot affect the current attempt. Existing history requires explicit transfer of the saved position to candidate releases or restarting from the beginning, because their timing may differ. Exhausted attempts retain an actionable error and manual source recovery. Manual playback keeps its existing timeouts.

Automatic-selection verification on WK03K used a separately identified debug application, leaving the installed signed release/settings/history untouched. The real catalog → Sintel → automatic 720p group → explicit restart flow rendered a first frame in 5.724 seconds with 3,342,336 of 143,100,616 bytes downloaded; video was visible and playback advanced. A throwaway on-device scenario first tried an unreachable hash, timed it out, then rendered public Sintel at 23.008 seconds overall (7.014 seconds for the second attempt), before full download. These are individual startup observations, not an improvement benchmark against the previous release. Download lookahead, connection limits and cache eviction were not changed. Diagnostic `LumaPlayer` events include `auto_start`, `auto_metadata`, `auto_first_frame` and `auto_outcome` with monotonic startup timings.

The same device runner cancelled an unreachable first candidate with Back and confirmed return to catalog, no player, completed cleanup and no late fallback after waiting beyond the metadata deadline. Debug build, lint and all 109 JVM tests passed, including 16 new ranking/startup regressions. The temporary runner and isolated application were removed after verification. No new audible-sound or sustained-throughput claim is made.

For catalog sources without provider-supplied trackers, magnets include `udp://tracker.opentrackr.org:1337/announce` and `udp://open.demonii.com:1337/announce` alongside normal DHT discovery. Explicit provider tracker lists are preserved instead of supplemented. These public trackers receive the torrent hash and network IP; they are peer-discovery services, not playback servers. Externally supplied magnets and private torrent documents are not modified. The validated hash uses the conventional readable `xt=urn:btih:` form; percent-encoded colons were also verified to parse correctly in libtorrent.

Artwork uses bounded sizes and a shared 16 MiB memory / 64 MiB disk cache. Text remains available when images fail. Settings include the official TMDB logo and required non-endorsement attribution. Review [TMDB usage requirements](https://developer.themoviedb.org/docs/faq) before distribution; commercial use requires separate agreement.

Verification on WK03K: `assembleDebug`, `testDebugUnitTest` and `lintDebug` passed; seven file-selection regression tests cover episode identity, ambiguity, contradictory metadata and torrent-index handling. With the owner's private token, real Interstellar and The Studio searches, localized posters, film details, series filtering, an empty filtered result, seasons, episode stills/dates, Back navigation and The Studio S1E2 source identity were exercised. Source listing for that series was checked without downloading it. Device checks exposed and fixed clipped card labels and a movie source-action button skipped by D-pad navigation; both fixes were exercised on the installed build.

Initial catalog playback attempts exposed metadata timeouts, a torrent without supported video files, and a 3D AVC 1920×2160 source exceeding the projector's decoder capabilities. Later native diagnostics corrected the earlier assumption that all metadata timeouts meant dead sources: DHT was running but could find no peer for an owner-reported hash; adding a public tracker to the same hash resolved its ten-file metadata in approximately five seconds. That diagnostic did not download its videos.

After the tracker correction, the previously timing-out Sintel 720p source was opened through the real TMDB → details → Torrentio source → native player flow on WK03K. Media3 logged `first_frame` with 2,228,224 bytes downloaded out of 143,100,616; a screenshot showed real video and playback positions advanced. Back stopped playback. This verifies catalog-source playback before full download, not universal source availability, codec compatibility or renewed audible-sound confirmation.

## Target device and stack

Verified on XGIMI MoGo 4 Laser (WK03K), Android TV 11 / API 30, `armeabi-v7a`, with approximately 1.65 GiB of RAM visible to the OS. The primary scenario is 1080p with built-in speakers and internal storage.

- Kotlin 2.0.21, Compose for TV and Material 3 touch controls.
- AndroidX Media3 1.5.1 / ExoPlayer.
- libtorrent4j 2.1.0-39, including ARMv7, ARM64, x86 and x86-64 native libraries.
- Gradle 8.9, Android Gradle Plugin 8.7.3, JDK 17.
- Android NDK 27.0.12077973 and CMake 3.22.1 for the small sparse-storage JNI library.
- Minimum Android API 28; compile/target API 35. Development APKs contain ARMv7, ARM64, x86 and x86-64 native binaries. Android selects the matching ABI at installation.
- NDK 27 flexible-page-size support builds the storage JNI library for 16 KB pages on 64-bit ABIs. All packaged ARM64 and x86-64 libraries, including upstream libtorrent and AndroidX graphics, must have ELF LOAD alignment of at least 16 KB; CI checks native contents, ELF class/machine and APK alignment.

## Build and install

Install JDK 17 and the Android SDK command-line tools. Install SDK packages and accept their licenses:

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.0.12077973" "cmake;3.22.1"
```

Set `JAVA_HOME` to your JDK 17 installation. Set `ANDROID_HOME` to your SDK directory, or put `sdk.dir=/path/to/android-sdk` in the ignored `local.properties` file.

```sh
./gradlew assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/verify-native-packaging.py app/build/outputs/apk/debug/app-debug.apk \
  --readelf "$ANDROID_HOME/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf" \
  --zipalign "$ANDROID_HOME/build-tools/35.0.0/zipalign"
adb shell am start -n dev.lumastream/.MainActivity
```

On a Homebrew-based Mac with `openjdk@17` and `android-commandlinetools`, the environment can be set with:

```sh
export JAVA_HOME="$(brew --prefix openjdk@17)"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
```

Use `adb -s DEVICE` if multiple devices are connected. Configure and authorize ADB on the TV first; never forward its port to the internet. `assembleDebug` produces debug-signed development builds, not the signed APK distributed through Releases. Debug keys from different machines are not interchangeable for in-place updates.

The native checker example uses the macOS NDK host directory; use `linux-x86_64` on Linux. It rejects missing/extra ABIs, missing torrent/storage JNI libraries, incorrect ELF class/machine, 64-bit ELF alignment below 16 KB, and invalid 16 KB APK alignment.

The Android GitHub Actions workflow builds and lints pull requests and `main`, and uploads a debug APK. No empty test suite is presented as behavioral verification.

### Signing a release

Release signing is performed locally with Android SDK tools; Gradle's `assembleRelease` output is intentionally unsigned. The maintainer's persistent PKCS#12 key (`release.p12`, alias `lumastream`) and password file (`store-password`) are stored outside the repository in `$HOME/.config/lumastream/signing/`, with directory mode `700` and file mode `600`. Keep an encrypted offline backup of both; losing the key prevents compatible updates. Never commit or upload either file as a release asset, and never generate a replacement key for a routine update.

With `JAVA_HOME` and `ANDROID_HOME` configured as above:

```sh
./gradlew assembleRelease testReleaseUnitTest lintRelease
TOOLS="$ANDROID_HOME/build-tools/35.0.0"
SIGNING="$HOME/.config/lumastream/signing"
OUT=".local/release-v0.8.0"
mkdir -p "$OUT"
"$TOOLS/zipalign" -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$OUT/aligned.apk"
"$TOOLS/apksigner" sign \
  --ks "$SIGNING/release.p12" --ks-key-alias lumastream \
  --ks-pass "file:$SIGNING/store-password" \
  --out "$OUT/lumastream-0.8.0-universal.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify --verbose --print-certs "$OUT/lumastream-0.8.0-universal.apk"
"$TOOLS/zipalign" -c -P 16 4 "$OUT/lumastream-0.8.0-universal.apk"
(cd "$OUT" && shasum -a 256 lumastream-0.8.0-universal.apk > SHA256SUMS)
```

Release certificate SHA-256: `c113d8eb54f1eb4cdfe327612236e7dbd7cedce27ab9235a4f4938ad01e35f70`.

For v0.6.0, `assembleRelease`, all **158 release unit tests** and `lintRelease` passed. The signed APK passed signature/alignment checks and matches the certificate of the installed 0.5.1 release. Its package is `dev.lumastream`, version code 8, non-debuggable and ARMv7-only; the owner's private TMDB token was absent from decompressed APK entries. Pause-prefetch and grouped-history device verification and its limits are recorded below.

For v0.5.1, `assembleRelease`, all 147 release unit tests and `lintRelease` passed. The signed ARMv7 APK passed signature/alignment checks with the original release certificate and a scan for the owner's private TMDB/OpenSubtitles credentials. Source-layout device evidence is recorded above; this release does not claim renewed playback or audible-sound verification.

For v0.2.0, `assembleRelease`, all 78 release unit tests and `lintRelease` passed. The distribution APK passed signature/alignment checks with the original certificate, has package `dev.lumastream`, version code 2 and version name 0.2.0, is non-debuggable, contains only ARMv7 native libraries and bundled notices, and was checked for absence of the owner's private TMDB and OpenSubtitles credentials.

Upgrade verification on WK03K used separately identified, non-debuggable 0.1.0 and 0.2.0 builds signed with the release key (`dev.lumastream.upgradesmoke`) to preserve the owner's debug installation. Installing 0.2.0 over 0.1.0 retained the exact configuration/history bytes, package UID and original installation time. The upgraded app loaded the test token/provider settings, Russian catalog language and Sintel resume position of 123000 ms; new subtitle/audio preferences initialized to English/Original. Actual resumed playback rendered its first frame at exactly 123000 ms with 4,330,136 of 129,241,752 bytes downloaded, advanced to 133007 ms and stopped normally with native shutdown. Screenshots verified the history card, settings, rendered video and updated progress. The temporary apps, runner and build override were removed. This verifies the release-mode upgrade/data path under an isolated application ID, not installation of the published package over the owner's incompatible debug signature; no new audible-output claim is made.

For v0.1.0, `assembleRelease`, all 24 release unit tests and `lintRelease` passed. The distribution APK passed signature/alignment checks, contains only ARMv7 native libraries and bundled third-party notices, is not debuggable, and was checked for absence of the owner's private TMDB token. On WK03K, a separately built release-mode copy signed with the same key and temporary application ID `dev.lumastream.releasesmoke` exercised fresh Home, external Sintel input, video selection, actual video rendering, advancing playback and normal native-session shutdown. First frame arrived with 2,626,200 of 129,241,752 bytes downloaded; playback reached 29,261 ms before stopping. The temporary app was removed without replacing the owner's debug installation. This checks release-mode runtime behavior, not installation of the exact distribution APK over a differently signed app; no new audible-sound claim is made.

### Universal APK verification (0.7.0)

Debug and release builds, all **158 release unit tests** and release lint passed. The locally signed `lumastream-0.7.0-universal.apk` has package `dev.lumastream`, version code 9, minimum API 28, both ARM ABIs, and the existing release certificate. The native packaging checker passed on debug and signed release APKs; all packaged ARM64 ELF LOAD segments have `0x4000` alignment.

On WK03K (ARMv7, Android 11, 4 KB pages), a separately identified debug build rendered Sintel with **1,184,408 / 129,241,752 bytes** downloaded and visibly advanced. Pause/resume worked; with prefetch enabled and a 1 GiB cache, the paused overlay's contiguous ahead bytes increased from **4.0 to 5.4 MiB**. A distant seek requested unavailable pieces and returned to advancing video at 314,581 ms. Home stopped the native session and left 8 KiB in cache. Reinstalling the isolated APK retained its seeded settings exactly. The isolated app was removed; the owner's installed release and data were not replaced.

On the ARM64-only Google TV Android 16 / API 36 emulator (`google-tv-ps16k`, actual page size **16,384 bytes**), the signed release APK rendered Sintel with **1,053,336 / 129,241,752 bytes** downloaded. Pause/resume and a missing-piece seek recovered to READY at 312,386 ms and advanced past 320 seconds; Home stopped the native session. These are emulator results, not hardware ARM64 decoder or audible-output certification.

The ARM64 check exposed a Settings crash when closing a cache-size dialog after its opener had been disposed by the lazy list. Cache-size and language dialogs now restore the opener's list item and wait for layout before requesting focus. The corrected signed APK passed cache selection/cancellation and catalog-language selection/cancellation, visibly restoring focus. Saving and reinstalling it retained prefetch On, 2 GiB cache and English. Its final playback rendered a first frame with **647,476 / 129,241,752 bytes** downloaded, advanced, and stopped normally. In the earlier ARM64 prefetch check, paused ahead-of-reader bytes grew from zero to 9.1 MiB. Private cache deletion was not directly measured on the non-debuggable ARM64 release; native shutdown was verified. The emulator was stopped after verification.

The certificate and version-code checks support in-place signed-release updates; the isolated reinstall is not an older-release migration test. No new audible-output claim is made, and decoder/network/filesystem limitations still apply.

### Adaptive Android verification (0.8.0)

Debug/release builds, all **158 release JVM tests**, release lint and the four-ABI packaging check passed. A deliberately mismatched x86-64 ELF machine was rejected by the checker. The locally signed APK uses the existing release certificate and contains no copy of the owner's private TMDB token.

On an ARM64 phone emulator with actual **16 KB pages**, real TMDB search, title details, Watch later, source selection and automatic catalog playback were exercised. Sintel rendered with **974,848 / 143,100,616 bytes** downloaded. Playback continued across portrait/landscape rotation without replacing the process. Touch pause/resume, audio and subtitle menus, scrollable subtitle timing and timeline seeking were exercised; a seek to 465,377 ms returned to advancing playback. Back stopped the native session and removed its cache directory. A 320 dp viewport with 1.5× font scale and short landscape keyboard/dialog layouts were inspected; cancelling a provider draft left the saved configuration unchanged.

The final signed APK was installed over signed **0.7.0** on an isolated ARM64 tablet emulator. The app UID and original install time were unchanged, and the previously saved non-default pause-prefetch setting remained On. Touch file selection then rendered Sintel with **123,188 / 129,241,752 bytes** downloaded, advanced visibly and stopped the native session through Back.

On the ARM64 Google TV emulator, D-pad search, focused result visibility, full-width settings focus and result viewport restoration after visiting details were checked. Focused buttons no longer scale outside their row, and initial result focus is distinct from restoring a saved viewport.

These are emulator checks, not physical phone/tablet, x86 runtime, foldable, hardware decoder or audible-output certification. The projector and its installed app were not changed during these development checks.


#### Published 0.8.0 release deployment on WK03K

The [published v0.8.0 release record](https://github.com/valdisd96/lumastream/releases/tag/v0.8.0) documents a subsequent physical-projector deployment, separate from the development checks above. The published APK was installed in place over **0.6.0** on **WK03K (ARMv7 / Android TV 11)** without uninstalling or clearing data, retaining the app UID and original installation time. Sintel rendered with **4,854,424 / 129,241,752 bytes** downloaded. Remote pause/resume and a **30-second seek** worked, video advanced past **60 seconds**, and Back stopped the native session.

Audible output and private-cache deletion were **not independently measured** in this release-mode check. This is a preserved published-release record, not a new device check performed during the documentation reorganization. It does not certify physical phone/tablet playback or x86 runtime.

## Use on phones and tablets

Open LumaStream from the ordinary Android launcher. Search fields are directly editable; submit the keyboard search action or tap Search. Catalog grids resize with the window, long headers scroll with their results, and narrow source screens stack title context above sources. Watch later and History use the same library as TV.

Tap the video to show Media3 controls; use the timeline, pause button, audio settings and CC menu by touch. Rotate between portrait and landscape without restarting the torrent session. System bars, display cutouts and the software keyboard have reserved insets; settings and dialogs scroll in short windows. TV retains landscape presentation and remote controls.

Leaving the app still stops playback and the torrent session. Picture-in-picture and background downloading are not added.

## Use with a remote

Open **LumaStream** from the TV launcher. Home presents **Search movies and series**, **Discover · Popular, top rated & genres**, **Watch later**, **History**, and secondary Archive / Settings buttons. Press OK on Search to type a title, then submit the keyboard search action or the Search button; All / Movies / Series filters appear in Search. Alternatively, open Discover and choose a collection or genre. Choose a poster, a season/episode for series, and a source. There are no editable torrent URLs or demo buttons in this flow.

During playback:

| Key | Action |
| --- | --- |
| OK / Enter | Pause/resume, or activate the focused CC/settings/next-episode control |
| Left / Right | Seek -10 / +30 seconds; while navigating actions, move between CC, settings and next episode (series only) |
| Up | Open subtitles; from the bottom action row, return to playback controls |
| Down | Focus the bottom action row, starting on settings |
| Back | Dismiss a menu, leave the bottom action row, or stop playback and return to the catalog |

Back from details, episodes, sources, Archive or Settings returns to the preceding catalog screen. During search editing it dismisses the keyboard/editor first. **Back on Home outside editing closes the activity and removes its task**, returning to the TV launcher rather than opening a second input screen. The remote Home key also backgrounds the app and stops its torrent session. Playback and native resources are released on exit.

To open the visible subtitle icon with the remote, press **Down → Left → OK**. For audio tracks or speed, press **Down → OK**. The bottom action row stays visible while navigating; Up or Back leaves that row and restores normal seeking/pause shortcuts. Opening CC/settings exits pinned action navigation; closing a menu restores automatic control hiding during playback. Paused controls remain visible.

For a catalog series episode, press **Down → Right → OK** to activate the visible next-episode icon beside the seek controls. This stops the current video and opens source selection for the next listed episode; it never starts another video automatically. Movies and external torrents do not offer this action.

Verified on WK03K after the CC focus correction: Down/Left visibly focused CC, OK opened the subtitle menu, Back/Right/OK opened settings, and Up restored pause/seek behavior. Back from the action row retained the paused video; a subsequent Back stopped playback. Build and lint passed.

### Continue watching and watched titles

Open **Home → History** for **Continue watching · Watched** after catalog playback renders its first frame. History shows **one card per movie or series**, ordered by most recent playback. A series card represents its most recently played episode (not necessarily the highest episode number), with that episode's season/number, saved position, duration and progress. Other episodes' resume points remain stored separately; grouping changes presentation, not stored history.

Select a card with the remote to **Continue**, **Start from beginning**, or **Choose another source**. Direct resume reopens the saved torrent and exact selected file without repeating TMDB search. Another source is looked up for the same title/episode; transferring the saved position requires an explicit choice because different cuts can have different timing. Finished videos remain visible as **Watched** and offer restart rather than resume.

The card dialog also offers **Mark watched**, **Mark unwatched · reset saved position**, and **Remove from history**. Mark watched preserves the saved position but offers restart instead of Continue; marking unwatched resets that record's position to zero. Removing a record removes its resume point. Series actions explicitly apply only to the displayed episode, not the whole series; removing it reveals the next most recently played retained episode on the same series card, if one exists. These edits preserve other records and their recency order, use the existing serialized atomic history writer, and do not alter Watch later.

Series history cards also offer **Choose episode**: open the title's seasons, select an episode, then choose its source without searching again. Details and episodes refresh in the configured catalog language with the normal Retry flow. Back returns through episodes and seasons to the originating History card. Continue still resumes the saved episode; choosing another episode uses that episode's separate saved position.

Positions are saved every ten seconds while playing, on pause/seek, and before releasing playback on exit. Unexpected process termination can lose progress since the last completed write. Failed preparation does not replace an existing resume point. History is an atomic app-private `files/watch-history.json` file containing at most 100 recent movie/episode records, cached catalog metadata and source information, but no catalog token. Read/write failures are reported on Home; unreadable history is not silently overwritten. External torrent intents and Archive playback do not create catalog history.

History does not retain downloaded video: leaving still stops the native session and removes its cache. Resume may buffer while pieces are downloaded again; unavailable sources still require choosing another source.

Grouped-history verification on WK03K (2026-10-26) used synthetic history in an isolated debug app: four persisted records (two episodes of one series, another series, and a same-numeric-ID movie) rendered as three cards. The series card and resume dialog selected the most recently played episode and its exact position, while reading the stored history confirmed that grouping had not removed any records. Remote Back restored series-card focus; removing only the latest episode through the production model exposed the older episode on one card, retained title focus, and persisted the other records unchanged. Screenshots verified the grouped row, dialog and replacement episode. Debug build, lint and all 149 JVM tests passed. No video/network playback was started; the installed signed release and its history were unchanged.

Catalog opening/playback failures now offer a remote-focused **Choose another source** button alongside the error cause. It reloads sources for the same movie or exact episode, retaining the existing catalog Back path; recovery from a direct history resume returns to History. The replacement confirmation explicitly offers position transfer or restart because release timing may differ. A failure before the first frame preserves the requested resume position; a playback failure captures the current position before releasing the player. External torrent inputs have no catalog identity and retain the normal Back-to-search action.

Recovery verification on WK03K: build, unit tests and lint passed. A throwaway device runner opened an invalid-hash catalog source with a requested position of `123000` ms, activated the focused recovery button via the remote dispatcher, and displayed real Sintel source results. The replacement confirmation retained `123000` ms; selecting the legal WebTorrent Sintel source through the production model rendered its first frame at exactly `123000` ms with 3,543,704 of 129,241,752 bytes downloaded, then advanced beyond 133 seconds. Screenshots confirmed the error, sources, transfer dialog, video and Home after Back; logs confirmed native shutdown. Forced mid-playback network/decoder failures and series recovery were not exercised in this smoke run. The temporary instrumentation was removed.

Verified on WK03K: `assembleDebug`, `testDebugUnitTest` and `lintDebug` passed, including eight history regression tests. Catalog-selected Sintel rendered, was sought/paused and backgrounded, and persisted position `53481` ms. After terminating the already-backgrounded process and relaunching, Home displayed the saved card; Continue rendered its first frame at exactly `53481` ms and advanced beyond 62 seconds. Start from beginning rendered at `0` ms. History's alternate-source action loaded Sintel sources and selecting another source displayed the explicit transfer warning. Seeking to the end produced `ENDED`, stopped the native session, and displayed **Watched** on Home. Screenshots verified the Home card, resume dialog, transfer warning, resumed video and finished card. Episode identity/roundtrip are unit-tested; live series resume and audible output were not independently verified in this run.

### Next episode

Catalog series playback offers **Next episode** both in the player controls and as the focused action after natural completion. Selecting it refreshes TMDB details and season episodes in the configured catalog language, then opens sources for the next listed episode. Numbering gaps and empty seasons are handled without assuming `episode + 1` or contiguous seasons. Regular episodes advance to the next numbered season; specials advance only within Season 0 because their chronological relationship to regular episodes is ambiguous.

No listed successor produces **No next episode is listed by TMDB**, with Refresh episodes and Back to seasons. Network/provider failures use the normal visible error and Retry flow rather than being treated as the end of a series. Leaving the catalog request cancels it. Back from the resulting source list shows that episode's season, then the series details/seasons. The user must select a source; any resume prompt belongs to the new episode's own history, never the previous episode's position. Future listed episodes may have no sources; metadata is not an availability guarantee.

Verified on WK03K: build, unit tests and lint passed, including six new resolver regression tests for numbering gaps, empty/noncontiguous seasons, series end, specials, missing season identity and error/cancellation propagation. A temporary device runner attached real Breaking Bad episode-navigation metadata to legal Sintel playback without changing the playback/history identity or downloading the series. Remote Down/Right/OK opened S1E2 sources from S1E1; actual Media3 `ENDED` retained the focused completion action, which opened S2E1 sources from S1E7. S5E16 displayed the no-next-episode state. Screenshots verified player focus, completion, exact source identities, final-episode state and Back to episodes/seasons. No autoplay occurred; native shutdown was logged. Actual series-file playback and forced live TMDB/provider failures were not exercised in this run. The temporary instrumentation was removed.

### Subtitles

During playback, press **Up** on TV or use **CC** on touch devices to select a supported embedded subtitle track, turn subtitles **Off**, or browse **Subtitles in this torrent**. **Find subtitles online** appears only when online subtitles are enabled and configured. Document/HTTPS import of one external **UTF-8 SRT / WebVTT** file remains available independently of online services. On the URL screen, use Back to dismiss the keyboard, then Down and OK to activate Load. Loading another external file replaces the previous one; position and play/pause state are retained while the media source prepares again. Document selection pauses playback; resume with OK after returning.

Files are limited to 2 MiB and validated before replacing the current subtitles. Manual HTTPS imports use 15-second connection/read timeouts, a bounded read loop, and no redirects: supply the final file URL, not a download page. Import errors appear over the video without intentionally stopping playback. Only the current external file is held in memory; it is discarded when playback stops. The selected HTTPS host receives the subtitle request and network IP.

Settings persist **Subtitle language** (English by default, with no automatic other-language fallback) and **Audio preference** (Original by default). Original audio uses TMDB's original-language metadata when available; unknown original language keeps the source's default rather than guessing English. Legacy history without that metadata remains readable. Supported embedded tracks in the selected subtitle language take precedence; otherwise one unambiguous matching torrent subtitle in that language loads automatically. Unknown-language or ambiguous files are never automatically selected.

Torrent subtitle discovery accepts SRT/VTT files up to 2 MiB, matches filenames, language suffixes and common `Subs/<video>/` layouts, and rejects contradictory episodes. The torrent menu exposes matching candidates for manual selection, including other or unknown languages. Auxiliary reads use the same verified-piece/disk-write checks and rolling-cache accounting as video, serialize with video reads, restore playback demand, and have a 20-second total deadline. A subtitle's ordinary unavailability does not intentionally stop the video.

Use **Up → Subtitle timing** to move supported embedded or imported subtitles earlier/later in 0.1-second or 1-second steps, within ±30 seconds. Positive values show subtitles later; negative values show them earlier. Select a subtitle track first, then choose **Apply** or reset to zero. Applying retains playback position, speed and play/pause state, but may briefly buffer. Corrections persist for the exact torrent hash/video file and subtitle track; external files are identified by content, not a temporary URL. Changing source, episode or subtitle content does not inherit another track's correction. Reset removes the saved correction. The atomic app-private store retains at most 128 nonzero entries; subtitle payloads remain session-only. This corrects a constant offset, not progressively drifting subtitles or a different edit.

External search is **button-only**: opening a video never calls OpenSubtitles. An explicit search can read the first/last 64 KiB to calculate a video hash, then sends that hash when available, IMDb/episode identity or a strict filename-derived query, release information, chosen language, and the network IP to OpenSubtitles. Results prioritize exact video hashes and matching release tokens, display release/file names and available SDH/FPS information, and reject mismatched language/episodes and multi-CD uploads. A hash/release match is not a universal synchronization guarantee. Choosing a result consumes the provider's download allowance; no quota-consuming POST is automatically retried. Rate limits, missing configuration and exhausted quotas appear as errors over the existing playback flow. API keys are not sent to subtitle file hosts.

**OpenSubtitles setup and distribution:** the owner/developer configures one **LumaStream application consumer key** in Settings, with **Allow anonymous downloads** enabled in the [consumer settings](https://www.opensubtitles.com/consumers). The key is masked in the UI, redacted from configuration strings and stored with existing app-private settings; do not commit it. Do not require each viewer to create an API key. The [integrator terms](https://opensubtitles.stoplight.io/docs/opensubtitles-api/branches/master/f43c0749230b3-terms-of-use-for-integrators), updated 2026-10-13, allow personal creator-owned tools but require a paid Light-or-higher consumer plan for an anonymously used public product; the free startup allowance is not a public-distribution entitlement. Account-based integration has different rules and is not implemented here. Before distributing this feature, the developer must arrange compliant application-key provisioning and the appropriate plan. The search/picker UI contains linked **Subtitles provided by OpenSubtitles.com** attribution; a public store listing must also include attribution. API access is not copyright permission; no scraping, shared subtitle mirror, bulk downloading or quota bypass is implemented. See the [API documentation](https://opensubtitles.stoplight.io/docs/opensubtitles-api/e3750fd63a100-getting-started) and [provider's quota guidance](https://forum.opensubtitles.com/t/proper-flow-and-quotas-for-integration-into-free-app/8038).

Enable **Online subtitles: OpenSubtitles** and Save settings only after application-provider setup is available. The default is Off, independently of the catalog; disabling preserves the key but blocks both online search and download. Local subtitle discovery/import and subtitle/audio language preferences remain available without either service.

The projector currently has no system document picker; use torrent subtitles, configured online search or HTTPS there, or install a compatible picker. File-picker success has not been device-verified. Embedded support depends on Media3/container support.

Device verification on WK03K: build, unit tests and lint passed. Loaded the AndroidX Media project's SRT and WebVTT test fixtures over HTTPS while playing Sintel; screenshots showed actual cues, Off removed the cue, and replacing SRT with WebVTT retained the paused position. A playing SRT import resumed at the pre-import position. An empty import displayed an error and preserved the existing cue. The earlier legacy-decoder failure was reproduced and corrected with `SubtitleExtractor`; the verified build uses extraction-time Media3 cues. Embedded-track playback and successful document-picker return were not independently exercised in this run.

Playback usability verification on WK03K: `assembleDebug`, `testDebugUnitTest` (35 tests) and `lintDebug` passed. Remote navigation exercised a real series Home card → Choose episode → seasons → a different episode's sources → Back to the original Home card, without downloading that series. Sintel playback showed separate live rate/progress/buffer/cache status and unobstructed video after dismissing CC. The timing dialog was operated with the remote. A throwaway on-device Media3 runner checked real imported SRT cues and empty intervals after paused seeks at offsets 0, +2 and −1 seconds, reset to zero, and advancing playback after Apply while playing at 1.5×. The initial runner completed cue assertions but had a cleanup reflection error; the corrected runner completed successfully. The runner was removed and existing watch history was preserved byte-for-byte. Embedded subtitle offset rendering, changing-cut/progressive-drift correction, and forced network/disk failures were not device-verified; the owner's generic IO screenshot does not establish its underlying cause.

Subtitle-discovery verification on WK03K: `assembleDebug`, `testDebugUnitTest` (72 passing tests) and `lintDebug` passed. A throwaway multi-file torrent served an AndroidX test video, separated English/Russian SRT files and a different episode's SRT through a local HTTP webseed. Foreground libtorrent/Media3 playback automatically selected and visibly rendered English; the candidate menu contained the two matching-episode subtitles. The initial device probe exposed an already-active-cue loss during subtitle replacement; the corrected build rendered the cue in the foreground player.

Remote UI checks covered the torrent candidate list, explicit online-search setup error with attribution, and saving/reloading English-only subtitles plus Original audio. Normal native shutdown was observed; the temporary app, torrent, server and smoke scaffolding were removed. Completed reports for the separate process-restart, per-track-offset and hash-byte probes were not retained across the resumed session, so those probes are not counted as confirmed device results here. Embedded-track auto-selection across diverse containers and auxiliary-read rollover under forced cache pressure were not independently device-verified in this run.

Live OpenSubtitles verification on WK03K: after privately configuring the developer-supplied consumer key, the actual player searched Sintel through **Find subtitles online (en)** and displayed two provider-reported hash matches. Selecting `sintel_en` downloaded and selected `sintel_en.srt`, retained the playback position, and rendered an English cue after seeking to 02:01. A screenshot confirmed the downloaded subtitle on the video; normal native shutdown was logged afterward. One other provider-reported hash match had visibly unrelated release/file metadata, so inspect result names rather than treating the provider's hash label as a correctness guarantee. No key or download URL was committed.

### Internet Archive search

From Home, choose **Archive**, then **Edit query** to open the keyboard with the remote. Enter a title or creator and submit the keyboard search action or choose **Search**. **Previous page** / **Next page** replace the current page; at most 20 result entries are retained. Select a result to review its creator, source URL, and reported license, then choose **Open torrent** and select a video.

Back dismisses query editing first, returns from details to the selected result, or leaves search. Leaving search, backgrounding the app, or editing the query cancels outstanding catalog requests. Search results survive returning from details or reopening search within the same activity; they are not persistent history.

Search uses a literal phrase, not user-supplied search operators. Queries are limited to 200 characters and API responses to 2 MiB; requests have a 30-second deadline and 15-second socket timeouts. Rate-limit responses surface a cooldown rather than automatic retries. Only the current page is retained, so browsing does not grow an unbounded list.

Catalog results are not promises of torrent availability: missing, restricted, oversized, and unreachable sources produce errors. Source and reported license URLs are displayed as text. Internet Archive does not guarantee uploaded rights declarations; verify permission before downloading or sharing. See its [API documentation](https://archive.org/developers/index-apis.html), [automated-access guidance](https://archive.org/developers/bots.html), [rights guidance](https://archivesupport.zendesk.com/hc/en-us/articles/360014759692-Rights), and [terms](https://archive.org/about/terms). The rendered terms, privacy and copyright policies were reviewed on 2026-10-18 (terms dated 2014-12-31): collection access is granted for scholarship and research purposes only, use must be noninfringing, service interference is prohibited, and access/content availability is not guaranteed. The Archive adapter is for uses consistent with those restrictions, not an unrestricted entertainment catalog. A reported Creative Commons license is not a guarantee of rights or permission to disregard service terms. This review is not legal clearance.

Example external opening:

```sh
adb shell am start -n dev.lumastream/.MainActivity \
  -a android.intent.action.VIEW \
  -d https://webtorrent.io/torrents/big-buck-bunny.torrent
```

The verification films [Sintel](https://durian.blender.org/about/) and [Big Buck Bunny](https://peach.blender.org/about/) are Blender Foundation open movies licensed under CC BY 3.0 and distributed by [WebTorrent](https://webtorrent.io/free-torrents). They are fetched at runtime for verification, not bundled; there is no sample button on Home.

## Storage and playback boundaries

Torrent payload storage uses a **rolling cache capped at 4 GiB by default**. Settings offers **Torrent disk cache: 1 / 2 / 4 / 8 GiB**; Save applies the choice to the next opened source. The actual budget is reduced at session start when free space is lower. A separate **1 GiB free-space reserve** and in-flight allowance remain protected. Selecting a video no longer requires enough space for the entire file. Cache accounting reserves every piece that has been requested, including incomplete/cancelled downloads, plus filesystem-block tails, directories and bookkeeping. These are disk limits, not RAM allocations.

The playback status separates **Download** (actual torrent payload rate and peers), **Downloaded** (unique verified bytes of the selected video), **Buffered** (seconds available to Media3), and **Cache** (budgeted storage). During a blocking read it also shows the required piece's completed blocks, elapsed wait, and downloading/verification/disk-write stage. Downloaded progress does not decrease after eviction, so it does not mean those bytes are still cached; the rate includes re-downloads and lookahead but excludes protocol overhead. The status hides with the controls during ready playback and appears while buffering.

**Continuous prefetch** is an optional Settings toggle, initially **Off**. Enable it and Save to accumulate selected-video data during playback, buffering and pause inside the app. Existing saved prefetch preferences are retained (the persisted `pausePrefetchEnabled` key is unchanged). The IO monitor admits low-priority batches bounded by 8 MiB / 32 pieces, while immediate video and subtitle/hash reads retain priority. Prefetch stops when the admission budget is full (with one whole piece retained as auxiliary-read headroom) or the selected video ends. It never evicts the useful forward reserve merely to download farther ahead. Playback continues consuming that reserve; downloads refill after demand-driven cache reclamation makes room. Pausing and resuming do not disable prefetch; seeks within the reserve reuse it, while unrelated seeks redirect the download window. Player errors, idle/ended playback and session shutdown disable speculative scheduling.

Playback status reports prefetch activity/fullness and **Ahead of reader**: contiguous verified selected-video bytes beyond the extractor's read cursor. This is not playback time, Media3's compressed-data buffer, or the cumulative Downloaded counter. Save persists both preferences; Back discards drafts. Leaving the app still stops the session and removes its cache; this is not background downloading.

Before admitting required pieces that would exceed the budget, LumaStream stops the native session and waits for its disk/network workers to finish. It retains the useful prefetched forward reserve when present, or the ordinary bounded forward window, plus approximately 8 MiB behind the current demand where capacity allows. Consumed lookbehind is reclaimed before useful forward reserve. It releases old physical blocks using `FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE`, then restores the session from in-memory resume data. Evicted pieces are cleared from the `have_pieces`/`verified_pieces` maps and partial-piece resume state is discarded. Native reads and eviction share one lock; no reader can observe a punched hole as verified video data. DHT state, trackers, web seeds and known peers survive the rollover.

Rollover can briefly buffer while peer connections are restored. Seeking into an evicted range downloads and verifies it again. File priorities permit real-file storage of shared boundary pieces, but only the selected video's demand/prefetch windows and explicitly requested auxiliary pieces have nonzero piece priorities; unrelated files are not downloaded in full. A startup probe verifies actual block reclamation and retained-byte integrity on the cache filesystem. Unsupported hole punching produces an explicit error rather than silently reverting to unbounded storage. Concurrent disk consumption by other applications can still trigger the reserve guard.

The cache remains session-only: normal stop, source replacement and leaving the app remove it. Persistent watch history is independent and survives this cleanup.

Ordinary demand lookahead and individual prefetch batches are limited to 8 MiB / 32 pieces, connections to 32, and Media3's target compressed-data buffer to 16 MiB. Prefetch batches can accumulate a larger disk reserve without increasing that RAM-buffer target. Torrent metadata is limited to 2 MiB, 4,096 files, 262,144 pieces, and an 8 MiB piece size. Symlinks, unsafe paths, and duplicate paths are rejected. These limits do not represent a bound on total process memory.

Magnet metadata times out after 90 seconds; torrent-document reads time out after 60 seconds. A demanded piece times out after 90 seconds without new block, verification or disk-write progress, with a five-minute absolute cap. Unrelated lookahead traffic cannot extend this wait. Concurrent readers serialize demand/wait/read so they cannot demote each other's required piece. Errors preserve the underlying source cause and distinguish missing-data timeouts from disk/hash failures; unavailable sources still require choosing another source. Containers are filtered to MP4/M4V/MKV/WebM/TS/MOV/MPG/MPEG, but codec support still depends on Media3 and the device. No video transcoding is performed.

Continuous-prefetch verification (2026-10-26): the debug build, JVM suite and debug lint passed. On the ARM64 Android phone emulator, the existing enabled preference appeared as **Continuous prefetch: On**. Actual Sintel playback reached its first frame with 922,264 of 129,241,752 bytes downloaded. While playback advanced to 01:00 without pausing, the visible status showed 3.4 MiB/s download, 121.1 MiB downloaded, 111.5 MiB ahead of the reader and 129.4 MiB cache usage under the selected 1 GiB limit. Pause/resume also transitioned successfully. This run did not fill the 1 GiB cache or renew physical-projector/audible-output verification; the earlier bounded-cache evidence below remains historical.

Pause-prefetch verification on WK03K (2026-10-26) used an isolated debug app, leaving the installed signed release and its data unchanged. A native Sintel reader filled a temporary **40 MiB** cache, accumulated **33,218,868 bytes** ahead, observed stable allocation at fullness, disabled prefetch, read a cached position at 12 MiB, then sought to 90 MiB and back to zero with byte-for-byte first-block verification. No observed allocation exceeded the limit. In the visible Media3 run, Sintel rendered its first frame with **791,192 / 129,241,752 bytes** downloaded. Remote pause accumulated more than 12 MiB of additional forward data; screenshots showed the paused prefetch status and resumed video. A seek to 300 seconds requested an unavailable piece, returned to READY, and advanced beyond 304 seconds. Normal stop shut down the native session and removed the cache.

Remote settings checks exercised the pause toggle, all four visible cache-size choices, and selecting 8 GiB; persisted preferences remained unchanged before Save, then contained the selected Off/8 GiB values after Save. Debug build, lint and all **156 JVM tests** passed. The temporary runner/application were removed. Filling an actual 8 GiB cache, competing-app low-space consumption, and audible output were not verified.

Rolling-cache verification on WK03K used a temporary **32 MiB** limit to force eviction with WebTorrent's **129,241,752-byte Sintel video**, whose torrent file offset is nonzero. A throwaway on-device reader streamed the entire file through `TorrentDataSource`, repeatedly observed allocation below the limit, then sought back to zero and compared the re-downloaded first 65,536 bytes byte-for-byte. It passed. A separate actual-Media3 run at 8× speed performed 16 evictions, sought from 603,298 ms back to zero, rendered a new first frame at zero, and subsequently reached `ENDED`; screenshots showed real video and the cache overlay, and remote pause/resume worked. The largest logged pre-eviction budgeted allocation was **33,411,072 bytes**, below the **33,554,432-byte** limit.

The player's throwaway secondary completion listener timed out waiting for completion, although production logs recorded `ENDED`, player release and native shutdown. Completion was independently confirmed from those logs, not by that observer's exit status. The smoke runners and small-limit override were removed afterward. Native block reclamation, retained-byte integrity and the full-stream backseek were exercised on the projector filesystem; unsupported-filesystem and competing-app low-space failures were not injected. No new audible-output claim is made.

The final 4 GiB build passed `assembleDebug`, `testDebugUnitTest` and `lintDebug`, including nine rolling-window/file-range regression tests. Final installation checks exposed and fixed an Activity-recreation race: cleanup from an old engine could delete a new engine's cache directory. Engines now share a serialized directory lease. Four rapid open/finish/reopen cycles then loaded metadata without errors. The final build rendered Sintel with 1,708,696 of 129,241,752 bytes available, advanced beyond 34 seconds, displayed the 4.00 GiB limit, and stopped/removed the session cache on Home.

## Implementation

`TorrentEngine` owns a single cancellable session and input loading. `NativeTorrent` owns native handles, validates metadata, selects the video, accounts admitted pieces and performs rolling eviction. `RollingPieceWindow` bounds the union of all admitted pieces, including abandoned partial requests. `SparseFiles` provides 64-bit ARM-safe hole punching and filesystem capability checks through a small JNI library. `TorrentDataSource` maps file-relative reads to global piece boundaries and delegates reads to `NativeTorrent`, which waits for hash verification **and completed disk writes**, then reads under the same lock used by eviction. Media3 uses this data source directly, not a separate HTTP server.

`MainActivity` hosts the Compose source/file screens and Media3's native video surface. Its `singleTask` launch mode routes external links to the same activity, preventing competing engines from sharing the session cache. TV keys are mapped explicitly to playback actions because Compose/native-view focus is not sufficient for reliable remote control on the target device. The player is released before torrent shutdown.

`PlaybackSubtitles` owns per-player discovery/import/selection and persisted timing restoration; asynchronous results are guarded against player/source replacement. `CompleteSubtitleMediaSource` retains the bounded in-memory subtitle timeline so importing or switching during an already-active cue does not discard it, while audio/video retain their requested playback clock. `SubtitleOffsetMediaSource` applies the selected correction on the media timeline. `SubtitleDelayStore` serializes atomic bounded updates independently of session cache cleanup.

Diagnostic logcat tags are `LumaTorrent` and `LumaPlayer`. Logs include buffering, first frames, selected tracks, download counts, seeks, errors, and playback position. Native dependency source links and licenses are bundled in [THIRD_PARTY_NOTICES.md](../app/src/main/assets/THIRD_PARTY_NOTICES.md).

## Verification on the projector

On 2026-10-17, the ARMv7 JNI library loaded and both Sintel and Big Buck Bunny played through the embedded engine. Big Buck Bunny rendered 1920×1080 H.264 using `OMX.MS.AVC.Decoder`; one measured first frame occurred with 8,126,324 of 276,134,947 video bytes downloaded. A seek from approximately 21 seconds to 3:21 requested missing pieces and returned to `READY` with playback advancing.

Remote pause, forward/backward seeking, settings navigation, and switching from AC-3 5.1 to MP3 stereo were exercised. Sintel magnet metadata was acquired from the network. Invalid torrent input produced a visible error. Screenshots and logcat were used for verification, not mocked playback. The owner confirmed audible playback through the projector's built-in speakers on 2026-10-17. This confirmation applies to the tested playback, not every possible audio codec or track.

Internet Archive search was also exercised on WK03K: query entry using the remote, real result pagination, details/source/license disclosure, and a visible restricted-item error. Searching for Sintel and selecting `https://archive.org/details/Sintel` resolved its torrent and exposed four playable video files. `Sintel/sintel-2048-stereo.mp4` rendered 2048×872 H.264 with an AAC stereo track; the first frame was logged with 6,914,557 of 282,690,045 video bytes downloaded, followed by advancing playback positions. This is a separate source from the original WebTorrent sample; audible output was not independently reconfirmed for this run.

The final build also exercised an explicit empty-results state, detail-to-result and search-to-player Back navigation, repeated catalog playback, playback stop via Back, and direct external WebTorrent input. On the repeated Archive run the first frame arrived with 3,768,829 bytes downloaded; normal Back released the player and stopped the native session, leaving 8 KiB in the app cache. Backgrounding during source opening returned to the stopped state. No forced rate-limit or offline-network fault was injected, and no fresh claim of audible output is made.

## Development and remaining product work

Use English for all documentation and code comments. Work in task branches; code and related documentation go through PRs into `main`. The only direct initialization commit was empty. PRs report checks actually performed and known limitations. Add tests for meaningful behavior and regressions, not coverage targets; device playback verification remains necessary.

Compliant public OpenSubtitles application-key provisioning remains separate from the verified private developer setup. Provider-reported seeds and resolution are not availability or decoder-compatibility guarantees. Exhaustive search across all torrents is not promised.

### Resuming in a new agent session

Start the session in the `lumastream` repository and read [AGENTS.md](../AGENTS.md). The first playback milestone is complete ([implementation PR #4](https://github.com/valdisd96/lumastream/pull/4), [owner audio confirmation PR #5](https://github.com/valdisd96/lumastream/pull/5), closed [issue #3](https://github.com/valdisd96/lumastream/issues/3)). Internet Archive search implements [issue #7](https://github.com/valdisd96/lumastream/issues/7); check current PR status before continuing. Additional catalogs still require availability/API/terms checks.

The project-local [projector-adb skill](../.agents/skills/projector-adb/SKILL.md) documents remote device operation and verification. Agents can read it directly; compatible skill-enabled clients discover it from `.agents/skills/` when starting in this repository. Local connection information is kept in ignored `.local/projector-serial`; verify that it still identifies the intended device each session.

Do not commit signing keys, tokens, personal device identifiers, or local network addresses. Use only content you are authorized to obtain and distribute. BitTorrent may upload to peers, who can see the network IP address; uninterrupted playback is not guaranteed.
