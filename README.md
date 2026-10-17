# LumaStream

An Android TV application for searching torrents and playing video before the download completes, in a single APK with no mandatory external server.

## Status

The project is in preparation. Application source code and APKs are not available yet; build and installation instructions will accompany the verified implementation.

## Target device

The first target is the XGIMI MoGo 4 Laser (WK03K):

- Android TV 11, API 30.
- Application ABI: `armeabi-v7a` (32-bit ARM).
- RAM visible to the OS: approximately 1.65 GiB.
- Primary use case: 1080p, built-in speakers, and internal storage for the cache.
- Remote-control navigation, with no mandatory mouse or phone.

ABI and memory were verified through ADB. Support for specific codecs and torrent playback have not been verified yet.

## Technical direction

- Kotlin and Jetpack Compose for TV for the application and UI.
- AndroidX Media3 / ExoPlayer for playback.
- libtorrent-rasterbar through JNI as the embedded torrent engine. The Android wrapper and dependency versions have not been selected; `armeabi-v7a` support is required.
- OkHttp for network requests; Room and DataStore for history and settings as those features are implemented.

The engine must prioritize pieces requested by the player, including random reads and seeking. Simply opening an incomplete file is insufficient. The Media3 integration approach will be selected during playback validation.

## Planned behavior

- Search multiple sources that permit programmatic access, deduplicate results by infohash, and display individual source errors.
- Select specific catalogs after checking availability, APIs, and terms of use. Exhaustive search across all torrents is not promised.
- Open magnet links and local `.torrent` files independently of search.
- Select torrents and video files; support buffering, seeking, audio tracks, and subtitles.
- Maintain a bounded disk cache with cleanup and a free-space reserve. The proposed initial limit is 4 GiB, not a video size limit. Larger files will require reclaiming already watched pieces and downloading them again when seeking backward.

The first technical milestone is to play legally distributable test video through the embedded engine on the actual projector, start before the full download completes, seek to an undownloaded section, and verify audio. Search and the full TV interface follow this validation.

## Development

Use English for all project documentation and code comments.

Work on tasks in separate branches; code and documentation changes go through pull requests into `main`. The sole exception is the initial empty commit used to create the base branch.

Pull requests describe changes, checks actually performed, and known limitations. Update documentation with each implemented task. Add tests for meaningful scenarios and bugs, not merely to increase coverage. Unit tests do not replace playback verification on the device.

Do not publish signing keys, tokens, personal device identifiers, or local network addresses. Use ADB only on a trusted local network, without forwarding its port to the internet.

## Legal and network limitations

Use only content you are authorized to obtain and distribute. BitTorrent may upload downloaded data to other participants, who can see the network IP address. Playback speed depends on piece availability and transfer speed; the application does not guarantee uninterrupted playback.
