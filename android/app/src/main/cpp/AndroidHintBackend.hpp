#pragma once
#include <android/performance_hint.h>
#include "AudioPerformanceHints.hpp"

namespace risynth2 {
// Android 13 / API 33 is already the app minimum. No Oboe dependency,
// thread affinity, scheduler override, hidden buffer growth or device whitelist.
class AndroidHintBackend final : public HintBackend {
    APerformanceHintManager* manager_ = nullptr;
    APerformanceHintSession* session_ = nullptr;
public:
    bool initialize() override {
        manager_ = APerformanceHint_getManager();
        return manager_ != nullptr;
    }
    bool open(int32_t callbackTid, int64_t targetNs) override {
        session_ = APerformanceHint_createSession(manager_, &callbackTid, 1, targetNs);
        return session_ != nullptr;
    }
    int updateTarget(int64_t targetNs) override {
        return APerformanceHint_updateTargetWorkDuration(session_, targetNs);
    }
    int report(int64_t actualNs) override {
        return APerformanceHint_reportActualWorkDuration(session_, actualNs);
    }
    void close() override {
        if (session_) APerformanceHint_closeSession(session_);
        session_ = nullptr;
    }
};
} // namespace risynth2
