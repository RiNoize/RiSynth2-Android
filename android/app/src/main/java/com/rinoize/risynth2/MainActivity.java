package com.rinoize.risynth2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements MidiController.Listener {
    private static final int IMPORT = 10, EXPORT = 11;
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
    private int requestedFrames = 512, routePreference = 0, volume = 48, bpm = 120;
    private boolean lowLatency = true, refreshing;
    private String pendingExport, initialPatch;
    private String[] info;
    private LinearLayout root, body;
    private TextView lcd, bankLabel, diagnostic, midiLabel, notice;
    private Button audioButton;
    private PianoView piano;
    private final Map<Integer, SeekBar> sliders = new HashMap<>();
    private final Map<Integer, TextView> valueLabels = new HashMap<>();
    private final Map<Integer, Button> toggles = new HashMap<>();
    private final Map<Integer, Integer> computerKeys = new HashMap<>();
    private final Runnable saveLater = this::saveSession;
    private final Runnable routeChanged = () -> { if (foreground && wantsAudio && engine != null) startAudio(); };
    private final AudioDeviceCallback audioDevices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { scheduleRouteChange(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { scheduleRouteChange(); }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            NativeEngine e = engine;
            if (!foreground || destroyed) return;
            if (e != null) {
                double[] s = e.stats();
                diagnostic.setText(String.format(Locale.US,
                    "%s · %.0f Hz · buffer %.0f fr (pedido %d) · burst %.0f\nRender %.1f%% · pico %.1f%% · voces %.0f · cortes %.0f · clip %.0f · cola %.0f\nSalida ID %.0f · %s · MIDI %d · pedal %s",
                    s[0] > 0 ? "AUDIO ACTIVO" : "AUDIO DETENIDO", s[1], s[3], requestedFrames, s[2],
                    s[5], s[6], s[7], s[4], s[8], s[9], s[10], s[12] == 12 ? "LOW LATENCY" : "NORMAL",
                    midi == null ? 0 : midi.messages.get(), midi != null && midi.sustain ? "ON" : "OFF"));
                audioButton.setText(s[0] > 0 ? "Detener audio" : "Iniciar audio");
                if (s[11] < 0) { wantsAudio = false; notice.setText("Audio desconectado (" + (int)s[11] + "). Pulsa Iniciar audio."); }
            }
            ui.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("risynth2", MODE_PRIVATE);
        volume = prefs.getInt("volume", 48); bpm = prefs.getInt("tempo", 120);
        requestedFrames = prefs.getInt("frames", 512); lowLatency = prefs.getBoolean("lowLatency", true);
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
                ui.post(() -> { refreshControls(); refreshLcd(); message("Motor listo. Importa tus .sy1 o bancos ZIP."); });
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
        sliders.clear(); valueLabels.clear(); toggles.clear();
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(16,21,28));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(12) + insets.getSystemWindowInsetLeft(), dp(4) + insets.getSystemWindowInsetTop(), dp(12) + insets.getSystemWindowInsetRight(), dp(4) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); setContentView(root);
        body.addView(text("RiSynth2  " + BuildConfig.VERSION_NAME + "  ·  Android", 23));
        LinearLayout tools = row(body);
        audioButton = button(tools, "Iniciar audio", () -> {
            NativeEngine e = engine;
            if (e != null && e.stats()[0] > 0) { wantsAudio = false; stopAudio(); }
            else { wantsAudio = true; startAudio(); }
        });
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
        LinearLayout files = row(body);
        button(files, "Importar", this::importDialog);
        button(files, "Exportar .sy1", this::exportDialog);
        button(files, "INIT", () -> {
            new AlertDialog.Builder(this).setTitle("Inicializar sonido")
                .setMessage("Reemplaza las ediciones actuales por INIT. No borra bancos.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Inicializar", (d, w) -> applyPatch(0, 0)).show();
        });
        button(files, "99 parametros", this::allParameters);
        LinearLayout togglesRow = row(body);
        for (int id : new int[]{59,65,66,73,77}) {
            String label = switch(id) { case 59 -> "ARP"; case 65 -> "Delay"; case 66 -> "Chorus"; case 73 -> "Unison"; default -> "Effect"; };
            Button b = button(togglesRow, label, () -> { NativeEngine e = engine; if (e != null) { e.param(id, e.values()[id] == 0 ? 1 : 0); refreshControls(); scheduleSave(); } });
            b.setTag(label); toggles.put(id, b);
        }
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
        diagnostic = text("Preparando motor…", 12); diagnostic.setTypeface(Typeface.MONOSPACE); body.addView(diagnostic);
        notice = text("Compatible con patches Synth1. Sin ROM ni firmware.", 13); body.addView(notice);
        body.addView(text("Render mide trabajo del callback, no CPU total ni latencia tecla–audio. Uso en primer plano.", 11));
        refreshControls(); refreshLcd();
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
        refreshing = false;
    }
    private void refreshLcd() {
        if (lcd == null) return;
        lcd.setText(String.format(Locale.ROOT, "%03d · %s", slot + 1, patchName));
        List<PatchStore.Bank> list = banks;
        bankLabel.setText(bankIndex >= 0 && bankIndex < list.size() ? list.get(bankIndex).name : "RiSynth2 · INIT");
        midiLabel.setText(midiName);
    }
    private static int findBank(List<PatchStore.Bank> list, String key) {
        for (int i = 0; i < list.size(); ++i) if (list.get(i).key.equals(key)) return i; return 0;
    }
    private void applyPatch(int bank, int program) {
        synchronized (libraryLock) {
            if (bank < 0 || bank >= banks.size() || program < 0 || program >= 128 || engine == null) return;
            PatchStore.Patch patch = banks.get(bank).slots[program];
            if (patch == null) return;
            if (!engine.load(patch.text)) { message("Cola MIDI llena: vuelve a cargar el patch."); return; }
            bankIndex = bank; slot = program; patchName = patch.name;
        }
        ui.post(() -> { refreshControls(); refreshLcd(); scheduleSave(); });
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
    private void bankDialog() {
        List<PatchStore.Bank> snapshot = banks;
        if (snapshot.isEmpty()) return;
        String[] names = snapshot.stream().map(b -> b.name).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Bancos").setItems(names, (d, index) -> { applyPatch(index, snapshot.get(index).first()); patchDialog(); }).setNegativeButton("Cerrar", null).show();
    }
    private void patchDialog() {
        List<PatchStore.Bank> list = banks; if (list.isEmpty()) return;
        int selectedBank = bankIndex; PatchStore.Bank bank = list.get(selectedBank);
        List<Integer> positions = new ArrayList<>(); List<String> names = new ArrayList<>();
        for (int i = 0; i < 128; ++i) if (bank.slots[i] != null) { positions.add(i); names.add(String.format(Locale.ROOT, "%03d · %s", i+1, bank.slots[i].name)); }
        new AlertDialog.Builder(this).setTitle(bank.name).setItems(names.toArray(new String[0]), (d, which) -> applyPatch(selectedBank, positions.get(which))).setNegativeButton("Cerrar", null).show();
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
        CheckBox low = new CheckBox(this); low.setText("Solicitar baja latencia (modo compartido)"); low.setChecked(lowLatency); panel.addView(low);
        panel.addView(text("Frecuencia nativa del dispositivo. Los cambios cierran y reabren el audio. No hay autoajuste oculto.", 13));
        new AlertDialog.Builder(this).setTitle("Audio").setView(panel).setNegativeButton("Cancelar", null).setPositiveButton("Aplicar", (d,w) -> {
            routePreference = ids.get(output.getSelectedItemPosition()); requestedFrames = frameOptions[buffer.getSelectedItemPosition()]; lowLatency = low.isChecked();
            prefs.edit().putInt("frames",requestedFrames).putBoolean("lowLatency",lowLatency).putBoolean("automaticRoute", routePreference == -1).apply();
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
        if (!foreground || !wantsAudio || destroyed) return;
        final int device;
        try { device = resolveDevice(); } catch (Exception e) { message(e.getMessage()); return; }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { message("No se obtuvo el foco de audio."); return; }
        final int frames = requestedFrames; final boolean low = lowLatency;
        notice.setText("Preparando audio…"); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            if (!foreground || !wantsAudio || destroyed || engine == null) return;
            String error = engine.start(device, frames, low);
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
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, IMPORT);
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
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == IMPORT) {
            String name = "Importado";
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null,null,null)) { if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0); }
            catch (Exception ignored) { }
            final String filename = name; message("Importando bancos…");
            worker.execute(() -> {
                try {
                    if (store == null) throw new IOException("Biblioteca no preparada");
                    InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new IOException("No se pudo abrir el archivo");
                    int count = store.importFile(in, filename);
                    List<PatchStore.Bank> scanned = store.scan(initialPatch);
                    synchronized (libraryLock) {
                        String key = banks.isEmpty() ? ":init" : banks.get(bankIndex).key;
                        banks = scanned; bankIndex = findBank(scanned, key);
                    }
                    ui.post(() -> { refreshLcd(); message(count + " patches importados. Toca el nombre del banco para elegir."); bankDialog(); });
                } catch (Exception ex) { message("Importacion fallida: " + ex.getMessage()); }
            });
        } else if (request == EXPORT && pendingExport != null) {
            String contents = pendingExport; pendingExport = null;
            worker.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("No se pudo abrir el destino");
                    out.write(contents.getBytes(StandardCharsets.UTF_8)); message("Patch exportado.");
                } catch (Exception ex) { message("No se pudo exportar: " + ex.getMessage()); }
            });
        }
    }
    private void scheduleSave() { ui.removeCallbacks(saveLater); ui.postDelayed(saveLater, 300); }
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
        foreground = false; ui.removeCallbacks(poll); ui.removeCallbacks(routeChanged); ui.removeCallbacks(saveLater);
        panic(); saveSession(); stopAudio(); audioManager.abandonAudioFocusRequest(focus); super.onPause();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); panic(); buildUi();
    }
    @Override protected void onDestroy() {
        destroyed = true; foreground = false; ui.removeCallbacksAndMessages(null);
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
