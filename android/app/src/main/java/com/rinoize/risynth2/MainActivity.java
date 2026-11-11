package com.rinoize.risynth2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity implements MidiController.Listener {
    private static final int IMPORT = 10, EXPORT = 11, IMPORT_TREE = 12;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object libraryLock = new Object();
    private volatile NativeEngine engine;
    private PatchStore store;
    private volatile List<PatchStore.Bank> banks = List.of();
    private volatile int bankIndex, slot;
    private volatile String patchName = "INIT", midiName = "MIDI: buscando teclado USB";
    private volatile boolean foreground, wantsAudio = true, destroyed;
    private MidiController midi;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private SharedPreferences prefs;
    private int requestedFrames = 1024, routePreference = 0, volume = 48, bpm = 120;
    private boolean lowLatency = true, performanceHints, refreshing;
    private int cpuPlacementMode; // 0=automatic; 1=best allowed capacity
    private String pendingExport, initialPatch;
    private String[] info;
    private LinearLayout root, body;
    private TextView lcd, bankLabel, diagnostic, midiLabel, notice;
    private Button audioButton, favoriteButton;
    private boolean favoriteSaving;
    private boolean currentPatchSaved;
    private long favoriteCheckSequence;
    private int livePitchBend = ControlMath.BEND_CENTER;
    private boolean live; // landscape-tablet performance layout (Look / KnobView / StripView)
    private LinearLayout chipRow;
    private TextView lcdNumber, octaveLabel;
    private View statusDot;
    private int modValue;
    private final Map<Integer, KnobView> knobs = new HashMap<>();
    private final Map<Integer, Integer> knobSwitch = new HashMap<>();
    private final DiagnosticMonitor monitor = new DiagnosticMonitor();
    private TextView monitorDetail, importProgress;
    private AlertDialog importProgressDialog;
    private final AtomicBoolean cancelImport = new AtomicBoolean();
    private final AtomicLong patchRequest = new AtomicLong();
    private volatile boolean importing;
    private String importStatus = "Preparando importación…";
    private long lastImportProgress;
    private double[] lastStats;
    private long lastStatsWall, lastStatsCpu;
    private final ExecutorService monitorWorker = Executors.newSingleThreadExecutor();
    private boolean statsPending;

    private PianoView piano;
    private final Map<Integer, SeekBar> sliders = new HashMap<>();
    private final Map<Integer, TextView> valueLabels = new HashMap<>();
    private final Map<Integer, Button> toggles = new HashMap<>();
    private final Map<Integer, Integer> computerKeys = new HashMap<>();
    private final Runnable saveLater = this::saveSession;
    private final Runnable favoriteCheckLater = () -> {
        NativeEngine e = engine;
        PatchStore localStore = store;
        if (!live || e == null || localStore == null || destroyed) return;
        // Take a snapshot only after editing settles, not for every touch movement.
        final String snapshot = e.snapshot();
        final long seq = ++favoriteCheckSequence;
        worker.execute(() -> {
            boolean saved = localStore.favorites.hasSnapshot(snapshot);
            ui.post(() -> {
                if (destroyed || seq != favoriteCheckSequence) return;
                currentPatchSaved = saved;
                if (live && !favoriteSaving && favoriteButton != null) favoriteButton.setText(favoriteLabel());
            });
        });
    };
    private final Runnable routeChanged = () -> { if (foreground && wantsAudio && engine != null) startAudio(); };
    private final AudioDeviceCallback audioDevices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { scheduleRouteChange(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { scheduleRouteChange(); }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            NativeEngine e = engine;
            if (!foreground || destroyed) return;
            if (e != null && !statsPending) {
                statsPending = true;
                monitorWorker.execute(() -> {
                    double[] sample = e.stats(); // never stall the UI on stream reconfiguration
                    String cpuLocation = e.cpuPlacement();
                    long wall = SystemClock.elapsedRealtime(), cpu = android.os.Process.getElapsedCpuTime();
                    ui.post(() -> {
                        statsPending = false;
                        if (destroyed || !foreground) return;
                        lastStats = sample; lastStatsWall = wall; lastStatsCpu = cpu;
                        monitor.sample(sample, wall, cpu, requestedFrames, diagnosticContext() + "\n" + cpuLocation);
                        showMonitor(sample, cpuLocation);
                        if (monitorDetail != null) monitorDetail.setText(monitor.detail());
                        audioButton.setText(audioLabel(sample[0] > 0));
                        if (sample[11] < 0) { wantsAudio = false; notice.setText("Audio desconectado (" + (int)sample[11] + "). Pulsa Iniciar audio."); }
                    });
                });
            }
            ui.postDelayed(this, 500);
        }
    };
    private String diagnosticContext() {
        PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
        String[] thermal = {"normal", "leve", "moderado", "severo", "crítico", "emergencia", "apagado"};
        int level = power == null ? -1 : power.getCurrentThermalStatus();
        return "Patch: " + patchName + " · polifonía: " + (engine == null ? 0 : engine.values()[94]) +
            "\n" + midiName + " · mensajes " + (midi == null ? 0 : midi.messages.get()) +
            " · sustain " + (midi != null && midi.sustain ? "ON" : "OFF") +
            "\nEstado térmico Android: " + (level >= 0 && level < thermal.length ? thermal[level] : "n/d") +
            " (no es temperatura en grados)";
    }
    private void monitorDialog() {
        TextView details = text(monitor.detail(), 13); details.setTypeface(Typeface.MONOSPACE); details.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(details);
        monitorDetail = details;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Monitor de audio y CPU")
            .setView(scroll).setPositiveButton("Copiar informe + patch", null).setNeutralButton("Reiniciar", null).setNegativeButton("Cerrar",null).create();
        dialog.setOnDismissListener(d -> { if (monitorDetail == details) monitorDetail = null; });
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("RiSynth2 diagnóstico",
                    monitor.report("RiSynth2 " + BuildConfig.VERSION_NAME + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE)
                    + "\n\nPatch actual al copiar (formato .sy1; contiene tus ajustes):\n"
                    + (engine == null ? "No disponible" : engine.snapshot())));
                Toast.makeText(this,"Informe copiado, listo para pegar",Toast.LENGTH_SHORT).show();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (lastStats != null) {
                    monitor.reset(lastStats,lastStatsWall,lastStatsCpu);
                    details.setText("Contadores reiniciados. El audio no se detuvo.");
                }
            });
        });
        dialog.show();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("risynth2", MODE_PRIVATE);
        volume = prefs.getInt("volume", 48); bpm = prefs.getInt("tempo", 120);
        requestedFrames = prefs.getInt("frames", 1024); lowLatency = prefs.getBoolean("lowLatency", true);
        performanceHints = prefs.getBoolean("performanceHints", false); // explicit A/B opt-in
        cpuPlacementMode = prefs.getInt("cpuPlacementMode", 0) == 1 ? 1 : 0;
        // Physical device IDs are session-specific: only local/automatic preference is persisted.
        routePreference = prefs.getBoolean("automaticRoute", false) ? -1 : 0;
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(change -> {
                if (change < 0) { wantsAudio = false; stopAudio(); message("Audio detenido: otra aplicacion solicito el sonido."); }
            }, ui).build();
        info = NativeEngine.parameterInfo(); initialPatch = NativeEngine.initialPatch();
        buildUi();
        midi = new MidiController(this, this);
        audioManager.registerAudioDeviceCallback(audioDevices, ui);
        worker.execute(() -> {
            try {
                NativeEngine created = new NativeEngine();
                created.volume(volume); created.tempo(bpm);
                created.load(prefs.getString("patch", initialPatch));
                engine = created;
                patchName = prefs.getString("patchName", "INIT");
                store = new PatchStore(new File(getFilesDir(), "banks"), getCacheDir());
                List<PatchStore.Bank> scanned = store.scan(initialPatch);
                synchronized (libraryLock) {
                    banks = scanned;
                    bankIndex = findBank(scanned, prefs.getString("bankKey", ":init"));
                    slot = Math.max(0, Math.min(127, prefs.getInt("slot", 0)));
                }
                ui.post(() -> { refreshControls(); refreshLcd(); message("Motor listo. Importa una carpeta con bancos, varios .sy1 o un ZIP."); });
            } catch (Exception ex) { message("No se pudo preparar el motor: " + ex.getMessage()); }
        });
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(220,230,241)); t.setPadding(dp(6), dp(4), dp(6), dp(4)); return t;
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); parent.addView(r, new LinearLayout.LayoutParams(-1, -2)); return r;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(13); b.setAllCaps(false); b.setMinWidth(0);
        parent.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1)); b.setOnClickListener(v -> action.run()); return b;
    }
    private void buildUi() {
        live = wantsLiveLayout();
        sliders.clear(); valueLabels.clear(); toggles.clear(); knobs.clear(); knobSwitch.clear();
        if (live) { buildLiveUi(); return; }
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(16,21,28));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(12) + insets.getSystemWindowInsetLeft(), dp(4) + insets.getSystemWindowInsetTop(), dp(12) + insets.getSystemWindowInsetRight(), dp(4) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body);
        diagnostic = text("CPU app · Render · OV · XRuns\nPreparando monitor…", 12);
        diagnostic.setTypeface(Typeface.MONOSPACE); diagnostic.setBackgroundColor(Color.rgb(25,42,58));
        diagnostic.setOnClickListener(v -> monitorDialog()); diagnostic.setContentDescription("Monitor CPU y audio. Tocar para detalles, copiar informe o reiniciar contadores.");
        root.addView(diagnostic, new LinearLayout.LayoutParams(-1,-2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        body.addView(text("RiSynth2  " + BuildConfig.VERSION_NAME + "  ·  Android", 23));
        LinearLayout tools = row(body);
        audioButton = button(tools, audioLabel(false), this::toggleAudio);
        button(tools, "PANIC", this::panic);
        button(tools, "Audio", this::audioDialog);
        button(tools, "MIDI", this::midiDialog);
        LinearLayout bankRow = row(body);
        button(bankRow, "Banco −", () -> changeBank(-1));
        bankLabel = text("RiSynth2 · INIT", 15); bankLabel.setGravity(Gravity.CENTER);
        bankRow.addView(bankLabel, new LinearLayout.LayoutParams(0, dp(48), 3)); bankLabel.setOnClickListener(v -> bankDialog());
        button(bankRow, "Banco +", () -> changeBank(1));
        LinearLayout patchRow = row(body);
        button(patchRow, "◀", () -> changePatch(-1));
        lcd = text("001 · INIT", 23); lcd.setTypeface(Typeface.MONOSPACE); lcd.setTextColor(Color.WHITE);
        lcd.setBackgroundColor(Color.rgb(15,57,113)); lcd.setGravity(Gravity.CENTER);
        patchRow.addView(lcd, new LinearLayout.LayoutParams(0, dp(62), 5)); lcd.setOnClickListener(v -> patchDialog());
        button(patchRow, "▶", () -> changePatch(1));
        LinearLayout favoriteRow = row(body);
        favoriteButton = button(favoriteRow, "☆ Guardar favorito", this::saveFavorite);
        button(favoriteRow, "★ Favoritos", () -> bankDialog(true));
        LinearLayout files = row(body);
        button(files, "Importar", this::importDialog);
        button(files, "Exportar .sy1", this::exportDialog);
        button(files, "INIT", this::initDialog);
        button(files, "99 parametros", this::allParameters);
        addToggles(body);
        LinearLayout columns = row(body);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout first = new LinearLayout(this), second = new LinearLayout(this);
        first.setOrientation(LinearLayout.VERTICAL); second.setOrientation(LinearLayout.VERTICAL);
        columns.addView(first, wide ? new LinearLayout.LayoutParams(0,-2,1) : new LinearLayout.LayoutParams(-1,-2));
        columns.addView(second, wide ? new LinearLayout.LayoutParams(0,-2,1) : new LinearLayout.LayoutParams(-1,-2));
        first.addView(text("FILTRO Y OSCILADORES", 16));
        addSlider(first, "Cutoff", 19); addSlider(first, "Resonancia", 20); addSlider(first, "Envolvente filtro", 21);
        addSlider(first, "Mezcla OSC", 5); addSlider(first, "Detune OSC 1", 76); addSlider(first, "Suboscilador", 95);
        second.addView(text("AMPLITUD Y EJECUCION", 16));
        addSlider(second, "Attack", 25); addSlider(second, "Decay", 26); addSlider(second, "Sustain", 27); addSlider(second, "Release", 28);
        addSlider(second, "Polifonia", 94);
        addSpecialSlider(second, "Volumen", 0, 100, volume, v -> { volume = v; NativeEngine e = engine; if (e != null) e.volume(v); scheduleSave(); });
        addSpecialSlider(second, "Tempo", 30, 300, bpm, v -> { bpm = v; NativeEngine e = engine; if (e != null) e.tempo(v); scheduleSave(); });
        LinearLayout octaves = row(body);
        button(octaves, "Octava −", () -> piano.setBase(piano.base() - 12));
        octaves.addView(text("Teclado tactil · MIDI USB · QWERTY Z–M", 13), new LinearLayout.LayoutParams(0, dp(48), 3));
        button(octaves, "Octava +", () -> piano.setBase(piano.base() + 12));
        piano = new PianoView(this, (key, down) -> midi(down ? 0x90 : 0x80, key, down ? 100 : 0));
        body.addView(piano, new LinearLayout.LayoutParams(-1, dp(110)));
        midiLabel = text(midiName, 13); body.addView(midiLabel);
        notice = text("Compatible con patches Synth1. Sin ROM ni firmware.", 13); body.addView(notice);
        body.addView(text("El monitor de arriba permanece visible. Tocalo para detalles, copiar o reiniciar. Uso en primer plano.", 11));
        refreshControls(); refreshLcd();
    }
    // ---- Live-performance layout ("Hardware ámbar"). Landscape tablets only; other screens keep the classic layout. ----
    private boolean wantsLiveLayout() {
        Configuration c = getResources().getConfiguration();
        return c.screenWidthDp >= 700 && c.screenWidthDp > c.screenHeightDp;
    }
    private LinearLayout.LayoutParams params(int width, int height, float weight, int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height, weight);
        p.topMargin = dp(topMarginDp);
        return p;
    }
    private Button navButton(LinearLayout parent, String label, int fill, int textColor, int stroke, Runnable action) {
        Button b = Look.button(this, label, fill, textColor, stroke);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(48));
        p.leftMargin = dp(8);
        parent.addView(b, p);
        return b;
    }
    private String audioLabel(boolean active) {
        if (live) return active ? "■ Detener" : "▶ Iniciar";
        return active ? "Detener audio" : "Iniciar audio";
    }
    private void toggleAudio() {
        NativeEngine e = engine;
        if (e != null && e.isStreaming()) { wantsAudio = false; stopAudio(); }
        else { wantsAudio = true; startAudio(); }
    }
    private void showMonitor(double[] sample, String cpuLocation) {
        String place = cpuLocation.split("\\R", 2)[0];
        if (!live) { diagnostic.setText(monitor.compact() + "\n" + place); return; }
        // Same counters as the classic monitor, condensed to two lines. Everything else is one tap away.
        String[] parts = place.split(" · ", 3);
        String shortPlace = parts.length > 1 ? parts[0] + " · " + parts[1] : place;
        if (shortPlace.length() > 46) shortPlace = shortPlace.substring(0, 45) + "…";
        diagnostic.setText(monitor.brief() + "\n" + shortPlace);
        if (statusDot != null) statusDot.setBackground(Look.round(sample[0] > 0 ? Look.CYAN : Look.MUTED, dp(5), 0, 0));
    }
    private KnobView liveKnob(String name, int id) {
        String[] parts = info[id].split("\\|");
        KnobView knob = new KnobView(this, name);
        knob.setRange(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        knob.setOnChange(v -> { NativeEngine e = engine; if (e != null) e.param(id, v); scheduleSave(); });
        knobs.put(id, knob);
        return knob;
    }
    /** A knob whose short tap also switches an effect on or off (Delay, Chorus). */
    private KnobView liveSwitchKnob(String name, int id, int switchId) {
        KnobView knob = liveKnob(name, id);
        knob.setToggle(on -> {
            NativeEngine e = engine;
            if (e != null) e.param(switchId, on ? 1 : 0);
            Toast.makeText(this, name + (on ? " activado" : " desactivado"), Toast.LENGTH_SHORT).show();
            scheduleSave();
        });
        knobSwitch.put(id, switchId);
        return knob;
    }
    private void addKnobGroup(LinearLayout parent, String title, float weight, KnobView... group) {
        if (parent.getChildCount() > 0) {
            View divider = new View(this); divider.setBackgroundColor(Look.LINE);
            LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(dp(1), -1);
            d.leftMargin = dp(14); d.rightMargin = dp(14); d.topMargin = dp(4); d.bottomMargin = dp(4);
            parent.addView(divider, d);
        }
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = Look.label(this, title, 12, Look.MUTED, Look.HEADING); heading.setLetterSpacing(0.14f);
        titleRow.addView(heading);
        View line = new View(this); line.setBackgroundColor(Look.LINE);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(0, dp(1), 1); lineParams.leftMargin = dp(10);
        titleRow.addView(line, lineParams);
        column.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(20)));
        LinearLayout knobRow = new LinearLayout(this);
        for (KnobView knob : group) knobRow.addView(knob, new LinearLayout.LayoutParams(0, -1, 1));
        column.addView(knobRow, new LinearLayout.LayoutParams(-1, 0, 1));
        parent.addView(column, new LinearLayout.LayoutParams(0, -1, weight));
    }
    private static String noteName(int midiNote) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        return names[midiNote % 12] + (midiNote / 12 - 1);
    }
    private String octaveText() {
        return piano == null ? "" : noteName(piano.base()) + "–" + noteName(piano.base() + 23);
    }
    private void shiftOctave(int semitones) {
        if (piano == null) return;
        piano.setBase(piano.base() + semitones);
        if (octaveLabel != null) octaveLabel.setText(octaveText());
    }
    private void buildLiveUi() {
        int keptBase = piano == null ? 48 : piano.base();
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Look.GROUND);
        root.setPadding(dp(14), dp(6), dp(14), dp(6));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(14) + insets.getSystemWindowInsetLeft(), dp(6) + insets.getSystemWindowInsetTop(), dp(14) + insets.getSystemWindowInsetRight(), dp(6) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        // Header: name, monitor (always visible, tap for details) and the main actions.
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this); brand.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this); titleRow.setGravity(Gravity.BOTTOM);
        titleRow.addView(Look.label(this, "RiSynth2", 22, Look.TEXT, Look.HEADING));
        TextView version = Look.label(this, BuildConfig.VERSION_NAME, 12, Look.MUTED, Typeface.MONOSPACE);
        version.setPadding(dp(8), 0, 0, dp(3)); titleRow.addView(version);
        brand.addView(titleRow);
        LinearLayout pill = new LinearLayout(this); pill.setGravity(Gravity.CENTER_VERTICAL); pill.setMinimumHeight(dp(30));
        statusDot = new View(this); statusDot.setBackground(Look.round(Look.MUTED, dp(5), 0, 0));
        pill.addView(statusDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        diagnostic = Look.label(this, "Preparando monitor…", 11, Look.TEXT, Typeface.MONOSPACE);
        diagnostic.setMaxLines(2); diagnostic.setEllipsize(TextUtils.TruncateAt.END); diagnostic.setPadding(dp(8), 0, 0, 0);
        pill.addView(diagnostic, new LinearLayout.LayoutParams(0, -2, 1));
        pill.setOnClickListener(v -> monitorDialog());
        pill.setContentDescription("Monitor CPU y audio. Tocar para detalles, copiar informe o reiniciar contadores.");
        brand.addView(pill, new LinearLayout.LayoutParams(-1, -2));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        audioButton = navButton(header, audioLabel(false), Look.PANEL2, Look.TEXT, Look.LINE, this::toggleAudio);
        audioButton.setMinWidth(dp(116)); audioButton.setMinimumWidth(dp(116));
        navButton(header, "Sonidos", Look.PANEL2, Look.TEXT, Look.LINE, this::libraryMenu);
        navButton(header, "Audio", Look.PANEL2, Look.TEXT, Look.LINE, this::audioDialog);
        navButton(header, "MIDI", Look.PANEL2, Look.TEXT, Look.LINE, this::midiDialog);
        navButton(header, "Editar", Look.PANEL2, Look.TEXT, Look.LINE, this::editDialog);
        navButton(header, "Pánico", Look.DANGER, Look.DARK, 0, this::panic);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // Status line: last message (tap to read it all) and the MIDI input.
        LinearLayout strip = new LinearLayout(this); strip.setGravity(Gravity.CENTER_VERTICAL);
        notice = Look.label(this, "Compatible con patches Synth1. Sin ROM ni firmware.", 12, Look.MUTED, Typeface.DEFAULT);
        notice.setSingleLine(true); notice.setEllipsize(TextUtils.TruncateAt.END);
        notice.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(notice.getText()).setPositiveButton("Cerrar", null).show());
        strip.addView(notice, new LinearLayout.LayoutParams(0, -2, 1));
        midiLabel = Look.label(this, midiName, 12, Look.MUTED, Typeface.DEFAULT);
        midiLabel.setSingleLine(true); midiLabel.setEllipsize(TextUtils.TruncateAt.END);
        midiLabel.setMaxWidth(dp(340)); midiLabel.setPadding(dp(16), 0, 0, 0);
        strip.addView(midiLabel);
        root.addView(strip, params(-1, dp(24), 0, 2));

        // Sound selector: big previous/next, amber display, save-as-favourite star.
        LinearLayout selector = new LinearLayout(this);
        Button previous = Look.button(this, "◀", Look.PANEL2, Look.TEXT, Look.LINE); previous.setTextSize(30);
        previous.setContentDescription("Sonido anterior"); previous.setOnClickListener(v -> changePatch(-1));
        selector.addView(previous, new LinearLayout.LayoutParams(dp(92), -1));
        LinearLayout screen = new LinearLayout(this); screen.setGravity(Gravity.CENTER_VERTICAL);
        screen.setBackground(Look.round(Look.LCD, dp(16), Look.LCD_LINE, dp(1))); screen.setPadding(dp(20), dp(6), dp(12), dp(6));
        screen.setOnClickListener(v -> patchDialog());
        LinearLayout readout = new LinearLayout(this); readout.setOrientation(LinearLayout.VERTICAL);
        bankLabel = Look.label(this, "RiSynth2 · INIT", 12, Look.AMBER_DIM, Typeface.MONOSPACE);
        bankLabel.setLetterSpacing(0.08f); bankLabel.setSingleLine(true); bankLabel.setEllipsize(TextUtils.TruncateAt.END);
        bankLabel.setOnClickListener(v -> bankDialog());
        readout.addView(bankLabel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout nameRow = new LinearLayout(this); nameRow.setGravity(Gravity.BOTTOM);
        lcdNumber = Look.label(this, "001", 30, Look.AMBER_DIM, Typeface.MONOSPACE);
        lcd = Look.label(this, "INIT", 38, Look.AMBER, Look.HEADING);
        lcd.setSingleLine(true); lcd.setEllipsize(TextUtils.TruncateAt.END);
        lcd.setShadowLayer(dp(10), 0, 0, 0x88F2AE45); lcd.setPadding(dp(16), 0, 0, 0);
        nameRow.addView(lcdNumber); nameRow.addView(lcd, new LinearLayout.LayoutParams(0, -2, 1));
        readout.addView(nameRow, new LinearLayout.LayoutParams(-1, -2));
        screen.addView(readout, new LinearLayout.LayoutParams(0, -2, 1));
        favoriteButton = Look.button(this, favoriteLabel(), 0xFF3A2A12, Look.AMBER, Look.AMBER_DIM);
        favoriteButton.setTextSize(28); favoriteButton.setPadding(0, 0, 0, 0);
        favoriteButton.setContentDescription("Guardar este sonido en Favoritos"); favoriteButton.setOnClickListener(v -> saveFavorite());
        screen.addView(favoriteButton, new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(0, -1, 1);
        screenParams.leftMargin = dp(12); screenParams.rightMargin = dp(12);
        selector.addView(screen, screenParams);
        Button next = Look.button(this, "▶", Look.PANEL2, Look.TEXT, Look.LINE); next.setTextSize(30);
        next.setContentDescription("Sonido siguiente"); next.setOnClickListener(v -> changePatch(1));
        selector.addView(next, new LinearLayout.LayoutParams(dp(92), -1));
        root.addView(selector, params(-1, dp(84), 0, 6));

        // Quick slots: the page of 8 sounds around the current one (filled by refreshChips).
        chipRow = new LinearLayout(this);
        root.addView(chipRow, params(-1, dp(44), 0, 8));

        // Knobs: the controls a performer touches most. Everything else is under Editar.
        LinearLayout panel = new LinearLayout(this);
        panel.setBackground(Look.round(Look.PANEL, dp(18), Look.LINE, dp(1))); panel.setPadding(dp(18), dp(8), dp(18), dp(8));
        addKnobGroup(panel, "FILTRO", 3, liveKnob("Cutoff", 19), liveKnob("Reso", 20), liveKnob("Env", 21));
        addKnobGroup(panel, "AMPLITUD", 2, liveKnob("Attack", 25), liveKnob("Release", 28));
        addKnobGroup(panel, "EFECTOS · TOCAR = ON/OFF", 2, liveSwitchKnob("Delay Mix", 37, 65), liveSwitchKnob("Chorus Mix", 56, 66));
        KnobView volumeKnob = new KnobView(this, "Volumen");
        volumeKnob.setRange(0, 100); volumeKnob.setValue(volume);
        volumeKnob.setOnChange(v -> { volume = v; NativeEngine e = engine; if (e != null) e.volume(v); scheduleSave(); });
        addKnobGroup(panel, "MASTER", 1, volumeKnob);
        root.addView(panel, params(-1, 0, 5, 8));

        // Stage: pitch bend, mod wheel, octave shift and keyboard.
        LinearLayout stage = new LinearLayout(this);
        StripView pitch = new StripView(this, "Pitch", ControlMath.BEND_MAX, ControlMath.BEND_CENTER, true,
            v -> { livePitchBend = v; midi(0xE0, ControlMath.bendLow(v), ControlMath.bendHigh(v)); });
        StripView mod = new StripView(this, "Mod", 127, modValue, false, v -> { modValue = v; midi(0xB0, 1, v); });
        stage.addView(pitch, new LinearLayout.LayoutParams(dp(56), -1));
        LinearLayout.LayoutParams modParams = new LinearLayout.LayoutParams(dp(56), -1); modParams.leftMargin = dp(8);
        stage.addView(mod, modParams);
        LinearLayout octaves = new LinearLayout(this); octaves.setOrientation(LinearLayout.VERTICAL);
        Button octaveUp = Look.button(this, "+ Oct", Look.PANEL2, Look.TEXT, Look.LINE);
        octaveUp.setContentDescription("Subir una octava"); octaveUp.setOnClickListener(v -> shiftOctave(12));
        octaveLabel = Look.label(this, "", 14, Look.MUTED, Typeface.MONOSPACE); octaveLabel.setGravity(Gravity.CENTER);
        Button octaveDown = Look.button(this, "− Oct", Look.PANEL2, Look.TEXT, Look.LINE);
        octaveDown.setContentDescription("Bajar una octava"); octaveDown.setOnClickListener(v -> shiftOctave(-12));
        octaves.addView(octaveUp, new LinearLayout.LayoutParams(-1, 0, 1));
        octaves.addView(octaveLabel, new LinearLayout.LayoutParams(-1, dp(26)));
        octaves.addView(octaveDown, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams octaveParams = new LinearLayout.LayoutParams(dp(92), -1);
        octaveParams.leftMargin = dp(8); octaveParams.rightMargin = dp(8);
        stage.addView(octaves, octaveParams);
        piano = new PianoView(this, (key, down) -> midi(down ? 0x90 : 0x80, key, down ? 100 : 0));
        piano.setPressedColors(Look.KEY_DOWN_WHITE, Look.KEY_DOWN_BLACK); piano.setBackgroundColor(0xFF0F0D0A);
        piano.setBase(keptBase); octaveLabel.setText(octaveText());
        stage.addView(piano, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(stage, params(-1, 0, 4, 8));

        setContentView(root);
        refreshControls(); refreshLcd();
    }
    private String favoriteLabel() { return !live ? "☆ Guardar favorito" : currentPatchSaved ? "★" : "☆"; }
    private void scheduleFavoriteCheck() {
        if (!live || destroyed) return;
        ++favoriteCheckSequence;
        ui.removeCallbacks(favoriteCheckLater);
        ui.postDelayed(favoriteCheckLater, 350);
    }
    /** Pitch bend must not remain detuned when the live strip is replaced or the app pauses. */
    private void centerLivePitch() {
        if (livePitchBend == ControlMath.BEND_CENTER) return;
        livePitchBend = ControlMath.BEND_CENTER;
        midi(0xE0, ControlMath.bendLow(ControlMath.BEND_CENTER), ControlMath.bendHigh(ControlMath.BEND_CENTER));
    }
    private void refreshChips() {
        if (chipRow == null) return;
        chipRow.removeAllViews();
        List<PatchStore.Bank> list = banks; int index = bankIndex, current = slot;
        if (index < 0 || index >= list.size()) return;
        PatchStore.Bank bank = list.get(index);
        final String key = bank.key;
        int page = current / 8 * 8;
        for (int i = 0; i < 8; ++i) {
            final int target = page + i;
            PatchStore.Patch patch = target < 128 ? bank.slots[target] : null;
            TextView chip = Look.chip(this, Integer.toString(target + 1), patch == null ? "—" : patch.name, target == current, patch != null);
            if (patch != null) chip.setOnClickListener(v -> applyPatch(findBank(banks, key), target));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) p.leftMargin = dp(8);
            chipRow.addView(chip, p);
        }
    }
    private void libraryMenu() {
        String[] items = {"Bancos y sonidos", "★ Favoritos", "Importar sonidos…", "Exportar sonido actual (.sy1)", "Inicializar sonido (INIT)"};
        new AlertDialog.Builder(this).setTitle("Sonidos").setItems(items, (d, which) -> {
            switch (which) {
                case 0: bankDialog(); break;
                case 1: bankDialog(true); break;
                case 2: importDialog(); break;
                case 3: exportDialog(); break;
                default: initDialog(); break;
            }
        }).setNegativeButton("Cerrar", null).show();
    }
    private void initDialog() {
        new AlertDialog.Builder(this).setTitle("Inicializar sonido")
            .setMessage("Reemplaza las ediciones actuales por INIT. No borra bancos.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Inicializar", (d, w) -> applyPatch(0, 0)).show();
    }
    /** Everything that is not a performance knob: switches, oscillator and envelope extras, tempo, all 99 parameters. */
    private void editDialog() {
        if (info == null) return;
        sliders.clear(); valueLabels.clear(); toggles.clear();
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16), dp(8), dp(16), dp(8));
        panel.addView(text("INTERRUPTORES", 14));
        addToggles(panel);
        panel.addView(text("OSCILADORES", 14));
        addSlider(panel, "Mezcla OSC", 5); addSlider(panel, "Detune OSC 1", 76); addSlider(panel, "Suboscilador", 95);
        panel.addView(text("AMPLITUD Y EJECUCION", 14));
        addSlider(panel, "Decay", 26); addSlider(panel, "Sustain", 27); addSlider(panel, "Polifonia", 94);
        addSpecialSlider(panel, "Tempo", 30, 300, bpm, v -> { bpm = v; NativeEngine e = engine; if (e != null) e.tempo(v); scheduleSave(); });
        Button all = new Button(this); all.setText("Los 99 parámetros…"); all.setAllCaps(false);
        panel.addView(all, new LinearLayout.LayoutParams(-1, dp(48)));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Editar").setView(scroll).setNegativeButton("Cerrar", null).create();
        all.setOnClickListener(v -> { dialog.dismiss(); allParameters(); });
        dialog.setOnDismissListener(d -> { sliders.clear(); valueLabels.clear(); toggles.clear(); });
        dialog.show();
        refreshControls();
    }
    private void addToggles(LinearLayout parent) {
        LinearLayout togglesRow = row(parent);
        for (int id : new int[]{59,65,66,73,77}) {
            String label = switch(id) { case 59 -> "ARP"; case 65 -> "Delay"; case 66 -> "Chorus"; case 73 -> "Unison"; default -> "Effect"; };
            Button b = button(togglesRow, label, () -> { NativeEngine e = engine; if (e != null) { e.param(id, e.values()[id] == 0 ? 1 : 0); refreshControls(); scheduleSave(); } });
            b.setTag(label); toggles.put(id, b);
        }
    }
    private interface IntChange { void changed(int value); }
    private SeekBar addSpecialSlider(LinearLayout parent, String name, int min, int max, int value, IntChange change) {
        LinearLayout r = row(parent); TextView label = text(name + " " + value, 13);
        r.addView(label, new LinearLayout.LayoutParams(dp(145), dp(40)));
        SeekBar bar = new SeekBar(this); bar.setMax(max - min); bar.setProgress(Math.max(0, Math.min(max-min, value-min)));
        r.addView(bar, new LinearLayout.LayoutParams(0, dp(40), 1));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int v, boolean user) { label.setText(name + " " + (v + min)); if (user && !refreshing) change.changed(v + min); }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        bar.setTag(label); return bar;
    }
    private void addSlider(LinearLayout parent, String name, int id) {
        String[] parts = info[id].split("\\|"); int min = Integer.parseInt(parts[1]), max = Integer.parseInt(parts[2]);
        SeekBar bar = addSpecialSlider(parent, name, min, max, min, v -> { NativeEngine e = engine; if (e != null) e.param(id, v); scheduleSave(); });
        sliders.put(id, bar); valueLabels.put(id, (TextView)bar.getTag());
    }
    private void refreshControls() {
        NativeEngine e = engine; if (e == null || info == null) return;
        int[] values = e.values(); refreshing = true;
        for (Map.Entry<Integer, SeekBar> item : sliders.entrySet()) {
            int id = item.getKey(), min = Integer.parseInt(info[id].split("\\|")[1]); item.getValue().setProgress(values[id] - min);
        }
        for (Map.Entry<Integer, Button> item : toggles.entrySet()) item.getValue().setText(item.getValue().getTag() + (values[item.getKey()] != 0 ? " ●" : " ○"));
        for (Map.Entry<Integer, KnobView> item : knobs.entrySet()) {
            item.getValue().setValue(values[item.getKey()]);
            Integer switchId = knobSwitch.get(item.getKey());
            if (switchId != null) item.getValue().setOn(values[switchId] != 0);
        }
        refreshing = false;
    }
    private void refreshLcd() {
        if (lcd == null) return;
        List<PatchStore.Bank> list = banks;
        String bankName = bankIndex >= 0 && bankIndex < list.size() ? list.get(bankIndex).name : "RiSynth2 · INIT";
        if (live) {
            lcdNumber.setText(String.format(Locale.ROOT, "%03d", slot + 1));
            lcd.setText(patchName);
            bankLabel.setText(String.format(Locale.ROOT, "%s · SLOT %03d / 128", bankName.toUpperCase(Locale.ROOT), slot + 1));
            if (!favoriteSaving) favoriteButton.setText(favoriteLabel());
            refreshChips();
            scheduleFavoriteCheck();
        } else {
            lcd.setText(String.format(Locale.ROOT, "%03d · %s", slot + 1, patchName));
            bankLabel.setText(bankName);
        }
        midiLabel.setText(midiName);
    }
    private static int findBank(List<PatchStore.Bank> list, String key) {
        for (int i = 0; i < list.size(); ++i) if (list.get(i).key.equals(key)) return i; return 0;
    }
    private void applyPatch(int bank, int program) {
        if (destroyed || importing) return;
        final PatchStore.Patch patch;
        final long request;
        synchronized (libraryLock) {
            if (bank < 0 || bank >= banks.size() || program < 0 || program >= 128 || engine == null) return;
            patch = banks.get(bank).slots[program];
            if (patch == null) return;
            bankIndex = bank; slot = program;
            request = patchRequest.incrementAndGet();
        }
        worker.execute(() -> {
            if (destroyed || request != patchRequest.get()) return;
            try {
                String text = patch.readText();
                if (request != patchRequest.get() || engine == null) return;
                if (!engine.load(text)) { message("Cola MIDI llena: vuelve a cargar el patch."); return; }
                patchName = patch.name;
                ui.post(() -> { if (!destroyed) { refreshControls(); refreshLcd(); scheduleSave(); } });
            } catch (IOException ex) { message("No se pudo cargar el patch: " + ex.getMessage()); }
        });
    }
    private void changePatch(int delta) {
        synchronized (libraryLock) {
            if (banks.isEmpty()) return;
            for (int i = 1; i <= 128; ++i) {
                int candidate = Math.floorMod(slot + delta * i, 128);
                if (banks.get(bankIndex).slots[candidate] != null) { applyPatch(bankIndex, candidate); return; }
            }
        }
    }
    private void changeBank(int delta) {
        synchronized (libraryLock) {
            if (banks.isEmpty()) return;
            int bank = Math.floorMod(bankIndex + delta, banks.size()); applyPatch(bank, banks.get(bank).first());
        }
    }
    private void bankDialog() { bankDialog(false); }
    private void bankDialog(boolean favoritesOnly) {
        List<PatchStore.Bank> snapshot = banks;
        if (snapshot.isEmpty()) return;
        if (favoritesOnly && snapshot.stream().noneMatch(FavoriteStore::isFavorite)) {
            message("Todavía no hay favoritos. Elegí un sonido y pulsá ☆ Guardar favorito.");
            return;
        }
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint("Buscar banco o carpeta…"); panel.addView(search);
        ListView list = new ListView(this); panel.addView(list,new LinearLayout.LayoutParams(-1,dp(300)));
        List<Integer> visible = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>()); list.setAdapter(adapter);
        Runnable filter = () -> {
            String query = search.getText().toString().toLowerCase(Locale.ROOT);
            visible.clear(); adapter.clear();
            for (int i=0;i<snapshot.size();++i) if ((!favoritesOnly || FavoriteStore.isFavorite(snapshot.get(i))) && snapshot.get(i).name.toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(i); adapter.add(snapshot.get(i).name);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { filter.run(); }
            public void afterTextChanged(Editable text) { }
        });
        filter.run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(favoritesOnly ? "★ Favoritos" : "Bancos ("+snapshot.size()+")").setView(panel).setNegativeButton("Cerrar",null).create();
        list.setOnItemClickListener((p,v,position,id) -> {
            int index = findBank(banks,snapshot.get(visible.get(position)).key);
            applyPatch(index,banks.get(index).first()); dialog.dismiss(); patchDialog();
        });
        dialog.show(); dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
    private void patchDialog() {
        List<PatchStore.Bank> list = banks; if (list.isEmpty()) return;
        int selectedBank = bankIndex; PatchStore.Bank bank = list.get(selectedBank);
        List<Integer> positions = new ArrayList<>(); List<String> names = new ArrayList<>();
        for (int i = 0; i < 128; ++i) if (bank.slots[i] != null) { positions.add(i); names.add(String.format(Locale.ROOT, "%03d · %s", i+1, bank.slots[i].name)); }
        new AlertDialog.Builder(this).setTitle(bank.name).setItems(names.toArray(new String[0]), (d, which) -> applyPatch(findBank(banks, bank.key), positions.get(which))).setNegativeButton("Cerrar", null).show();
    }
    private void saveFavorite() {
        if (destroyed || engine == null || favoriteSaving || importing) return;
        final String snapshot = engine.snapshot();
        favoriteSaving = true;
        favoriteButton.setEnabled(false); favoriteButton.setText(live ? "…" : "Guardando…");
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
                if (!destroyed) {
                    if (favoriteButton != null) favoriteButton.setEnabled(true);
                    scheduleFavoriteCheck();
                }
            }); }
        });
    }

    private void allParameters() {
        NativeEngine e = engine; if (e == null) return;
        int[] values = e.values(); String[] labels = new String[info.length];
        for (int i = 0; i < labels.length; ++i) labels[i] = i + " · " + info[i].split("\\|")[0] + " = " + values[i];
        new AlertDialog.Builder(this).setTitle("Parametros originales").setItems(labels, (d, id) -> editParameter(id)).setNegativeButton("Cerrar", null).show();
    }
    private void editParameter(int id) {
        NativeEngine e = engine; if (e == null) return;
        String[] parts = info[id].split("\\|"); int min = Integer.parseInt(parts[1]), max = Integer.parseInt(parts[2]);
        EditText input = new EditText(this); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED); input.setText(Integer.toString(e.values()[id])); input.selectAll();
        new AlertDialog.Builder(this).setTitle(parts[0] + " (" + min + "…" + max + ")").setView(input).setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar", (d, w) -> { try { e.param(id, Math.max(min, Math.min(max, Integer.parseInt(input.getText().toString().trim())))); refreshControls(); scheduleSave(); } catch (NumberFormatException ex) { message("Valor no valido"); } }).show();
    }
    private void audioDialog() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16),0,dp(16),0);
        panel.addView(text("Salida de audio", 15));
        List<Integer> ids = new ArrayList<>(List.of(0, -1));
        List<String> outputs = new ArrayList<>(List.of("Tablet / auriculares cableados (sin USB)", "Automatico de Android (puede elegir USB)"));
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) { ids.add(d.getId()); outputs.add(d.getProductName() + " · tipo " + d.getType() + " · ID " + d.getId()); }
        Spinner output = spinner(panel, outputs.toArray(new String[0])); output.setSelection(Math.max(0, ids.indexOf(routePreference)));
        panel.addView(text("Buffer solicitado (el real puede ser mayor)", 15));
        int[] frameOptions = {256,512,1024,2048}; Spinner buffer = spinner(panel, new String[]{"256 frames", "512 frames", "1024 frames", "2048 frames"});
        for (int i = 0; i < frameOptions.length; ++i) if (frameOptions[i] == requestedFrames) buffer.setSelection(i);
        panel.addView(text("CPU del hilo de síntesis", 15));
        Spinner cpuMode = spinner(panel, new String[]{"Automático (Android)", "Núcleos de rendimiento (mayor capacidad)"});
        cpuMode.setSelection(cpuPlacementMode);
        panel.addView(text("Solo el hilo de síntesis; no suma dos núcleos ni fija su frecuencia. Reabre audio al aplicar. Si Android lo rechaza, continúa sin restricción adicional. Puede aumentar consumo/calor.", 12));
        CheckBox low = new CheckBox(this); low.setText("Solicitar baja latencia (modo compartido)"); low.setChecked(lowLatency); panel.addView(low);
        CheckBox hints = new CheckBox(this); hints.setText("Ayuda de rendimiento (ADPF · experimental)");
        hints.setChecked(performanceHints); panel.addView(hints);
        panel.addView(text("Avisa a Android del trabajo del hilo de audio. Puede no estar disponible o no mejorar los cortes. Puede aumentar consumo/calor. Ver estado ADPF en el monitor.", 12));
        panel.addView(text("Frecuencia nativa del dispositivo. Los cambios cierran y reabren el audio. No hay autoajuste oculto.", 13));
        ScrollView audioScroll = new ScrollView(this); audioScroll.addView(panel);
        new AlertDialog.Builder(this).setTitle("Audio").setView(audioScroll).setNegativeButton("Cancelar", null).setPositiveButton("Aplicar", (d,w) -> {
            routePreference = ids.get(output.getSelectedItemPosition()); requestedFrames = frameOptions[buffer.getSelectedItemPosition()]; lowLatency = low.isChecked(); performanceHints = hints.isChecked(); cpuPlacementMode = cpuMode.getSelectedItemPosition();
            prefs.edit().putInt("frames",requestedFrames).putBoolean("lowLatency",lowLatency).putBoolean("performanceHints",performanceHints).putInt("cpuPlacementMode",cpuPlacementMode).putBoolean("automaticRoute", routePreference == -1).apply();
            if (wantsAudio && foreground) startAudio();
        }).show();
    }
    private Spinner spinner(LinearLayout parent, String[] items) {
        Spinner s = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items); s.setAdapter(adapter); parent.addView(s); return s;
    }
    private int resolveDevice() {
        if (routePreference == -1) return 0;
        AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (routePreference > 0) { for (AudioDeviceInfo d : outputs) if (d.getId() == routePreference) return routePreference; throw new IllegalStateException("La salida elegida fue desconectada"); }
        for (AudioDeviceInfo d : outputs) if (d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) return d.getId();
        for (AudioDeviceInfo d : outputs) if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return d.getId();
        throw new IllegalStateException("No se encontro salida local. Elige una salida en Audio.");
    }
    private void startAudio() {
        if (!foreground || !wantsAudio || destroyed || importing) return;
        final int device;
        try { device = resolveDevice(); } catch (Exception e) { message(e.getMessage()); return; }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { message("No se obtuvo el foco de audio."); return; }
        final int frames = requestedFrames, placement = cpuPlacementMode; final boolean low = lowLatency, hint = performanceHints;
        notice.setText("Preparando audio…"); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            if (!foreground || !wantsAudio || destroyed || importing || engine == null) return;
            String error = engine.start(device, frames, low, hint, placement);
            if (!error.isEmpty()) message("No se pudo iniciar: " + error);
            else message("Audio listo. Comienza con volumen bajo.");
        });
    }
    private void stopAudio() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!destroyed) worker.execute(() -> { NativeEngine e = engine; if (e != null) e.stop(); });
    }
    private void scheduleRouteChange() {
        ui.removeCallbacks(routeChanged); ui.postDelayed(routeChanged, 200);
    }
    private void midiDialog() {
        if (midi == null) return;
        List<MidiController.Source> sources = midi.sources();
        List<String> labels = new ArrayList<>(); labels.add("Desconectar"); labels.add("Canal MIDI (actual: " + (midi.channel < 0 ? "todos" : midi.channel+1) + ")");
        for (MidiController.Source s : sources) labels.add(s.label);
        new AlertDialog.Builder(this).setTitle("Entrada MIDI").setItems(labels.toArray(new String[0]), (d,i) -> {
            if (i == 0) midi.disconnect();
            else if (i == 1) {
                String[] channels = new String[17]; channels[0] = "Todos (Omni)"; for (int c = 1; c <= 16; ++c) channels[c] = "Canal " + c;
                new AlertDialog.Builder(this).setTitle("Canal").setItems(channels, (dialog,c) -> midi.setChannel(c-1)).show();
            } else midi.select(sources.get(i-2));
        }).setNegativeButton("Cerrar",null).show();
    }
    @Override public void midi(int status, int d1, int d2) { NativeEngine e = engine; if (e != null) e.midi(status, d1, d2); }
    @Override public void program(int bank, int program) { applyPatch(bank < 0 ? bankIndex : bank, program); }
    @Override public void disconnected() { NativeEngine e = engine; if (e != null) e.panic(); }
    @Override public void state(String value) { midiName = "MIDI: " + value; ui.post(this::refreshLcd); }
    private void panic() { if (piano != null) piano.releaseAll(); computerKeys.clear(); NativeEngine e = engine; if (e != null) e.panic(); }
    private void importDialog() {
        if (importing) { showImportProgress(); return; }
        new AlertDialog.Builder(this).setTitle("Importar sonidos")
            .setItems(new String[]{"Carpeta completa (incluye subcarpetas)","Archivos .sy1 / un banco ZIP"},(d,which) -> {
                try {
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivityForResult(intent,IMPORT_TREE);
                    } else {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
                        startActivityForResult(intent,IMPORT);
                    }
                } catch (android.content.ActivityNotFoundException ex) { message("No hay selector de archivos disponible."); }
            }).setNegativeButton("Cancelar",null).show();
    }
    private void showImportProgress() {
        if (destroyed || !importing || (importProgressDialog != null && importProgressDialog.isShowing())) return;
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16),dp(8),dp(16),dp(8));
        ProgressBar bar = new ProgressBar(this); panel.addView(bar);
        importProgress = text(importStatus,14); panel.addView(importProgress);
        panel.addView(text("Se copian los .sy1; los originales no se modifican. El audio queda detenido durante la importación.",12));
        importProgressDialog = new AlertDialog.Builder(this).setTitle("Importando bancos")
            .setView(panel).setNegativeButton("Cancelar",null).setCancelable(false).create();
        importProgressDialog.setOnShowListener(d -> importProgressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelImport.set(true); importProgress.setText("Cancelando y descartando la importación incompleta…");
            importProgressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }));
        importProgressDialog.show();
    }
    private void importProgress(int folders,int patches,int skipped,String current) {
        long now = SystemClock.elapsedRealtime();
        if (now-lastImportProgress<200) return;
        lastImportProgress = now;
        String value = folders+" carpetas · "+patches+" patches · "+skipped+" omitidos\n"+current;
        ui.post(() -> { importStatus=value; if (!destroyed && importProgress != null && !cancelImport.get()) importProgress.setText(value); });
    }
    private void beginImport(List<Uri> uris,boolean tree) {
        if (importing || destroyed || uris.isEmpty()) return;
        importing=true; cancelImport.set(false); lastImportProgress=0;
        importStatus="Leyendo la selección…"; panic(); patchRequest.incrementAndGet(); showImportProgress();
        worker.execute(() -> {
            String outcome;
            boolean success=false;
            try {
                if (engine != null) engine.stop();
                if (store == null) throw new IOException("Biblioteca no preparada");
                FolderImporter.Result result;
                if (tree) {
                    DocumentSource source = DocumentSource.tree(getContentResolver(),uris.get(0));
                    result=FolderImporter.run(store,source,source.root,cancelImport::get,this::importProgress);
                } else {
                    String name=DocumentSource.displayName(getContentResolver(),uris.get(0),"Importado");
                    if (uris.size()==1 && !name.toLowerCase(Locale.ROOT).endsWith(".sy1")) {
                        // Existing ZIP parser remains bounded. Make cancellation visible even while reading.
                        InputStream raw=getContentResolver().openInputStream(uris.get(0));
                        if (raw==null) throw new IOException("No se pudo abrir el archivo");
                        int count=store.importFile(raw,name,cancelImport::get);
                        result=new FolderImporter.Result(); result.patches=count;
                        outcome=count+" patches importados desde "+name;
                    } else {
                        DocumentSource source=DocumentSource.files(getContentResolver(),uris);
                        result=FolderImporter.run(store,source,source.root,cancelImport::get,this::importProgress);
                    }
                }
                ui.post(() -> {
                    if (!destroyed && importProgressDialog!=null) {
                        importProgress.setText("Importación guardada. Organizando la biblioteca…");
                        importProgressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                    }
                });
                List<PatchStore.Bank> scanned=store.scan(initialPatch);
                synchronized (libraryLock) {
                    String key=banks.isEmpty()?":init":banks.get(bankIndex).key;
                    banks=scanned; bankIndex=findBank(scanned,key);
                }
                outcome=(result.banks>0?result.summary():result.patches+" patches importados")+
                    (result.warnings.isEmpty()?"":"\n\nArchivos omitidos (hasta 8):\n"+String.join("\n",result.warnings));
                success=true;
            } catch (Exception ex) { outcome=ex.getMessage()==null?"Importación fallida":ex.getMessage(); }
            final String report=outcome; final boolean completed=success;
            ui.post(() -> {
                importing=false;
                if (importProgressDialog!=null) { importProgressDialog.dismiss(); importProgressDialog=null; importProgress=null; }
                if (destroyed) return;
                refreshLcd(); message(report);
                new AlertDialog.Builder(this).setTitle(completed?"Importación terminada":"Importación no completada")
                    .setMessage(report).setPositiveButton(completed?"Ver bancos":"Cerrar",(d,w)->{if(completed) bankDialog();}).show();
                if (foreground && wantsAudio) startAudio();
            });
        });
    }
    private void exportDialog() {
        NativeEngine e = engine; if (e == null) return;
        EditText name = new EditText(this); name.setSingleLine(true); name.setText(patchName);
        new AlertDialog.Builder(this).setTitle("Exportar sonido actual").setView(name).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(d,w) -> {
            String chosen = name.getText().toString().replace('\n',' ').replace('\r',' ').trim(); if (chosen.isEmpty()) chosen = "RiSynth2";
            pendingExport = e.save(chosen);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, chosen.replaceAll("[\\\\/:*?\"<>|]", "_") + ".sy1");
            startActivityForResult(intent, EXPORT);
        }).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (result != RESULT_OK || data == null) return;
        if (request==IMPORT || request==IMPORT_TREE) {
            LinkedHashSet<Uri> selected=new LinkedHashSet<>();
            ClipData clips=data.getClipData();
            if (clips!=null) for (int i=0;i<clips.getItemCount();++i) selected.add(clips.getItemAt(i).getUri());
            else if (data.getData()!=null) selected.add(data.getData());
            beginImport(new ArrayList<>(selected),request==IMPORT_TREE);
        } else if (request==EXPORT && pendingExport!=null && data.getData()!=null) {
            Uri uri=data.getData(); String contents=pendingExport; pendingExport=null;
            worker.execute(() -> {
                try (OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {
                    if (out==null) throw new IOException("No se pudo abrir el destino");
                    out.write(contents.getBytes(StandardCharsets.UTF_8)); message("Patch exportado.");
                } catch (Exception ex) { message("No se pudo exportar: "+ex.getMessage()); }
            });
        }
    }
    private void scheduleSave() {
        ui.removeCallbacks(saveLater);
        ui.postDelayed(saveLater, 300);
        scheduleFavoriteCheck();
    }
    private void saveSession() {
        synchronized (libraryLock) {
            NativeEngine e = engine; if (e == null) return;
            prefs.edit().putString("patch", e.save(patchName)).putString("patchName", patchName)
                .putString("bankKey", banks.isEmpty() ? ":init" : banks.get(bankIndex).key).putInt("slot", slot)
                .putInt("volume",volume).putInt("tempo",bpm).apply();
        }
    }
    private void message(String value) { ui.post(() -> { if (!destroyed && notice != null) notice.setText(value); }); }
    @Override protected void onResume() {
        super.onResume(); foreground = true; ui.removeCallbacks(poll); ui.post(poll); if (wantsAudio) startAudio();
    }
    @Override protected void onPause() {
        centerLivePitch();
        foreground = false; ui.removeCallbacks(poll); ui.removeCallbacks(routeChanged); ui.removeCallbacks(saveLater); ui.removeCallbacks(favoriteCheckLater);
        panic(); saveSession(); stopAudio(); audioManager.abandonAudioFocusRequest(focus); super.onPause();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        centerLivePitch();
        panic();
        ui.removeCallbacks(favoriteCheckLater);
        ++favoriteCheckSequence;
        currentPatchSaved = false;
        buildUi();
    }
    @Override protected void onDestroy() {
        destroyed = true; foreground = false; cancelImport.set(true); patchRequest.incrementAndGet();
        if (importProgressDialog != null) { importProgressDialog.dismiss(); importProgressDialog=null; }
        monitorWorker.shutdown(); ui.removeCallbacksAndMessages(null);
        if (midi != null) midi.close(); audioManager.unregisterAudioDeviceCallback(audioDevices);
        worker.execute(() -> { NativeEngine e = engine; engine = null; if (e != null) e.close(); }); worker.shutdown();
        super.onDestroy();
    }
    private int qwerty(int key) {
        int[] keys = {KeyEvent.KEYCODE_Z,KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_X,KeyEvent.KEYCODE_D,KeyEvent.KEYCODE_C,KeyEvent.KEYCODE_V,KeyEvent.KEYCODE_G,KeyEvent.KEYCODE_B,KeyEvent.KEYCODE_H,KeyEvent.KEYCODE_N,KeyEvent.KEYCODE_J,KeyEvent.KEYCODE_M};
        for (int i = 0; i < keys.length; ++i) if (keys[i] == key) return (piano == null ? 48 : piano.base()) + i; return -1;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (getCurrentFocus() instanceof EditText) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode(), note = qwerty(key);
        if (note >= 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) { computerKeys.put(key,note); midi(0x90,note,100); }
            if (event.getAction() == KeyEvent.ACTION_UP) { Integer held = computerKeys.remove(key); if (held != null) midi(0x80,held,0); }
            return true;
        }
        if (key == KeyEvent.KEYCODE_SPACE) { if (event.getRepeatCount() == 0) midi(0xb0,64,event.getAction() == KeyEvent.ACTION_DOWN ? 127 : 0); return true; }
        return super.dispatchKeyEvent(event);
    }
}
