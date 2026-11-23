# Changelog

All notable changes to OBS Director (plugin and Android app). The project follows
[Semantic Versioning](https://semver.org) and the format of [Keep a Changelog](https://keepachangelog.com).

## [Unreleased]

## [0.5.0] - 2026-11-23

A simpler, more colourful app, built around one Clip button, with clip lists saved on the PC.

### Android app
- New look: a head bar on every screen with the PC, the delay, Stage mode and the connect icon; coloured sections; Roboto throughout.
- Monitor: the Clip button sits in the Recording section and shows the clip's length while it runs. Tags and quick desk messages are gone.
- Meters read like OBS's: −60 to 0 dB, green, yellow from −20, red from −9, falling at OBS's rate, with a 20-second peak hold.
- Plays through whatever output the phone is using, Bluetooth included. Call-only Bluetooth headsets get the mix over the call channel.
- Volume up to 300%, with a soft limiter.
- Recordings: one row per recording; inside, each clip has a **+** with Play, Adjust, Note, Share audio and Delete (with undo). Export lives inside the recording.
- Stream screen with Stream, Computer and Network tabs, opened from the Stream chip on Monitor.
- Stage mode: black and dim, with the time, the meter and a big Clip button.
- Floating bubble over other apps, with the time and the Clip button.
- Long-press the app icon for Start clip, Stage mode and Close OBS Director. Back three times closes the app.
- Warnings when the phone's battery runs low, and a Wi-Fi quality line under the meter.
- Settings is one list in coloured groups, with choices in a sheet from the bottom.

### Plugin
- New `clips.write` command: saves each recording's clip list as an EDL and a CSV next to the recording, only for recordings OBS made while the plugin was running.

## [0.4.1] - 2026-11-22

### Windows installer
- A ticked **Allow phones on this network through Windows Firewall** option. It adds the plugin's rules for local networks only, and removes any rule Windows made that blocks OBS: those beat every allow rule and stopped phones connecting.

### Android app
- The PC list is fresh every time: only PCs that answer the scan, each with a yellow dot meaning ready to connect. No more "last used" entry.
- When a PC can't be reached, the app says why: the PC didn't answer (Windows Firewall), OBS isn't accepting phones (plugin not running), OBS didn't reply, or the phone is on another network.

## [0.4.0] - 2026-11-22

No more PIN: tap your PC to connect, and allow the phone once in OBS. The installer now opens the firewall and is ready for code signing.

### Connecting
- Tap a PC in the list to connect. There's no PIN. The first time, OBS asks **Allow "phone" to connect?**; once allowed, the phone connects with a tap and OBS doesn't ask again. Tools, OBS Director lists the allowed phones and can remove them.
- After **Deny** or no answer, that address waits before it can ask again, and only three questions can be open at once.
- The app shows each PC's name with its IP. A PC typed in by address is asked for its name, and **Search** gets answers at once instead of waiting for the next beacon.
- A PC on two networks (for example Wi-Fi and Ethernet) is listed once. The app and OBS now show the same address: the one the phone can reach.
- Monitor offers **Connect to <your PC>** in one tap when the app isn't connected.
- "Couldn't reach the PC" now says what to check.
- Protocol version 2. An app and a plugin from different releases can't pair: install both from the same release.

### Windows installer
- Opens TCP 4466 and 4468 and UDP 4467 in Windows Firewall for the local network only, so phones aren't blocked; removes the rules on uninstall.
- An icon, full version and publisher details, and **Start OBS Studio** on the last page.
- Code signing with Azure Artifact Signing for the plugin, the installer and its uninstaller, switched on by six repository secrets (docs/RELEASING.md). The release notes say whether a release is signed.

## [0.3.0] - 2026-11-22

Connecting is now one tap, Settings is split into pages, and the plugin shows one address.

### Android app
- Connect from the new connect icon in the top bar. A pop-up lists the PCs found on the network, with **Search** to look again, a way to type the IP address, and the PIN. It closes once connected, and opens by itself the first time.
- When not connected, Monitor shows a short card with a Connect button instead of a long form.
- Settings is a short menu of pages: Connection, Listening, Saved audio, Clips and export, Alerts, Live picture, Controls and display, and Updates and about. Each shows what's set at a glance; the Back button returns to the menu.
- This phone's name in OBS can now be changed in Settings, Connection.
- Settings shows the plugin's version next to the app's, and says if they don't match.

### Plugin
- Tools, OBS Director shows one address: the PC's main network address. Virtual adapters (Hyper-V, WSL, VPNs, virtual machines) are no longer listed.

### Releases
- The release workflow waits up to 2 minutes for GitHub to report the new release as latest, instead of failing after 5 seconds.
- The app and the plugin always share one version number, set once in `buildspec.json`.

## [0.2.0] - 2026-11-22

The plugin is now Windows only and installs with a setup program, in the plugin layout OBS Studio 33 introduced.

### Security
- Plugin: wrong PINs are slowed down per device (three free tries, then 2 s doubling up to 5 minutes), unpaired connections are capped at eight, and the PIN is compared in constant time.
- Plugin: the status sends the recording's file name only, never its full path, and never falls back to an output URL.
- Plugin: messages and device names from phones are shown in the dock as plain text.

### Plugin
- Windows only: the macOS and Ubuntu builds are removed.
- The plugin now ships as a Windows installer, `OBS-Director-<version>-Windows-Setup.exe`. It installs to OBS Studio 33's plugin layout (`C:\ProgramData\obs-studio\plugins\obs-director`), removes copies from legacy locations that OBS 34 will stop loading, and checks that OBS 33 or later is installed.
- Needs OBS Studio 33 or later.

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

[Unreleased]: https://github.com/SharpTalons/OBS-Director/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/SharpTalons/OBS-Director/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/SharpTalons/OBS-Director/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/SharpTalons/OBS-Director/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/SharpTalons/OBS-Director/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/SharpTalons/OBS-Director/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/SharpTalons/OBS-Director/releases/tag/v0.1.0
