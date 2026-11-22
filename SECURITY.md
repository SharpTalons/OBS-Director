# Security

## Reporting a problem

Please don't open a public issue for a security problem. Report it privately through
GitHub instead: on this repository, open **Security**, then **Report a vulnerability**.
Include what you found, how to reproduce it, and which version you tested.

## How OBS Director protects your session

- The plugin only talks to devices on your local network. Don't forward its ports
  (TCP 4466 and 4468, UDP 4467) to the internet.
- A phone has to enter the PIN shown under **Tools, OBS Director** before it can see or
  control anything. Wrong guesses are slowed down per device, and after repeated misses
  the plugin makes a new PIN.
- Audio is only sent back to the paired phone's own address. Live video needs a one-time
  token that's only given to a paired phone.
- The phone gets the recording's file name, never its full path on the PC.
- Messages from the phone are shown in the OBS dock as plain text.
- The app downloads updates over HTTPS from this repository's releases only. Android
  refuses an update that isn't signed with the same key as the installed app.

If you think the PIN has been seen by someone it shouldn't have, press **New PIN** under
**Tools, OBS Director**.
