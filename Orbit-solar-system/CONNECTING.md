# How the Bomba Live apps connect

OverLook, bomba-live and Orbit are separate repos, but they all join
the same **wall**. Any device that enters the same wall code sees the same screens.

| App | Watches the wall | Shares its own screen |
|---|---|---|
| bomba-live (web page) | Yes — grid, tap a screen for full screen | Computers (Chrome/Edge/Firefox) |
| Orbit (Android) | Yes — around the sun and in Live screens | Phones, via the Orbit share service |
| OverLook | Same wall code | — |

**How it works.** Each sharing screen registers on the PeerJS network as
`bomba-<code>-<device name>`. Every watching screen calls each device on the
list, so any number of screens can watch any number of devices at the same time.

**`bomba-wall.js` is the shared piece — keep it identical in every repo.**
It adds:
- **Shared device list.** Add or remove a device name on any screen and every
  other screen with the same code picks it up within seconds.
- **Relay (TURN) servers**, so a phone on cell data can reach a computer on
  Wi‑Fi. These are PeerJS's free public relays (best effort). To use your own,
  see the comment at the top of `bomba-wall.js`.

If you change `bomba-wall.js`, copy the new file into the other repos.
Don't change the peer id format (`bomba-<code>-<device>`) in only one app, or
that app will drop off the wall.
