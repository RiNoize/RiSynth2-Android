package com.rinoize.risynth2;

public final class ControlMathTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void near(double a, double b, String why) { if (Math.abs(a - b) > 0.001) throw new AssertionError(why + ": " + a + " vs " + b); }

    public static void main(String[] args) {
        // fraction: endpoints, clamping and degenerate ranges
        near(ControlMath.fraction(0, 0, 127), 0, "min is 0");
        near(ControlMath.fraction(127, 0, 127), 1, "max is 1");
        near(ControlMath.fraction(-24, -24, 24), 0, "negative minimum");
        near(ControlMath.fraction(0, -24, 24), 0.5, "negative range midpoint");
        near(ControlMath.fraction(500, 0, 127), 1, "above max clamps");
        near(ControlMath.fraction(-5, 0, 127), 0, "below min clamps");
        near(ControlMath.fraction(3, 5, 5), 0, "empty range must not divide by zero");

        // knob drag: up raises, down lowers, always inside the native range
        check(ControlMath.dragValue(64, 0, 200, 0, 127) == 64, "no movement keeps the value");
        check(ControlMath.dragValue(0, 200, 200, 0, 127) == 127, "a full-range drag up covers the range");
        check(ControlMath.dragValue(127, -200, 200, 0, 127) == 0, "a full-range drag down covers the range");
        check(ControlMath.dragValue(100, 1000, 200, 0, 127) == 127, "over-drag clamps at max");
        check(ControlMath.dragValue(10, -1000, 200, 0, 127) == 0, "under-drag clamps at min");
        check(ControlMath.dragValue(0, 100, 200, 0, 100) == 50, "half a drag is half of a 0..100 range");
        check(ControlMath.dragValue(5, 10, 0, 0, 127) == 0, "invalid drag distance is safe");
        check(ControlMath.dragValue(5, 10, 200, 7, 7) == 7, "empty range returns its only value");

        // knob geometry follows Canvas.drawArc: 7:30 -> 4:30 clockwise
        near(ControlMath.pointerDegrees(0), 135, "pointer starts at 7:30");
        near(ControlMath.pointerDegrees(0.5f), 270, "pointer is straight up at the middle");
        near(ControlMath.pointerDegrees(1), 405, "pointer ends at 4:30");
        near(ControlMath.pointerDegrees(2), 405, "pointer fraction clamps");

        // strips: top of travel is max, bottom is 0, thumb position inverts the value
        float top = 30f, height = 200f, thumb = 40f;
        check(ControlMath.stripValue(top, top, height, thumb, 127) == 127, "touch above the track reads max");
        check(ControlMath.stripValue(top + height, top, height, thumb, 127) == 0, "touch below the track reads 0");
        check(ControlMath.stripValue(top + height / 2f, top, height, thumb, 127) == 64, "middle touch reads the middle");
        check(ControlMath.stripValue(top + height / 2f, top, height, thumb, ControlMath.BEND_MAX) == ControlMath.BEND_CENTER,
            "middle of the pitch strip is the MIDI pitch-bend centre 8192");
        check(ControlMath.stripValue(10, top, 30f, thumb, 127) == 0, "a track shorter than the thumb is safe");
        for (int v = 0; v <= 127; ++v) {
            float thumbTop = ControlMath.stripThumbTop(v, 127, top, height, thumb);
            check(thumbTop >= top - 0.001 && thumbTop <= top + height - thumb + 0.001, "thumb stays inside the track for " + v);
            int back = ControlMath.stripValue(thumbTop + thumb / 2f, top, height, thumb, 127);
            check(back == v, "strip value round-trips for " + v + " got " + back);
        }
        near(ControlMath.stripThumbTop(127, 127, top, height, thumb), top, "max value puts the thumb at the top");
        near(ControlMath.stripThumbTop(0, 127, top, height, thumb), top + height - thumb, "zero puts the thumb at the bottom");

        // 14-bit pitch bend split must match the engine's (low | high << 7) join
        for (int v : new int[]{0, 1, 127, 128, ControlMath.BEND_CENTER, 12345, ControlMath.BEND_MAX}) {
            int joined = ControlMath.bendLow(v) | (ControlMath.bendHigh(v) << 7);
            check(joined == v, "pitch bend bytes rebuild " + v + " got " + joined);
            check(ControlMath.bendLow(v) < 128 && ControlMath.bendHigh(v) < 128, "data bytes stay 7-bit for " + v);
        }
        check(ControlMath.bendLow(ControlMath.BEND_CENTER) == 0 && ControlMath.bendHigh(ControlMath.BEND_CENTER) == 64,
            "centre is the standard E0 00 40");

        System.out.println("Control math: knob drag, arc geometry, strip mapping and pitch-bend bytes OK");
    }
}
