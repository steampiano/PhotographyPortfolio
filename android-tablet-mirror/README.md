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

**Security.** The host mints a random 6-digit PIN per session. Both sides derive
a session key from the PIN plus fresh nonces (PBKDF2-HMAC-SHA256, 120k
iterations) and prove knowledge of it with domain-separated HMACs before any
screen content moves. Everything after that is AES-256-GCM with per-direction
implicit counters, and the message type is bound in as additional authenticated
data. See the threat model note in `Handshake.kt` for what this does and does not
buy you.

### Source map

| Path | Role |
| --- | --- |
| `net/Protocol.kt` | Wire format and payload codecs |
| `net/MessageChannel.kt` | Length-prefixed transport, encryption switchover |
| `net/SecureChannel.kt` | AES-GCM sealing with implicit sequence numbers |
| `net/Handshake.kt` | PIN-authenticated key agreement |
| `net/Discovery.kt` | mDNS advertise / browse |
| `host/ScreenCaptureService.kt` | Foreground service owning the capture session |
| `host/ScreenEncoder.kt` | VirtualDisplay → H.264 |
| `host/HostServer.kt` | Accepts one viewer, streams video, applies input |
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
2. Tap **Turn on remote control** and enable *Tablet Mirror remote control* in
   Settings → Accessibility. Skip this and the tablet can be watched but not
   touched. Android cannot grant this itself — only you can.
3. Pick a quality, then **Start sharing** and accept the system screen-capture
   prompt.
4. Note the 6-digit PIN and the listed address.

**On the controlling tablet (viewer):**

1. Open the app → **Control another tablet**.
2. Pick the host from the discovered list, or type its address.
3. Enter the PIN and **Connect**.

The viewer's bottom bar has Back / Home / Recents / Notifications, text entry,
and a **View only** toggle for watching without touching. `Controls` hides the
bar.

### Quality presets

| Preset | Longest edge | Bitrate | Use when |
| --- | --- | --- | --- |
| Low | 960 px | 2.5 Mbps | Congested or 2.4 GHz Wi-Fi |
| Balanced | 1280 px | 6 Mbps | Default |
| High | 1600 px | 10 Mbps | Good 5 GHz link |
| Native | unscaled | 14 Mbps | Reading small text, fast link only |

## Limits and honest caveats

**Not yet run on hardware.** 70 JVM unit tests pass, covering the wire protocol,
AES-GCM channel, PIN handshake (over a real loopback socket), coordinate
geometry, and the gesture scheduling state machine. Everything that touches the
Android framework — capture, encode, decode, stroke dispatch, the UI — was
written against the documented APIs but has **not been compiled or executed on a
device**, because this was developed in an environment with no Android SDK and no
access to Google's Maven repository. Expect to shake out real-device issues on
first run, particularly around codec behaviour, which varies by chipset.

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

**The PIN is a session secret, not a password.** It is regenerated on every start
for a reason: a 6-digit PIN is only safe while it is short-lived. Do not modify
this to use a fixed PIN.

**Sharing is always visible.** An ongoing notification stays on the shared tablet
for as long as capture is active, and the app never tries to hide it. If you need
different behaviour than that, this is the wrong codebase to start from.
