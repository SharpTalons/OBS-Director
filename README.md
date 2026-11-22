# OBS Director

Monitor and direct an OBS session from your phone while volunteers run the PC.

OBS Director is two parts:

- **The OBS plugin** (this repository's root): runs inside OBS on the streaming PC. It sends the recording and stream status, the program mix and a small live picture to your phone over the local network, takes start/stop/pause commands, writes your clips into the recording as chapter markers, and adds a dock where volunteers see your messages.
- **The Android app** ([`android/`](android)): listen to the mix, watch record and stream health, start and stop the recording, and mark clips with tags and notes for cutting short videos later.

## What the app does

- **Monitor**: the recording's file name and timecode, the mix (left and right meters, listening with about 40 ms of delay), stream health, and the live output when you switch it on (off by default, never in the background).
- **Clips**: one button. A clip starts a minute before your tap (so the build-up is in) and ends a couple of seconds after the second tap. Add tags (Key point, Funny, Story, Q&A), a typed or spoken note, and adjust the edges later on a waveform.
- **Export**: DaVinci Resolve marker EDL (clips become coloured markers spanning their length), CSV or a plain list. Clip audio can be shared as .m4a.
- **Alerts**: silent mix, clipping, recording stopped by someone else, disk nearly full, dropped frames, connection lost. The phone buzzes even when locked, and most alerts can be sent to the desk with one tap.
- **Desk**: quick and typed messages to the volunteers' dock in OBS; they answer with one tap.
- **Background**: with the screen off the mix keeps playing and clips can be started and ended from the notification. Video always stops.
- **Drops**: if Wi-Fi drops, a running clip is saved at the drop, listening pauses, and the app reconnects by itself. OBS keeps recording on the PC throughout.
- **Updates**: the app checks this repository's releases and installs new versions.

## Install

1. Download the plugin for your OS from the [latest release](https://github.com/SharpTalons/OBS-Director/releases/latest) and install it (Windows installer or zip, macOS pkg, Ubuntu deb). Needs OBS 31 or later.
2. Install `obs-director-android-<version>.apk` from the same release on your phone (Android 10 or later). Android asks you to allow installing from your browser or file manager the first time.
3. In OBS, open **Tools, OBS Director** to see the PIN. On the phone, pick the PC, enter the PIN, connect.

For clips to be written into the recording as chapters, set OBS to record in **Hybrid MP4** (Settings, Output, Recording, Recording Format).

The phone and the PC must be on the same network. Allow OBS through the Windows firewall on private networks (TCP 4466 and 4468, UDP 4467).

## Versioning

One version for the whole project, following [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

- The single source of truth is `"version"` in [`buildspec.json`](buildspec.json). The plugin's CMake build reads it, and the Android build reads the same file.
- Android `versionName` is that version; `versionCode` is `MAJOR × 10000 + MINOR × 100 + PATCH` (so `1.4.2` is `10402`).
- A release is a git tag with the same version and no prefix (`0.1.0`, or `0.2.0-beta1` for a pre-release). Pushing the tag builds the plugin for Windows, macOS and Ubuntu and the signed Android APK, and publishes a GitHub release with all of them. The app's in-app updater reads that release.
- Bump MAJOR when the [protocol](docs/PROTOCOL.md) changes in a way older apps or plugins can't follow; MINOR for new features; PATCH for fixes. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

To release: update `buildspec.json` and `CHANGELOG.md`, merge to `main`, then `git tag 0.1.0 && git push origin 0.1.0`.

## Building

**Android**: `cd android && ./gradlew assembleDebug` (JDK 17, Android SDK 35). CI builds on every push and pull request; see [`.github/workflows/android.yaml`](.github/workflows/android.yaml).

Release APKs must always be signed with the same key, or updates won't install over each other. Add these repository secrets once (Settings, Secrets and variables, Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Without them CI signs with a throwaway debug key and says so in the build log.

**Plugin**: built with [obs-plugintemplate](https://github.com/obsproject/obs-plugintemplate)'s CMake presets and workflows, for example `cmake --preset windows-x64 && cmake --build --preset windows-x64`. See the template's documentation for local setup.

## Protocol

[docs/PROTOCOL.md](docs/PROTOCOL.md) describes the discovery beacon, the control channel, the audio packets and the video stream.

## Licences

- The OBS plugin (`src/`, `cmake/`, `data/`) links against libobs and is licensed under the GNU GPL v2 or later.
- The Android app (`android/`) is under the MIT licence in [LICENSE](LICENSE).
- The app bundles Public Sans and JetBrains Mono, both under the SIL Open Font License 1.1.
- "OBS" and "OBS Studio" are trademarks of the OBS Project; this project is not affiliated with it.
