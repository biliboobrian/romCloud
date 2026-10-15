#include "perfhint.h"

#include <dlfcn.h>
#include <unistd.h>

#include "log.h"

namespace libretrodroid {

namespace {

typedef void* (*GetManager)();
typedef void* (*CreateSession)(void* manager, const int32_t* tids, size_t size, int64_t targetNanos);
typedef int (*UpdateTarget)(void* session, int64_t targetNanos);
typedef int (*ReportActual)(void* session, int64_t actualNanos);
typedef void (*CloseSession)(void* session);

struct Api {
    GetManager getManager = nullptr;
    CreateSession createSession = nullptr;
    UpdateTarget updateTarget = nullptr;
    ReportActual reportActual = nullptr;
    CloseSession closeSession = nullptr;
};

const Api* api() {
    static Api loaded = [] {
        Api a;
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return a;
        a.getManager = reinterpret_cast<GetManager>(dlsym(lib, "APerformanceHint_getManager"));
        a.createSession = reinterpret_cast<CreateSession>(dlsym(lib, "APerformanceHint_createSession"));
        a.updateTarget = reinterpret_cast<UpdateTarget>(dlsym(lib, "APerformanceHint_updateTargetWorkDuration"));
        a.reportActual = reinterpret_cast<ReportActual>(dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
        a.closeSession = reinterpret_cast<CloseSession>(dlsym(lib, "APerformanceHint_closeSession"));
        return a;
    }();
    const bool complete = loaded.getManager && loaded.createSession && loaded.updateTarget && loaded.reportActual && loaded.closeSession;
    return complete ? &loaded : nullptr;
}

}  // namespace

void PerformanceHint::begin(double frameRate) {
    started = std::chrono::steady_clock::now();
    if (unavailable || frameRate <= 0) return;
    const int64_t target = (int64_t) (1e9 / frameRate);
    if (!session) {
        const Api* a = api();
        void* manager = a ? a->getManager() : nullptr;
        const int32_t tid = gettid();
        session = manager ? a->createSession(manager, &tid, 1, target) : nullptr;
        if (!session) {
            unavailable = true;
            LOGI("Performance hint unavailable");
            return;
        }
        targetNanos = target;
        LOGI("Performance hint session created (target %lld ns)", (long long) target);
    } else if (target != targetNanos) {
        api()->updateTarget(session, target);
        targetNanos = target;
    }
}

void PerformanceHint::end() {
    if (!session) return;
    const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - started).count();
    if (elapsed > 0) api()->reportActual(session, (int64_t) elapsed);
}

void PerformanceHint::close() {
    if (session) api()->closeSession(session);
    session = nullptr;
    unavailable = false;
}

}
