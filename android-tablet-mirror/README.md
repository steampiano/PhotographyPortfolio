# Tablet Mirror

An Android app that mirrors one tablet's screen onto another over the local
network and lets the second tablet drive the first — tap, swipe, drag, navigate
and type.

One APK covers both ends. On launch you pick a role:

| Role | What the tablet does |
| --- | --- |
| **Share this tablet's screen** (host) | Captures its own screen, waits for a viewer, applies the input it receives. |
| **Control another tablet** (viewer) | Connects to a host, shows its screen, forwards your touches. |

## How it works

```
   HOST (shared tablet)                        VIEWER (controlling tablet)
   ────────────────────                        ──────────────────────────
   MediaProjection                             AspectRatioSurfaceView
        │ screen pixels                                  ▲ decoded frames
        ▼                                                │
   VirtualDisplay ──▶ MediaCodec (H.264) ──┐   ┌──▶ MediaCodec decoder
                                           │   │
                                     ┌─────┴───┴─────┐
                                     │  TCP socket   │  PIN handshake,
                                     │  AES-GCM      │  then encrypted
                                     └─────┬───┬─────┘
                                           │   │
   AccessibilityService ◀── touches ───────┘   └──── MotionEvent
   (dispatchGesture)                                 (normalised 0..1)
```

**Video.** `MediaProjection` renders the screen directly into a hardware H.264
encoder's input surface, so frame pixels are never copied through the app
process. Frames are length-prefixed onto a TCP socket and decoded straight onto
a `SurfaceView` on the viewer. Output buffers are released for display as soon
as they decode rather than being paced against their timestamps: for a control
session, low latency beats smooth playback.

**Control.** A non-system app cannot inject `MotionEvent`s, so remote input goes
through an `AccessibilityService` and `dispatchGesture`. That API describes whole
gestures rather than individual events, which shapes the whole design: a live
pointer stream has to be turned into short stroke segments chained end to end.
That scheduling logic lives in `GestureStateMachine`, kept free of Android types
so it is unit tested directly; `GestureInjector` is the thin platform half that
builds the actual `StrokeDescription`s.

**Coordinates** travel as 0..1 fractions of the host screen, so the two tablets
need not share a resolution, density or aspect ratio. The viewer's video view
measures itself to the host's aspect ratio, so its bounds *are* the picture and a
tap on a letterbox bar can never be mistaken for a tap on the host.

**Security.** Two tablets pair once, then trust each other by identity — the same
shape as TLS 1.3 or Noise IK with pinned keys, and no PIN to steal.

- Each tablet has an **EC P-256 identity key generated inside the Android
  Keystore**, non-exportable and hardware-backed where the device has a TEE or
  StrongBox. A compromised tablet cannot hand its identity to another device.
- Every session does an **ephemeral ECDH exchange**, so keys are **forward
  secret**: capturing the traffic and later stealing both tablets does not decrypt
  what was recorded, because those keys no longer exist.
- Both sides **sign a transcript hash** covering the version, both ephemeral keys,
  both identity keys and the pairing flag. Verifying that against a *pinned*
  identity is the authentication step. Anything altered in flight breaks it.
- **Only pinned identities may connect.** An unknown device is refused before any
  key agreement, unless an operator has deliberately opened a two-minute pairing
  window on both tablets.
- First pairing uses **numeric comparison, not a typed PIN**: both tablets show a
  six-digit code derived from the completed exchange and someone confirms they
  match. A PIN is a secret an attacker can capture and grind offline; this code
  verifies an exchange that already happened, so an attacker in the middle would
  have to produce matching codes on both screens — one chance in a million, and
  detected the instant they differ.
- Traffic is **AES-256-GCM** with a separate key per direction and implicit
  sequence counters, message type bound in as additional authenticated data.
  Tampering, replay, reordering and reflection are all fatal to the connection.
- Repeated rejected handshakes **throttle** further attempts.

`Handshake.kt` states the residual threat model plainly: this does not defend
against a tablet that is itself compromised, or an operator who confirms a code
without looking at the other screen.

### Source map

| Path | Role |
| --- | --- |
| `crypto/Identity.kt` | Identity keys, verification, fingerprints |
| `crypto/KeystoreIdentity.kt` | Hardware-backed identity in the Android Keystore |
| `crypto/Hkdf.kt` | HKDF-SHA256 (RFC 5869), checked against the RFC vectors |
| `crypto/TrustStore.kt` | Pinned peer identities |
| `net/Handshake.kt` | Signed ephemeral ECDH, pinning, pairing |
| `net/PairingGate.kt` | Pairing window and the code-comparison prompt |
| `net/Protocol.kt` | Wire format and payload codecs |
| `net/MessageChannel.kt` | Length-prefixed transport, encryption switchover |
| `net/SecureChannel.kt` | AES-GCM sealing with implicit sequence numbers |
| `net/Discovery.kt` | mDNS advertise / browse |
| `host/ScreenCaptureService.kt` | Foreground service owning the capture session |
| `host/ScreenEncoder.kt` | VirtualDisplay → H.264 |
| `host/HostServer.kt` | Accepts one paired viewer, streams video, gates input |
| `host/HostSettings.kt` | Host-side policy, including the control gate |
| `host/ConnectionLog.kt` | Local audit trail of connections and pairings |
| `host/MirrorAccessibilityService.kt` | Gesture / navigation / text injection |
| `host/GestureStateMachine.kt` | Pointer stream → gesture segments (pure, tested) |
| `host/GestureInjector.kt` | Segments → chained `StrokeDescription`s |
| `viewer/ViewerConnection.kt` | Client socket, read and send loops |
| `viewer/VideoDecoder.kt` | H.264 → Surface |
| `util/Geometry.kt` | Letterbox fitting, coordinate mapping, encoder sizing |

## Building

Needs the Android SDK (compileSdk 35) and JDK 17+. The Gradle wrapper JAR is not
committed, so either open the project in Android Studio or generate the wrapper
once:

```bash
cd android-tablet-mirror
gradle wrapper          # only needed once, if not using Android Studio
./gradlew assembleDebug
./gradlew test          # JVM unit tests
```

Install on **both** tablets:

```bash
./gradlew installDebug
```

## Setting up a pair

Both tablets must be on the same network, and that network must allow direct
device-to-device connections — many guest and corporate Wi-Fi networks isolate
clients, which blocks this regardless of app.

**On the tablet to be controlled (host):**

1. Open the app → **Share this tablet's screen**.
2. Pick a quality, then **Start sharing** and accept the system screen-capture
   prompt. Note the address it shows.
3. If you want the other tablet to be able to *touch* this one:
   - Turn on **Let the paired tablet touch this screen**. This is **off by
     default**, and it is enforced here — with it off, input arriving from the
     viewer is discarded by this tablet rather than merely discouraged.
   - Enable *Tablet Mirror remote control* in Settings → Accessibility. Android
     cannot grant this itself; only you can.
4. Tap **Pair a tablet**. That opens a two-minute window.

**On the controlling tablet (viewer):**

1. Open the app → **Control another tablet**.
2. Pick the host from the discovered list, or type its address.
3. Tick **Pair with this tablet for the first time**, then **Connect**.

Both tablets now show the same six-digit code. **Compare them.** If they match,
tap *Codes match* on both. If they differ, tap *They differ / cancel* — something
is between your tablets.

After that first pairing, neither tablet asks anything again: the kitchen tablet
connects and authenticates on its own, including after a reboot or a Wi-Fi drop.

The viewer's bottom bar has Back / Home / Recents / Notifications, text entry,
and a **View only** toggle. `Controls` hides the bar.

**Paired tablets** and **connection history** are both visible on the host
screen, so you can see which device is trusted and when it last connected. Use
**Unpair** to revoke a tablet; it cannot reconnect afterwards without a fresh
pairing on both sides.

### Quality presets

| Preset | Longest edge | Bitrate | Use when |
| --- | --- | --- | --- |
| Low | 960 px | 2.5 Mbps | Congested or 2.4 GHz Wi-Fi |
| Balanced | 1280 px | 6 Mbps | Default |
| High | 1600 px | 10 Mbps | Good 5 GHz link |
| Native | unscaled | 14 Mbps | Reading small text, fast link only |

## Running this in front of a POS

This was built for a specific job: mirroring a cashier tablet's point-of-sale
screen to a kitchen tablet that can also drive it. That setting deserves some
plain warnings that have nothing to do with code quality.

**Card data and PCI scope.** Mirroring a POS screen puts whatever is on that
screen onto your network. If the POS ever displays full card numbers, or takes PIN
entry on the tablet's glass, then both tablets and this app fall inside PCI DSS
scope, with everything that implies for you as a merchant. Check what your POS
actually renders before relying on this.

Well-behaved payment apps mark those screens `FLAG_SECURE`, which makes them
appear **black** in the mirror. That is the POS protecting cardholder data and
working exactly as intended. This app contains nothing to circumvent it, and
adding such a thing would be both a compliance and a security failure. If the
kitchen needs to see payment screens, the answer is a change to your workflow,
not to this code.

**The kitchen tablet is as powerful as the cashier tablet.** With control on, it
can do anything a person standing at the till could: void a sale, issue a refund,
open the cash drawer, change settings. That is a till-fraud consideration, not
just a security one. Two mitigations, both worth taking:

- Leave **Let the paired tablet touch this screen** off unless the kitchen truly
  needs to act. If they only need to read orders, view-only is a strictly smaller
  risk and gives up nothing.
- Use your POS's own roles and permissions. This app cannot distinguish a refund
  from a tap on a coffee button; your POS can.

**Network.** A dedicated store network is the right call. To get the most from it:

- Keep the tablets on their own VLAN or SSID with **client isolation off** (they
  need to reach each other) and no route to guest Wi-Fi.
- WPA3, or WPA2-AES with a long random passphrase that is not the guest password
  and not written on the router.
- Do **not** port-forward the mirror port from the internet. There is no reason
  to, and the app is not designed to face it.
- The connection is encrypted and mutually authenticated regardless, so a
  compromised Wi-Fi password alone does not let anyone connect or watch. That is
  defence in depth, not a licence to skip the above.

**Physical access.** Anyone holding an unlocked kitchen tablet inherits its
pairing. Set a screen lock on both tablets. The identity key is hardware-backed
and cannot be copied off a device, but it can be *used* by whoever is holding one.

**Auditing.** The host records pairings, connections, disconnections and refused
attempts, viewable under *View connection history*. Check it after installation to
confirm only your two tablets appear, and glance at it if anything looks off. It
is capped at 300 entries and never leaves the tablet.

## Limits and honest caveats

**Not yet run on hardware.** 96 JVM unit tests pass, covering the wire protocol,
the AES-GCM channel, HKDF against the RFC 5869 vectors, the full handshake over
real loopback sockets (including impostor, unpaired-device and declined-pairing
rejection), the pairing gate, coordinate geometry, and the gesture scheduling
state machine. Everything that touches the Android framework — capture, encode,
decode, stroke dispatch, the Keystore, the UI — was written against the documented
APIs but has **not been compiled or executed on a device**, because this was
developed in an environment with no Android SDK and no access to Google's Maven
repository. Expect to shake out real-device issues on first run, particularly
around codec behaviour, which varies by chipset.

Because the security model matters here, be specific about what that does and
does not cover. The handshake logic, key derivation, pinning and pairing rules are
exercised end to end on real sockets with real ECDH and real signatures, so the
protocol itself is tested. What is *not* tested is the one piece the JVM cannot
reach: `KeystoreIdentity`, which asks Android for a hardware-backed key. Verify on
first run that the host screen reports **"held in secure hardware"** rather than
"software-backed key". Either is functional and both are non-exportable, but the
hardware-backed path is the stronger claim and the one worth confirming.

**Remote control needs an accessibility service.** Unavoidable for a
user-installed app. Consequences:

- Gestures are dispatched as short chained segments, so input has a small
  inherent lag (tens of milliseconds) beyond network latency.
- Multi-touch is forwarded (up to 10 pointers). The scheduling is unit tested,
  but it is still the least certain part in practice: chaining several strokes
  across dispatches leans on platform behaviour that varies more between Android
  versions than single-pointer gestures do.
- Text entry uses `ACTION_SET_TEXT` on the focused node, so it appends to a
  focused editable field rather than typing keys. Fields that reject programmatic
  text, and anything that needs a real key event (Enter, Tab, arrow keys), will
  not respond.
- Some system surfaces cannot be touched by an accessibility service at all.

**A sleeping host captures black.** The service holds a partial wake lock, which
keeps the CPU alive but cannot keep the display on. Set a long screen timeout on
the shared tablet.

**DRM-protected content renders black** in the capture. That is enforced by the
platform, not something the app can or should work around.

**One viewer at a time.** A second connection is refused with a clear message.
Two controllers fighting over one gesture injector would produce nonsense.

**Local network only.** No relay, no NAT traversal, no internet path.

**Pairing is the trust decision.** Do not change the pairing window to stay open,
and do not make the code auto-confirm. Both would turn an authenticated channel
into an open one.

**Sharing is always visible.** An ongoing notification stays on the shared tablet
for as long as capture is active, and the app never tries to hide it. If you need
different behaviour than that, this is the wrong codebase to start from.
