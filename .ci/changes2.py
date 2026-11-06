CHANGES = [
    ('android/OPTIMIZATION.md', None, '333e26f7df6c3fcc285ac134f96609b42e2c85b4', [
        (0, 0, r'''# 0.3.0 — exact-cache optimization and favorites

## Scope

The user reported roughly 80% app CPU/render with a 3-voice-unison pad and five held notes, and about 97% at 32 active voices, even at a requested 2048-frame buffer. The exact third-party patch has not been provided. The synthetic pad benchmark is not that patch and the development host is not the Tab A9.

This release changes execution cost, not patch interpretation: no polyphony reduction, lower sample rate, reduced control-update rate, float-for-double substitution, reduced effects, or fast-math. All existing parameter IDs and ranges remain. Timing is still patch- and device-dependent.

## Engine changes

- Cache ADSR coefficients by sample rate, four controls and sustain law. Trigger, release and live edits still take effect as before.
- Cache per-voice base oscillator frequencies, bend ratio, detune ratios and velocity gain by their exact inputs.
- Compute global pitch-LFO ratios once per sample for all voices, retaining the original summation order and 1/128-octave pitch quantization. Cache exp2 for identical quantized pitch values. Portamento and modulation envelopes remain per voice.
- Cache filter settings only on exact equality. Recalculate at every changed cutoff value; interpolate only the coefficient set used by that filter. No new cutoff approximation is introduced.

No allocation, Java callback, producer lock or file I/O was added to the audio render callback. Cached values are owned by the render thread and invalidated by the inputs they depend on. Waveforms, measured coefficient tables and effects are unchanged.

## Reproducible validation

`bash android/test-engine.sh` builds the engine shipped at `450c699c3c44a526cc5e821c4a53fc6529706690` in a separate namespace, alongside the candidate. Both use `-O3 -fno-fast-math`. It requires bit-identical float output over 19,939,200 channel samples / 67 synthetic scenarios, 44.1 and 48 kHz, all five filters, oscillator waveforms, both LFOs, 3-voice unison, sustain, bend, note stealing, patch changes, every parameter, variable callback lengths, and sample-rate changes. This is regression equivalence to 0.2.0, not proof of complete identity to original Synth1 or every possible patch.

The benchmark reports median elapsed render time of five alternating baseline/candidate trials, 48 kHz and 512-frame blocks. Warmup and table construction are excluded for both. It is not a deadline, output-driver, thermal or key-to-audio latency test. Results are written to `app/build/reports/engine-performance.txt` and uploaded with CI diagnostics. Do not extrapolate percentages to the tablet.

In one development x86_64 run, the synthetic unison-pad cases (15 and 32 active voices) required about 25% less render time, simple filter cases about 6–13% less, detune/sub about 5% less, and LPDL was essentially unchanged (a small regression within the observed run-to-run variation). The exact benchmark report, not these rounded numbers, is authoritative for each build. There is no claim that every patch gets faster.

52 of the 53 original upstream tests pass. The unchanged desktop-only `PatchManagerMoveRenamesAndSwaps` failure is accepted only when reproduced identically on original `48322f7`, with its library/dependencies/tests unchanged. Android uses its separately tested PatchStore, not that desktop library.

## Favorites

`☆ Guardar favorito` takes an atomic JNI snapshot of the selected patch's name and parameter state at tap time. Subsequent browsing cannot substitute a different sound. Saving does not change the selected bank or program. A new directory in the private `.favorites` folder is committed by rename, containing `patch.sy1` and display metadata. Originals are never rewritten. SHA-256 deduplicates identical snapshots; an edited sound with the same name is a separate copy, not an overwrite.

`★ Favoritos` opens only the favorites banks. They are also pinned after INIT in the main bank chooser. More than 128 sounds create additional pages. On-disk names are generated, never derived from untrusted patch names. Patch data remains lazily loaded. Saving refreshes only favorites metadata rather than rescanning all imported banks. Favorites are private app data: uninstalling or clearing storage removes them. Export important selected favorites with Exportar .sy1. Master volume/tempo are not patch parameters and are not stored in .sy1.

Host tests cover 262 snapshots, duplicate presses, changed values with the same name, restart, page boundaries, Unicode/path-like names, lazy loading, source preservation, failed and interrupted writes. Android UI and actual device performance still require tablet testing.
'''),
    ]),
    ('android/README.md', '5f671eba7ff74c040d351ab72ccb86a6452e33f6', 'f9b7a37c49cf6bc8edd8a239bd9b7179457a8090', [
        (0, 1, r'''# RiSynth2 Android 0.3.0
'''),
        (6, 7, r'''GitHub **Actions → Build Android APK → latest successful run → Artifacts → RiSynth2-0.3.0-arm64**. Two APKs are included. **For users of 0.1.0, install `RiSynth2-0.3.0-preview-arm64.apk`.** It appears as **RiSynth2 Preview** and uses `com.rinoize.risynth2.preview`, leaving the original application and its data untouched. Import your bank folders into Preview; private libraries/settings are not automatically shared. Keep 0.1.0 installed until important edited sounds have been exported.
'''),
        (13, 13, r'''
## New in 0.3.0

**☆ Guardar favorito** saves an independent .sy1 copy with the current parameter edits. Repeated presses do not duplicate an identical snapshot; changed settings create a new copy even with the same name. **★ Favoritos** opens the collection, also pinned after INIT in the bank list. Collections larger than 128 sounds are paged automatically. Saving leaves the source and current program untouched. Favorites are private app data; export important patches before uninstalling/clearing storage.

The engine now reuses exact calculations for envelopes, tuning and static filters, and shares global pitch-LFO work between voices. No lower-quality mode or reduced polyphony is used. Automated tests compare output sample by sample against 0.2.0. Performance is patch-dependent and needs tablet verification: see [OPTIMIZATION.md](OPTIMIZATION.md). Increasing the buffer alone does not reduce the synthesis work per second.

Install the **Preview 0.3.0** APK over **Preview 0.2.0** without uninstalling: package ID and pinned signing certificate are retained. It remains separate from the old 0.1.0 application.
'''),
        (40, 41, r'''All 99 parameter indices/ranges are unchanged. Engine calculations are now memoized as documented in OPTIMIZATION.md; measured tables and synthesis formulas are retained. Stereo AAudio float output at the actual device rate; shared output, optional low-latency request; explicit stop/reopen for settings. Local tablet speaker/wired output is preferred without silently selecting USB audio. USB MIDI source/port/channel selection, sustain, mod wheel, pitch bend, channel pressure, program change and bank select. CC0/32 selects the visible bank index, zero-based; Program Change selects slots 1–128. Library order can change after import.
'''),
        (56, 56, r'''bash android/test-engine.sh
'''),
    ]),
    ('android/app/build.gradle', '952f09def2d7cf793bcaec8c78e6f1a84b47983a', 'e5e8a3f1509543b9402066321efb40afd2e8cd87', [
        (14, 16, r'''        versionCode 3
        versionName '0.3.0'
'''),
    ]),
    ('android/app/src/main/java/com/rinoize/risynth2/FavoriteStore.java', None, 'af58dc73463663fcbc4a6dd26407f33ac85c6990', [
        (0, 0, r'''package com.rinoize.risynth2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Private, independent snapshots, not references to imported banks.
 * One hidden staging directory and one atomic rename per saved sound.
 * Serialized by the control worker; no audio-thread or UI-thread file I/O. */
final class FavoriteStore {
    private static final String PREFIX = ":favorites:";
    private final File root;
    static final class Saved {
        final String bankKey;
        final int slot;
        final boolean alreadySaved;
        Saved(int index, boolean alreadySaved) {
            bankKey = PREFIX + (index / 128); slot = index % 128;
            this.alreadySaved = alreadySaved;
        }
    }
    FavoriteStore(File root) { this.root = root; }
    static boolean isFavorite(PatchStore.Bank bank) { return bank.key.startsWith(PREFIX); }
    private List<File> entries() throws IOException {
        if (!root.exists()) return new ArrayList<>();
        File[] files = root.listFiles(f -> f.isDirectory() && f.getName().matches("[0-9]{8}-[0-9a-f]{64}"));
        if (files == null) throw new IOException("No se pudo leer Favoritos");
        Arrays.sort(files, Comparator.comparing(File::getName));
        return new ArrayList<>(Arrays.asList(files));
    }
    List<PatchStore.Bank> scan() throws IOException {
        List<PatchStore.Bank> banks = new ArrayList<>();
        List<File> entries = entries();
        for (int start = 0; start < entries.size(); start += 128) {
            PatchStore.Patch[] slots = new PatchStore.Patch[128];
            for (int i = start; i < Math.min(start + 128, entries.size()); ++i) {
                File dir = entries.get(i), sound = new File(dir,"patch.sy1");
                if (!sound.isFile()) throw new IOException("Favorito incompleto: " + dir.getName());
                String name = new String(PatchStore.readBounded(new FileInputStream(new File(dir,"name.txt")), 4096), StandardCharsets.UTF_8);
                slots[i-start] = new PatchStore.Patch(sound, name, i-start);
            }
            int page = start/128;
            banks.add(new PatchStore.Bank(PREFIX+page, page==0 ? "★ Favoritos" : "★ Favoritos ("+(page+1)+")", slots));
        }
        return banks;
    }
    synchronized Saved save(String snapshot) throws IOException {
        byte[] data = snapshot.getBytes(StandardCharsets.UTF_8);
        if (data.length > PatchStore.MAX_PATCH) throw new IOException("Patch demasiado grande");
        String text = PatchStore.validate(data);
        data = text.getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT,"%02x",b & 255));
            hash = hex.toString();
        } catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 no disponible",ex); }
        List<File> saved = entries();
        for (int i=0; i<saved.size(); ++i) if (saved.get(i).getName().endsWith("-"+hash)) return new Saved(i,true);
        if (saved.size() >= 131072) throw new IOException("Favoritos alcanzó su límite de 131072 sonidos");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("No se pudo crear Favoritos");
        int sequence = saved.isEmpty() ? 1 : Integer.parseInt(saved.get(saved.size()-1).getName().substring(0,8)) + 1;
        File staging = new File(root,".pending-"+UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("No se pudo preparar el favorito");
        boolean committed = false;
        try {
            // Never use the patch's display name as a path. Edited patches
            // with the same name get their own snapshot; originals are untouched.
            String name = new PatchStore.Patch(text,0).name;
            if (name.length()>512) name=name.substring(0,512);
            Files.write(new File(staging,"patch.sy1").toPath(),data);
            Files.write(new File(staging,"name.txt").toPath(),name.getBytes(StandardCharsets.UTF_8));
            File destination = new File(root,String.format(Locale.ROOT,"%08d-%s",sequence,hash));
            if (destination.exists() || !staging.renameTo(destination)) throw new IOException("No se pudo guardar el favorito");
            committed = true;
            return new Saved(saved.size(),false);
        } finally {
            if (!committed) {
                File[] children=staging.listFiles();
                if (children!=null) for (File child:children) child.delete();
                staging.delete();
            }
        }
    }
}
'''),
    ]),
    ('android/app/src/main/java/com/rinoize/risynth2/MainActivity.java', '96756812829a6473cefce390449d54494810d074', '1a437057f77e5a098519ac6276316ec3fed0c558', [
        (50, 51, r'''    private Button audioButton, favoriteButton;
    private boolean favoriteSaving;
'''),
        (214, 214, r'''        LinearLayout favoriteRow = row(body);
        favoriteButton = button(favoriteRow, "☆ Guardar favorito", this::saveFavorite);
        button(favoriteRow, "★ Favoritos", () -> bankDialog(true));
'''),
        (329, 330, r'''    private void bankDialog() { bankDialog(false); }
    private void bankDialog(boolean favoritesOnly) {
'''),
        (332, 332, r'''        if (favoritesOnly && snapshot.stream().noneMatch(FavoriteStore::isFavorite)) {
            message("Todavía no hay favoritos. Elegí un sonido y pulsá ☆ Guardar favorito.");
            return;
        }
'''),
        (340, 341, r'''            for (int i=0;i<snapshot.size();++i) if ((!favoritesOnly || FavoriteStore.isFavorite(snapshot.get(i))) && snapshot.get(i).name.toLowerCase(Locale.ROOT).contains(query)) {
'''),
        (351, 352, r'''        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(favoritesOnly ? "★ Favoritos" : "Bancos ("+snapshot.size()+")").setView(panel).setNegativeButton("Cerrar",null).create();
'''),
        (363, 364, r'''        new AlertDialog.Builder(this).setTitle(bank.name).setItems(names.toArray(new String[0]), (d, which) -> applyPatch(findBank(banks, bank.key), positions.get(which))).setNegativeButton("Cerrar", null).show();
'''),
        (365, 365, r'''    private void saveFavorite() {
        if (destroyed || engine == null || favoriteSaving || importing) return;
        final String snapshot = engine.snapshot();
        favoriteSaving = true;
        favoriteButton.setEnabled(false); favoriteButton.setText("Guardando…");
        // The snapshot is captured at tap time, so browsing the next patch
        // immediately cannot save a different sound. Only file I/O is queued.
        worker.execute(() -> {
            try {
                if (destroyed || engine == null || store == null) return;
                FavoriteStore.Saved saved = store.favorites.save(snapshot);
                List<PatchStore.Bank> favoriteBanks = store.favorites.scan();
                synchronized (libraryLock) {
                    String currentKey = banks.isEmpty() ? ":init" : banks.get(bankIndex).key;
                    // Avoid rescanning tens of thousands of imported patches.
                    List<PatchStore.Bank> updated = new ArrayList<>();
                    for (PatchStore.Bank bank : banks) if (!FavoriteStore.isFavorite(bank)) updated.add(bank);
                    updated.addAll(Math.min(1, updated.size()), favoriteBanks);
                    banks = Collections.unmodifiableList(updated);
                    bankIndex = findBank(banks, currentKey);
                }
                message(saved.alreadySaved ? "★ Este sonido ya está guardado en Favoritos." :
                    "★ Guardado en Favoritos con tus ajustes. El banco original no se modificó.");
                ui.post(() -> { if (!destroyed) { refreshLcd(); scheduleSave(); } });
            } catch (Exception ex) { message("No se pudo guardar el favorito: " + ex.getMessage()); }
            finally { ui.post(() -> {
                favoriteSaving = false;
                if (!destroyed) { favoriteButton.setEnabled(true); favoriteButton.setText("☆ Guardar favorito"); }
            }); }
        });
    }

'''),
    ]),
    ('android/app/src/main/java/com/rinoize/risynth2/NativeEngine.java', '8dfd17bdd1ef4c87d3fb7995613c0a7acfa7521b', '0567241d29dc2c565dc52be3eda037fb4d8d5226', [
        (8, 8, r'''    private String loadedName = "INIT";
'''),
        (26, 27, r'''        boolean accepted = loadNative(handle, sy1);
        if (accepted) loadedName = sy1.split("\\R", 2)[0];
        flushStopped(); return accepted;
'''),
        (28, 28, r'''    // One lock captures parameters and their matching loaded name at tap time.
    synchronized String snapshot() { return handle == 0 ? "" : saveNative(handle, loadedName); }
'''),
    ]),
    ('android/app/src/main/java/com/rinoize/risynth2/PatchStore.java', '65e74af9ca1c86d93a13d7ebdde599809c4a8439', 'a4b2b56f27a68936f80afd43d4b75e7d95c8746f', [
        (48, 48, r'''    final FavoriteStore favorites;
'''),
        (50, 50, r'''        favorites = new FavoriteStore(new File(root, ".favorites"));
'''),
        (83, 83, r'''        // Keep INIT at zero; favorites are immediately accessible before
        // imported banks. Stable keys retain the current selection on rescan.
        result.addAll(1, favorites.scan());
'''),
    ]),
    ('android/test-engine.sh', None, 'cab5539d447e9e2cb676f60ebc72c82dad3610a9', [
        (0, 0, r'''#!/usr/bin/env bash
# Rebuild the previous shipped engine in a separate namespace. Never compare
# against an edited fixture or claim desktop measurements are tablet results.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=450c699c3c44a526cc5e821c4a53fc6529706690
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/source" "$BUILD/baseline" "$ROOT/android/app/build/reports"
if [[ -n "${RISYNTH2_BASELINE_DIR:-}" ]]; then
  cp -R "$RISYNTH2_BASELINE_DIR/EngineCore" "$BUILD/source/"
else
  git -C "$ROOT" archive "$BASE" EngineCore | tar -x -C "$BUILD/source"
fi
python3 - "$BUILD" <<'PY'
import sys
from pathlib import Path
root=Path(sys.argv[1])
for f in (root/'source/EngineCore').glob('*.hpp'):
    (root/'baseline'/f.name).write_text(f.read_text().replace('oxo::s2','reference::s2'))
PY
REPORT="$ROOT/android/app/build/reports/engine-performance.txt"
{
  echo "Reference commit: $BASE"
  echo "Candidate commit: $(git -C "$ROOT" rev-parse HEAD)"
  uname -sm
  ${CXX:-clang++} --version | head -n 1
  echo 'Identical compiler flags: -std=c++20 -O3 -fno-fast-math'
} > "$REPORT"
${CXX:-clang++} -std=c++20 -O3 -fno-fast-math -Wall -Wextra -I"$ROOT" -I"$BUILD" "$ROOT/android/tests/engine_compare.cpp" -o "$BUILD/compare"
"$BUILD/compare" | tee -a "$REPORT"
"$BUILD/compare" --bench | tee -a "$REPORT"
'''),
    ]),
]
