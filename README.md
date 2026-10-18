# LumaStream

An Android TV application for playing torrents before the download completes, in a single APK with an embedded libtorrent engine and no mandatory external server.

## Download

The first signed preview is [v0.1.0](https://github.com/valdisd96/lumastream/releases/tag/v0.1.0). Download `lumastream-0.1.0-armeabi-v7a.apk` and `SHA256SUMS` from its assets. It requires Android TV 9 / API 28 or later with ARMv7 support; ARM64-only devices and x86 emulators are not supported. GitHub Packages is not used.

Verify the download with `shasum -a 256 -c SHA256SUMS`, then sideload with `adb -s DEVICE install -r lumastream-0.1.0-armeabi-v7a.apk`, or transfer it to the TV and use an APK installer with installation from that source enabled. Configure your own TMDB API Read Access Token in Settings; no token or media is bundled.

The release certificate differs from development debug keys. Android cannot update a debug installation with this APK in place. Uninstalling the debug app removes its settings and watch history; do not uninstall it unless you accept that loss. Future release updates must retain the release signing key and increase `versionCode`.

## Current functionality

- Search movies and series from a search-first Home screen, choose a title/episode and a source, and play without entering or editing torrent URLs. External `ACTION_VIEW` and `ACTION_SEND` torrent inputs remain supported for file managers and integration checks; the manual-link form, torrent-picker button and Sintel demo button have been removed.
- Choose a video from a multi-file torrent; only the selected video's requested pieces and a bounded lookahead are prioritized.
- Play through Media3, pause, seek into undownloaded sections, and choose available audio tracks through the player settings.
- Display download progress, peers, buffering, and actionable input/network/storage errors.
- Stop playback and the native session when leaving the application; clean the session cache on stop or when opening another source.
- Search Internet Archive video items, browse bounded result pages, review creator/source/reported license, and open an item's torrent through the same video-selection flow.

The playback milestone, Internet Archive search, visual TMDB catalog and catalog watch history have device verification. Torrent storage uses a bounded rolling cache rather than requiring free space for the full selected video.

## Visual movie and series catalog

The initial screen separates movie/series metadata from torrent availability. Configure a personal **TMDB API Read Access Token** in Settings; this is the read token, not the shorter API key. Obtain it through your [TMDB API account settings](https://www.themoviedb.org/settings/api). Do not commit or paste credentials into issues. Settings are stored in app-private `files/catalog-config.json`; Android backup is disabled. The token is not bundled in the APK.

Search supports All / Movies / Series, poster cards, year and media type. Details show available artwork, descriptions, seasons and episodes. Source requests use the selected title's IMDb ID and, for series, its season and episode. Archive remains a separate navigation destination, not a fallback mixed into TMDB results.

Settings include **Catalog language**, defaulting to **English (en-US)** for both new installs and existing configurations without a language field. Choose English, Russian, Ukrainian, German, French, Spanish, Italian, Portuguese (Brazil), Japanese, Korean or Chinese (Simplified), then **Save settings**. This saved choice controls TMDB search results, details, seasons and episode metadata, independently of the search query's language, device locale, audio and subtitles. Changing it clears the current catalog results so languages are not mixed; TMDB translation availability still varies by title.

The selected filter remains active when editing a query. Use All or Movies to find Interstellar (2014); Series deliberately excludes films.

Search-first Home verification on WK03K: build, unit tests and lint passed. An existing configuration without a language field produced English Interstellar results and an English synopsis. Selecting Russian with the remote, saving, and reinstalling/relaunching preserved the language and produced Russian results/overview for the same English query. Token and provider settings were unchanged; the device was returned to English. Screenshots verified Home, focused search, readable language options and complete result-card labels. Back exited Home to the TV launcher; during editing, the projector keyboard consumed the first Back, the next closed editing, and Home Back exited.

The final build also exercised Home search → Sintel details → source selection → real playback. The first frame arrived with 3,063,808 of 143,100,616 bytes downloaded; a screenshot showed video and playback positions advanced. Back stopped the native session and restored catalog source selection; subsequent Back presses returned through details/results and exited to the launcher. No renewed audible-sound claim is made.

Settings accept an HTTPS Stremio `/manifest.json` URL. The current default is Torrentio; live source lists were exercised on the projector for Sintel, Big Buck Bunny and The Studio S1E2, and catalog-selected Sintel playback was verified. Only torrent `infoHash` streams are supported; HTTP video, debrid, DRM and external-player streams are not. Provider availability and permission to obtain each work are separate concerns. Metadata access is not permission to download a work.

Sources display provider-reported quality, languages, size and seeders only when available. Identity checks precede selection; duplicates are combined by hash and selected file. Actual torrent file metadata is checked before playback: contradictory filename/year/episode information or ambiguous episode files produce an error rather than selecting the largest video.

For catalog sources without provider-supplied trackers, magnets include `udp://tracker.opentrackr.org:1337/announce` and `udp://open.demonii.com:1337/announce` alongside normal DHT discovery. Explicit provider tracker lists are preserved instead of supplemented. These public trackers receive the torrent hash and network IP; they are peer-discovery services, not playback servers. Externally supplied magnets and private torrent documents are not modified. The validated hash uses the conventional readable `xt=urn:btih:` form; percent-encoded colons were also verified to parse correctly in libtorrent.

Artwork uses bounded sizes and a shared 16 MiB memory / 64 MiB disk cache. Text remains available when images fail. Settings include the official TMDB logo and required non-endorsement attribution. Review [TMDB usage requirements](https://developer.themoviedb.org/docs/faq) before distribution; commercial use requires separate agreement.

Verification on WK03K: `assembleDebug`, `testDebugUnitTest` and `lintDebug` passed; seven file-selection regression tests cover episode identity, ambiguity, contradictory metadata and torrent-index handling. With the owner's private token, real Interstellar and The Studio searches, localized posters, film details, series filtering, an empty filtered result, seasons, episode stills/dates, Back navigation and The Studio S1E2 source identity were exercised. Source listing for that series was checked without downloading it. Device checks exposed and fixed clipped card labels and a movie source-action button skipped by D-pad navigation; both fixes were exercised on the installed build.

Initial catalog playback attempts exposed metadata timeouts, a torrent without supported video files, and a 3D AVC 1920×2160 source exceeding the projector's decoder capabilities. Later native diagnostics corrected the earlier assumption that all metadata timeouts meant dead sources: DHT was running but could find no peer for an owner-reported hash; adding a public tracker to the same hash resolved its ten-file metadata in approximately five seconds. That diagnostic did not download its videos.

After the tracker correction, the previously timing-out Sintel 720p source was opened through the real TMDB → details → Torrentio source → native player flow on WK03K. Media3 logged `first_frame` with 2,228,224 bytes downloaded out of 143,100,616; a screenshot showed real video and playback positions advanced. Back stopped playback. This verifies catalog-source playback before full download, not universal source availability, codec compatibility or renewed audible-sound confirmation.

## Target device and stack

Verified on XGIMI MoGo 4 Laser (WK03K), Android TV 11 / API 30, `armeabi-v7a`, with approximately 1.65 GiB of RAM visible to the OS. The primary scenario is 1080p with built-in speakers and internal storage.

- Kotlin 2.0.21 and Compose for TV.
- AndroidX Media3 1.5.1 / ExoPlayer.
- libtorrent4j 2.1.0-39, including the ARMv7 native library.
- Gradle 8.9, Android Gradle Plugin 8.7.3, JDK 17.
- Android NDK 27.0.12077973 and CMake 3.22.1 for the small sparse-storage JNI library.
- Minimum Android API 28; compile/target API 35. This APK contains only ARMv7 native binaries, not emulator or ARM64-only builds.

## Build and install

Install JDK 17 and the Android SDK command-line tools. Install SDK packages and accept their licenses:

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.0.12077973" "cmake;3.22.1"
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

Use `adb -s DEVICE` if multiple devices are connected. Configure and authorize ADB on the TV first; never forward its port to the internet. `assembleDebug` produces debug-signed development builds, not the signed APK distributed through Releases. Debug keys from different machines are not interchangeable for in-place updates.

The Android GitHub Actions workflow builds and lints pull requests and `main`, and uploads a debug APK. No empty test suite is presented as behavioral verification.

### Signing a release

Release signing is performed locally with Android SDK tools; Gradle's `assembleRelease` output is intentionally unsigned. The maintainer's persistent PKCS#12 key (`release.p12`, alias `lumastream`) and password file (`store-password`) are stored outside the repository in `$HOME/.config/lumastream/signing/`, with directory mode `700` and file mode `600`. Keep an encrypted offline backup of both; losing the key prevents compatible updates. Never commit or upload either file as a release asset, and never generate a replacement key for a routine update.

With `JAVA_HOME` and `ANDROID_HOME` configured as above:

```sh
./gradlew assembleRelease testReleaseUnitTest lintRelease
TOOLS="$ANDROID_HOME/build-tools/35.0.0"
SIGNING="$HOME/.config/lumastream/signing"
OUT=".local/release-v0.1.0"
mkdir -p "$OUT"
"$TOOLS/zipalign" -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$OUT/aligned.apk"
"$TOOLS/apksigner" sign \
  --ks "$SIGNING/release.p12" --ks-key-alias lumastream \
  --ks-pass "file:$SIGNING/store-password" \
  --out "$OUT/lumastream-0.1.0-armeabi-v7a.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify --verbose --print-certs "$OUT/lumastream-0.1.0-armeabi-v7a.apk"
"$TOOLS/zipalign" -c 4 "$OUT/lumastream-0.1.0-armeabi-v7a.apk"
(cd "$OUT" && shasum -a 256 lumastream-0.1.0-armeabi-v7a.apk > SHA256SUMS)
```

Release certificate SHA-256: `c113d8eb54f1eb4cdfe327612236e7dbd7cedce27ab9235a4f4938ad01e35f70`.

For v0.1.0, `assembleRelease`, all 24 release unit tests and `lintRelease` passed. The distribution APK passed signature/alignment checks, contains only ARMv7 native libraries and bundled third-party notices, is not debuggable, and was checked for absence of the owner's private TMDB token. On WK03K, a separately built release-mode copy signed with the same key and temporary application ID `dev.lumastream.releasesmoke` exercised fresh Home, external Sintel input, video selection, actual video rendering, advancing playback and normal native-session shutdown. First frame arrived with 2,626,200 of 129,241,752 bytes downloaded; playback reached 29,261 ms before stopping. The temporary app was removed without replacing the owner's debug installation. This checks release-mode runtime behavior, not installation of the exact distribution APK over a differently signed app; no new audible-sound claim is made.

## Use with a remote

Open **LumaStream** from the TV launcher. Home presents one prominent **Search movies and series** control, All / Movies / Series filters, and secondary Archive / Settings buttons. Press OK on Search to type a title, then submit the keyboard search action or the Search button. Choose a poster, a season/episode for series, and a source. There are no editable torrent URLs or demo buttons in this flow.

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

Home shows **Continue watching · Watched** after catalog playback renders its first frame. Cards show the poster, title, movie year or series season/episode, saved position, duration and progress. Each series has one Home card for its most recently watched episode; positions remain separate for individual episodes. Search results replace this row while browsing.

Select a card with the remote to **Continue**, **Start from beginning**, or **Choose another source**. Direct resume reopens the saved torrent and exact selected file without repeating TMDB search. Another source is looked up for the same title/episode; transferring the saved position requires an explicit choice because different cuts can have different timing. Finished videos remain visible as **Watched** and offer restart rather than resume.

Series history cards also offer **Choose episode**: open the title's seasons, select an episode, then choose its source without searching again. Details and episodes refresh in the configured catalog language with the normal Retry flow. Back returns through episodes and seasons to the originating Home card. Continue still resumes the saved episode; choosing another episode uses that episode's separate saved position.

Positions are saved every ten seconds while playing, on pause/seek, and before releasing playback on exit. Unexpected process termination can lose progress since the last completed write. Failed preparation does not replace an existing resume point. History is an atomic app-private `files/watch-history.json` file containing at most 100 recent movie/episode records, cached catalog metadata and source information, but no catalog token. Read/write failures are reported on Home; unreadable history is not silently overwritten. External torrent intents and Archive playback do not create catalog history.

History does not retain downloaded video: leaving still stops the native session and removes its cache. Resume may buffer while pieces are downloaded again; unavailable sources still require choosing another source.

Catalog opening/playback failures now offer a remote-focused **Choose another source** button alongside the error cause. It reloads sources for the same movie or exact episode, retaining the existing catalog Back path; recovery from a direct history resume returns to Home. The replacement confirmation explicitly offers position transfer or restart because release timing may differ. A failure before the first frame preserves the requested resume position; a playback failure captures the current position before releasing the player. External torrent inputs have no catalog identity and retain the normal Back-to-search action.

Recovery verification on WK03K: build, unit tests and lint passed. A throwaway device runner opened an invalid-hash catalog source with a requested position of `123000` ms, activated the focused recovery button via the remote dispatcher, and displayed real Sintel source results. The replacement confirmation retained `123000` ms; selecting the legal WebTorrent Sintel source through the production model rendered its first frame at exactly `123000` ms with 3,543,704 of 129,241,752 bytes downloaded, then advanced beyond 133 seconds. Screenshots confirmed the error, sources, transfer dialog, video and Home after Back; logs confirmed native shutdown. Forced mid-playback network/decoder failures and series recovery were not exercised in this smoke run. The temporary instrumentation was removed.

Verified on WK03K: `assembleDebug`, `testDebugUnitTest` and `lintDebug` passed, including eight history regression tests. Catalog-selected Sintel rendered, was sought/paused and backgrounded, and persisted position `53481` ms. After terminating the already-backgrounded process and relaunching, Home displayed the saved card; Continue rendered its first frame at exactly `53481` ms and advanced beyond 62 seconds. Start from beginning rendered at `0` ms. History's alternate-source action loaded Sintel sources and selecting another source displayed the explicit transfer warning. Seeking to the end produced `ENDED`, stopped the native session, and displayed **Watched** on Home. Screenshots verified the Home card, resume dialog, transfer warning, resumed video and finished card. Episode identity/roundtrip are unit-tested; live series resume and audible output were not independently verified in this run.

### Next episode

Catalog series playback offers **Next episode** both in the player controls and as the focused action after natural completion. Selecting it refreshes TMDB details and season episodes in the configured catalog language, then opens sources for the next listed episode. Numbering gaps and empty seasons are handled without assuming `episode + 1` or contiguous seasons. Regular episodes advance to the next numbered season; specials advance only within Season 0 because their chronological relationship to regular episodes is ambiguous.

No listed successor produces **No next episode is listed by TMDB**, with Refresh episodes and Back to seasons. Network/provider failures use the normal visible error and Retry flow rather than being treated as the end of a series. Leaving the catalog request cancels it. Back from the resulting source list shows that episode's season, then the series details/seasons. The user must select a source; any resume prompt belongs to the new episode's own history, never the previous episode's position. Future listed episodes may have no sources; metadata is not an availability guarantee.

Verified on WK03K: build, unit tests and lint passed, including six new resolver regression tests for numbering gaps, empty/noncontiguous seasons, series end, specials, missing season identity and error/cancellation propagation. A temporary device runner attached real Breaking Bad episode-navigation metadata to legal Sintel playback without changing the playback/history identity or downloading the series. Remote Down/Right/OK opened S1E2 sources from S1E1; actual Media3 `ENDED` retained the focused completion action, which opened S2E1 sources from S1E7. S5E16 displayed the no-next-episode state. Screenshots verified player focus, completion, exact source identities, final-episode state and Back to episodes/seasons. No autoplay occurred; native shutdown was logged. Actual series-file playback and forced live TMDB/provider failures were not exercised in this run. The temporary instrumentation was removed.

### Subtitles

During playback, press **Up** to select a supported embedded subtitle track, turn subtitles **Off**, browse **Subtitles in this torrent**, or **Find subtitles online**. Document/HTTPS import of one external **UTF-8 SRT / WebVTT** file remains available. On the URL screen, use Back to dismiss the keyboard, then Down and OK to activate Load. Loading another external file replaces the previous one; position and play/pause state are retained while the media source prepares again. Document selection pauses playback; resume with OK after returning.

Files are limited to 2 MiB and validated before replacing the current subtitles. Manual HTTPS imports use 15-second connection/read timeouts, a bounded read loop, and no redirects: supply the final file URL, not a download page. Import errors appear over the video without intentionally stopping playback. Only the current external file is held in memory; it is discarded when playback stops. The selected HTTPS host receives the subtitle request and network IP.

Settings persist **Subtitle language** (English by default, with no automatic other-language fallback) and **Audio preference** (Original by default). Original audio uses TMDB's original-language metadata when available; unknown original language keeps the source's default rather than guessing English. Legacy history without that metadata remains readable. Supported embedded tracks in the selected subtitle language take precedence; otherwise one unambiguous matching torrent subtitle in that language loads automatically. Unknown-language or ambiguous files are never automatically selected.

Torrent subtitle discovery accepts SRT/VTT files up to 2 MiB, matches filenames, language suffixes and common `Subs/<video>/` layouts, and rejects contradictory episodes. The torrent menu exposes matching candidates for manual selection, including other or unknown languages. Auxiliary reads use the same verified-piece/disk-write checks and rolling-cache accounting as video, serialize with video reads, restore playback demand, and have a 20-second total deadline. A subtitle's ordinary unavailability does not intentionally stop the video.

Use **Up → Subtitle timing** to move supported embedded or imported subtitles earlier/later in 0.1-second or 1-second steps, within ±30 seconds. Positive values show subtitles later; negative values show them earlier. Select a subtitle track first, then choose **Apply** or reset to zero. Applying retains playback position, speed and play/pause state, but may briefly buffer. Corrections persist for the exact torrent hash/video file and subtitle track; external files are identified by content, not a temporary URL. Changing source, episode or subtitle content does not inherit another track's correction. Reset removes the saved correction. The atomic app-private store retains at most 128 nonzero entries; subtitle payloads remain session-only. This corrects a constant offset, not progressively drifting subtitles or a different edit.

External search is **button-only**: opening a video never calls OpenSubtitles. An explicit search can read the first/last 64 KiB to calculate a video hash, then sends that hash when available, IMDb/episode identity or a strict filename-derived query, release information, chosen language, and the network IP to OpenSubtitles. Results prioritize exact video hashes and matching release tokens, display release/file names and available SDH/FPS information, and reject mismatched language/episodes and multi-CD uploads. A hash/release match is not a universal synchronization guarantee. Choosing a result consumes the provider's download allowance; no quota-consuming POST is automatically retried. Rate limits, missing configuration and exhausted quotas appear as errors over the existing playback flow. API keys are not sent to subtitle file hosts.

**OpenSubtitles setup and distribution:** the owner/developer configures one **LumaStream application consumer key** in Settings, with **Allow anonymous downloads** enabled in the [consumer settings](https://www.opensubtitles.com/consumers). The key is masked in the UI, redacted from configuration strings and stored with existing app-private settings; do not commit it. Do not require each viewer to create an API key. The [integrator terms](https://opensubtitles.stoplight.io/docs/opensubtitles-api/branches/master/f43c0749230b3-terms-of-use-for-integrators), updated 2026-10-13, allow personal creator-owned tools but require a paid Light-or-higher consumer plan for an anonymously used public product; the free startup allowance is not a public-distribution entitlement. Account-based integration has different rules and is not implemented here. Before distributing this feature, the developer must arrange compliant application-key provisioning and the appropriate plan. The search/picker UI contains linked **Subtitles provided by OpenSubtitles.com** attribution; a public store listing must also include attribution. API access is not copyright permission; no scraping, shared subtitle mirror, bulk downloading or quota bypass is implemented. See the [API documentation](https://opensubtitles.stoplight.io/docs/opensubtitles-api/e3750fd63a100-getting-started) and [provider's quota guidance](https://forum.opensubtitles.com/t/proper-flow-and-quotas-for-integration-into-free-app/8038).

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

Torrent payload storage uses a **rolling cache capped at 4 GiB**, reduced at session start when free space is lower. A separate **1 GiB free-space reserve** and in-flight allowance remain protected. Selecting a video no longer requires enough space for the entire file. Cache accounting reserves every piece that has been requested, including incomplete/cancelled downloads, plus filesystem-block tails, directories and bookkeeping. The player displays budgeted cache use and its limit; the allowance means this can exceed the payload's actual allocated bytes.

The playback status separates **Download** (actual torrent payload rate and peers), **Downloaded** (unique verified bytes of the selected video), **Buffered** (seconds available to Media3), and **Cache** (budgeted storage). During a blocking read it also shows the required piece's completed blocks, elapsed wait, and downloading/verification/disk-write stage. Downloaded progress does not decrease after eviction, so it does not mean those bytes are still cached; the rate includes re-downloads and lookahead but excludes protocol overhead. The status hides with the controls during ready playback and appears while buffering.

Before admitting pieces that would exceed the budget, LumaStream stops the native session and waits for its disk/network workers to finish. It keeps a piece-aligned neighborhood of approximately 8 MiB behind the current demand and the bounded forward window, releases old physical blocks using `FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE`, then restores the session from in-memory resume data. Evicted pieces are cleared from the `have_pieces`/`verified_pieces` maps and partial-piece resume state is discarded. Native reads and eviction share one lock; no reader can observe a punched hole as verified video data. DHT state, trackers, web seeds and known peers survive the rollover. This uses libtorrent's documented [resume parameters](https://www.libtorrent.org/reference-Add_Torrent.html#add_torrent_params), not a fabricated complete-piece map or a full-torrent recheck.

Rollover can briefly buffer while peer connections are restored. Seeking into an evicted range downloads and verifies it again. File priorities permit real-file storage of shared boundary pieces, but only the selected video's demand window has nonzero piece priorities; unrelated files are not downloaded in full. A startup probe verifies actual block reclamation and retained-byte integrity on the cache filesystem. Unsupported hole punching produces an explicit error rather than silently reverting to unbounded storage. Concurrent disk consumption by other applications can still trigger the reserve guard.

The cache remains session-only: normal stop, source replacement and leaving the app remove it. Persistent watch history is independent and survives this cleanup.

Lookahead is limited to 8 MiB / 32 pieces, connections to 32, and Media3's target compressed-data buffer to 16 MiB. Torrent metadata is limited to 2 MiB, 4,096 files, 262,144 pieces, and an 8 MiB piece size. Symlinks, unsafe paths, and duplicate paths are rejected. These limits do not represent a bound on total process memory.

Magnet metadata times out after 90 seconds; torrent-document reads time out after 60 seconds. A demanded piece times out after 90 seconds without new block, verification or disk-write progress, with a five-minute absolute cap. Unrelated lookahead traffic cannot extend this wait. Concurrent readers serialize demand/wait/read so they cannot demote each other's required piece. Errors preserve the underlying source cause and distinguish missing-data timeouts from disk/hash failures; unavailable sources still require choosing another source. Containers are filtered to MP4/M4V/MKV/WebM/TS/MOV/MPG/MPEG, but codec support still depends on Media3 and the device. No video transcoding is performed.

Rolling-cache verification on WK03K used a temporary **32 MiB** limit to force eviction with WebTorrent's **129,241,752-byte Sintel video**, whose torrent file offset is nonzero. A throwaway on-device reader streamed the entire file through `TorrentDataSource`, repeatedly observed allocation below the limit, then sought back to zero and compared the re-downloaded first 65,536 bytes byte-for-byte. It passed. A separate actual-Media3 run at 8× speed performed 16 evictions, sought from 603,298 ms back to zero, rendered a new first frame at zero, and subsequently reached `ENDED`; screenshots showed real video and the cache overlay, and remote pause/resume worked. The largest logged pre-eviction budgeted allocation was **33,411,072 bytes**, below the **33,554,432-byte** limit.

The player's throwaway secondary completion listener timed out waiting for completion, although production logs recorded `ENDED`, player release and native shutdown. Completion was independently confirmed from those logs, not by that observer's exit status. The smoke runners and small-limit override were removed afterward. Native block reclamation, retained-byte integrity and the full-stream backseek were exercised on the projector filesystem; unsupported-filesystem and competing-app low-space failures were not injected. No new audible-output claim is made.

The final 4 GiB build passed `assembleDebug`, `testDebugUnitTest` and `lintDebug`, including nine rolling-window/file-range regression tests. Final installation checks exposed and fixed an Activity-recreation race: cleanup from an old engine could delete a new engine's cache directory. Engines now share a serialized directory lease. Four rapid open/finish/reopen cycles then loaded metadata without errors. The final build rendered Sintel with 1,708,696 of 129,241,752 bytes available, advanced beyond 34 seconds, displayed the 4.00 GiB limit, and stopped/removed the session cache on Home.

## Implementation

`TorrentEngine` owns a single cancellable session and input loading. `NativeTorrent` owns native handles, validates metadata, selects the video, accounts admitted pieces and performs rolling eviction. `RollingPieceWindow` bounds the union of all admitted pieces, including abandoned partial requests. `SparseFiles` provides 64-bit ARM-safe hole punching and filesystem capability checks through a small JNI library. `TorrentDataSource` maps file-relative reads to global piece boundaries and delegates reads to `NativeTorrent`, which waits for hash verification **and completed disk writes**, then reads under the same lock used by eviction. Media3 uses this data source directly, not a separate HTTP server.

`MainActivity` hosts the Compose source/file screens and Media3's native video surface. Its `singleTask` launch mode routes external links to the same activity, preventing competing engines from sharing the session cache. TV keys are mapped explicitly to playback actions because Compose/native-view focus is not sufficient for reliable remote control on the target device. The player is released before torrent shutdown.

`PlaybackSubtitles` owns per-player discovery/import/selection and persisted timing restoration; asynchronous results are guarded against player/source replacement. `CompleteSubtitleMediaSource` retains the bounded in-memory subtitle timeline so importing or switching during an already-active cue does not discard it, while audio/video retain their requested playback clock. `SubtitleOffsetMediaSource` applies the selected correction on the media timeline. `SubtitleDelayStore` serializes atomic bounded updates independently of session cache cleanup.

Diagnostic logcat tags are `LumaTorrent` and `LumaPlayer`. Logs include buffering, first frames, selected tracks, download counts, seeks, errors, and playback position. Native dependency source links and licenses are bundled in [THIRD_PARTY_NOTICES.md](app/src/main/assets/THIRD_PARTY_NOTICES.md).

## Verification on the projector

On 2026-10-17, the ARMv7 JNI library loaded and both Sintel and Big Buck Bunny played through the embedded engine. Big Buck Bunny rendered 1920×1080 H.264 using `OMX.MS.AVC.Decoder`; one measured first frame occurred with 8,126,324 of 276,134,947 video bytes downloaded. A seek from approximately 21 seconds to 3:21 requested missing pieces and returned to `READY` with playback advancing.

Remote pause, forward/backward seeking, settings navigation, and switching from AC-3 5.1 to MP3 stereo were exercised. Sintel magnet metadata was acquired from the network. Invalid torrent input produced a visible error. Screenshots and logcat were used for verification, not mocked playback. The owner confirmed audible playback through the projector's built-in speakers on 2026-10-17. This confirmation applies to the tested playback, not every possible audio codec or track.

Internet Archive search was also exercised on WK03K: query entry using the remote, real result pagination, details/source/license disclosure, and a visible restricted-item error. Searching for Sintel and selecting `https://archive.org/details/Sintel` resolved its torrent and exposed four playable video files. `Sintel/sintel-2048-stereo.mp4` rendered 2048×872 H.264 with an AAC stereo track; the first frame was logged with 6,914,557 of 282,690,045 video bytes downloaded, followed by advancing playback positions. This is a separate source from the original WebTorrent sample; audible output was not independently reconfirmed for this run.

The final build also exercised an explicit empty-results state, detail-to-result and search-to-player Back navigation, repeated catalog playback, playback stop via Back, and direct external WebTorrent input. On the repeated Archive run the first frame arrived with 3,768,829 bytes downloaded; normal Back released the player and stopped the native session, leaving 8 KiB in the app cache. Backgrounding during source opening returned to the stopped state. No forced rate-limit or offline-network fault was injected, and no fresh claim of audible output is made.

## Development and remaining product work

Use English for all documentation and code comments. Work in task branches; code and related documentation go through PRs into `main`. The only direct initialization commit was empty. PRs report checks actually performed and known limitations. Add tests for meaningful behavior and regressions, not coverage targets; device playback verification remains necessary.

Compliant public OpenSubtitles application-key provisioning remains separate from the verified private developer setup. Provider-reported seeds and resolution are not availability or decoder-compatibility guarantees. Exhaustive search across all torrents is not promised.

### Resuming in a new agent session

Start the session in the `lumastream` repository and read [AGENTS.md](AGENTS.md). The first playback milestone is complete ([implementation PR #4](https://github.com/valdisd96/lumastream/pull/4), [owner audio confirmation PR #5](https://github.com/valdisd96/lumastream/pull/5), closed [issue #3](https://github.com/valdisd96/lumastream/issues/3)). Internet Archive search implements [issue #7](https://github.com/valdisd96/lumastream/issues/7); check current PR status before continuing. Additional catalogs still require availability/API/terms checks.

The project-local [projector-adb skill](.agents/skills/projector-adb/SKILL.md) documents remote device operation and verification. Agents can read it directly; compatible skill-enabled clients discover it from `.agents/skills/` when starting in this repository. Local connection information is kept in ignored `.local/projector-serial`; verify that it still identifies the intended device each session.

Do not commit signing keys, tokens, personal device identifiers, or local network addresses. Use only content you are authorized to obtain and distribute. BitTorrent may upload to peers, who can see the network IP address; uninterrupted playback is not guaranteed.
