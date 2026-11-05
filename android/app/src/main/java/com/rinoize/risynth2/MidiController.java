package com.rinoize.risynth2;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class MidiController implements AutoCloseable {
    interface Listener {
        void midi(int status, int d1, int d2);
        void program(int bank, int program);
        void disconnected();
        void state(String description);
    }
    static final class Source {
        final MidiDeviceInfo info;
        final int port;
        final String label;
        Source(MidiDeviceInfo info, int port, String label) { this.info = info; this.port = port; this.label = label; }
    }
    private final MidiManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MidiByteParser parser;
    private MidiDevice device;
    private MidiOutputPort port;
    private int deviceId = -1, generation;
    private boolean closed, autoConnect = true;
    volatile int channel = -1; // -1 = omni; 0..15 = MIDI channels 1..16
    private int bankMsb, bankLsb;
    private boolean bankReceived;
    final AtomicLong messages = new AtomicLong();
    volatile boolean sustain;
    private final MidiReceiver receiver = new MidiReceiver() {
        @Override public void onSend(byte[] data, int offset, int count, long timestamp) {
            // Delivery-time scheduling in 0.1: no JNI or UI calls from the audio callback.
            parser.accept(data, offset, count);
        }
        @Override public void onFlush() { parser.reset(); listener.disconnected(); }
    };
    private final MidiManager.DeviceCallback callback = new MidiManager.DeviceCallback() {
        @Override public void onDeviceAdded(MidiDeviceInfo info) { maybeAutoConnect(); }
        @Override public void onDeviceRemoved(MidiDeviceInfo info) {
            if (info.getId() == deviceId) { disconnect(); autoConnect = true; maybeAutoConnect(); }
        }
    };
    MidiController(Context context, Listener listener) {
        this.listener = listener;
        manager = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
        parser = new MidiByteParser((status, d1, d2) -> {
            if (channel >= 0 && (status & 15) != channel) return;
            messages.incrementAndGet();
            int type = status & 0xf0;
            if (type == 0xb0) {
                if (d1 == 0) { bankMsb = d2; bankReceived = true; }
                if (d1 == 32) { bankLsb = d2; bankReceived = true; }
                if (d1 == 64) sustain = d2 >= 64;
            }
            if (type == 0xc0) listener.program(bankReceived ? bankMsb * 128 + bankLsb : -1, d1);
            else listener.midi(status, d1, d2);
        });
        if (manager != null) {
            manager.registerDeviceCallback(callback, main);
            main.post(this::maybeAutoConnect);
        } else listener.state("MIDI no disponible; teclado en pantalla habilitado");
    }
    List<Source> sources() {
        List<Source> result = new ArrayList<>();
        if (manager == null) return result;
        for (MidiDeviceInfo info : manager.getDevices()) {
            String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME, "Dispositivo MIDI");
            for (MidiDeviceInfo.PortInfo p : info.getPorts()) {
                if (p.getType() != MidiDeviceInfo.PortInfo.TYPE_OUTPUT) continue;
                result.add(new Source(info, p.getPortNumber(), name + " / " + (p.getName().isEmpty() ? "salida " + (p.getPortNumber() + 1) : p.getName())));
            }
        }
        return result;
    }
    private void maybeAutoConnect() {
        if (closed || !autoConnect || device != null || deviceId >= 0) return;
        for (Source source : sources()) {
            if (source.info.getType() == MidiDeviceInfo.TYPE_USB) { select(source); return; }
        }
    }
    void select(Source source) {
        disconnect();
        if (closed || manager == null) return;
        autoConnect = false;
        int ticket = ++generation;
        deviceId = source.info.getId();
        listener.state("Abriendo " + source.label);
        manager.openDevice(source.info, opened -> {
            if (closed || ticket != generation) { closeQuietly(opened); return; }
            if (opened == null) { deviceId = -1; listener.state("No se pudo abrir MIDI"); return; }
            device = opened;
            port = opened.openOutputPort(source.port);
            if (port == null) { disconnect(); listener.state("Puerto MIDI ocupado o no disponible"); return; }
            port.connect(receiver);
            listener.state(source.label);
        }, main);
    }
    void disconnect() {
        ++generation;
        autoConnect = false;
        if (port != null) { port.disconnect(receiver); closeQuietly(port); port = null; }
        closeQuietly(device); device = null; deviceId = -1;
        parser.reset(); bankReceived = false; bankMsb = bankLsb = 0; sustain = false;
        listener.disconnected(); listener.state("MIDI desconectado");
    }
    void setChannel(int value) {
        channel = value;
        parser.reset(); bankReceived = false; bankMsb = bankLsb = 0; sustain = false;
        listener.disconnected();
    }
    private static void closeQuietly(AutoCloseable value) {
        if (value != null) try { value.close(); } catch (Exception ignored) { }
    }
    public void close() {
        closed = true;
        if (manager != null) manager.unregisterDeviceCallback(callback);
        disconnect();
    }
}
