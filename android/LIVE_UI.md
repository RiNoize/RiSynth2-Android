# RiSynth2 Android — live-performance layout ("Hardware ámbar")

A second layout for playing on stage. It is chosen automatically on **landscape screens at least 700 dp wide** (the Galaxy Tab A9 in landscape). Phones and portrait screens keep the classic scrolling layout, which is unchanged apart from the accent colour. Rotating the device rebuilds the layout; nothing else about audio, MIDI, import or favorites changes. No DSP, `.sy1`, parameter index or native code was touched.

The palette and every drawing routine are original to this app (`Look`, `KnobView`, `StripView`, `PianoView`); no third-party artwork or Synth1 bitmap is used.

## What is on screen

- **Header**: name and version, the monitor, then Iniciar/Detener audio, Sonidos, Audio, MIDI, Editar and Pánico.
- **Monitor** (always visible, tap for the full dialog with Copy report and Reset): line 1 is the audio state, actual buffer, CPU app, Render, OV and XRuns, taken from the same counters as the classic monitor (`DiagnosticMonitor.brief()`); line 2 is the CPU placement mode and status. Nothing in the dialog changed.
- **Status line**: last message (tap to read it in full) and the MIDI input name.
- **Display**: bank and slot, big slot number and patch name, previous/next sound, and a ☆/★ button that saves the current sound to Favorites exactly as the classic "Guardar favorito" does (★ means the sound being shown belongs to a favorites bank).
- **Quick slots**: the page of 8 slots (1–8, 9–16 …) that contains the current sound, from the **current bank**. Tap one to load it; empty slots are dimmed. Previous/next moves across pages. This is not a separate favorites list: to play from favorites, open Sonidos → Favoritos and pick a favorites bank.
- **Knobs** (drag up/down; about 170 dp of travel covers the full range): Cutoff (19), Reso (20), Env = filter amount (21), Attack (25), Release (28), Delay mix (37), Chorus level (56), Volumen (0–100, the same master volume as before). A short **tap** on the Delay or Chorus knob switches that effect on or off (parameters 65 and 66); the knob dims when it is off.
- **Pitch** (spring-return pitch bend, 14-bit, centre 8192) and **Mod** (CC 1, stays where you leave it), octave buttons showing the keyboard range, and the two-octave multitouch keyboard (pressed keys are amber).
- **Sonidos** menu: banks, favorites, import, export and INIT. **Editar** dialog: ARP, Delay, Chorus, Unison and Effect switches, OSC mix, OSC 1 detune, sub oscillator, Decay, Sustain, polyphony, tempo, and the list of all 99 parameters.

## Decisions to review

- ARP, Unison and Effect on/off exist only in Editar; Delay and Chorus on/off are on their knobs.
- Pitch springs back to centre on release; Mod holds its value. Rebuilding the layout (rotation) resets the Pitch strip and keeps the Mod value shown.
- The knob parameters were chosen from the design; swap them in `buildLiveUi` if you prefer others.

## Manual test plan (a green CI build is not a device test)

1. Install the Preview APK on the tablet in landscape: the amber layout appears; rotate to portrait: the classic layout appears; rotate back.
2. Header: audio starts/stops; the monitor updates twice a second and still shows OV/XRuns; tapping it opens the dialog; Pánico silences held notes.
3. Play chords with several fingers while turning two knobs at once: no stuck notes, knobs follow the finger without jumping, no accidental toggle after a drag.
4. Tap Delay and Chorus: sound changes and the Editar dialog switches agree; load another patch: the knobs and switches follow it.
5. Pitch bend returns to centre on release (no detune left); Mod works and survives changing sounds.
6. Save a favorite with the star; open Favoritos; select it from the quick slots after reopening the app.
7. Compare Render, OV and XRuns with the classic layout (rotate to portrait) at the same buffer and CPU mode, while playing and while turning knobs, to confirm the new views add no audible or measured load. This has not been measured.
8. Check tap targets and legibility at stage distance and in bright light; report anything under about 48 dp that is hard to hit.

## Tests

`ControlMath` (knob drag, arc angles, strip mapping, pitch-bend bytes) and the monitor summary line run in `android/test-host.sh`. Touch handling and drawing need a device.

## Corrections in the RiNoize integration

The star is now filled only if the **exact current .sy1 snapshot including its edited parameters** belongs to the favorites collection; it is independent of whether the currently selected bank is a favorites bank. Membership uses an in-memory SHA-256 index, refreshed off the UI thread and debounced while turning controls, with no disk operations in the audio callback. When an edited sound differs, the star empties until that edit is saved as a new favorite.

Changing orientation or leaving the app sends MIDI pitch bend centre if the strip was not centered; the Mod strip still retains its visual setting. Delay and Chorus knobs are labelled **Delay Mix** and **Chorus Mix**, with ON/OFF indicators and tap feedback. Native DSP, audio-affinity placement and ADPF are untouched. Android compilation and on-device appearance still require build/device validation.
