#include "EngineCore/Synth.hpp"
#include "EngineCore/InitPatch.hpp"
#include "android/app/src/main/cpp/EventQueue.hpp"
#include <array>
#include <cassert>
#include <cmath>
#include <iostream>
#include <thread>
int main() {
    using namespace oxo::s2;
    const auto original = InitPatch();
    const auto decoded = ParseSy1(WriteSy1(original));
    assert(original.values == decoded.values);
    for (const double rate : {44100.0, 48000.0}) {
        Synth s; s.SetSampleRate(rate); s.SetPatch(original.values); s.SetVolume(32);
        s.NoteOn(60,100); s.NoteOn(64,100); s.NoteOn(67,100);
        std::array<float,512> l{}, r{};
        double energy = 0;
        for (int block=0; block<100; ++block) {
            s.BeginBlock(512); s.Render(l.data(),r.data(),512);
            for (int i=0;i<512;++i) { assert(std::isfinite(l[i]) && std::isfinite(r[i])); energy += l[i]*l[i] + r[i]*r[i]; }
        }
        assert(energy > 0.001);
        s.ControlChange(64,127); s.NoteOff(60); s.NoteOff(64); s.NoteOff(67);
        s.Panic(); assert(s.GatedVoices() == 0);
        std::cout << "Engine smoke: " << rate << " Hz finite stereo, chord, sustain, panic OK\n";
    }
    EventQueue<int,4> tiny;
    assert(tiny.push(1) && tiny.push(2) && tiny.push(3) && !tiny.push(4));
    int x=0; assert(tiny.pop(x) && x==1); assert(tiny.push(4));
    assert(tiny.pop(x) && x==2); assert(tiny.pop(x) && x==3); assert(tiny.pop(x) && x==4); assert(!tiny.pop(x));
    EventQueue<int,128> queue;
    constexpr int count=10000;
    std::thread a([&]{for(int i=0;i<count;++i) while(!queue.push(1)) std::this_thread::yield();});
    std::thread b([&]{for(int i=0;i<count;++i) while(!queue.push(2)) std::this_thread::yield();});
    int seen=0,sum=0;
    while(seen < count*2) if(queue.pop(x)) { sum+=x; ++seen; } else std::this_thread::yield();
    a.join(); b.join(); assert(sum==count*3);
    std::cout << "Event queue: capacity, wraparound, 20000 multi-producer events OK\n";
}
