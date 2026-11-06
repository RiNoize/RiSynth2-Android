#pragma once
#include <algorithm>
#include <atomic>
#include <cstdint>

// One audio producer, one diagnostics consumer. Bounded, lock-free and no I/O.
// An overrun is callback work taking longer than frames/sampleRate. It is NOT
// an AAudio xrun: buffering can absorb work overruns, and scheduling can cause
// xruns even when the callback itself is inexpensive.
struct RenderMetrics {
    static_assert(std::atomic<uint64_t>::is_always_lock_free);
    static_assert(std::atomic<double>::is_always_lock_free);
    std::atomic<uint64_t> callbacks{0}, wallNanos{0}, framesTotal{0}, overruns{0};
    std::atomic<int> lastFrames{0};
    std::atomic<double> intervalPeak{0};
    // Called ONLY with the audio stream stopped.
    void clear() {
        callbacks.store(0); wallNanos.store(0); framesTotal.store(0);
        overruns.store(0); lastFrames.store(0); intervalPeak.store(0);
    }
    void record(uint64_t nanos, int frames, double sampleRate) {
        if (frames <= 0 || sampleRate <= 0) return;
        const double percent = double(nanos) * sampleRate / (double(frames) * 1.0e7);
        wallNanos.fetch_add(nanos, std::memory_order_relaxed);
        framesTotal.fetch_add(uint64_t(frames), std::memory_order_relaxed);
        if (percent > 100.0) overruns.fetch_add(1, std::memory_order_relaxed);
        lastFrames.store(frames, std::memory_order_relaxed);
        // Never store an old peak back after the UI exchanges it for zero.
        // Boundary samples may belong to either adjacent sampling interval.
        if (percent > intervalPeak.load(std::memory_order_relaxed))
            intervalPeak.store(percent, std::memory_order_relaxed);
        callbacks.fetch_add(1, std::memory_order_release);
    }
};
