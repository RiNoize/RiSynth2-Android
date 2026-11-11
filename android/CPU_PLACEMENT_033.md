# RiSynth2 Android 0.3.3 — Audio CPU placement

Source: exact `audio_cpu_placement.h` from RiMU2000-Android commit `ecbeabf6d84264f44a4b285ae09c56cf1c1800e0`, BSD-3-Clause. Original 12 selection tests are also preserved.

## Architecture

RiSynth2 renders all voice, filter, LFO and effects work in **one** native AAudio data callback. No DSP producer queue and no concurrent audio workers. Only that callback TID is a CPU-placement owner. The Java UI, file library, MIDI input and ADPF worker are not pinned.

Before starting AAudio, on the serial control worker, prepare the CPU topology from `/sys/devices/system/cpu`. On the first callback per TID, get its own original affinity via `sched_getaffinity(0)`, select a permitted maximum-capacity subset, apply via `sched_setaffinity(0)`, then read back the effective mask. Later callbacks only compare the TID and sample the CPU. No sysfs I/O, string building, or repeated configuration in normal rendering. A callback after stop attempts restoration from itself; otherwise closing AAudio ends the callback owner. Never target a stale TID from another thread.

Capacity is preferred when complete and nonuniform. Only if capacity is incomplete does the selector consider complete, nonuniform maximum-frequency metadata. Missing/uniform data or denied affinity leaves the original placement. The code never hardcodes A9 CPUs 6/7 or changes priority, governor, sample rate, buffer, voices or DSP maths. ADPF remains an independent optional checkbox.

The Audio dialog persists **Automático** (default) or **Núcleos de rendimiento**, applied by reopening the stream. The monitor and copied report include owner TID, criterion, original/requested/effective CPU masks, sampled CPU IDs and errno. CPU samples are not a continuous scheduling trace; a successful mask is not a two-thread synthesizer.

## Validation and safe comparison

`android/test-host.sh` runs RiMU2000's 12 pure selector tests, including restricted/noncontiguous CPU masks and IDs over 63; host tests do not change actual affinity. Existing MIDI, imports, favorites, audio smoke, exact engine regressions and ADPF tests are retained. CI builds native ARM64 and signs the Preview package with the pinned certificate.

On the Tab A9 compare exactly the same patch and notes with 2048 requested frames. Run Automático and Rendimiento in the same APK and same audio route/ADPF setting, measure **actual callback frames**, sample rate, Render average and peaks, OV, XRun increments and thermal status. Repeat in reverse order, then try smaller buffers. No performance gain is promised without device tests.

RiMU2000's earlier measured Render improvement (127.8%→54.3%) also involved a separate resampler change, so it cannot be attributed entirely to affinity; nor does affinity reduce the number of synth DSP operations.
