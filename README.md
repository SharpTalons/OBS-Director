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
- **Updates**: each time the app opens it checks this repository's releases. When a newer version is out it asks once, with **Update** and **Ignore**; Update downloads it and hands it to Android's installer, keeping your clips and settings. Settings, About shows the version and a **Check for updates** button.

## Setup

### What you need

- A Windows, macOS or Ubuntu PC with **OBS Studio 31 or later**.
- An Android phone with **Android 10 or later**.
- The phone and the PC on the **same network** (the same Wi-Fi, or the PC wired to the same router).

### 1. Install the plugin on the PC

1. Close OBS.
2. Download the plugin for your system from the [latest release](https://github.com/SharpTalons/OBS-Director/releases/latest):
   - **Windows**: `OBS Director-<version>-windows-x64` installer (or the zip, unpacked into your OBS folder).
   - **macOS**: `OBS Director-<version>-macos-universal.pkg`.
   - **Ubuntu**: `OBS Director-<version>-x86_64-linux-gnu.deb`, installed with `sudo apt install ./<file>.deb`.
3. Open OBS. You should now see **Tools, OBS Director** in the menu and an **OBS Director** dock (turn it on under **Docks** if it's hidden).

### 2. Let the phone reach the PC

The plugin uses TCP 4466 (control), UDP 4467 (finding the PC) and TCP 4468 (live picture).

- **Windows**: the first time OBS starts with the plugin, Windows asks whether to allow OBS on networks. Allow it on **private** networks. If you missed the prompt, allow OBS in Windows Security, Firewall, Allow an app through firewall, and make sure your Wi-Fi is set to a private network.
- **macOS**: if asked, allow OBS to accept incoming connections.
- **Ubuntu**: if you use `ufw`, run `sudo ufw allow 4466,4468/tcp` and `sudo ufw allow 4467/udp`.

Keep these ports on your local network. Don't forward them to the internet.

### 3. Set up recording (for chapter markers)

For clips to be written into the recording as chapters, set OBS to record in **Hybrid MP4**: Settings, Output, Recording, Recording Format. Everything else works with any format.

### 4. Install the app on the phone

1. On the phone, open the [latest release](https://github.com/SharpTalons/OBS-Director/releases/latest) and download `OBS-Director-<version>.apk`.
2. Open it. The first time, Android asks you to allow installs from your browser or file manager; allow it, then install.
3. After that, the app updates itself.

### 5. Pair the phone

1. In OBS, open **Tools, OBS Director**. It shows the PIN, the PC's name and its addresses.
2. In the app, pick the PC from the list (or type one of the addresses), enter the PIN and a name for your phone, and connect.
3. Volunteers see your phone's name in the OBS dock while you're connected.

To stop a device from connecting again, press **New PIN** under **Tools, OBS Director**.

### Troubleshooting

- **The PC doesn't appear in the app**: check that both are on the same network, then type the address from **Tools, OBS Director**. Some guest or office networks block devices from seeing each other; use a different network if so.
- **"That PIN doesn't match"**: check the PIN in **Tools, OBS Director**. After too many wrong PINs the plugin makes you wait before trying again, and may make a new PIN.
- **Connects, but no sound or picture**: the firewall is letting the control channel through but not UDP 4467 or TCP 4468. Check step 2.
- **No chapters in the recording**: the recording format isn't Hybrid MP4 (step 3).

## Building from source

**Android app**: needs JDK 17 and the Android SDK 35.

```bash
cd android && ./gradlew assembleDebug
```

The debug app (`dev.sharptalons.obsdirector.debug`) installs beside the released one.

**Plugin**: built with [obs-plugintemplate](https://github.com/obsproject/obs-plugintemplate)'s CMake presets, for example:

```bash
cmake --preset windows-x64 && cmake --build --preset windows-x64
```

Other presets are `macos` and `ubuntu-x86_64`. See the template's documentation for the tools each platform needs.

CI builds both on every push and pull request. Maintainers: releasing and signing are described in [docs/RELEASING.md](docs/RELEASING.md).

## Protocol

[docs/PROTOCOL.md](docs/PROTOCOL.md) describes the discovery beacon, the control channel, the audio packets and the video stream.

## Security

See [SECURITY.md](SECURITY.md) for how the connection is protected and how to report a problem privately.

## Licences

- The OBS plugin (`src/`, `cmake/`, `data/`) links against libobs and is licensed under the GNU GPL v2 or later.
- The Android app (`android/`) is under the MIT licence in [LICENSE](LICENSE).
- The app bundles Public Sans and JetBrains Mono, both under the SIL Open Font License 1.1.
- "OBS" and "OBS Studio" are trademarks of the OBS Project; this project is not affiliated with it.
