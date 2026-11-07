package com.rinoize.risynth2;

import java.util.ArrayDeque;
import java.util.Locale;

/** Arithmetic only: Android clocks/CPU readings are injected by the UI, testable on host.
 * CPU app is process CPU ms / monotonic wall ms: 100% = one fully occupied core.
 * Native stats 0..12 keep the original contract; 13..20 are callback counters.
 */
final class DiagnosticMonitor {
    static final int CALLBACKS=13, WALL_NS=14, FRAMES=15, OVERRUNS=16, INTERVAL_PEAK=17, CALLBACK_FRAMES=18, CAPACITY=19, GENERATION=20;
    static final int HINT_STATE=23, HINT_TID=24, HINT_TARGET=25, HINT_WORK=26, HINT_REPORTS=27,
        HINT_DROPPED=28, HINT_SKIPPED=29, HINT_ERROR=30, HINT_LAG=31;
    static String hintState(double[] s) {
        if (s.length <= HINT_LAG) return "n/d";
        switch ((int)s[HINT_STATE]) {
            case 0: return "OFF";
            case 1: return "esperando audio";
            case 2: return "sesión activa";
            case 3: return "no disponible";
            case 4: return "error";
            case 5: return "detenido";
            default: return "n/d";
        }
    }
    private double[] previous, baseline;
    private long previousWall, previousCpu, origin;
    private double cpu, cpuPeak, render, renderPeak, overrunRate, intervalSeconds;
    private long newXruns;
    private double voiceRender, effectsRender;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private String compact = "Preparando monitor…", detail = "", context = "";
    void reset(double[] s, long wallMs, long cpuMs) {
        previous = s.clone(); baseline = s.clone(); previousWall = origin = wallMs; previousCpu = cpuMs;
        cpu = cpuPeak = render = renderPeak = overrunRate = intervalSeconds = 0; newXruns = 0;
        voiceRender = effectsRender = 0;
        history.clear();
    }
    void sample(double[] s, long wallMs, long cpuMs, int requested, String extra) {
        if (s.length <= GENERATION) return;
        boolean newStream = previous == null || previous[GENERATION] != s[GENERATION];
        if (newStream) {
            reset(s, wallMs, cpuMs);
            // Keep counts from the first callback(s), before the first UI sample.
            baseline[4]=baseline[8]=baseline[9]=baseline[CALLBACKS]=baseline[WALL_NS]=baseline[FRAMES]=baseline[OVERRUNS]=0;
            history.add("--- Nueva sesión de audio " + (long)s[GENERATION] + " ---");
        }
        long dt = wallMs - previousWall;
        intervalSeconds = dt > 0 ? dt / 1000.0 : 0;
        if (dt > 0) {
            cpu = Math.max(0, (cpuMs - previousCpu) * 100.0 / dt);
            cpuPeak = Math.max(cpuPeak, cpu);
            double frames = s[FRAMES] - previous[FRAMES];
            render = frames > 0 ? Math.max(0, (s[WALL_NS] - previous[WALL_NS]) * s[1] / (frames * 1.0e7)) : 0;
            if (frames > 0 && s.length > 22 && previous.length > 22) {
                voiceRender = Math.max(0, s[21] - previous[21]) * s[1] / (frames * 1.0e7);
                effectsRender = Math.max(0, s[22] - previous[22]) * s[1] / (frames * 1.0e7);
            } else voiceRender = effectsRender = 0;
            overrunRate = Math.max(0, s[OVERRUNS] - previous[OVERRUNS]) / intervalSeconds;
            newXruns = s[4] < 0 || previous[4] < 0 ? 0 : (long)Math.max(0, s[4] - previous[4]);
        }
        renderPeak = Math.max(renderPeak, s[INTERVAL_PEAK]);
        long ov = delta(s, OVERRUNS), blocks = delta(s, CALLBACKS);
        double ovPercent = blocks > 0 ? ov * 100.0 / blocks : 0;
        String xruns = s[4] < 0 ? "n/d" : Long.toString(delta(s, 4));
        compact = String.format(Locale.US,
            "CPU app %.1f%% · Render %.1f%% · pico %.1f%% · voces %.0f\nOV %d · %.1f/s · XRuns %s (+%d) · clip %d\n%s · %.0f Hz · buffer %.0f fr (pedido %d) · tocar para detalles",
            cpu, render, renderPeak, s[7], ov, overrunRate, xruns, newXruns, delta(s,8), s[0]>0?"ACTIVO":"DETENIDO", s[1], s[3], requested);
        if (s.length > HINT_LAG) compact += " · ADPF " + hintState(s);
        context = extra;
        detail = String.format(Locale.US,
            "CPU app: %.1f%% · pico %.1f%%\nRender medio: %.1f%% · pico intervalo %.1f%% · máximo %.1f%%\nOverruns (OV): %d · %.2f/s · %.2f%% de %d callbacks\nXRuns / underruns AAudio: %s · nuevos +%d\nVoces activas: %.0f · muestras recortadas: %d · cola MIDI perdida: %d\n\nAudio %s · %.0f Hz\nBuffer solicitado: %d fr · real: %.0f fr · capacidad: %.0f fr\nBurst: %.0f fr · último callback: %.0f fr (presupuesto %.2f ms)\nBuffer / frecuencia: %.2f ms (NO es latencia tecla–audio)\nSalida ID %.0f · %s · error %.0f\nVentana real: %.3f s · desde reinicio del monitor: %.1f s\n\n%s\n\nCPU app: 100%% = un núcleo; puede superar 100%%.\nRender: tiempo de pared del callback / tiempo de audio producido.\nOV: callback tardó más que su presupuesto; no equivale a un corte.\nXRuns: contador del controlador de audio; puede no reflejar todos los cortes audibles.\nCambiar/reiniciar audio abre una nueva sesión. No mide latencia real.",
            cpu, cpuPeak, render, s[INTERVAL_PEAK], renderPeak, ov, overrunRate, ovPercent, blocks,
            xruns, newXruns, s[7], delta(s,8), delta(s,9), s[0]>0?"ACTIVO":"DETENIDO", s[1], requested, s[3], s[CAPACITY], s[2], s[CALLBACK_FRAMES],
            s[1]>0?s[CALLBACK_FRAMES]*1000/s[1]:0, s[1]>0?s[3]*1000/s[1]:0, s[10], s[12]==12?"LOW LATENCY":"NORMAL", s[11], intervalSeconds, (wallMs-origin)/1000.0, extra);
        if (s.length > 22) detail += String.format(Locale.US,
            "\n\nDesglose de Render: Voces/LFO %.1f%% · Efectos/salida %.1f%%\nSon tiempos de pared sobre tiempo de audio, no CPU por componente. El resto incluye MIDI, mezcla de salida y diagnóstico.",
            voiceRender, effectsRender);
        if (s.length > HINT_LAG) detail += String.format(Locale.US,
            "\n\nADPF: %s · TID de audio %.0f\nObjetivo: %.3f ms (80%% del callback real) · último trabajo: %.3f ms\nInformes aceptados por API: %.0f · cola llena: %.0f · omitidos/antiguos: %.0f · error: %.0f\nEdad en cola antes del envío: %.3f ms\nContadores ADPF desde inicio del audio. Sesión activa NO confirma un núcleo rápido ni una mejora. OFF no usa hilo auxiliar. La API corre fuera del callback; puede fallar sin detener el audio.",
            hintState(s),s[HINT_TID],s[HINT_TARGET]/1e6,s[HINT_WORK]/1e6,s[HINT_REPORTS],s[HINT_DROPPED],s[HINT_SKIPPED],s[HINT_ERROR],s[HINT_LAG]/1e6);
        history.add(String.format(Locale.US,
            "t=%.3fs dt=%.3fs active=%.0f cpu=%.1f%% render=%.1f%% peak=%.1f%% ov=%d ov/s=%.2f xruns=%s dx=%d voices=%.0f clip=%d queue=%d buffer=%.0f cb=%.0f sr=%.0f",
            (wallMs-origin)/1000.0, intervalSeconds, s[0], cpu, render, s[INTERVAL_PEAK], ov, overrunRate, xruns, newXruns, s[7], delta(s,8), delta(s,9), s[3], s[CALLBACK_FRAMES], s[1])
            + (s.length > 22 ? String.format(Locale.US," voiceRender=%.1f%% fxRender=%.1f%%",voiceRender,effectsRender) : "")
            + (s.length > HINT_LAG ? String.format(Locale.US," adpf=%.0f tid=%.0f targetNs=%.0f hintReports=%.0f hintDrop=%.0f hintSkip=%.0f hintError=%.0f queueAgeNs=%.0f",
                s[HINT_STATE],s[HINT_TID],s[HINT_TARGET],s[HINT_REPORTS],s[HINT_DROPPED],s[HINT_SKIPPED],s[HINT_ERROR],s[HINT_LAG]) : ""));
        while (history.size()>120) history.removeFirst();
        previous=s.clone(); previousWall=wallMs; previousCpu=cpuMs;
    }
    private long delta(double[] s,int index) { return (long)Math.max(0,s[index]-baseline[index]); }
    String compact() { return compact; }
    String detail() { return detail; }
    String report(String device) {
        return device + "\n" + detail + "\n\nÚltimas 120 muestras (intervalos reales):\n" + String.join("\n",history);
    }
    double cpuPercent() { return cpu; }
    double renderPercent() { return render; }
    double overrunRate() { return overrunRate; }
}
