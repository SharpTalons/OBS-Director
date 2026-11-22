# Changelog

All notable changes to OBS Director (plugin and Android app). The project follows
[Semantic Versioning](https://semver.org) and the format of [Keep a Changelog](https://keepachangelog.com).

## [Unreleased]

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
- In-app updates from GitHub Releases.

### Known limits
- The browser version (WebRTC and WebSocket modes) is not built yet; the app uses Direct UDP.
- Audio missing from the phone's copy during a Wi-Fi drop is not yet refilled from the PC.

[Unreleased]: https://github.com/SharpTalons/OBS-Director/compare/0.1.0...HEAD
[0.1.0]: https://github.com/SharpTalons/OBS-Director/releases/tag/0.1.0
