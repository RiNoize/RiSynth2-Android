package com.rinoize.risynth2;
public final class DiagnosticMonitorTest {
    private static void near(double a,double b,String why) { if(Math.abs(a-b)>0.001) throw new AssertionError(why+": "+a+" vs "+b); }
    public static void main(String[] args) {
        DiagnosticMonitor m=new DiagnosticMonitor(); double[] s=new double[21];
        s[0]=1; s[1]=48000; s[3]=1024; s[18]=256; s[20]=1;
        m.sample(s,1000,100,1024,"test");
        s[13]=100; s[14]=250000000; s[15]=24000; s[16]=5; s[17]=150; s[4]=2;
        m.sample(s,1500,850,1024,"test");
        near(m.cpuPercent(),150,"CPU above one core must not be capped");
        near(m.renderPercent(),50,"Render is divided by produced audio time");
        near(m.overrunRate(),10,"OV rate uses actual sampling interval");
        if(!m.detail().contains("XRuns / underruns AAudio: 2")) throw new AssertionError("real xruns distinct from overruns");
        m.reset(s,1500,850);
        s[13]+=200; s[14]+=1000000000; s[15]+=96000; s[16]+=6; s[17]=80;
        m.sample(s,3500,1850,1024,"test");
        near(m.cpuPercent(),50,"two-second delayed sampling"); near(m.overrunRate(),3,"reset baselines");
        s=new double[21]; s[1]=44100; s[4]=-1; s[20]=2;
        m.sample(s,3600,1850,512,"new stream");
        if(!m.detail().contains("AAudio: n/d")) throw new AssertionError("unsupported xrun counter");
        near(m.cpuPercent(),0,"no old-session CPU carryover");
        for(int i=0;i<200;i++) m.sample(s,4100+i*500,1850,512,"bounded");
        if(m.report("test").split("active=").length>121) throw new AssertionError("unbounded log");
        s=new double[23]; s[0]=1; s[1]=48000; s[20]=3;
        m.sample(s,200000,20000,1024,"stages");
        s[15]=24000; s[14]=250000000; s[21]=200000000; s[22]=25000000;
        m.sample(s,200500,20250,1024,"stages");
        if(!m.detail().contains("Voces/LFO 40.0% · Efectos/salida 5.0%")) throw new AssertionError("stage timing denominator");
        m.reset(s,200500,20250); m.sample(s,201000,20250,1024,"stopped");
        if(!m.detail().contains("Voces/LFO 0.0%")) throw new AssertionError("stale stage counters");
        s=new double[32];s[0]=1;s[1]=48000;s[20]=4;s[23]=2;s[24]=321;s[25]=8533333;s[26]=5000000;s[27]=50;s[31]=750000;
        m.sample(s,202000,20250,2048,"hints");
        if(!m.compact().contains("ADPF sesión activa") || !m.detail().contains("TID de audio 321") ||
            !m.detail().contains("Objetivo: 8.533 ms") || !m.detail().contains("NO confirma")) throw new AssertionError("honest ADPF diagnostics");
        s[23]=3;s[30]=-2;m.sample(s,202500,20250,2048,"unsupported");
        if(!m.compact().contains("ADPF no disponible")) throw new AssertionError("must not claim unavailable hints are active");
        s[23]=0;m.sample(s,203000,20250,2048,"off");
        if(!m.compact().contains("ADPF OFF")) throw new AssertionError("ADPF toggle state");
        if(!m.brief().startsWith("ACTIVO · ") || !m.brief().contains("OV ") || !m.brief().contains("XRuns ")) throw new AssertionError("one-line header summary");
        System.out.println("Diagnostic monitor: CPU scales, render, irregular sampling, resets, unsupported xruns and bounded log OK");
    }
}
