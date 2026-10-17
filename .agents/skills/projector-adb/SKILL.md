---
name: projector-adb
description: Connect to the LumaStream XGIMI projector with ADB, install and launch the APK, operate TV remote keys, capture screenshots, and verify torrent playback using device logs.
---

# Projector ADB operations

Run commands from the repository root. Read README.md for current behavior and build prerequisites. This skill describes the verified WK03K / Android TV 11 / API 30 / armeabi-v7a device, not every XGIMI firmware.

## Ownership and privacy

- Before sending keys, opening media, installing, or stopping the app, tell the owner that you will control the projector and ask them not to use the remote during that check. Do not compete with their input. Only one agent operates the device at a time; other agents can edit or inspect captured evidence.
- ADB is already installed on the development Mac. First inspect existing connections; do not reinstall tools or reset authorization without evidence that it is necessary.
- Keep addresses, device dumps, screenshots, and logs under ignored `.local/` or outside the repository. Do not commit them or publish raw dumps. Never expose ADB to the internet. No root, bootloader unlocking, factory reset, or unrelated app/data deletion is needed.
- Sound audibility requires the owner's confirmation. Selected-track logs and screenshots cannot prove sound. The owner already confirmed sound for the initial playback milestone; request another check only when relevant changes need it.

## Connect and identify

```sh
adb devices -l
# Read the existing local endpoint, if present; it may change after DHCP renewal.
export PROJECTOR_SERIAL="$(cat .local/projector-serial)"
adb -s "$PROJECTOR_SERIAL" get-state
adb -s "$PROJECTOR_SERIAL" shell getprop ro.product.model
adb -s "$PROJECTOR_SERIAL" shell getprop ro.build.version.sdk
adb -s "$PROJECTOR_SERIAL" shell getprop ro.product.cpu.abilist
```

Expected target: `WK03K`, SDK `30`, ABI list `armeabi-v7a,armeabi`. Always use `-s`; do not pick the first connected device blindly. The serial file is plain text containing one ADB endpoint, not an executable shell configuration. If it is missing, select the matching authorized device from `adb devices -l` and store its endpoint locally. If none is connected, obtain the current address from the owner/device network screen; do not scan the LAN or guess a new address.

For this firmware, enabling **USB debugging** also exposed network ADB on port `5555`; a separate Wireless debugging menu was absent. On first setup: Settings → System → About → press Android TV OS build seven times → Developer options → USB debugging. The TV and Mac must share a trusted LAN.

```sh
# PROJECTOR_SERIAL must be the verified IP:port, not a placeholder.
adb connect "$PROJECTOR_SERIAL"
```

`unauthorized`: the owner must accept **Allow debugging?** on the projector, optionally allowing this trusted computer permanently. `offline`/unreachable: check power, network, current IP, and debugging setting. Reconnect only the target endpoint; do not indiscriminately kill the shared ADB server. On firmware with Wireless debugging, use the displayed pairing endpoint with `adb pair`, then the separate connection endpoint with `adb connect`. Do not assume the ports are identical.

## Build, install, and launch

Use the README build environment (JDK 17 and Android SDK). In-place installation preserves app data; a signing mismatch is not permission to uninstall the app.

```sh
./gradlew assembleDebug lintDebug
adb -s "$PROJECTOR_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$PROJECTOR_SERIAL" shell am start -n dev.lumastream/.MainActivity
# Open the legal 1080p test torrent; this replaces the current source.
adb -s "$PROJECTOR_SERIAL" shell am start -n dev.lumastream/.MainActivity \
  -a android.intent.action.VIEW \
  -d https://webtorrent.io/torrents/big-buck-bunny.torrent
```

Home is a movie/series search screen, with no manual URL entry or sample button. For an explicit integration sample, open `https://webtorrent.io/torrents/sintel.torrent` via `ACTION_VIEW` as above. External torrent opening shows the video list; it does not itself select/play a file. Wait for metadata and inspect the screen before sending OK. The app uses `singleTask`; new external links should reuse the activity. `am start` exiting successfully does not prove playback.

For magnet links, remember that `adb shell` introduces a second shell: host quoting alone does not protect `&` in a magnet URI. Pass a shell-quoted URI to the remote shell (for example `shlex.quote(uri)` as the `-d` argument in a Python subprocess argument list); never interpolate arbitrary URI text into shell code.

## Remote keys

```sh
adb -s "$PROJECTOR_SERIAL" shell input keyevent KEYCODE_DPAD_CENTER
```

Send deliberate keys, then inspect the result instead of firing long blind sequences:

| Keycode | Playback action |
| --- | --- |
| `KEYCODE_DPAD_CENTER` | Pause/resume; activate focused CC/settings button in the bottom action row |
| `KEYCODE_DPAD_LEFT` / `KEYCODE_DPAD_RIGHT` | Seek -10 / +30 seconds; in the bottom action row select CC / settings |
| `KEYCODE_DPAD_UP` | Open subtitles; leave the bottom action row if navigating it |
| `KEYCODE_DPAD_DOWN` | Focus the bottom action row, initially settings; Left selects CC |
| `KEYCODE_BACK` | Dismiss menu/keyboard or leave the action row; otherwise stop playback and return to catalog. On catalog Home outside editing, close the activity/task. |
| `KEYCODE_HOME` | Background app; current implementation stops the torrent and cleans the session |

Menus own their own navigation. Do not interpret a key as a playback command while an audio/settings popup is open. Compose/native PlayerView focus required explicit key routing on this device; a successful `input` command is not proof that the intended action occurred.

## Screenshots

```sh
python3 .agents/skills/projector-adb/scripts/capture.py \
  --serial "$PROJECTOR_SERIAL" --output .local/projector.png
```

Open the resulting image with the agent's image-reading tool. The helper uses `adb exec-out screencap -p` and removes the firmware's observed text prefix before the PNG signature (`Lve init...` / `Create png ... fail!`). Do not decode screenshot bytes as UTF-8 or use a PTY. Inspect the actual screenshot; a saved file is not visual proof by itself. Captures may contain private input or account details.

## Logs and acceptance evidence

```sh
adb -s "$PROJECTOR_SERIAL" logcat -d -v time -s LumaTorrent:I LumaPlayer:I AndroidRuntime:E '*:S'
adb -s "$PROJECTOR_SERIAL" shell dumpsys activity top
adb -s "$PROJECTOR_SERIAL" shell df -h /data
adb -s "$PROJECTOR_SERIAL" shell run-as dev.lumastream du -sk cache
```

`run-as` requires the debug build. For live logs, omit `-d` and own/stop the capture process at the end. Do not clear the device's global log buffer. Correlate timestamps and process IDs to the current run; old errors are not new failures. On this firmware `dumpsys media.codec` was unavailable; use app/logcat decoder and track evidence instead.

For a changed playback path, record the relevant subset of these checks:

1. Metadata arrives and the intended video is focused/selected.
2. Screenshot shows real video; `first_frame` and advancing positions confirm playback. Compare `downloadedBytes` with `totalBytes` at the first frame to prove start before full download.
3. Seek beyond the downloaded region: `Seek demand ... ready=false`, followed by `READY` and advancing playback, proves recovery from missing pieces. A seek timestamp alone is insufficient.
4. Check pause/resume, audio settings navigation, and keyboard dismissal when those paths change. Reopening a source or backgrounding then opening an external link must not create competing activities/engines.
5. Stop normally and observe `Native torrent session stopped`; cache cleanup is asynchronous, so an immediate `du` reading may precede completion. Leave no unattended test torrent running.

The first milestone already has device evidence in README.md and closed issue #3. Do not rerun the entire matrix for documentation-only changes. Do not claim universal codec compatibility or a rolling cache: neither follows from these checks.

## End the session

Stop your playback through normal navigation (Back as needed, or Home), stop only your log capture, and tell the owner that remote control is theirs again. Report exactly what was observed and what remains owner-only. Do not delete the owner's files, clear app data, force-stop as a substitute for testing normal cleanup, or disconnect an ADB connection another task still uses.
