#include "android/app/src/main/cpp/RenderMetrics.hpp"
#include <cassert>
#include <iostream>
int main() {
    RenderMetrics m;
    m.record(1000000,480,48000); // 10% of a 10 ms budget
    assert(m.callbacks.load()==1 && m.overruns.load()==0);
    m.record(10000000,480,48000); // equal budget is not strictly over
    assert(m.overruns.load()==0);
    m.record(11000000,480,48000); // 110%
    assert(m.overruns.load()==1 && m.intervalPeak.exchange(0)==110.0);
    m.record(5000000,480,48000);
    assert(m.intervalPeak.load()==50.0 && m.framesTotal.load()==1920);
    m.record(123,0,48000); assert(m.callbacks.load()==4);
    m.clear(); assert(m.callbacks.load()==0 && m.wallNanos.load()==0 && m.intervalPeak.load()==0);
    m.record(6000000,256,48000); assert(m.overruns.load()==1); // use callback frames, NOT output buffer size
    std::cout << "Render metrics: budget, strict overruns, peaks, reset and variable callback size OK\n";
}
