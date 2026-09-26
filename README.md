# aBulb

A *little* bulb app. Native Android control of a BLE-mesh smart bulb (tested with
the LEDVANCE Smart+) directly from your phone — no hub, no cloud, no home server,
no 300 MB vendor app with an ad SDK and a login wall.

The big-brand bulb apps all want an account, your wifi password, and a server in
someone else's datacenter. aBulb is ~2.5 MB, needs no network permission at all,
and the only thing it ever talks to is the radio in the bulb.

The phone joins the mesh as a proxy client and speaks the Bluetooth Mesh protocol
itself (Nordic nRF Mesh library). Brightness is driven via the standard Mesh Light
Lightness model.

## What you need

- An Android phone (Android 12+ recommended, Bluetooth LE required)
- A BLE-mesh bulb that has already been **provisioned onto a mesh network**
- The three mesh keys of that network (128-bit hex values): network key, app key,
  and the bulb's device key

## Getting the keys (via a Raspberry Pi)

If you already have a provisioned bulb and a Raspberry Pi running the open-source
[nRF Mesh python stack](https://github.com/NordicSemiconductor/Python-BLEMesh)
(or any other mesh provisioner), the keys live in the provisioner's state file
(typically a JSON blob containing `netKeys`, `appKeys`, and per-node
`deviceKey`). Export the three values, and you're set.

If your bulb is **not yet provisioned**, you can provision it with the official
**nRF Mesh** mobile app (Android/iOS, free), then do the same key export from its
network backup feature, or from any Pi-based provisioner. There are many guides
online for "provisioning a generic BLE mesh bulb with nRF Mesh" — the short
version: power-cycle the bulb, scan, find the "LEDVANCE ..." unprovisioned device,
provision it with default settings, then export the network.

Note: bulb firmware is often picky — a power cycle (off → wait 5s → on) resets the
provisioning state and is required after some failures.

## Installing

1. Download the latest `app-release.apk` from the [Releases](../../releases) page.
2. Install it (allow "install from unknown sources" when prompted).
3. Open the app → tap **⋯** (top right) → **Keys & sharing** → paste the 3 keys by hand, or tap
   **Import file** and pick an `abulb-keys.json` (`{"format":"abulb-keys-v1", "netKey":..., "appKey":...,
   "deviceKey":..., "mac":..., "bulbUnicast":...}`). Optionally add the bulb's MAC and
   unicast address (defaults to `0x0002`) → Save → restart the app.
4. Power-cycle the bulb and make sure it's powered (it advertises the mesh proxy
   only while powered).
5. Tap **Connect**. The status shows *finding bulb… → connecting… → connected*,
   then the bulb's current level appears and the slider controls it.

The phone joins as its own mesh node — it picks its own source address on first run — so it coexists
with your Pi/other controllers: sequence numbers are tracked per source address, so the Pi and the
phone never fight over one counter.

## Two phones, one bulb

Both can control it, with one caveat in each direction:

- **Writes are not exclusive.** Any device holding the network + app keys can address the bulb, and
  it acts on whatever arrives. Each aBulb install sends from its **own** mesh address (chosen on
  first run), because the bulb keeps a *replay-protection entry per source address*: two installs
  sharing an address — or one install whose sequence counter restarted — are silently ignored while
  the app still reports *connected*.
- **The BLE link is exclusive.** A bulb's GATT proxy generally accepts one connection at a time, so
  two phones can't be attached to the same bulb simultaneously. Take turns, or add a second
  always-powered mesh node (a second bulb, a dev board) and have each phone attach to a different
  one.

If it says *connected* while the bulb ignores you, tap **Fix link** — that re-joins from a freshly
chosen mesh address, which clears the replay-guard case. Still nothing? Then the bulb is very likely
on a different network than your keys: re-import the keys, or pair it again.

## Troubleshooting

- **"Add your mesh keys"** — open the Keys dialog and paste the exported keys.
- **"Proxy not found"** — the bulb isn't advertising the proxy; power-cycle it and keep
  it powered. It advertises only while on.
- **"No response from bulb (timeout)"** — the keys are probably for a different network;
  re-export them.
- **"Decryption failed"** — keys match a different network or wrong device key.
- **Connected, but brightness doesn't change** — the write isn't landing. Tap **Fix link**: this
  install re-joins from a fresh mesh source address, clearing a stale replay-protection entry at the
  bulb. If that doesn't help, the bulb is on a different network than your keys.
- **Crashes** — the app shows the previous crash's stack trace on next start;
  open an issue with it, please.

## Building

Standard Android/Gradle project:

```bash
sdk.dir=/path/to/Android/sdk   # local.properties
./gradlew :app:assembleRelease
```

Built against `no.nordicsemi.android:mesh:3.3.7` (Mesh Profile / provisioner stack)
and `no.nordicsemi.android:ble:2.6.1` for the proxy GATT client. The proxy data
characteristics used: 0x2ADD (write) / 0x2ADE (notify), service 0x1828.

## Privacy

The app contains no analytics, no network permission, and talks only to the bulb's
radio. Keys are stored in the app's private preferences on your device.

## License

MIT — see LICENSE.
