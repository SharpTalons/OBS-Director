# OBS Director

Monitor and direct an OBS session from your phone while volunteers run the PC.

OBS Director is two parts:

- **The OBS plugin** (this repository's root): runs inside OBS on the Windows streaming PC. It sends the recording and stream status, the program mix and a small live picture to your phone over the local network, takes start/stop/pause commands, writes your clips into the recording as chapter markers, and adds a dock where volunteers see your messages.
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

- A Windows 10 or 11 PC (64-bit) with **OBS Studio 33 or later**.
- An Android phone with **Android 10 or later**.
- The phone and the PC on the **same network** (the same Wi-Fi, or the PC wired to the same router).

The plugin is for Windows only.

### 1. Install the plugin on the PC

1. Close OBS.
2. Download `OBS-Director-<version>-Windows-Setup.exe` from the [latest release](https://github.com/SharpTalons/OBS-Director/releases/latest) and run it. Windows asks for permission to make changes, because the plugin is installed for everyone on the PC.
3. Follow the steps. The installer puts the plugin where OBS 33 and later look for plugins, `C:\ProgramData\obs-studio\plugins\obs-director`, and removes any older copy from the legacy locations.
4. Open OBS. You should now see **Tools, OBS Director** in the menu and an **OBS Director** dock (turn it on under **Docks** if it's hidden).

To update, run the newer installer the same way. To remove the plugin, use **Settings, Apps, Installed apps, OBS Director, Uninstall**. Your PIN is kept, so phones you've paired still connect after an update.

Windows may show "Windows protected your PC" because the installer isn't code-signed yet. Choose **More info**, then **Run anyway**.

### 2. Let the phone reach the PC

The plugin uses TCP 4466 (control), UDP 4467 (finding the PC) and TCP 4468 (live picture).

The first time OBS starts with the plugin, Windows asks whether to allow OBS on networks. Allow it on **private** networks. If you missed the prompt, allow OBS in **Windows Security, Firewall & network protection, Allow an app through firewall**, and make sure your Wi-Fi is set to a private network (**Settings, Network & internet, Wi-Fi**, your network, **Private network**).

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
- **The plugin doesn't appear in OBS**: check that OBS is version 33 or later (**Help, About**), then run the installer again.

## Building from source

**Android app**: needs JDK 17 and the Android SDK 35.

```bash
cd android && ./gradlew assembleDebug
```

The debug app (`dev.sharptalons.obsdirector.debug`) installs beside the released one.

**Plugin**: Windows only, with Visual Studio 2022, CMake 3.28 or later and PowerShell 7. Built on [obs-plugintemplate](https://github.com/obsproject/obs-plugintemplate):

```bash
cmake --preset windows-x64 && cmake --build --preset windows-x64
```

To build the installer as well, install [Inno Setup 6](https://jrsoftware.org/isinfo.php), then run `.github/scripts/Build-Windows.ps1` and `.github/scripts/Package-Windows.ps1` with the `CI` environment variable set. The installer lands in `release/`.

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
