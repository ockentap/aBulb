# aBulb

A *little* bulb app. Native Android control of a BLE-mesh smart bulb (tested with
the LEDVANCE Smart+) directly from your phone — no hub, no cloud, no home server,
no 300 MB vendor app with an ad SDK and a login wall.

The big-brand bulb apps all want an account, your wifi password, and a server in
someone else's datacenter. aBulb is ~2.5 MB, needs no network permission at all,
and the only thing it ever talks to is the radio in the bulb.

The phone joins the mesh as a proxy client and speaks the Bluetooth Mesh protocol
itself (Nordic nRF Mesh library). Brightness is driven via the standard Mesh Light
Lightness model. It can also **provision a factory-fresh bulb itself** — no nRF Mesh
app, no Raspberry Pi, no other tool.

## What you need

- An Android phone (Android 12+ recommended, Bluetooth LE required)
- A BLE-mesh bulb (tested with the LEDVANCE Smart+)
- Then either **pair it from the app** (if it's factory-fresh), or **import the keys**
  of the network it already belongs to

## A. Pair a bulb from the app (no other tools needed)

1. The bulb must be **unprovisioned**: power-cycle it 6 times quickly (off 1s, on 1s,
   repeat) until it stops remembering its old network, or factory-reset it in whatever
   app owns it now.
2. Open aBulb → **⋯** (top right) → **Pair a new bulb** → *Start pairing*.
3. The app creates a **brand-new mesh network owned by this phone**, runs the full
   provisioning handshake over GATT, adds the app key and binds the light model. The
   status line narrates each step.
4. When it finishes, the keys are stored on the phone. Hand them to another phone with
   **⋯ → Keys & sharing → Key file**.

Pairing does **not** steal a bulb that already belongs to a network — it only joins an
unprovisioned one. A bulb that is in a network you can reach should be added with keys
instead (option B).

## B. Import the keys of an existing network

You need three 128-bit hex values: the **network key**, the **app key**, and the
bulb's **device key**. They live wherever the network was created:

- **nRF Mesh app** (Android/iOS, free): open the network → Export → Network JSON.
- **A Raspberry Pi or any other provisioner**: the state file (typically JSON with
  `netKeys`, `appKeys` and a per-node `deviceKey`).
- **Another aBulb install**: ⋯ → Keys & sharing → share the key file.

Then on the phone: **⋯ → Keys & sharing** → paste the three values (or *Import file*
an `abulb-keys.json`: `{"format":"abulb-keys-v1", "netKey":..., "appKey":...,
"deviceKey":..., "mac":..., "bulbUnicast":...}`) → Save.

Optional fields: the bulb's **MAC** (forces connecting to one specific device) and its
**unicast address** (defaults to `0x0002`).

Note: bulb firmware is often picky — a power cycle (off → wait 5s → on) resets a failed
provisioning state.

## Installing

1. Download the latest `app-release.apk` from the [Releases](../../releases) page.
2. Install it (allow "install from unknown sources" when prompted).
3. Add the keys (option B) or pair the bulb (option A).
4. Power-cycle the bulb and make sure it's powered — it advertises the mesh proxy only
   while it has power.
5. That's it: the app connects by itself on launch and the orb shows the bulb's current
   level. Drag the slider or tap a preset to change it.

## Using it

- **The orb is the readout** — the current level renders inside it, and it glows and
  breathes while the bulb is connected.
- **Slider** — drag for any level; it's sent live as you drag. **Presets** — off, ember,
  low, max (they ramp rather than jump).
- **Double-tap the orb** to toggle between off and the last level it was lit at.
- **One button**, Connect / Disconnect. You'll rarely need it: the app connects on
  launch and manages the radio itself.
- **⋯** holds everything else: *Keys & sharing* (enter, import, share, clear keys),
  *Pair a new bulb*, *Disconnect*, and *Help & diagnostics* — which shows the mesh
  address this install is using and whether the bulb is answering.

## Two phones, one bulb

Both can control it, with one caveat in each direction:

- **Writes are not exclusive.** Any device holding the network + app keys can address
  the bulb, and it acts on the latest message it hears. Each aBulb install sends from its
  **own** mesh address (chosen on first run), because the bulb keeps a *replay-protection
  entry per source address*: two installs sharing an address — or one install whose
  sequence counter restarted — are silently ignored while the app still reports
  *connected*.
- **The BLE link is exclusive.** A bulb's GATT proxy generally accepts one connection at
  a time, so two phones can't be attached to the same bulb at once. aBulb handles that for
  you: it holds the radio while you're actually changing the level, hands it back a few
  seconds after your last change (and immediately when you leave the app), and silently
  takes it back — re-applying whatever you asked for — the moment you change the level
  again or return to the app. Browsing menus doesn't hold it, and the screen keeps
  reading *connected* throughout: the app does have the bulb, only the radio is shared.

For genuinely simultaneous links, add a second always-powered mesh node (a second bulb,
a dev board) and have each phone attach to a different one.

## Troubleshooting

- **Bulb ignores your changes** — the write isn't landing. The app notices two unanswered
  changes and re-joins under a fresh mesh source address by itself, which clears a stale
  replay-protection entry at the bulb; **Fix link** in the app does the same thing on
  demand. If neither helps, the bulb is very likely on a different network than your
  keys: re-import the keys, or pair it again.
- **"Proxy not found"** — the bulb isn't advertising the proxy; power-cycle it and keep it
  powered. It advertises only while on.
- **"No response from bulb (timeout)"** — the keys are probably for a different network;
  re-export them.
- **"Decryption failed"** — keys match a different network or wrong device key.
- **The phone says connected while another phone is using it** — expected: the radio is
  handed back and re-taken automatically, and the other phone's changes are not undone.
  Only one phone can hold the radio at a time.
- **Crashes** — the app shows the previous crash's stack trace on next start; open an
  issue with it, please.

## Building

Standard Android/Gradle project:

```bash
sdk.dir=/path/to/Android/sdk   # local.properties
./gradlew :app:assembleRelease
```

Every push to `main` builds an APK as a workflow artifact, and every `v*` tag publishes a
[release](../../releases) with the APK attached. That APK is keyless: it carries no
credentials, and you pair a bulb or import your own keys on first run.

Built against `no.nordicsemi.android:mesh:3.3.7` (Mesh Profile / provisioner stack)
and `no.nordicsemi.android:ble:2.6.1` for the proxy GATT client. The proxy data
characteristics used: 0x2ADD (write) / 0x2ADE (notify), service 0x1828; provisioning
uses service 0x1827.

## Privacy

The app contains no analytics, no network permission, and talks only to the bulb's
radio. Keys are stored in the app's private preferences on your device, and the APK
published here contains none of them.

## License

MIT — see LICENSE.
