# LumaStream

An Android TV application for playing torrents before the download completes, in a single APK with an embedded libtorrent engine and no mandatory external server.

## Current functionality

- Open a magnet link, an HTTPS `.torrent` URL, or a local `.torrent` through the system document picker. External `ACTION_VIEW` and `ACTION_SEND` intents are supported.
- Choose a video from a multi-file torrent; only the selected video's requested pieces and a bounded lookahead are prioritized.
- Play through Media3, pause, seek into undownloaded sections, and choose available audio tracks through the player settings.
- Display download progress, peers, buffering, and actionable input/network/storage errors.
- Stop playback and the native session when leaving the application; clean the session cache on stop or when opening another source.

This implements the first device-validation milestone. Catalog search, history, external subtitle loading, and rolling disk-cache eviction are not implemented yet. There are no search controls pretending otherwise.

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

`MainActivity` hosts the Compose source/file screens and Media3's native video surface. TV keys are mapped explicitly to playback actions because Compose/native-view focus is not sufficient for reliable remote control on the target device. The player is released before torrent shutdown.

Diagnostic logcat tags are `LumaTorrent` and `LumaPlayer`. Logs include buffering, first frames, selected tracks, download counts, seeks, errors, and playback position. Native dependency source links and licenses are bundled in [THIRD_PARTY_NOTICES.md](app/src/main/assets/THIRD_PARTY_NOTICES.md).

## Verification on the projector

On 2026-10-17, the ARMv7 JNI library loaded and both Sintel and Big Buck Bunny played through the embedded engine. Big Buck Bunny rendered 1920×1080 H.264 using `OMX.MS.AVC.Decoder`; one measured first frame occurred with 8,126,324 of 276,134,947 video bytes downloaded. A seek from approximately 21 seconds to 3:21 requested missing pieces and returned to `READY` with playback advancing.

Remote pause, forward/backward seeking, settings navigation, and switching from AC-3 5.1 to MP3 stereo were exercised. Sintel magnet metadata was acquired from the network. Invalid torrent input produced a visible error. Screenshots and logcat were used for verification, not mocked playback. Audible output from the physical speakers still needs the owner's confirmation; selecting an audio track is not proof of audible sound.

## Development and remaining product work

Use English for all documentation and code comments. Work in task branches; code and related documentation go through PRs into `main`. The only direct initialization commit was empty. PRs report checks actually performed and known limitations. Add tests for meaningful behavior and regressions, not coverage targets; device playback verification remains necessary.

Next product work: catalog adapters with permitted programmatic access, parallel search and infohash deduplication, bounded rolling storage, history, and subtitle handling. Specific catalogs remain unselected pending availability/API/terms checks; exhaustive search across all torrents is not promised. Room, DataStore, and additional networking dependencies will be added only when their features need them.

Do not commit signing keys, tokens, personal device identifiers, or local network addresses. Use only content you are authorized to obtain and distribute. BitTorrent may upload to peers, who can see the network IP address; uninterrupted playback is not guaranteed.
