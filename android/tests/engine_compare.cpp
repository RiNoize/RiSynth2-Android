// Same source renders both namespaces. Baseline headers are prepared by test-engine.sh.
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
struct Scenario { const char* name; PatchValues patch; int notes; int baseNote = 36; };
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
    auto pan = pad; pan[kLfo1Dest]=7; pan[kLfo2On]=1; pan[kLfo2Dest]=7;
    pan[kLfo2Depth]=90; pan[kLfo2Type]=4;
    out.push_back({"Stereo-LFO-15",pan,5});
    out.push_back({"Stereo-LFO-32",pan,12});
    auto pulse=detune; pulse[kOsc1Shape]=2; pulse[kOsc2Shape]=2;
    pulse[kLfo1Dest]=5; pulse[kLfo2On]=1; pulse[kLfo2Dest]=4; pulse[kLfo2Depth]=83;
    out.push_back({"Pulse-detune-32",pulse,12});
    auto saw=detune; saw[kOsc1Shape]=1; saw[kOsc2Shape]=1;
    out.push_back({"Saw-detune-32",saw,12});
    auto sine=detune; sine[kOsc1Shape]=0; sine[kSubShape]=0;
    out.push_back({"Sine-detune-15",sine,5});
    auto diode=detune; diode[kFilterType]=4; diode[kFilterRes]=110;
    out.push_back({"LPDL-unison-32",diode,12});
    auto plain=base; plain[kLfo1On]=plain[kLfo2On]=0;
    plain[kFilterRes]=100; plain[kOsc1Shape]=2; plain[kOsc2Shape]=2;
    out.push_back({"Single-voice",plain,1});
    out.push_back({"Bass-table-15",plain,15,0});
    out.push_back({"High-table-15",plain,15,108});
    return out;
}
template<class S> void start(S& s,const Scenario& c,int sr) {
    s.SetSampleRate(sr); s.SetPatch(c.patch); s.SetTransport(123,0,true);
    for(int k=0;k<c.notes;++k) s.NoteOn(c.baseNote+k,80+k%40);
}
void waveEquality() {
    uint64_t reads=0;
    for (int sr : {44100,48000}) {
        auto old=reference::s2::WaveTableSet::ForSampleRate(sr);
        auto now=WaveTableSet::ForSampleRate(sr);
        std::minstd_rand rng(771);
        for(int note=0;note<128;++note) for(int shape=0;shape<2;++shape) {
            const auto& a=shape?old->Triangle(note):old->Saw(note);
            const auto& b=shape?now->Triangle(note):now->Saw(note);
            for(int n=0;n<4096;++n) {
                const double phase=n==0?0:n==1?std::nextafter(1.0,0.0):double(rng())/2147483647.0;
                const float x=a.Read(phase),y=b.Read(phase);
                if(std::bit_cast<uint32_t>(x)!=std::bit_cast<uint32_t>(y)) {
                    std::fprintf(stderr,"WAVETABLE DIFFERENCE sr=%d note=%d shape=%d phase=%a old=%a new=%a\n",sr,note,shape,phase,x,y);
                    std::exit(1);
                }
                ++reads;
            }
        }
    }
    std::printf("Exact wavetable regression: %llu reads, all 128 notes, saw/triangle, wrap edges and random phases OK\n",(unsigned long long)reads);
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
            if(block==20) for(int k=0;k<c.notes;++k) {old.NoteOff(c.baseNote+k);now.NoteOff(c.baseNote+k);}
            if(block==30) {old.PitchBend(11000);now.PitchBend(11000);}
            if(block==35) {old.NoteOn(72,119);now.NoteOn(72,119);}
            if(block==40) {old.ControlChange(64,0);now.ControlChange(64,0);}
            if(block==50) {old.BeginPatchChange();now.BeginPatchChange();old.SetPatch(kInitPatchValues);now.SetPatch(kInitPatchValues);}
            if(block==70) {old.Reset();now.Reset();old.NoteOn(60,99);now.NoteOn(60,99);}
            if(block>=72) {int id=(block-72)%kParamCount;int v=kParams[id].min+int(rng()%unsigned(kParams[id].max-kParams[id].min+1));old.SetParam(id,v);now.SetParam(id,v);}
            if(block==160) {old.SetSampleRate(sr==44100?48000:44100);now.SetSampleRate(sr==44100?48000:44100);}
            old.BeginBlock(n);now.BeginBlock(n);old.Render(a.data(),b.data(),n);
            RenderTimings timing;
            now.Render(x.data(),y.data(),n,block%2 ? &timing : nullptr);
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
    if(argc==1) {waveEquality();equality();return 0;}
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
