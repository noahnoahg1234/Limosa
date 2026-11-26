// Limosa XR OpenXR runtime: stand-ins for the Android platform, for desktop tests only.
// Renders into an 800x400 pbuffer on Mesa's surfaceless EGL platform.
#ifdef LIMOSA_HOST_TEST
#include "runtime.h"

#include <cstdarg>
#include <cstdio>

namespace lx {

void log(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[runtime] ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
}

static std::mutex gWindowMutex;
std::mutex& platformWindowMutex() { return gWindowMutex; }
void* platformWindow() { return reinterpret_cast<void*>(1); }

EGLDisplay platformEglDisplay() {
    static EGLDisplay d = [] {
        auto get = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
        return get ? get(0x31DD /* EGL_PLATFORM_SURFACELESS_MESA */, EGL_DEFAULT_DISPLAY, nullptr) : eglGetDisplay(EGL_DEFAULT_DISPLAY);
    }();
    return d;
}
EGLint platformSurfaceType() { return EGL_PBUFFER_BIT; }
EGLSurface platformCreateSurface(EGLDisplay dpy, EGLConfig cfg, void*) {
    const EGLint attrs[] = {EGL_WIDTH, 800, EGL_HEIGHT, 400, EGL_NONE};
    return eglCreatePbufferSurface(dpy, cfg, attrs);
}

static std::vector<float> gHaptics;
void pushHaptic(int hand, float amplitude, float seconds) { gHaptics.insert(gHaptics.end(), {float(hand), amplitude, seconds}); }

void platformInit(JavaVM*, jobject) { gScreenW = 800; gScreenH = 400; }
void platformShutdown() {}

}  // namespace lx

extern "C" __attribute__((visibility("default"))) void limosa_test_set_input(const float* f, int n) { lx::inputFromFloats(f, n); }
extern "C" __attribute__((visibility("default"))) int limosa_test_haptic_count() { return int(lx::gHaptics.size() / 3); }
extern "C" __attribute__((visibility("default"))) EGLDisplay limosa_test_display() { return lx::platformEglDisplay(); }
#endif
