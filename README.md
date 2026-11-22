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
- **Updates**: each time the app opens it checks this repository's releases. When a newer version is out it asks once, with **Update** and **Ignore**; Update downloads it and hands it to Android's installer, keeping your clips and settings. Settings, About shows the version and a **Check for updates** button. Built to the [Design-Constants update-check spec](https://github.com/SharpTalons/Design-Constants/tree/main/plugins/design-constants/skills/update-check).

## Install

1. Download the plugin for your OS from the [latest release](https://github.com/SharpTalons/OBS-Director/releases/latest) and install it (Windows installer or zip, macOS pkg, Ubuntu deb). Needs OBS 31 or later.
2. Install `OBS-Director-<version>.apk` from the same release on your phone (Android 10 or later). Android asks you to allow installing from your browser or file manager the first time. After that, the app updates itself.
3. In OBS, open **Tools, OBS Director** to see the PIN. On the phone, pick the PC, enter the PIN, connect.

For clips to be written into the recording as chapters, set OBS to record in **Hybrid MP4** (Settings, Output, Recording, Recording Format).

The phone and the PC must be on the same network. Allow OBS through the Windows firewall on private networks (TCP 4466 and 4468, UDP 4467).

## Versioning

One version for the whole project, following [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

- The single source of truth is `"version"` in [`buildspec.json`](buildspec.json). The plugin's CMake build reads it, the Android build reads the same file, and CI derives everything else from it.
- Android `versionName` is that version; `versionCode` is `MAJOR × 10000 + MINOR × 100 + PATCH` (so `1.4.2` is `10402`), so it always goes up with the version.
- Bump MAJOR when the [protocol](docs/PROTOCOL.md) changes in a way older apps or plugins can't follow, MINOR for new features, PATCH for fixes. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

| Thing | Value |
| --- | --- |
| Release tag | `v<version>`, for example `v0.1.0` |
| Release title | `OBS Director <version>` |
| App | `OBS-Director-<version>.apk` |
| Plugin | `OBS Director-<version>-windows-x64` (installer and zip), `-macos-universal.pkg`, `-x86_64-linux-gnu.deb` |
| Marked latest | every release; a pre-release (`0.2.0-beta1`) never is |

**To release:** change `"version"` in `buildspec.json`, add its section to `CHANGELOG.md`, and merge to `main`. The push to `main` builds everything and publishes `v<version>` with the plugin and the app, marked latest; the app on everyone's phone then offers it. Pushing `main` again without a bump replaces that release's files. A pre-release is published as a pre-release, so the update check never offers it.

## Building

**Android**: `cd android && ./gradlew assembleDebug` (JDK 17, Android SDK 35). CI builds on every push and pull request; see [`.github/workflows/android.yaml`](.github/workflows/android.yaml).

Release APKs must always be signed with the same key, or updates won't install over each other. Add these repository secrets once (Settings, Secrets and variables, Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Pull requests build without them, as a debug app (`dev.sharptalons.obsdirector.debug`) that installs beside the real one. A push to `main` refuses to publish without them: an APK signed with any other key would not install over earlier versions, and every in-app update would fail.

To make the key once (keep the file and passwords somewhere safe; losing them means everyone has to uninstall to get updates):

```bash
keytool -genkeypair -v -keystore obs-director.jks -alias obs-director -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 obs-director.jks   # paste the output into ANDROID_KEYSTORE_BASE64
```

**Plugin**: built with [obs-plugintemplate](https://github.com/obsproject/obs-plugintemplate)'s CMake presets and workflows, for example `cmake --preset windows-x64 && cmake --build --preset windows-x64`. See the template's documentation for local setup.

## Protocol

[docs/PROTOCOL.md](docs/PROTOCOL.md) describes the discovery beacon, the control channel, the audio packets and the video stream.

## Licences

- The OBS plugin (`src/`, `cmake/`, `data/`) links against libobs and is licensed under the GNU GPL v2 or later.
- The Android app (`android/`) is under the MIT licence in [LICENSE](LICENSE).
- The app bundles Public Sans and JetBrains Mono, both under the SIL Open Font License 1.1.
- "OBS" and "OBS Studio" are trademarks of the OBS Project; this project is not affiliated with it.
