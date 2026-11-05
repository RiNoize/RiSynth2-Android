#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <mutex>

// Multiple non-audio producers; single audio consumer. The consumer never locks,
// allocates, waits, or calls Java. Queue overflow must be handled by the caller.
template<class T, size_t Capacity> class EventQueue {
    static_assert(Capacity > 1);
    static_assert(std::atomic<size_t>::is_always_lock_free);
    std::array<T, Capacity> data_{};
    alignas(64) std::atomic<size_t> read_{0};
    alignas(64) std::atomic<size_t> write_{0};
    std::mutex producers_;
public:
    bool push(const T& event) {
        std::lock_guard<std::mutex> lock(producers_);
        const size_t w = write_.load(std::memory_order_relaxed);
        const size_t next = (w + 1) % Capacity;
        if (next == read_.load(std::memory_order_acquire)) return false;
        data_[w] = event;
        write_.store(next, std::memory_order_release);
        return true;
    }
    bool pop(T& event) {
        const size_t r = read_.load(std::memory_order_relaxed);
        if (r == write_.load(std::memory_order_acquire)) return false;
        event = data_[r];
        read_.store((r + 1) % Capacity, std::memory_order_release);
        return true;
    }
};
