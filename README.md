# LumaStream

An Android TV application for playing torrents before the download completes, in a single APK with an embedded libtorrent engine and no mandatory external server.

## Current functionality

- Open a magnet link, an HTTPS `.torrent` URL, or a local `.torrent` through the system document picker. External `ACTION_VIEW` and `ACTION_SEND` intents are supported.
- Choose a video from a multi-file torrent; only the selected video's requested pieces and a bounded lookahead are prioritized.
- Play through Media3, pause, seek into undownloaded sections, and choose available audio tracks through the player settings.
- Display download progress, peers, buffering, and actionable input/network/storage errors.
- Stop playback and the native session when leaving the application; clean the session cache on stop or when opening another source.
- Search Internet Archive video items, browse bounded result pages, review creator/source/reported license, and open an item's torrent through the same video-selection flow.

The playback milestone and Internet Archive search have device verification. The visual TMDB catalog and Stremio torrent-source integration are implemented on the visual-catalog branch but still require credentialed end-to-end verification. History, external subtitle loading, and rolling disk-cache eviction are not implemented yet.

## Visual movie and series catalog

The initial screen separates movie/series metadata from torrent availability. Configure a personal **TMDB API Read Access Token** in Settings; this is the read token, not the shorter API key. Obtain it through your [TMDB API account settings](https://www.themoviedb.org/settings/api). Do not commit or paste credentials into issues. Settings are stored in app-private `files/catalog-config.json`; Android backup is disabled. The token is not bundled in the APK.

Search supports All / Movies / Series, poster cards, year and media type. Details show available artwork, descriptions, seasons and episodes. Source requests use the selected title's IMDb ID and, for series, its season and episode. Archive remains a separate navigation destination, not a fallback mixed into TMDB results.

Settings accept an HTTPS Stremio `/manifest.json` URL. The current default is Torrentio; its live responses were inspected for Sintel and Big Buck Bunny, but playback through this integration has not yet been verified. Only torrent `infoHash` streams are supported; HTTP video, debrid, DRM and external-player streams are not. Provider availability and permission to obtain each work are separate concerns. Metadata access is not permission to download a work.

Sources display provider-reported quality, languages, size and seeders only when available. Identity checks precede selection; duplicates are combined by hash and selected file. Actual torrent file metadata is checked before playback: contradictory filename/year/episode information or ambiguous episode files produce an error rather than selecting the largest video.

Artwork uses bounded sizes and a shared 16 MiB memory / 64 MiB disk cache. Text remains available when images fail. Settings include the official TMDB logo and required non-endorsement attribution. Review [TMDB usage requirements](https://developer.themoviedb.org/docs/faq) before distribution; commercial use requires separate agreement.

Verification for this branch: `assembleDebug`, `testDebugUnitTest` and `lintDebug` passed. Seven file-selection regression tests cover episode selection, ambiguity, contradictory metadata and torrent-index handling. The APK was installed on WK03K and its missing-token screen was observed. Real TMDB results, image rendering, series navigation and source-to-playback acceptance remain blocked on an owner-provided read token; these are not claimed as verified.

## Target device and stack

Verified on XGIMI MoGo 4 Laser (WK03K), Android TV 11 / API 30, `armeabi-v7a`, with approximately 1.65 GiB of RAM visible to the OS. The primary scenario is 1080p with built-in speakers and internal storage.

- Kotlin 2.0.21 and Compose for TV.
- AndroidX Media3 1.5.1 / ExoPlayer.
- libtorrent4j 2.1.0-39, including the ARMv7 native library.
- Gradle 8.9, Android Gradle Plugin 8.7.3, JDK 17.
- Minimum Android API 28; compile/target API 35. This APK contains only ARMv7 native binaries, not emulator or ARM64-only builds.

## Build and install

Install JDK 17 and the Android SDK command-line tools. Install SDK packages and accept their licenses:

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

Set `JAVA_HOME` to your JDK 17 installation. Set `ANDROID_HOME` to your SDK directory, or put `sdk.dir=/path/to/android-sdk` in the ignored `local.properties` file.

```sh
./gradlew assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.lumastream/.MainActivity
```

On a Homebrew-based Mac with `openjdk@17` and `android-commandlinetools`, the environment can be set with:

```sh
export JAVA_HOME="$(brew --prefix openjdk@17)"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
```

Use `adb -s DEVICE` if multiple devices are connected. Configure and authorize ADB on the TV first; never forward its port to the internet. Builds are debug-signed, not production releases. Debug keys from different machines are not interchangeable for in-place updates.

The Android GitHub Actions workflow builds and lints pull requests and `main`, and uploads a debug APK. No empty test suite is presented as behavioral verification.

## Use with a remote

Open **LumaStream** from the TV launcher. Enter a link, choose a torrent document, or select **Open Sintel sample**. When metadata is ready, focus moves to the first video. Press OK to play.

During playback:

| Key | Action |
| --- | --- |
| OK / Enter | Pause or resume |
| Left / Right | Seek backward 10 seconds / forward 30 seconds |
| Up | Show playback controls |
| Down | Open settings, including audio tracks and playback speed |
| Back | Dismiss an open menu, or stop playback and clear the session |

When editing a link, Back dismisses input focus/keyboard instead of stopping the torrent. If the TV has no document-picker application, use a link or an external file manager that grants URI read access.

### Internet Archive search

With no torrent session open, choose **Search Internet Archive**, then **Edit query** to open the keyboard with the remote. Enter a title or creator and submit the keyboard search action or choose **Search**. **Previous page** / **Next page** replace the current page; at most 20 result entries are retained. Select a result to review its creator, source URL, and reported license, then choose **Open torrent** and select a video.

Back dismisses query editing first, returns from details to the selected result, or leaves search. Leaving search, backgrounding the app, or editing the query cancels outstanding catalog requests. Search results survive returning from details or reopening search within the same activity; they are not persistent history.

Search uses a literal phrase, not user-supplied search operators. Queries are limited to 200 characters and API responses to 2 MiB; requests have a 30-second deadline and 15-second socket timeouts. Rate-limit responses surface a cooldown rather than automatic retries. Only the current page is retained, so browsing does not grow an unbounded list.

Catalog results are not promises of torrent availability: missing, restricted, oversized, and unreachable sources produce errors. Source and reported license URLs are displayed as text. Internet Archive does not guarantee uploaded rights declarations; verify permission before downloading or sharing. See its [API documentation](https://archive.org/developers/index-apis.html), [automated-access guidance](https://archive.org/developers/bots.html), [rights guidance](https://archivesupport.zendesk.com/hc/en-us/articles/360014759692-Rights), and [terms](https://archive.org/about/terms). The current terms page's body did not load during this session; full terms review remains a release prerequisite, not implied legal clearance.

Example external opening:

```sh
adb shell am start -n dev.lumastream/.MainActivity \
  -a android.intent.action.VIEW \
  -d https://webtorrent.io/torrents/big-buck-bunny.torrent
```

The sample button opens [Sintel](https://durian.blender.org/about/) by the Blender Foundation, licensed under CC BY 3.0. The film is fetched at runtime, not bundled. The additional 1080p verification sample is [Big Buck Bunny](https://peach.blender.org/about/), also a Blender Foundation open movie, distributed by [WebTorrent](https://webtorrent.io/free-torrents).

## Storage and playback boundaries

The current implementation uses normal sparse torrent storage. **It does not yet implement the proposed 4 GiB rolling cache.** Selecting a video requires enough free space for its remaining contents, boundary/lookahead allowance, and a 1 GiB reserve. Downloaded pieces remain until stop or source replacement; videos exceeding available storage are rejected rather than silently filling the device. The reserve is also checked during playback.

Lookahead is limited to 8 MiB / 32 pieces, connections to 32, and Media3's target compressed-data buffer to 16 MiB. Torrent metadata is limited to 2 MiB, 4,096 files, 262,144 pieces, and an 8 MiB piece size. Symlinks, unsafe paths, and duplicate paths are rejected. These limits do not represent a bound on total process memory.

Magnet metadata and demanded-piece waits time out after 90 seconds; torrent-document reads time out after 60 seconds. Internet sources may be unavailable or slow. Containers are filtered to MP4/M4V/MKV/WebM/TS/MOV/MPG/MPEG, but codec support still depends on Media3 and the device. No video transcoding is performed.

## Implementation

`TorrentEngine` owns a single cancellable session and input loading. `NativeTorrent` owns native handles, validates metadata, selects the video, and changes piece priorities around the requested position. `TorrentDataSource` maps file-relative reads to global torrent piece boundaries and waits for hash verification **and completed disk writes** before reading; it never returns sparse-file holes as video data. Media3 uses this data source directly, not a separate HTTP server.

`MainActivity` hosts the Compose source/file screens and Media3's native video surface. Its `singleTask` launch mode routes external links to the same activity, preventing competing engines from sharing the session cache. TV keys are mapped explicitly to playback actions because Compose/native-view focus is not sufficient for reliable remote control on the target device. The player is released before torrent shutdown.

Diagnostic logcat tags are `LumaTorrent` and `LumaPlayer`. Logs include buffering, first frames, selected tracks, download counts, seeks, errors, and playback position. Native dependency source links and licenses are bundled in [THIRD_PARTY_NOTICES.md](app/src/main/assets/THIRD_PARTY_NOTICES.md).

## Verification on the projector

On 2026-10-17, the ARMv7 JNI library loaded and both Sintel and Big Buck Bunny played through the embedded engine. Big Buck Bunny rendered 1920×1080 H.264 using `OMX.MS.AVC.Decoder`; one measured first frame occurred with 8,126,324 of 276,134,947 video bytes downloaded. A seek from approximately 21 seconds to 3:21 requested missing pieces and returned to `READY` with playback advancing.

Remote pause, forward/backward seeking, settings navigation, and switching from AC-3 5.1 to MP3 stereo were exercised. Sintel magnet metadata was acquired from the network. Invalid torrent input produced a visible error. Screenshots and logcat were used for verification, not mocked playback. The owner confirmed audible playback through the projector's built-in speakers on 2026-10-17. This confirmation applies to the tested playback, not every possible audio codec or track.

Internet Archive search was also exercised on WK03K: query entry using the remote, real result pagination, details/source/license disclosure, and a visible restricted-item error. Searching for Sintel and selecting `https://archive.org/details/Sintel` resolved its torrent and exposed four playable video files. `Sintel/sintel-2048-stereo.mp4` rendered 2048×872 H.264 with an AAC stereo track; the first frame was logged with 6,914,557 of 282,690,045 video bytes downloaded, followed by advancing playback positions. This is a separate source from the original WebTorrent sample; audible output was not independently reconfirmed for this run.

The final build also exercised an explicit empty-results state, detail-to-result and search-to-player Back navigation, repeated catalog playback, playback stop via Back, and direct external WebTorrent input. On the repeated Archive run the first frame arrived with 3,768,829 bytes downloaded; normal Back released the player and stopped the native session, leaving 8 KiB in the app cache. Backgrounding during source opening returned to the stopped state. No forced rate-limit or offline-network fault was injected, and no fresh claim of audible output is made.

## Development and remaining product work

Use English for all documentation and code comments. Work in task branches; code and related documentation go through PRs into `main`. The only direct initialization commit was empty. PRs report checks actually performed and known limitations. Add tests for meaningful behavior and regressions, not coverage targets; device playback verification remains necessary.

Next: supply the TMDB read token and complete real-device catalog-to-source-to-playback checks, including Interstellar and The Studio title identity, poster rendering and remote navigation. Then address bounded rolling storage, history and subtitles. Exhaustive search across all torrents is not promised.

### Resuming in a new agent session

Start the session in the `lumastream` repository and read [AGENTS.md](AGENTS.md). The first playback milestone is complete ([implementation PR #4](https://github.com/valdisd96/lumastream/pull/4), [owner audio confirmation PR #5](https://github.com/valdisd96/lumastream/pull/5), closed [issue #3](https://github.com/valdisd96/lumastream/issues/3)). Internet Archive search implements [issue #7](https://github.com/valdisd96/lumastream/issues/7); check current PR status before continuing. Additional catalogs still require availability/API/terms checks.

The project-local [projector-adb skill](.agents/skills/projector-adb/SKILL.md) documents remote device operation and verification. Agents can read it directly; compatible skill-enabled clients discover it from `.agents/skills/` when starting in this repository. Local connection information is kept in ignored `.local/projector-serial`; verify that it still identifies the intended device each session.

Do not commit signing keys, tokens, personal device identifiers, or local network addresses. Use only content you are authorized to obtain and distribute. BitTorrent may upload to peers, who can see the network IP address; uninterrupted playback is not guaranteed.
