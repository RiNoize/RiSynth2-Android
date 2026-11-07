#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <thread>

namespace risynth2 {

// Only this POD crosses from the AAudio callback to the reporting thread.
// Work duration excludes time sleeping/waiting between callbacks and delivery lag.
struct HintSample {
    int32_t tid = 0;
    int64_t budgetNs = 0, workNs = 0, completedNs = 0;
};

template<size_t N> class HintQueue {
    static_assert(N > 1);
    static_assert(std::atomic<size_t>::is_always_lock_free);
    std::array<HintSample, N> samples_{};
    alignas(64) std::atomic<size_t> read_{0};
    alignas(64) std::atomic<size_t> write_{0};
public:
    // One producer (serialized audio callbacks), one consumer (hint worker).
    bool push(const HintSample& value) noexcept {
        const size_t w = write_.load(std::memory_order_relaxed), next = (w + 1) % N;
        if (next == read_.load(std::memory_order_acquire)) return false;
        samples_[w] = value;
        write_.store(next, std::memory_order_release);
        return true;
    }
    bool pop(HintSample& value) noexcept {
        const size_t r = read_.load(std::memory_order_relaxed);
        if (r == write_.load(std::memory_order_acquire)) return false;
        value = samples_[r];
        read_.store((r + 1) % N, std::memory_order_release);
        return true;
    }
    void clearStopped() noexcept { read_.store(0); write_.store(0); }
};

enum HintState { HintOff = 0, HintWaiting = 1, HintActive = 2,
                 HintUnavailable = 3, HintError = 4, HintStopped = 5 };
struct HintStats {
    static_assert(std::atomic<int64_t>::is_always_lock_free);
    std::atomic<int> state{HintOff}, error{0}, tid{0};
    std::atomic<int64_t> targetNs{0}, workNs{0}, lagNs{0};
    std::atomic<uint64_t> reports{0}, dropped{0}, skipped{0};
    void reset(bool enabled) {
        state.store(enabled ? HintWaiting : HintOff); error.store(0); tid.store(0);
        targetNs.store(0); workNs.store(0); lagNs.store(0);
        reports.store(0); dropped.store(0); skipped.store(0);
    }
};

// Android calls are isolated here so failure, timing and lifecycle can be tested
// without an Android service. Every method is called ONLY by one hint worker.
class HintBackend {
public:
    virtual ~HintBackend() = default;
    virtual bool initialize() = 0;
    virtual bool open(int32_t callbackTid, int64_t targetNs) = 0;
    virtual int updateTarget(int64_t targetNs) = 0;
    virtual int report(int64_t actualNs) = 0;
    virtual void close() = 0;
};

class HintSessionController {
    HintBackend& backend_;
    HintStats& stats_;
    bool opened_ = false, failed_ = false;
    int32_t tid_ = 0;
    int64_t target_ = 0, lastSample_ = 0;
    void close() {
        if (opened_) backend_.close();
        opened_ = false; tid_ = 0; target_ = 0;
        stats_.tid.store(0); stats_.targetNs.store(0);
    }
    void fail(int code, int state = HintError) {
        close(); failed_ = true;
        stats_.error.store(code); stats_.state.store(state);
    }
public:
    HintSessionController(HintBackend& backend, HintStats& stats) : backend_(backend), stats_(stats) {}
    ~HintSessionController() { close(); }
    bool initialize() {
        if (!backend_.initialize()) { fail(-1, HintUnavailable); return false; }
        return true;
    }
    bool failed() const { return failed_; }
    // Leave 20% of the ACTUAL callback's audio duration as scheduling headroom.
    // This is a declared target, not an inflated measured duration or buffer size.
    static int64_t targetFor(int64_t budgetNs) { return std::max<int64_t>(1, budgetNs * 4 / 5); }
    void process(const HintSample& sample, int64_t nowNs) {
        if (failed_) return;
        if (sample.tid <= 0 || sample.workNs <= 0 || sample.budgetNs <= 0 || sample.budgetNs > 1000000000LL ||
            sample.completedNs <= 0 || nowNs < sample.completedNs ||
            nowNs - sample.completedNs > std::max<int64_t>(20000000, sample.budgetNs * 2)) {
            stats_.skipped.fetch_add(1); return;
        }
        lastSample_ = sample.completedNs;
        const int64_t target = targetFor(sample.budgetNs);
        if (opened_ && sample.tid != tid_) close(); // AAudio replaced its callback thread.
        if (!opened_) {
            if (!backend_.open(sample.tid, target)) { fail(-2, HintUnavailable); return; }
            opened_ = true; tid_ = sample.tid; target_ = target;
            stats_.tid.store(tid_); stats_.targetNs.store(target_);
        } else if (target != target_) {
            const int result = backend_.updateTarget(target);
            if (result != 0) { fail(result); return; }
            target_ = target; stats_.targetNs.store(target_);
        }
        const int result = backend_.report(sample.workNs);
        if (result != 0) { fail(result); return; }
        stats_.workNs.store(sample.workNs); stats_.lagNs.store(nowNs - sample.completedNs);
        stats_.reports.fetch_add(1); stats_.state.store(HintActive);
    }
    void idle(int64_t nowNs) {
        // No session is kept alive indefinitely if callbacks stop unexpectedly.
        if (opened_ && nowNs - lastSample_ > 250000000) {
            close(); stats_.state.store(HintWaiting);
        }
    }
    void finish() {
        close();
        if (!failed_) stats_.state.store(HintStopped);
    }
};

class AudioPerformanceHints {
    HintBackend& backend_;
    HintQueue<64> queue_;
    std::atomic<bool> accepting_{false}, stopRequested_{false};
    std::thread worker_;
    void run() noexcept {
        try {
            HintSessionController session(backend_, stats);
            if (!session.initialize()) return;
            if (stopRequested_.load(std::memory_order_acquire)) { session.finish(); return; }
            accepting_.store(true, std::memory_order_release);
            while (!stopRequested_.load(std::memory_order_acquire)) {
                HintSample current, latest;
                unsigned count = 0;
                while (count < 63 && queue_.pop(current)) { latest = current; ++count; }
                if (count) {
                    // Do not flood Binder with a burst of obsolete reports after
                    // the worker is delayed. Normal operation reports each callback.
                    if (count > 1) stats.skipped.fetch_add(count - 1);
                    session.process(latest, monotonicNs());
                    if (session.failed()) break;
                }
                session.idle(monotonicNs());
                // Only this non-real-time worker sleeps; the callback never wakes
                // or waits for it. ~1 ms polling delay is exposed as report lag.
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
            accepting_.store(false, std::memory_order_release);
            session.finish();
        } catch (...) {
            accepting_.store(false, std::memory_order_release);
            stats.error.store(-4); stats.state.store(HintError);
        }
    }
public:
    HintStats stats;
    explicit AudioPerformanceHints(HintBackend& backend) : backend_(backend) {}
    ~AudioPerformanceHints() { stop(); }
    static int64_t monotonicNs() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    // Control-thread only. Start is called with the AAudio stream stopped.
    void start(bool enabled) noexcept {
        stop(); queue_.clearStopped(); stats.reset(enabled);
        stopRequested_.store(false);
        if (!enabled) return;
        try { worker_ = std::thread([this] { run(); }); }
        catch (...) { stats.error.store(-3); stats.state.store(HintError); }
    }
    bool accepting() const noexcept { return accepting_.load(std::memory_order_acquire); }
    void submit(const HintSample& sample) noexcept {
        if (accepting() && !queue_.push(sample)) stats.dropped.fetch_add(1, std::memory_order_relaxed);
    }
    // Safe in the AAudio error callback: stores only, no joining/closing there.
    void requestStop() noexcept {
        accepting_.store(false, std::memory_order_release);
        stopRequested_.store(true, std::memory_order_release);
    }
    // Control-thread only, after AAudio callbacks stop. Any service delay is
    // confined to this worker/join, never to a running audio callback.
    void stop() noexcept {
        requestStop();
        if (worker_.joinable()) worker_.join();
    }
};
} // namespace risynth2
