# Security

## Reporting a problem

Please don't open a public issue for a security problem. Report it privately through
GitHub instead: on this repository, open **Security**, then **Report a vulnerability**.
Include what you found, how to reproduce it, and which version you tested.

## How OBS Director protects your session

- The plugin only talks to devices on your local network. Its installer opens TCP 4466, 4468 and
  4470 and UDP 4467 in Windows Firewall for the local subnet only. Don't forward them to the internet.
- A phone can only connect after someone at the PC presses **Allow** in OBS. OBS then remembers
  that phone (by a random ID the app made for itself) and lets it connect without asking again.
  **Tools, OBS Director** lists the allowed phones; removing one disconnects it at once.
- After **Deny**, or no answer, that address has to wait before it can ask again, and only a few
  questions can be open at once, so nobody can flood OBS with requests.
- The connection isn't encrypted, so treat the network like the room: use it on a network you
  trust, not open public Wi-Fi.
- Audio is only sent back to the paired phone's own address. Live video needs a one-time
  token that's only given to a paired phone.
- The phone gets the recording's file name, never its full path on the PC.
- Messages from the phone are shown in the OBS dock as plain text.
- The browser version (TCP 4470) pairs exactly like a phone: OBS asks once. The page is only
  served under a name the PC answers to (its IP address, `localhost` or its computer name), and
  only that page may open the control channel, so a website elsewhere can't use your browser to
  reach OBS. The page loads nothing from the internet, and what it keeps (settings, clips, saved
  audio) stays in that browser.
- The app downloads updates over HTTPS from this repository's releases only. Android
  refuses an update that isn't signed with the same key as the installed app.
- When the project's code-signing certificate is set up, the Windows installer, its uninstaller
  and the plugin DLL are signed; the release notes say whether a release is.

If a phone you don't recognise is in the allowed list, select it under **Tools, OBS Director** and
press **Remove**, or press **Remove all** and allow your own phones again.
