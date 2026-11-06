package com.rinoize.risynth2;

// Every JNI entry is serialized against destruction/start/stop. No Java code runs
// inside the native audio callback. MIDI receivers may call this from their thread.
final class NativeEngine implements AutoCloseable {
    static { System.loadLibrary("risynth2"); }
    private long handle = create();
    private boolean streaming;
    private String loadedName = "INIT";
    synchronized String start(int device, int frames, boolean lowLatency) {
        if (handle == 0) return "Motor cerrado";
        String error = startNative(handle, device, frames, lowLatency);
        streaming = error.isEmpty();
        return error;
    }
    synchronized boolean isStreaming() { return streaming; }
    synchronized void stop() { streaming = false; if (handle != 0) stopNative(handle); }
    synchronized void midi(int status, int d1, int d2) { if (handle != 0) midiNative(handle, status, d1, d2); }
    synchronized void panic() { if (handle != 0) panicNative(handle); }
    // When stopped there is no audio consumer. Drain controls off-line so edits,
    // imports and session restoration cannot fill the bounded event queue.
    private void flushStopped() { if (!streaming && handle != 0) stopNative(handle); }
    synchronized void param(int id, int value) { if (handle != 0) { paramNative(handle, id, value); flushStopped(); } }
    synchronized void volume(int value) { if (handle != 0) { volumeNative(handle, value); flushStopped(); } }
    synchronized void tempo(int value) { if (handle != 0) { tempoNative(handle, value); flushStopped(); } }
    synchronized boolean load(String sy1) {
        if (handle == 0) return false;
        boolean accepted = loadNative(handle, sy1);
        if (accepted) loadedName = sy1.split("\\R", 2)[0];
        flushStopped(); return accepted;
    }
    // One lock captures parameters and their matching loaded name at tap time.
    synchronized String snapshot() { return handle == 0 ? "" : saveNative(handle, loadedName); }
    synchronized String save(String name) { return handle == 0 ? "" : saveNative(handle, name); }
    synchronized int[] values() { return handle == 0 ? new int[99] : valuesNative(handle); }
    synchronized double[] stats() {
        if (handle == 0) return new double[21];
        double[] result = statsNative(handle);
        if (result[0] == 0) streaming = false;
        return result;
    }
    public synchronized void close() { streaming = false; if (handle != 0) { destroy(handle); handle = 0; } }
    static native String initialPatch();
    static native String[] parameterInfo();
    private static native long create();
    private static native void destroy(long h);
    private static native String startNative(long h, int device, int frames, boolean lowLatency);
    private static native void stopNative(long h);
    private static native void midiNative(long h, int status, int d1, int d2);
    private static native void panicNative(long h);
    private static native void paramNative(long h, int id, int value);
    private static native void volumeNative(long h, int value);
    private static native void tempoNative(long h, int value);
    private static native boolean loadNative(long h, String sy1);
    private static native String saveNative(long h, String name);
    private static native int[] valuesNative(long h);
    private static native double[] statsNative(long h);
}
