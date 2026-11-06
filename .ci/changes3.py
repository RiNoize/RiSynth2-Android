CHANGES = [
    ('android/test-host.sh', '3318d537e441e1e918f92e553650ce8444ca4a04', '77072253a7a6548bb01a05d413f401a3901dcd22', [
        (6, 7, r'''javac -encoding UTF-8 -d "$BUILD" "$JAVA/MidiByteParser.java" "$JAVA/PatchStore.java" "$JAVA/FavoriteStore.java" "$JAVA/FolderImporter.java" "$JAVA/DiagnosticMonitor.java" "$ROOT/android/tests/MidiParserTest.java" "$ROOT/android/tests/PatchStoreTest.java" "$ROOT/android/tests/FavoriteStoreTest.java" "$ROOT/android/tests/FolderImporterTest.java" "$ROOT/android/tests/DiagnosticMonitorTest.java"
'''),
        (9, 9, r'''java -cp "$BUILD" com.rinoize.risynth2.FavoriteStoreTest
'''),
    ]),
    ('android/test-upstream.sh', '9e20ce1a98701f86f70aae2c27121f872821562a', 'e834ae7f36f74227808ae11d7174cee310c411b6', [
        (14, 16, r'''# Only the unchanged desktop library failure is allowed. DSP changes are separately
# checked by test-engine.sh against the previous shipped engine, sample by sample.
git -C "$ROOT" diff --exit-code "$BASE" HEAD -- EngineCore/PatchLibrary.hpp EngineCore/Params.hpp EngineCore/Sy1Codec.hpp EngineCore/TextEncoding.hpp EngineCore/LegacyTables.hpp EngineCore/MeasuredTables.hpp EngineCoreTests
'''),
        (31, 32, r'''  printf '\n### Known upstream Linux library test failure\n\n52/53 upstream tests pass. `PatchManagerMoveRenamesAndSwaps` fails identically on the original `48322f7` baseline. The desktop library, its dependencies and EngineCoreTests are unchanged; DSP optimizations have a separate exact-audio regression. This desktop bank-move implementation is not used by the Android port. Any different failure remains fatal.\n' >> "$GITHUB_STEP_SUMMARY"
'''),
    ]),
    ('android/tests/FavoriteStoreTest.java', None, '19f6d87301d2e22114fbef36f02a26a0af100b04', [
        (0, 0, r'''package com.rinoize.risynth2;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class FavoriteStoreTest {
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static String patch(int i) { return "Pad "+i+"\nver=112\n0,1\n19,"+(i%128)+"\n"; }
    public static void main(String[] args) throws Exception {
        File base=Files.createTempDirectory("risynth2-favorites-").toFile();
        try {
            PatchStore store=new PatchStore(new File(base,"banks"),new File(base,"cache"));
            check(store.scan(patch(0)).size()==1,"empty favorites must not change INIT");
            store.importFile(new ByteArrayInputStream(patch(777).getBytes(StandardCharsets.UTF_8)),"original.sy1");
            PatchStore.Bank original=store.scan(patch(0)).get(1);
            for(int i=0;i<260;++i) {
                FavoriteStore.Saved saved=store.favorites.save(patch(i));
                check(!saved.alreadySaved && saved.slot==i%128,"pagination/slots");
            }
            check(store.favorites.save(patch(2)).alreadySaved,"identical repeated press must not duplicate");
            String edited=patch(2)+"20,95\n";
            check(!store.favorites.save(edited).alreadySaved,"edited same-name patch needs independent copy");
            String hostile="../../Fuera ★\nver=112\n0,1\n";
            store.favorites.save(hostile);
            List<PatchStore.Bank> all=store.scan(patch(0));
            check(all.size()==5 && FavoriteStore.isFavorite(all.get(1)),"favorites pinned, 3 pages, original retained");
            check(all.get(1).slots[2].readText().equals(patch(2)),"original snapshot retained");
            check(all.get(3).slots[4].readText().equals(edited),"edits preserved");
            check(all.get(3).slots[5].readText().equals(hostile),"unicode and path-like name preserved safely");
            check(original.slots[0].readText().equals(patch(777)),"source bank untouched");
            check(all.get(1).slots[0].isLazy(),"lazy favorites data");
            check(!new File(base,"Fuera ★").exists(),"no path injection");
            PatchStore reopened=new PatchStore(new File(base,"banks"),new File(base,"cache"));
            check(reopened.favorites.save(edited).alreadySaved,"dedup survives restart");
            File interrupted=new File(base,"banks/.favorites/.pending-interrupted");interrupted.mkdir();
            Files.writeString(new File(interrupted,"patch.sy1").toPath(),patch(999));
            check(reopened.favorites.scan().size()==3,"incomplete staging is never visible");
            try {reopened.favorites.save("invalid");throw new AssertionError("invalid accepted");}catch(IOException expected){}
            File blocked=new File(base,"blocked");Files.writeString(blocked.toPath(),"file");
            try {new FavoriteStore(blocked).save(patch(1));throw new AssertionError("blocked path accepted");}catch(IOException expected){}
            check(reopened.scan(patch(0)).size()==5,"failed save leaves library untouched");
            System.out.println("Favorites: 262 snapshots, repeat dedup, edits, restart, 128-slot pages, lazy reads, source safety and failed/partial writes OK");
        } finally {try(var paths=Files.walk(base.toPath())) {paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
    }
}
'''),
    ]),
    ('android/tests/engine_compare.cpp', None, '92f09d1e00aeba399d014d9acba1aea06bdffe6a', [
        (0, 0, r'''// Same source renders both namespaces. Baseline headers are prepared by test-engine.sh.
#include "baseline/Synth.hpp"
#include "EngineCore/Synth.hpp"
#include "EngineCore/InitPatch.hpp"
#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <stdexcept>
#include <vector>
using namespace oxo::s2;
struct Scenario { const char* name; PatchValues patch; int notes; };
std::vector<Scenario> scenarios() {
    std::vector<Scenario> out;
    auto base = kInitPatchValues;
    base[kPolyphony]=32; base[kFilterAmount]=96; base[kFilterAttack]=45;
    base[kAmpAttack]=35; base[kAmpRelease]=70;
    for(int filter=0;filter<5;++filter) {
        auto p=base; p[kFilterType]=filter;
        static const char* names[]={"LP12-15","LP24-15","HP12-15","BP12-15","LPDL-15"};
        out.push_back({names[filter],p,15});
    }
    auto pad=base; pad[kUnisonOn]=1; pad[kUnisonVoices]=3; pad[kUnisonDetune]=64; pad[kUnisonPan]=100;
    pad[kLfo1On]=1; pad[kLfo1Type]=5; pad[kLfo1Dest]=2; pad[kLfo1Depth]=48;
    pad[kChorusOn]=1; pad[kDelayOn]=1;
    out.push_back({"Pad-unison3-15",pad,5});
    out.push_back({"Pad-unison3-32",pad,12});
    auto detune=pad; detune[kOsc1Detune]=40; detune[kSubGain]=60;
    out.push_back({"Detune-sub-15",detune,5});
    auto fm=base; fm[kLfo1On]=1; fm[kLfo2On]=1; fm[kLfo1Dest]=2; fm[kLfo2Dest]=1;
    fm[kLfo1Depth]=64; fm[kLfo2Depth]=40; fm[kOsc1FM]=65; fm[kOsc2Sync]=1;
    fm[kModEnvOn]=1; fm[kModEnvDest]=0; fm[kModEnvAmount]=90;
    out.push_back({"Dual-pitch-FM-32",fm,32});
    return out;
}
template<class S> void start(S& s,const Scenario& c,int sr) {
    s.SetSampleRate(sr); s.SetPatch(c.patch); s.SetTransport(123,0,true);
    for(int k=0;k<c.notes;++k) s.NoteOn(36+k,80+k%40);
}
void equality() {
    uint64_t samples=0; auto cases=scenarios();
    // Every LFO destination/wave combination + automation/voice stealing/releases.
    for(int dest=1;dest<=7;++dest) for(int type=0;type<=5;++type) {
        auto p=kInitPatchValues; p[kPolyphony]=32; p[kLfo1On]=p[kLfo2On]=1;
        p[kLfo1Dest]=dest;p[kLfo2Dest]=8-dest;p[kLfo1Type]=type;p[kLfo2Type]=5-type;
        p[kLfo1Depth]=65;p[kLfo2Depth]=80;p[kModEnvOn]=1;p[kModEnvDest]=type%3;
        p[kOsc2Ring]=type%2;p[kOsc1Detune]=type%2?44:0;p[kSubGain]=type%2?35:0;
        p[kPortaTime]=63;p[kPlayMode]=type%3;p[kUnisonOn]=1;p[kUnisonVoices]=3;
        p[kArpOn]=(dest==7);p[kFilterType]=type%5;
        cases.push_back({"automation",p,5});
    }
    for(int osc1=0;osc1<4;++osc1) for(int osc2=1;osc2<=4;++osc2) {
        auto p=kInitPatchValues;p[kOsc1Shape]=osc1;p[kOsc2Shape]=osc2;
        p[kOsc1Detune]=50;p[kSubGain]=70;p[kSubShape]=osc1;p[kOsc2Ring]=osc2%2;
        p[kOsc2Sync]=1;p[kOsc1FM]=60;p[kFilterType]=(osc1+osc2)%5;p[kFilterRes]=100;
        p[kEffectOn]=1;p[kEffectType]=(osc1*4+osc2)%10;
        cases.push_back({"oscillator-effects",p,8});
    }
    for(int sr:{44100,48000}) for(const auto& c:cases) {
        reference::s2::Synth old; Synth now;start(old,c,sr);start(now,c,sr);
        std::array<float,2048> a{},b{},x{},y{};
        std::minstd_rand rng(435);
        for(int block=0;block<180;++block) {
            int n=std::array<int,7>{1,16,63,64,256,512,2048}[block%7];
            if(block==18) {old.ControlChange(64,127);now.ControlChange(64,127);}
            if(block==20) for(int k=0;k<c.notes;++k) {old.NoteOff(36+k);now.NoteOff(36+k);}
            if(block==30) {old.PitchBend(11000);now.PitchBend(11000);}
            if(block==35) {old.NoteOn(72,119);now.NoteOn(72,119);}
            if(block==40) {old.ControlChange(64,0);now.ControlChange(64,0);}
            if(block==50) {old.BeginPatchChange();now.BeginPatchChange();old.SetPatch(kInitPatchValues);now.SetPatch(kInitPatchValues);}
            if(block==70) {old.Reset();now.Reset();old.NoteOn(60,99);now.NoteOn(60,99);}
            if(block>=72) {int id=(block-72)%kParamCount;int v=kParams[id].min+int(rng()%unsigned(kParams[id].max-kParams[id].min+1));old.SetParam(id,v);now.SetParam(id,v);}
            if(block==160) {old.SetSampleRate(sr==44100?48000:44100);now.SetSampleRate(sr==44100?48000:44100);}
            old.BeginBlock(n);now.BeginBlock(n);old.Render(a.data(),b.data(),n);now.Render(x.data(),y.data(),n);
            for(int i=0;i<n;++i) {
                if(!std::isfinite(x[i])||!std::isfinite(y[i])||std::bit_cast<uint32_t>(a[i])!=std::bit_cast<uint32_t>(x[i])||std::bit_cast<uint32_t>(b[i])!=std::bit_cast<uint32_t>(y[i])) {
                    std::fprintf(stderr,"DIFFERENCE %s sr=%d block=%d sample=%d old=%a/%a new=%a/%a\n",c.name,sr,block,i,a[i],b[i],x[i],y[i]);std::exit(1);
                }
            }
            samples+=2*n;
        }
    }
    std::printf("Exact audio regression: %llu stereo-channel samples, %zu scenarios, 44.1/48 kHz, variable blocks, all-parameter automation and rate changes OK\n",(unsigned long long)samples,cases.size());
}
template<class S> double bench(const Scenario& c,int frames) {
    S s; start(s,c,48000);
    std::vector<float> l(frames),r(frames);
    for(int i=0;i<40;++i) {s.BeginBlock(frames);s.Render(l.data(),r.data(),frames);}
    const int blocks=48000*2/frames;
    auto t=std::chrono::steady_clock::now();
    for(int i=0;i<blocks;++i) {s.BeginBlock(frames);s.Render(l.data(),r.data(),frames);}
    return std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count();
}
int main(int argc,char**) {
    if(argc==1) {equality();return 0;}
    std::puts("Host benchmark: median of 5 alternating baseline/optimized trials; 48000 Hz, 512 frames; no device/audio driver.");
    for(const auto& c:scenarios()) {
        std::array<double,5>a,b;
        for(int i=0;i<5;++i) {
            if(i%2) {b[i]=bench<Synth>(c,512);a[i]=bench<reference::s2::Synth>(c,512);}
            else {a[i]=bench<reference::s2::Synth>(c,512);b[i]=bench<Synth>(c,512);}
        }
        std::sort(a.begin(),a.end());std::sort(b.begin(),b.end());
        std::printf("%-20s baseline %.6f s optimized %.6f s work reduction %+.1f%%\n",c.name,a[2],b[2],100*(1-b[2]/a[2]));
    }
}
'''),
    ]),
]
