# RiSynth2 0.3.2 — optional Android performance hints and exact LPDL cache

## Two independent changes

The user authorized points 1 and 2 of the external review only. No affinity,
float32 conversion, approximate sine/pow, extra DSP render threads, voice cap,
quality reduction or hidden buffer adjustment is added. The synthesis engine
remains single-threaded. The new worker only talks to Android's hint service.

### 1. Audio → Ayuda de rendimiento (ADPF · experimental)

Off by default; the explicit setting is saved. Applying audio settings stops and
reopens the stream, as before, so OFF/ON can be compared using the same patch,
notes, output, low-latency mode and buffer. Existing banks/favorites/settings and
Preview application ID/signing certificate are preserved. This is an option for
experimentation, not proof that Samsung's implementation improves this workload.

The callback publishes its own kernel TID, measured wall work duration, actual
callback audio duration (`frames / sampleRate`) and completion timestamp through
a fixed 64-slot single-producer/single-consumer queue. It never allocates, locks,
waits, performs JNI/Binder calls or creates a hint session. It does not register
the Java UI thread or the reporting worker as the audio workload. Queue-full
samples are dropped rather than delaying audio; this is separate from MIDI loss.

A persistent non-real-time worker polls every 1 ms. It alone calls the public
Android 13/API 33 `APerformanceHint` manager/create/update/report/close functions.
Session APIs are not thread safe; one owner handles all of them. A new callback
TID closes/recreates the session. Actual callback-size changes update the target.
The target is explicitly **80% of the actual callback audio duration**, leaving
20% scheduling headroom. It is NOT based on requested output buffer size, and
actual work time is never inflated to simulate an artificial load.

Each completed callback is normally reported. If the worker falls behind, it
keeps the newest pending sample rather than rapidly replaying an old backlog.
It discards invalid/old samples (older than max(20 ms, 2 callback periods)).
Reports/skips/queue-full counts and queue age are visible. Idle sessions close
if callbacks disappear for 250 ms. Stop/error/route/app-pause shuts down the
session off the real-time thread; stop joins only after audio callbacks stop.
Unavailable manager/session or API errors disable hinting for that run without
preventing normal audio. Restarting/reapplying settings permits another attempt.

Monitor status distinguishes OFF, waiting, active session, unavailable, error,
and stopped. An **active session only means creation/report calls succeeded**;
it does not prove that Android changed frequency, used a fast CPU core or reduced
underruns. No core affinity is forced. CPU app includes the extra worker, whereas
Render does not include the worker's service calls. Render does include callback
publication overhead. Queue age excludes time spent within the Android API.
ADPF counters are since audio start, not since resetting the display baseline.

Supported APIs can exist on a device without providing a useful scheduling
benefit. Frequency increases can also increase power use/heat. Compare peaks,
OV, XRuns and audible glitches, plus Android thermal status over a longer play
session; a lower average app CPU percentage is not the only success criterion.

### 2. LPDL logarithms prepared once

`FilterTables` precomputes the same 288 double-precision `log(double(kLpdlW[i]))`
values during non-audio initialization (2,304 bytes per shared sample-rate set).
`SetDiode` retains the original `exp(a + (b - a) * ct)` arithmetic order, the
resonance interpolation, 1/8-knob update threshold, matrix solve, nonlinearities
and all states. No new approximations, measured-table changes or precision
reduction. All voices use the prepared values. Static cutoff already hits an
exact cache: no meaningful speedup is promised there or for other filter types.

## Validation

`test-engine.sh` now compares directly with shipped 0.3.1 `8200beadd03549cd08ef9148914901d639c81b27`.
It retains 2,097,152 exact wavetable reads and 22,617,600 channel samples in 76 DSP
scenarios. The new focused LPDL test checks 1,689,600 **double** outputs, all 128
resonances, 44.1/48/96 kHz, ascending/descending cutoff, repeats, random jumps,
and 1/8-step/upper extrapolation boundaries. Native ARM64 and x86_64 comparisons
must pass bit-for-bit. These are regressions against 0.3.1, not every Synth1 patch.

A separate seven-trial median measures the LPDL filter with static/moving cutoff;
the existing 18-scenario engine benchmark remains. These are host measurements,
not tablet measurements. Results for unchanged paths fluctuate with the host.
No fixed percentage improvement is promised for the user's pad.

`test-host.sh` includes fake-backend ADPF tests: correct callback TID/target/work,
variable callback lengths, thread replacement, missing service/session, report
and target errors, idle/stop/restart, stale/overflow queues, concurrent queue
transport, and a deliberately blocked service that cannot block audio submission.
It also checks honest monitor statuses and retains all favorites, import, MIDI
and audio tests. Address/undefined-behavior sanitizer runs cover the native hint
tests. Device-specific Android service acceptance and acoustic stability require
testing on the tablet; neither fake tests nor a successful APK build prove them.

See `VALIDATION.md` for the unchanged independently reproduced desktop-only
upstream bank-swap test exception. It is not silently removed or generalized.

## First tablet comparison

Install Preview 0.3.2 over Preview 0.3.1 without uninstalling. Use the same demanding
pad, notes and 2048-frame setting first. Record a baseline with ADPF OFF. Enable
Audio → Ayuda de rendimiento → Aplicar and inspect the ADPF state in the monitor.
Record the ON comparison after startup transients; keep playing to evaluate heat
and sustained stability. Use Copiar informe + patch. If hints are unavailable or
make things worse, disable the checkbox; the LPDL cache remains active either way.
Do not lower the buffer until the like-for-like comparison is complete.

## Primary API references

- https://developer.android.com/ndk/reference/group/a-performance-hint
- https://source.android.com/docs/core/perf/performance-hint-api
- https://google.github.io/oboe/classoboe_1_1_audio_stream.html

Oboe documents the same changing-synth-workload motivation; this port still uses
AAudio directly, without adding an Oboe dependency.
