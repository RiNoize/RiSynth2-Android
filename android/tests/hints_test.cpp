#include "android/app/src/main/cpp/AudioPerformanceHints.hpp"
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <thread>
#include <vector>
using namespace risynth2;

struct FakeBackend final : HintBackend {
    bool available = true, canOpen = true, live = false;
    int updateError = 0, reportError = 0;
    int opens = 0, closes = 0, updates = 0, reports = 0, tid = 0;
    int64_t target = 0, actual = 0;
    std::thread::id caller;
    void sameThread() {
        if (caller == std::thread::id{}) caller = std::this_thread::get_id();
        assert(caller == std::this_thread::get_id());
    }
    bool initialize() override { sameThread(); return available; }
    bool open(int32_t t, int64_t n) override {
        sameThread(); assert(!live); ++opens; tid = t; target = n;
        return live = canOpen;
    }
    int updateTarget(int64_t n) override { sameThread(); assert(live); ++updates; target=n; return updateError; }
    int report(int64_t n) override { sameThread(); assert(live); ++reports; actual=n; return reportError; }
    void close() override { sameThread(); assert(live); live=false; ++closes; }
};
HintSample sample(int tid=123, int64_t done=1000000000, int64_t budget=10000000, int64_t work=6000000) {
    return {tid,budget,work,done};
}
void controllerTests() {
    FakeBackend b; HintStats stats; stats.reset(true);
    {
        HintSessionController c(b,stats); assert(c.initialize());
        c.process(sample(),1001000000);
        assert(b.opens==1 && b.tid==123 && b.target==8000000 && b.actual==6000000);
        assert(stats.state==HintActive && stats.reports==1 && stats.lagNs==1000000);
        c.process(sample(123,1010000000),1011000000); assert(b.opens==1 && b.updates==0 && b.reports==2);
        c.process(sample(123,1020000000,20000000,7000000),1021000000);
        assert(b.updates==1 && b.target==16000000 && b.actual==7000000);
        c.process(sample(456,1040000000),1041000000);
        assert(b.closes==1 && b.opens==2 && b.tid==456);
        c.process(sample(456,1040000000),1200000000); // stale: never report a backlog as current work
        c.process(sample(0,1200000000),1201000000);
        c.process(sample(456,1200000000,0),1201000000);
        c.process(sample(456,1200000000,10000000,0),1201000000);
        assert(stats.skipped==4 && b.reports==4);
        c.idle(1400000000); assert(b.closes==2 && stats.state==HintWaiting);
        c.process(sample(789,1410000000),1411000000); assert(b.opens==3 && b.tid==789);
        b.reportError=EPIPE;c.process(sample(789,1420000000),1421000000);
        assert(c.failed() && !b.live && stats.state==HintError && stats.error==EPIPE);
        c.process(sample(789,1430000000),1431000000); assert(b.opens==3);
        c.finish(); assert(stats.state==HintError && b.closes==3);
    }
    for (int mode=0;mode<3;++mode) {
        FakeBackend f; HintStats st; st.reset(true); HintSessionController c(f,st);
        if(mode==0) {f.available=false;assert(!c.initialize());assert(st.state==HintUnavailable && st.error==-1);}
        else {
            assert(c.initialize());
            if(mode==1) {f.canOpen=false;c.process(sample(),1001000000);assert(st.state==HintUnavailable && st.error==-2 && f.closes==0);}
            else {c.process(sample(),1001000000);f.updateError=EINVAL;c.process(sample(123,1010000000,5000000),1011000000);assert(st.error==EINVAL && f.closes==1);}
        }
    }
}
void queueTests() {
    HintQueue<4> q;
    assert(q.push(sample(1)));assert(q.push(sample(2)));assert(q.push(sample(3)));assert(!q.push(sample(4)));
    HintSample s;assert(q.pop(s)&&s.tid==1);assert(q.push(sample(4)));
    for(int id=2;id<=4;++id) {assert(q.pop(s)&&s.tid==id);}assert(!q.pop(s));
    HintQueue<64> threaded;
    std::thread producer([&] {for(int i=1;i<=50000;++i) while(!threaded.push(sample(i))) std::this_thread::yield();});
    for(int i=1;i<=50000;++i) {while(!threaded.pop(s)) std::this_thread::yield();assert(s.tid==i);}
    producer.join();
}
void workerTests() {
    for(int cycle=0;cycle<30;++cycle) {
        FakeBackend b; AudioPerformanceHints h(b);
        h.start(false);assert(!h.accepting() && h.stats.state==HintOff);h.stop();assert(b.opens==0);
        h.start(true);
        for(int i=0;i<1000 && !h.accepting();++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        assert(h.accepting());
        h.submit(sample(444,AudioPerformanceHints::monotonicNs()));
        for(int i=0;i<1000 && h.stats.reports==0;++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        assert(h.stats.reports>=1 && h.stats.tid==444);
        h.requestStop();h.stop();
        assert(!b.live && b.closes==1 && b.caller!=std::this_thread::get_id());
        assert(h.stats.state==HintStopped && !h.accepting());
        b.caller={};h.start(true);h.requestStop();h.stop(); // stop during initialization
        assert(!h.accepting() && !b.live);
    }
    FakeBackend unavailable;unavailable.available=false;AudioPerformanceHints h(unavailable);h.start(true);
    for(int i=0;i<1000 && h.stats.state==HintWaiting;++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    assert(h.stats.state==HintUnavailable && !h.accepting());h.stop();
}
struct SlowBackend final : HintBackend {
    std::atomic<bool> entered{false}, release{false};
    bool initialize() override { return true; }
    bool open(int32_t,int64_t) override { return true; }
    int updateTarget(int64_t) override { return 0; }
    int report(int64_t) override {
        entered.store(true);
        while(!release.load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        return 0;
    }
    void close() override {}
};
void slowBackendTest() {
    SlowBackend backend; AudioPerformanceHints h(backend);h.start(true);
    for(int i=0;i<1000 && !h.accepting();++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    assert(h.accepting());h.submit(sample(777,AudioPerformanceHints::monotonicNs()));
    for(int i=0;i<1000 && !backend.entered.load();++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    assert(backend.entered.load());
    // Simulate a stalled Android service. The audio producer must complete
    // without releasing that service, even when its fixed queue fills.
    for(int i=0;i<2000;++i) h.submit(sample(777,AudioPerformanceHints::monotonicNs()));
    assert(h.stats.dropped>0);backend.release.store(true);h.stop();
    assert(!h.accepting());
}
int main() {
    controllerTests();queueTests();workerTests();slowBackendTest();
    std::puts("ADPF: callback TID, actual work/target, changed callback sizes, stale/overflow queues, unavailable/errors, lifecycle and single-worker API ownership OK");
}
