package com.rinoize.risynth2;

// Every JNI entry is serialized against destruction/start/stop. No Java code runs
// inside the native audio callback. MIDI receivers may call this from their thread.
final class NativeEngine implements AutoCloseable {
    static { System.loadLibrary("risynth2"); }
    private long handle = create();
    synchronized String start(int device, int frames, boolean lowLatency) {
        return handle == 0 ? "Motor cerrado" : startNative(handle, device, frames, lowLatency);
    }
    synchronized void stop() { if (handle != 0) stopNative(handle); }
    synchronized void midi(int status, int d1, int d2) { if (handle != 0) midiNative(handle, status, d1, d2); }
    synchronized void panic() { if (handle != 0) panicNative(handle); }
    synchronized void param(int id, int value) { if (handle != 0) paramNative(handle, id, value); }
    synchronized void volume(int value) { if (handle != 0) volumeNative(handle, value); }
    synchronized void tempo(int value) { if (handle != 0) tempoNative(handle, value); }
    synchronized boolean load(String sy1) { return handle != 0 && loadNative(handle, sy1); }
    synchronized String save(String name) { return handle == 0 ? "" : saveNative(handle, name); }
    synchronized int[] values() { return handle == 0 ? new int[99] : valuesNative(handle); }
    synchronized double[] stats() { return handle == 0 ? new double[13] : statsNative(handle); }
    public synchronized void close() { if (handle != 0) { destroy(handle); handle = 0; } }
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
