// Limosa XR OpenXR runtime: Android glue inside the game's process.
//
// The runtime's Java half (com.limosa.xr.runtime.RuntimeUi) lives in the Limosa XR APK. We load
// it into the game's process through a package context, register our native methods on it and
// start it: it adds a full-screen window with the eye view (a SurfaceView) and the touch
// controls on top of the game, and streams the controller state back to us.
#ifdef __ANDROID__
#include "runtime.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <cstdarg>

namespace lx {

static const char* kPackage = "com.limosa.xr";
static const char* kClass = "com.limosa.xr.runtime.RuntimeUi";

static std::mutex gLogMutex;
static std::deque<std::string> gLogLines;

void log(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "LimosaXR-RT", "%s", buf);
    std::lock_guard<std::mutex> g(gLogMutex);
    gLogLines.push_back(buf);
    if (gLogLines.size() > 300) gLogLines.pop_front();
}

static std::mutex gWindowMutex;
static ANativeWindow* gWindow = nullptr;
std::mutex& platformWindowMutex() { return gWindowMutex; }
void* platformWindow() { return gWindow; }
EGLDisplay platformEglDisplay() { return eglGetDisplay(EGL_DEFAULT_DISPLAY); }
EGLint platformSurfaceType() { return EGL_WINDOW_BIT; }
EGLSurface platformCreateSurface(EGLDisplay dpy, EGLConfig cfg, void* window) {
    return eglCreateWindowSurface(dpy, cfg, static_cast<ANativeWindow*>(window), nullptr);
}

static std::mutex gHapticMutex;
static std::vector<float> gHaptics;
void pushHaptic(int hand, float amplitude, float seconds) {
    std::lock_guard<std::mutex> g(gHapticMutex);
    if (gHaptics.size() < 30) gHaptics.insert(gHaptics.end(), {float(hand), amplitude, seconds});
}

// ------------------------------------------------------------------- JNI natives --
static void JNICALL nativeSetState(JNIEnv* env, jclass, jfloatArray arr) {
    if (!arr) return;
    jsize n = env->GetArrayLength(arr);
    float buf[kInputFloats + 8];
    if (n > jsize(sizeof buf / sizeof buf[0])) n = sizeof buf / sizeof buf[0];
    env->GetFloatArrayRegion(arr, 0, n, buf);
    inputFromFloats(buf, n);
}

static void JNICALL nativeSetSurface(JNIEnv* env, jclass, jobject surface) {
    std::lock_guard<std::mutex> g(gWindowMutex);
    compositorDropSurface();
    if (gWindow) ANativeWindow_release(gWindow);
    gWindow = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (gWindow) {
        int w = ANativeWindow_getWidth(gWindow), h = ANativeWindow_getHeight(gWindow);
        if (w > 0 && h > 0) {
            gScreenW = w > h ? w : h;
            gScreenH = w > h ? h : w;
        }
        LXLOG("display surface %dx%d", w, h);
    } else {
        LXLOG("display surface gone");
    }
}

static void JNICALL nativeSetScreen(JNIEnv*, jclass, jint w, jint h) {
    if (w > 0 && h > 0) {
        gScreenW = w > h ? w : h;
        gScreenH = w > h ? h : w;
    }
}

static void JNICALL nativeRequestExit(JNIEnv*, jclass) { gExitRequested = true; }

static jfloatArray JNICALL nativeTakeHaptics(JNIEnv* env, jclass) {
    std::vector<float> v;
    {
        std::lock_guard<std::mutex> g(gHapticMutex);
        v.swap(gHaptics);
    }
    if (v.empty()) return nullptr;
    jfloatArray a = env->NewFloatArray(jsize(v.size()));
    env->SetFloatArrayRegion(a, 0, jsize(v.size()), v.data());
    return a;
}

static jstring JNICALL nativeTakeLog(JNIEnv* env, jclass) {
    std::string all;
    {
        std::lock_guard<std::mutex> g(gLogMutex);
        for (auto& l : gLogLines) { all += l; all += '\n'; }
        gLogLines.clear();
    }
    return all.empty() ? nullptr : env->NewStringUTF(all.c_str());
}

static const JNINativeMethod kNatives[] = {
    {"nativeSetState", "([F)V", reinterpret_cast<void*>(nativeSetState)},
    {"nativeSetSurface", "(Landroid/view/Surface;)V", reinterpret_cast<void*>(nativeSetSurface)},
    {"nativeSetScreen", "(II)V", reinterpret_cast<void*>(nativeSetScreen)},
    {"nativeRequestExit", "()V", reinterpret_cast<void*>(nativeRequestExit)},
    {"nativeTakeHaptics", "()[F", reinterpret_cast<void*>(nativeTakeHaptics)},
    {"nativeTakeLog", "()Ljava/lang/String;", reinterpret_cast<void*>(nativeTakeLog)},
};

// ------------------------------------------------------------------------- setup --
static JavaVM* gVm = nullptr;
static jclass gUiClass = nullptr;

static JNIEnv* env(bool* attached) {
    *attached = false;
    JNIEnv* e = nullptr;
    if (!gVm) return nullptr;
    if (gVm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) == JNI_OK) return e;
    if (gVm->AttachCurrentThread(&e, nullptr) != JNI_OK) return nullptr;
    *attached = true;
    return e;
}

static bool check(JNIEnv* e, const char* what) {
    if (!e->ExceptionCheck()) return true;
    jthrowable t = e->ExceptionOccurred();
    e->ExceptionClear();
    jclass c = e->GetObjectClass(t);
    jmethodID ts = e->GetMethodID(c, "toString", "()Ljava/lang/String;");
    auto js = static_cast<jstring>(e->CallObjectMethod(t, ts));
    if (e->ExceptionCheck()) e->ExceptionClear();
    const char* s = js ? e->GetStringUTFChars(js, nullptr) : "?";
    LXLOG("%s failed: %s", what, s);
    if (js) e->ReleaseStringUTFChars(js, s);
    return false;
}

void platformInit(JavaVM* vm, jobject activity) {
    if (vm) gVm = vm;
    if (!gVm || !activity) {
        LXLOG("no Android activity was passed; running without a display");
        return;
    }
    bool attached;
    JNIEnv* e = env(&attached);
    if (!e) return;
    do {
        if (!gUiClass) {
            jclass actCls = e->GetObjectClass(activity);
            jmethodID cpc = e->GetMethodID(actCls, "createPackageContext", "(Ljava/lang/String;I)Landroid/content/Context;");
            jstring pkg = e->NewStringUTF(kPackage);
            jobject ctx = e->CallObjectMethod(activity, cpc, pkg, jint(3) /* INCLUDE_CODE | IGNORE_SECURITY */);
            if (!check(e, "createPackageContext") || !ctx) break;
            jclass ctxCls = e->GetObjectClass(ctx);
            jobject loader = e->CallObjectMethod(ctx, e->GetMethodID(ctxCls, "getClassLoader", "()Ljava/lang/ClassLoader;"));
            if (!check(e, "getClassLoader") || !loader) break;
            jclass loaderCls = e->GetObjectClass(loader);
            jmethodID load = e->GetMethodID(loaderCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
            jstring name = e->NewStringUTF(kClass);
            auto cls = static_cast<jclass>(e->CallObjectMethod(loader, load, name));
            if (!check(e, "loading the overlay class") || !cls) break;
            if (e->RegisterNatives(cls, kNatives, sizeof kNatives / sizeof kNatives[0]) != 0) {
                check(e, "RegisterNatives");
                break;
            }
            gUiClass = static_cast<jclass>(e->NewGlobalRef(cls));
            jmethodID start = e->GetStaticMethodID(gUiClass, "start", "(Landroid/app/Activity;Landroid/content/Context;)V");
            if (!check(e, "finding RuntimeUi.start") || !start) break;
            e->CallStaticVoidMethod(gUiClass, start, activity, ctx);
            if (!check(e, "RuntimeUi.start")) break;
            LXLOG("overlay started in the game's process");
        }
    } while (false);
    if (attached) gVm->DetachCurrentThread();
}

void platformShutdown() {
    if (!gUiClass) return;
    bool attached;
    JNIEnv* e = env(&attached);
    if (!e) return;
    jmethodID stop = e->GetStaticMethodID(gUiClass, "stop", "()V");
    if (stop) e->CallStaticVoidMethod(gUiClass, stop);
    check(e, "RuntimeUi.stop");
    if (attached) gVm->DetachCurrentThread();
}

}  // namespace lx
#endif  // __ANDROID__
