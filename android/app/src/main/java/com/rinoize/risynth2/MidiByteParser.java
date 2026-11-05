package com.rinoize.risynth2;

/** MIDI 1 byte-stream parser. Packets may split messages or contain several.
 * Real-time bytes must not destroy running status. SysEx is deliberately ignored. */
final class MidiByteParser {
    interface Sink { void message(int status, int data1, int data2); }
    private final Sink sink;
    private int status, needed, used, first;
    private boolean sysex;
    MidiByteParser(Sink sink) { this.sink = sink; }
    synchronized void reset() { status = needed = used = first = 0; sysex = false; }
    synchronized void accept(byte[] data, int offset, int count) {
        if (offset < 0 || count < 0 || offset > data.length - count) throw new IndexOutOfBoundsException();
        for (int i = offset; i < offset + count; ++i) {
            int b = data[i] & 255;
            if (b >= 0xf8) continue;
            if (b == 0xf0) { sysex = true; status = needed = used = 0; continue; }
            if (b == 0xf7) { sysex = false; status = needed = used = 0; continue; }
            if ((b & 128) != 0) {
                sysex = false; used = 0;
                if (b >= 0xf0) { status = needed = 0; continue; }
                status = b;
                int type = b & 0xf0;
                needed = type == 0xc0 || type == 0xd0 ? 1 : 2;
                continue;
            }
            if (sysex || status == 0) continue;
            if (used++ == 0) first = b;
            if (used == needed) {
                sink.message(status, first, needed == 2 ? b : 0);
                used = 0;
            }
        }
    }
}
