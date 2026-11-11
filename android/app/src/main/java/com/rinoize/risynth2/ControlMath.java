package com.rinoize.risynth2;

/** Pure value/geometry math for the live-performance knobs and strips.
 * No Android imports, so android/test-host.sh can verify it on the host. */
final class ControlMath {
    /** Canvas.drawArc convention: 0 = 3 o'clock, clockwise. A knob sweeps 270 degrees from 7:30 to 4:30. */
    static final float START_DEGREES = 135f, SWEEP_DEGREES = 270f;
    static final int BEND_CENTER = 8192, BEND_MAX = 16383;

    private ControlMath() { }

    static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }

    /** Position of value inside [min, max] as 0..1; a degenerate range reads as 0. */
    static float fraction(int value, int min, int max) {
        if (max <= min) return 0f;
        return (clamp(value, min, max) - min) / (float) (max - min);
    }

    /** Vertical drag on a knob. Dragging up (positive upwardPx) raises the value;
     * fullRangePx is the drag distance that covers the whole range. */
    static int dragValue(int startValue, float upwardPx, float fullRangePx, int min, int max) {
        if (max <= min || fullRangePx <= 0f) return min;
        return clamp(startValue + Math.round(upwardPx / fullRangePx * (max - min)), min, max);
    }

    /** Canvas angle of the knob pointer for a 0..1 fraction. */
    static float pointerDegrees(float fraction) { return START_DEGREES + SWEEP_DEGREES * clamp01(fraction); }

    /** Value 0..max of a vertical strip for a touch at y. The thumb is thumbHeight tall and
     * its centre travels between the top of the track area and its bottom. */
    static int stripValue(float y, float trackTop, float trackHeight, float thumbHeight, int max) {
        float travel = trackHeight - thumbHeight;
        if (travel <= 0f || max <= 0) return 0;
        float fraction = 1f - (y - trackTop - thumbHeight / 2f) / travel;
        return clamp(Math.round(clamp01(fraction) * max), 0, max);
    }

    /** Top edge of the thumb for a strip value (inverse of stripValue). */
    static float stripThumbTop(int value, int max, float trackTop, float trackHeight, float thumbHeight) {
        float travel = Math.max(0f, trackHeight - thumbHeight);
        float fraction = max <= 0 ? 0f : clamp01(value / (float) max);
        return trackTop + (1f - fraction) * travel;
    }

    /** MIDI pitch bend is 14 bits: data byte 1 = low 7 bits, data byte 2 = high 7 bits. */
    static int bendLow(int value) { return value & 0x7f; }
    static int bendHigh(int value) { return (value >> 7) & 0x7f; }
}
