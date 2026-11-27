# Changelog

All notable changes to OBS Director (plugin and Android app). The project follows
[Semantic Versioning](https://semver.org) and the format of [Keep a Changelog](https://keepachangelog.com).

## [Unreleased]

## [0.8.1] - 2026-11-27

Fixes from design audits and reviews of the app, the browser version and the OBS dock.

### Android app
- **Connection status off Monitor:** Recordings, Desk, Settings and Stream show a slim amber line at the top while the connection is lost or connecting (tap it to connect), since they have no top bar.
- **Clip titles:** a clip without a note is named by its time ("Clip at 00:52:00") with "No note" under it, instead of "Add a note", which read like a button.
- **Volume in words:** the line under the slider says how loud in plain terms, such as "About twice as loud (+12 dB)".
- **Delay and Bluetooth:** the Delay choices are now Auto, 20, 40, 80 and 160 ms, and on Bluetooth the row says it uses 160 ms there.
- Recordings uses more of a tablet's width (840 dp). Stage mode names itself to screen readers properly.

### Browser version
- **Volume like the app:** 1 to 100% on the same loudness scale (50% as sent, +24 dB at 100%), a scale under the slider, the level in words, and a limiter in place of the old soft clip, so the boost is really louder. A saved 300% becomes 70%.
- **How it works:** a short guide under Settings, About.
- **Hold to stop** is a filled button now, not one that looks switched off.
- **On phones:** the header shows on Monitor (and anywhere while the connection needs attention), and the Stream tab is labelled "Stream" so it fits.
- Clip titles match the app.

### A new trimmer (app and browser)
- **Trim the way phones trim video.** The sound of the clip is drawn with 15 s either side and the kept part lit. Drag the handle at either end to move it, or drag the lit part to move the whole clip; tap anywhere else to bring the nearer end there.
- **Hear each cut:** letting go of an end plays a moment of sound at that edge.
- **Cuts land between words:** an end you let go settles in the nearest pause within half a second (when the session audio is saved).
- **Fine-tune:** ±1 s moves whichever end you last touched; tap Start or End under the trimmer to pick one. In the browser the ends are sliders too: arrow keys move them 1 s, Page Up and Page Down 5 s.
- Dragging an end to the side of the view widens it. **Reset** puts the clip back; nothing changes until **Save**.

### OBS plugin: update check
- **Tells you when a new version is out.** Once a day, shortly after OBS starts, the plugin asks GitHub for the newest release (nothing about the PC is sent). If it's newer, OBS offers **Download** (the Windows installer), **Skip this version** or **Later**, with the release notes under **Show Details**.
- **Never mid-show:** while streaming or recording it only leaves a note in the dock, and asks once both have stopped.
- **Tools, OBS Director** shows how the last check went, has **Check now**, and a switch to turn the daily check off.

### OBS dock
- The status line at the top uses the chat's dark colours, so there's no light strip in light OBS themes.
- The empty chat says how to write: Enter for a new line, Ctrl+Enter to send.
- Phones and browsers in Tools, OBS Director read out with the date they were allowed.

## [0.8.0] - 2026-11-27

A simpler Recordings tab, in the app and the browser version.

### Recordings, clips first
- **One screen.** Each recording is a card with its clips listed inside, so there is no second page to open and come back from. Tap a heading to open or fold it. The recording OBS is making and the two newest others start open.
- **Play from the list.** Every clip has its own Play button. While one plays, a small player shows at the bottom with its progress and Stop.
- **One sheet for a clip.** Tap a clip for Play, Trim, Note, Share (Download in the browser) and Delete.
- **Simpler trim:** Start and End, 5 seconds at a time, over the sound around the clip, with a button to hear the result.
- **Export and delete** are in each recording's ⋯ menu. In the app, tap a format to share it or the arrow to save it to the phone; in the browser, click a format to download it, or Copy.
- **Undo** after deleting a clip or a recording, for six seconds.
- **Monitor's "See all"** opens Recordings at that recording.
- Recordings without clips stay folded at the end, each with its own delete button.

### Volume (app)
- **1 to 100%, on a loudness scale.** The old slider went to 300%, but its limiter squashed the peaks so hard that 300% sounded little louder than 100%. Now each step of the slider is an even step in loudness: 50% is the mix as OBS sends it, 75% is 12 dB louder and 100% is 24 dB louder. A scale under the slider shows −40 dB, −20, As sent, +12 and +24 dB.
- **A real boost:** a new limiter brings quiet speech up by the full amount and holds peaks just under full scale, without distorting. Your saved volume carries over to the new scale.
- **Phone volume:** when the phone's own media volume is below 70%, the line under the slider says so, since nothing can be louder than that allows.

### Smooth listening on Bluetooth (app)
- **No more crackling on Bluetooth headsets.** Bluetooth takes audio in uneven bursts, and the app's small buffer kept running dry. On Bluetooth it now keeps a deeper cushion (a 400 ms buffer, starting at 120 ms and holding about 160 ms), so listening is about 150 ms further behind than on the speaker, and smooth.
- **No more clicks from keeping time, on any output.** The app used to drop a 5 ms packet when it got ahead and play one twice when it fell behind; each was a jump in the sound. Now it plays up to 0.6% faster or slower, which no one can hear, and if Wi-Fi stalls it puts the whole cushion back in one go instead of crackling at the edge.
- Packets lost on the way now play as a short gap that keeps the timing steady.

### Touch sound and vibration (app and browser)
- **A louder tap:** the tap sound is compressed and brought up to just under full scale, about 9 dB louder on average, and the Touch sound setting is now a loudness scale (25, 50, 75 and 100% play at −12, −6, −3 and 0 dB; 100% used to be 60% of the tap).
- **On the media volume:** the app plays it on the volume the volume buttons set while listening, instead of the quieter system-sounds volume.
- **Stronger vibration:** taps are now a firm pulse (18, 28 or 40 ms at strength 140, 210 or 255) instead of Android's faint built-in clicks, and every buzz is stronger and longer at each level.

### Top bar (app)
- Only Monitor has the top bar now. Recordings, Desk, Settings and Stream start straight with their title, which gives their content more room.

### OBS dock
- **No quick replies:** "Got it", "On it" and "Fixed" are gone; write a message instead.
- **Easier to read on dark screens:** white message text, light grey times and notes, lighter sender names, and bubbles with an edge so they stand out from the background.

### Floating bubble
- It's the app icon now. A small badge on its corner shows OBS is recording (red) or paused (amber), and a red ring shows a clip is running.

## [0.7.2] - 2026-11-27

Fixes from design audits of the OBS plugin, the browser version and the app.

### OBS plugin
- **No more beep:** a new message no longer plays the PC's system sound, which OBS could capture into the stream or recording. While OBS is in the background, it flashes in the taskbar instead.
- **Screen readers:** the message box has a name, each message reads as one line (who, when, what), and new messages are read out (OBS 31 and later).
- **Text size:** the chat follows the PC's text size instead of fixed pixel sizes.
- **Narrow docks:** messages rewrap to fit the dock instead of being cut off on the right, and the hint in the message box shortens to "Message" when it wouldn't fit.
- **Long sessions:** the chat keeps the last 600 lines, like the PC side, so a week-long OBS session stays light. It stays at the newest message unless you scroll up to read.
- **Quick replies** wait three seconds before they go. Click the reply again to cancel it.
- **Tools, OBS Director:** the notes are in normal text colour, easier to read in dark themes, and the window's text can now be translated like the rest of OBS.

### Browser version
- **Phone keyboard:** the page shrinks above the keyboard, so the chat box stays in sight while typing.
- **Desk tab:** the unread count turns amber when an alert is unread, and red for a severe one. Connection notes no longer count as unread.
- **Recordings without clips** are folded into one row at the end of the list.
- **Settings:** at phone width, the name field moves under its label instead of squeezing it.
- **Switches** answer a 48 px tall touch.
- **Battery:** the level meter stops drawing when no meter is on screen.

### Android app
- **Desk chat:** screen readers read out new messages while the chat is open.
- **Confirmations** use Android's own snackbar, above the tabs.
- **Tablets and unfolded phones:** Recordings shows the list and the open recording side by side.

## [0.7.1] - 2026-11-26

Fixes from a design audit and review of the Android app.

### Easier to find
- **Stream and PC details:** a link under the status chips on Monitor opens the Stream screen. Before, only tapping the first chip did.
- **Alerts on the Desk tab:** the unread count turns amber when an alert is among the unread lines, and red for a severe one. Connection notes no longer count as unread.
- **Recordings without clips** are folded into one row at the end of the list, so the ones with clips come first.
- **Settings** is shorter: Listening comes first, **Play through** shows where the sound is playing now (so the separate "Playing now" row is gone), the bubble moved to Controls and the battery warning to Alerts.
- **How it works:** a short guide on the not-connected screen and under About in Settings.

### Accessibility
- Confirmations such as "Clip saved" are now read out by screen readers.
- Every button, switch and link is at least 48 dp: the round icons at the top, a clip's + and Delete, the voice button, the switches, and Revert and Save when adjusting a clip.
- "Volume" no longer breaks across two lines with large text, and the Listen caption shows just the output, so it isn't cut off.
- PCs in the connect pop-up are announced as buttons.

### Android
- **Desk chat and the keyboard:** the message box now moves up above the keyboard, so you can see what you type. Before, the keyboard covered it.
- **Back:** the usual Android way, with the back-to-home preview. From Desk, Recordings or Settings, Back goes to Monitor; from Monitor, it leaves the app while listening and recording carry on, with the bubble and notification to come back. The triple-Back exit is gone; **Close OBS Director** on the app icon's long-press still closes it.
- **Tablets, unfolded phones and landscape:** the tabs move to a rail down the side.
- **Not connected:** the screen matches the rest of the app.
- **Battery:** the level meter stops drawing once listening is off and the bars have fallen.

## [0.7.0] - 2026-11-26

The desk is now a group chat, everywhere.

### Desk chat
- **Group chat:** the OBS dock, every phone and every browser see every message.
- **Layout:** like WhatsApp. Others on the left with their names in colour, your own messages on the right in green, and day separators.
- **Ticks:** a clock while sending, grey ticks once OBS has the message, blue once the desk has seen it. A message that didn't go shows in red; tap to try again.
- **Typing:** Enter starts a new line, and Ctrl+Enter or the send button sends. Messages go up to 1000 characters.
- **Android app:** a Desk tab of its own, with an unread count, and notifications that open the chat. The tabs step aside while the keyboard is up. The desk card is gone from Monitor.
- **Browser version:** a Desk tab of its own, with an unread count.
- **OBS dock:** rebuilt as the same chat, with "Got it", "On it" and "Fixed" quick replies. Connections and recording events show as small notes. A phone or browser that connects later gets the conversation so far.

### Alerts live in the chat
- Alerts no longer sit as banners on top of the screens. Each one sends a notification, vibrates, and shows up in the Desk chat as a centred card (red for a severe one) with its action, **Start recording** or **Tell the desk**.
- Tapping an alert notification opens the chat. Connection lost and reconnected show there too. The phone's low-battery warning is now an alert like the others.

### Touch sound and vibration
- **Touch sound:** a tap sound on every button, tile, row, switch and tab, with its volume under Settings, Controls (Off, 25, 50, 75 or 100%). It's one clean tap cut from the supplied recording, which has ten. The app stays quiet while the phone is on silent or vibrate. The browser version plays it too, from the plugin.
- **Vibration:** a new strength setting (Off, Light, Medium or Strong) under Controls, for taps, clips, alerts and messages. Picking one buzzes so you can feel it. The browser version vibrates on phones whose browser can.
- **Design constants:** both are now design tokens (`FeedbackTokens` in the app's theme), and are written up with the colours, type and sizes in [docs/DESIGN.md](docs/DESIGN.md).

### Android app
- **Stage mode:** comedy and tragedy masks replace the moon, which looked like a dark-mode switch. This applies to the top bar, the long-press shortcut and the browser version.
- **Sound on every output:** the mix now plays on Bluetooth, USB sound cards and headsets that stayed silent before. Android only starts an ordinary audio track once its buffer has been filled, and the app's 200 ms buffer was never filled; it now uses a small buffer, starts at 20 ms, and remakes a track that doesn't start.
- **Play through:** a new setting to pick the output (Automatic, or any connected speaker, Bluetooth, headphones, USB, HDMI or line out). When the chosen one isn't connected, the mix plays automatically until it is. **Playing now** shows where it actually goes, and **Play a test sound** checks the output without OBS.
- **Delete recordings:** delete one recording from its page, or all of them from the end of the Recordings list or from Settings. Their clips, notes and saved audio go too; the files on the PC aren't touched.
- **Floating bubble:** works like other floating bubbles. Tap it to open the app, drag it anywhere and it settles against the side, or drop it on the ✕ to put it away.
- **Nothing jumps:** one fixed line under the Clip button replaces "Cancel this clip", the saved message and "see all". The note opens in a sheet over the screen. Pause and Hold to stop are the same size. The three status chips, the volume caption and the Wi-Fi line are always there. The undo bar floats over the list.

### Browser version
- Delete one recording, or all of them, from the Recordings pages.
- The same fixed line under the Clip button (the note fits in it), an always-visible volume caption, and a floating undo bar.

## [0.6.1] - 2026-11-25

Fixes from the design audit of the browser version.

### Browser version
- Screen readers hear each alert once: banners that are already showing are kept, the "lost … ago" seconds tick visibly only, and the disk alert's hours update in place.
- Card titles are headings, so screen reader users can jump between sections.
- Text fields show the same 2 px focus ring as everything else; the desk placeholder is easier to read.
- Hold to stop works by touch: a press doesn't turn into a scroll or open the long-press menu.
- The Listen and Live picture switches have a 44 px touch target.
- Meters stop redrawing when nothing moves; the last hard-coded colours, text selection, the caret and the scrollbar come from the theme; icons instead of text symbols.

## [0.6.0] - 2026-11-24

OBS Director in a browser: open the address OBS shows, on any computer on the network, and do everything the phone does.

### Browser version
- Served by the plugin itself on TCP 4470; nothing to install. Works in Chrome, Edge and Firefox, on another computer or on the OBS PC.
- Pairs like a phone: OBS asks **Allow** once, then the browser connects by itself.
- **Monitor:** the recording and its timecode, the Clip button with its length inside (or press Space), Pause and Hold to stop, and listening through the computer's sound output (OBS-style meters, volume to 300% with a soft limiter, a network line). Also the live picture, chips for the stream and disk, and the desk.
- **Stream and PC:** the Stream, Computer and Network tabs.
- **Stage mode:** a black screen with the time and a big Clip button; Space clips and Esc leaves.
- **Recordings:** each clip has Play, Adjust start and end, Edit note, Download audio (WAV) and Delete with undo. Export downloads as Resolve EDL, CSV or text, or copies it. The clip list is saved next to the recording on the PC too.
- **Alerts:** silent mix, clipping, recording stopped by someone else, low disk, dropped frames, connection lost.
- **Settings:** saved in the browser.

### Plugin
- A small web server on TCP 4470: the page, and a WebSocket carrying the same messages, the mix and the live output.
- It only answers under the PC's own addresses and names, and the WebSocket only accepts its own page.
- **Tools, OBS Director** shows the browser address, and lists browsers with the phones.

### Windows installer
- The firewall option also opens TCP 4470 for the local network.

## [0.5.1] - 2026-11-23

Fixes from the design audit of 0.5.0.

### Android app
- TalkBack: the bottom tabs say which one is selected and read each name once; new alerts are announced as they appear.
- Every button is at least 48 dp tall, including those in the connect pop-up, alert banners and the Adjust panel.
- Tablets, landscape and unfolded phones keep a phone's reading width instead of stretching. Stage mode in landscape puts the Clip button beside the time.
- Recordings and their clips are lazy lists, so long histories scroll smoothly.
- The last hard-coded colours, the floating bubble's included, now come from the theme.

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

[Unreleased]: https://github.com/SharpTalons/OBS-Director/compare/v0.6.2...HEAD
[0.6.1]: https://github.com/SharpTalons/OBS-Director/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/SharpTalons/OBS-Director/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/SharpTalons/OBS-Director/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/SharpTalons/OBS-Director/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/SharpTalons/OBS-Director/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/SharpTalons/OBS-Director/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/SharpTalons/OBS-Director/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/SharpTalons/OBS-Director/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/SharpTalons/OBS-Director/releases/tag/v0.1.0
