package com.rinoize.risynth2;
import java.util.*;
public final class MidiParserTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        List<String> out = new ArrayList<>();
        MidiByteParser p = new MidiByteParser((s,a,b) -> out.add(s+":"+a+":"+b));
        p.accept(new byte[]{(byte)0x90,60},0,2);
        check(out.isEmpty(),"split message");
        p.accept(new byte[]{100,62,(byte)0xf8,99,(byte)0x80,60,0},0,7);
        check(out.equals(Arrays.asList("144:60:100","144:62:99","128:60:0")),"running status / clock / off");
        out.clear();
        p.accept(new byte[]{(byte)0xc3,7,8,(byte)0xd3,64,(byte)0xe3,0,64},0,8);
        check(out.equals(Arrays.asList("195:7:0","195:8:0","211:64:0","227:0:64")),"one byte messages and bend");
        out.clear();
        p.accept(new byte[]{(byte)0xf0,1,2,(byte)0xf8,3,(byte)0xf7,60,100,(byte)0x91,64,127},0,11);
        check(out.equals(Collections.singletonList("145:64:127")),"sysex ignored, no leaked status");
        out.clear(); p.reset();
        p.accept(new byte[]{60,100,(byte)0x90,60,0},0,5);
        check(out.equals(Collections.singletonList("144:60:0")),"reset and velocity zero preserved");
        System.out.println("MIDI parser: 5 scenarios passed");
    }
}
