# OBS Director

Monitor and direct an OBS session from your phone, or from a browser on any computer, while volunteers run the PC.

OBS Director has three parts:

- **The OBS plugin** (this repository's root): runs inside OBS on the Windows streaming PC. It sends the recording and stream status, the program mix and a small live picture to your phone over the local network, takes start/stop/pause commands, writes your clips into the recording as chapter markers, and adds a dock in OBS: a group chat between the desk and every phone and browser.
- **The Android app** ([`android/`](android)): listen to the mix, watch record and stream health, start and stop the recording, and mark clips with notes for cutting short videos later.
- **The browser version** ([`src/web/`](src/web)): the same app in Chrome, Edge or Firefox, served by the plugin itself. Open the address OBS shows under **Tools, OBS Director**, on a laptop, the OBS PC or any computer on the same network. Nothing to install.

## What the app does

- **Monitor**: the recording's file name and timecode, the Clip button, the mix with meters that read like OBS's own (−60 to 0 dB, green, yellow from −20, red from −9, with a peak hold), listening with about 40 ms of delay, and the live picture when you switch it on (off by default, never in the background). A line under the meter says how the phone's Wi-Fi is holding up.
- **Listening**: plays through whatever your phone is using (Bluetooth, a wired headset, a USB sound card, the headphone jack or the speaker), or pick one in **Settings, Listening, Play through**. **Play a test sound** checks it without OBS. Volume goes up to 300% for quiet rooms, with a soft limiter so it doesn't crackle.
- **Clips**: one button, which shows the clip's length while it runs. A clip starts a minute before your tap (so the build-up is in) and ends a couple of seconds after the second tap. Add a typed or spoken note.
- **Recordings**: one screen, clips first. Each recording is a card with its clips inside; tap a heading to open or fold it. Every clip has its own Play button, and a small player shows above the tabs while one plays. Tap a clip for one sheet with Play, Trim (start and end, 5 s at a time), Note, Share (its audio as .m4a) and Delete, with Undo. Recordings without clips stay folded at the end. Delete a recording from its ⋯ menu, or all of them from the end of the list or Settings; the files on the PC aren't touched.
- **Export**: from a recording's ⋯ menu, as a DaVinci Resolve marker EDL, CSV or a plain list, to share or save on the phone. The plugin also saves the EDL and CSV next to the recording on the PC, so the editor finds them there (you can turn this off in Settings).
- **Stream**: tap **Stream and PC details** (or the stream chip) on Monitor for the stream, the PC (processor, frame rate, lag, disk) and the network, each on its own tab.
- **Stage mode**: the masks icon in the top bar. A black, dim screen with the time, the meter and a big Clip button, for a phone propped up during a service or a show.
- **Outside the app**: a floating bubble while connected, showing the app icon with a red badge while OBS records (amber when paused) and a red ring while a clip runs; tap it to open the app, drag it anywhere, or drop it on the ✕ to put it away. Long-press the app icon for **Start clip**, **Stage mode** and **Close OBS Director**. Back works the usual Android way: from the other tabs it goes to Monitor, and from Monitor it leaves the app while listening and recording carry on.
- **Alerts**: silent mix, clipping, recording stopped by someone else, disk nearly full, dropped frames, connection lost, and the phone's own battery running low. Each one notifies (the phone buzzes even when locked) and shows up as a card in the Desk chat, most with a **Tell the desk** button.
- **Desk**: a group chat with the volunteers at the OBS dock and everyone else connected, laid out like WhatsApp: names, times, and ticks that turn blue when the desk has read your message. Enter starts a new line; Ctrl+Enter or the send button sends.
- **Touch sound and vibration**: every tap clicks softly and ticks. Set the **Touch sound** volume and **Vibration** strength in **Settings, Controls**.
- **Drops**: if Wi-Fi drops, a running clip is saved at the drop, listening pauses, and the app reconnects by itself. OBS keeps recording on the PC throughout.
- **Updates**: each time the app opens it checks this repository's releases. When a newer version is out it asks once, with **Update** and **Ignore**; Update downloads it and hands it to Android's installer, keeping your clips and settings. To check yourself at any time, open **Settings**, scroll to **About**, and press **Check for updates**.

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

To update, run the newer installer the same way. To remove the plugin, use **Settings, Apps, Installed apps, OBS Director, Uninstall**. Phones you've allowed are remembered, so they still connect after an update.

On the **Windows Firewall** page, keep **Allow phones and browsers on this network through Windows Firewall** ticked. On the last page you can tick **Start OBS Studio**.

If the release notes say the installer isn't code-signed, Windows may show "Windows protected your PC". Choose **More info**, then **Run anyway**.

### 2. Let the phone reach the PC

The plugin uses TCP 4466 (control), UDP 4467 (finding the PC), TCP 4468 (live picture) and TCP 4470 (the browser version).

With **Allow phones and browsers on this network through Windows Firewall** ticked, the installer allows these from local networks only, whether Windows calls the network Private or Public.

It also removes any rule Windows made that **blocks** OBS. Windows makes one when it asks "allow OBS on this network?" and someone presses **Cancel**, or ticks only Private while the network is Public, and a block rule beats every allow rule. If phones still can't connect, run the installer again with that option ticked.

Keep these ports on your local network. Don't forward them to the internet.

### 3. Set up recording (for chapter markers)

For clips to be written into the recording as chapters, set OBS to record in **Hybrid MP4**: Settings, Output, Recording, Recording Format. Everything else works with any format.

### 4. Install the app on the phone

1. On the phone, open the [latest release](https://github.com/SharpTalons/OBS-Director/releases/latest) and download `OBS-Director-<version>.apk`.
2. Open it. The first time, Android asks you to allow installs from your browser or file manager; allow it, then install.
3. After that, the app updates itself.

### 5. Connect the phone (or a browser)

1. Open OBS on the PC.
2. In the app, tap the **connect icon** at the top right. (The first time you open the app, this pop-up opens by itself.) It scans the network and lists the PCs that answer, by name with each one's IP address. A **yellow dot** means the PC is ready to connect. The list is fresh every time you open it; press **Search** to scan again.
3. **Tap your PC.** That's all on the phone. If your PC isn't listed, press **Search**, or tap **Enter the IP address yourself** and type the address from **Tools, OBS Director** in OBS.
4. The first time, OBS on the PC asks **Allow "your phone" to connect?** Press **Allow**. The app says "Waiting for Allow in OBS" until someone does.

After that, the phone connects with one tap, and OBS doesn't ask again. When the app opens and isn't connected, **Connect to <your PC>** on the Monitor screen reconnects in one tap.

Volunteers see your phone's name in the OBS dock while you're connected. To change that name, open **Settings, Connection** in the app.

**In a browser instead:** open the address under **In a browser** in **Tools, OBS Director** (it looks like `http://192.168.1.24:4470`) in Chrome, Edge or Firefox on any computer on the same network, the OBS PC included. OBS asks **Allow "Chrome on Windows" to connect?** once, the same as for a phone. Everything the app does works there:

- listening through that computer's sound output, with the same meters and volume up to 300%
- the live picture
- Start clip (or press **Space**), and Stage mode (**Esc** leaves it)
- recordings, with Play, Adjust, Note, Download audio and Delete for each clip
- Export as a download
- alerts, the desk, and Settings

What a browser keeps (settings, clips and the session audio for playing clips back) stays in that browser. The page comes from the plugin, so it updates when you update the plugin.

**Tools, OBS Director** in OBS shows the PC's address and lists the phones and browsers you've allowed. Select one and press **Remove** to stop it connecting; it would have to be allowed again.

### Troubleshooting

- **The PC doesn't appear in the app**: check that both are on the same network and press **Search** in the connect pop-up. If it still isn't listed, tap **Enter the IP address yourself** and type the address from **Tools, OBS Director**. Some guest or office networks block devices from seeing each other; use a different network if so.
- **"The PC didn't answer"**: Windows Firewall is blocking OBS. Run the installer again with **Allow phones and browsers on this network through Windows Firewall** ticked (step 2).
- **"OBS isn't accepting phones"**: the phone reached the PC, but the plugin isn't running. Check OBS is open and **Tools, OBS Director** is in its menu (OBS 33 or later).
- **"This phone can't reach…"**: the phone and the PC are on different networks. Some guest or venue Wi-Fi keeps devices apart; use another network or a phone hotspot if so.
- **"OBS on this PC said no"**: someone pressed **Deny**. Wait a minute, tap the PC again, and press **Allow** in OBS this time.
- **Nothing appears in OBS when you tap the PC**: the Allow question may be behind the OBS window; check the taskbar.
- **The IP in the app differs from OBS**: your PC is on more than one network (for example Wi-Fi and Ethernet). The app shows the address your phone can actually reach; once the phone has searched or connected, OBS shows that same address.
- **Connects, but no sound or picture**: the firewall is letting the control channel through but not TCP 4468 or the audio. Check step 2.
- **The browser page doesn't open**: use the exact address from **Tools, OBS Director**, starting with `http://` and ending in `:4470`. If it still doesn't open from another computer, run the installer again with the firewall option ticked (step 2); this adds TCP 4470.
- **No sound in the browser**: browsers only play sound after a click. Switch **Listen in this browser** on.
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

## Design

The colours, type, sizes, touch sound and vibration patterns are in [docs/DESIGN.md](docs/DESIGN.md).

## Protocol

[docs/PROTOCOL.md](docs/PROTOCOL.md) describes the discovery beacon, the control channel, the audio packets and the video stream.

## Security

See [SECURITY.md](SECURITY.md) for how the connection is protected and how to report a problem privately.

## Licences

- The OBS plugin (`src/`, `cmake/`, `data/`) links against libobs and is licensed under the GNU GPL v2 or later.
- The Android app (`android/`) is under the MIT licence in [LICENSE](LICENSE).
- The app bundles the Roboto font, under the Apache License 2.0.
- "OBS" and "OBS Studio" are trademarks of the OBS Project; this project is not affiliated with it.
