# Changelog

All notable changes to OBS Director (plugin and Android app). The project follows
[Semantic Versioning](https://semver.org) and the format of [Keep a Changelog](https://keepachangelog.com).

## [Unreleased]

### Security
- Plugin: wrong PINs are slowed down per device (three free tries, then 2 s doubling up to 5 minutes), unpaired connections are capped at eight, and the PIN is compared in constant time.
- Plugin: the status sends the recording's file name only, never its full path, and never falls back to an output URL.
- Plugin: messages and device names from phones are shown in the dock as plain text.

### Docs
- Step-by-step setup in the README, including firewall and troubleshooting; release and signing notes moved to docs/RELEASING.md; SECURITY.md added.

## [0.1.0] - 2026-11-22

First release of the plugin and the Android app.

### Plugin
- Control channel on TCP 4466 with PIN pairing; the PIN is shown and can be changed under Tools, OBS Director.
- Discovery beacon on UDP 4467.
- Status four times a second: recording (file, position, size, disk space), stream (duration, bitrate, dropped frames), PC (CPU, frame rate, render and encoder lag), scene.
- Start, stop, pause and resume recording; end the stream.
- Program mix to each listening device as 48 kHz stereo PCM over UDP, stamped with the recording's file time.
- Live output as 640×360 H.264 at about 24 fps on TCP 4468, running only while a device watches.
- Chapter markers in Hybrid MP4 recordings when a clip starts.
- Volunteer dock: who is connected and listening, messages with Got it / On it / Fixed, activity feed.

### Android app
- Connect: PCs found on the network or typed in, PIN, device name.
- Monitor: recording file and timecode, clips along the file, hold to stop, pause; live output (off by default, stops with the screen); mix meters and listening; stream health by the minute; desk messages.
- Clips: one-button clips starting 1 minute before the tap, tags, typed or spoken notes, adjust on a waveform with nudges, play, share clip audio.
- Session audio saved on the phone as AAC 128 kb/s, lined up with the recording.
- Export as Resolve marker EDL, CSV or text: copy, share or save.
- Alerts: silent mix, clipping, recording stopped by someone else, disk nearly full, dropped frames, connection lost.
- Keeps playing with the screen off; clip controls in the notification.
- Auto-ends a running clip and reconnects when the network drops.
- In-app updates: checks this repository's releases each time the app opens, asks once with Update and Ignore, downloads with a percentage and hands the new version to Android's installer, keeping clips and settings. Settings, About has Check for updates and the release page.

### Releases
- Every push to `main` publishes release `v<version>` with the plugin for Windows, macOS and Ubuntu and `OBS-Director-<version>.apk`, marked latest. Releases are signed with the app's permanent key only.

### Known limits
- The browser version (WebRTC and WebSocket modes) is not built yet; the app uses Direct UDP.
- Audio missing from the phone's copy during a Wi-Fi drop is not yet refilled from the PC.

[Unreleased]: https://github.com/SharpTalons/OBS-Director/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/SharpTalons/OBS-Director/releases/tag/v0.1.0
