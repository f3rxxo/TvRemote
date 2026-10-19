# TV Remote (Kotlin port)

Native Android port of the Termux/Python Google TV remote. Speaks the Android
TV Remote Protocol v2 directly - no server, no Termux, just the APK.

## How it was built

The protocol details (message framing, pairing handshake math, certificate
parameters) were extracted directly from the working `androidtvremote2`
Python library's source and compiled `.proto` descriptors, not
reverse-engineered independently. The two `.proto` files under
`app/src/main/proto/` are reconstructed field-for-field from that reference.

## Building

Push to GitHub with Actions enabled and the workflow in
`.github/workflows/build.yml` builds a debug APK on every push, and it shows
up under the run's "Artifacts" section as `tvremote-debug-apk`.

To build locally instead: `gradle assembleDebug` from the project root (needs
JDK 17 and an Android SDK; Android Studio handles both automatically if you
open the project there).

## First build expectations

This was written and assembled without a local Android toolchain to compile
against, so treat the first CI build the way we treated the early Python
versions: if it fails, paste the error log back and it'll get fixed. Once it
compiles, the actual TV protocol logic (pairing, keys, text) was ported
line-for-line from the already-proven Python implementation, so it should
behave the same way the working Termux script did.

## Feature parity with the Termux version

- Pairing (enter the 6-character code shown on the TV)
- D-pad, OK, Home, Back, Power, Volume, media transport keys
- Press-and-hold on OK/Left/Right for long-press actions (Nuvio submenu,
  fast-seek)
- Native-keyboard mode (D-pad-driven TV keyboard) and IME-typing mode
  (raw text injection), switchable without restarting
- Gesture pad (swipe/tap/hold) as an alternative to the D-pad buttons
- Text Send/Clear, sending raw and unclearing exactly like the final
  Python version - no auto-diffing or auto-clearing logic to fight with
