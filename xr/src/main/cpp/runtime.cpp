// Limosa XR OpenXR runtime: instance, session, spaces, views, frame loop and dispatch.
#include "runtime.h"

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <thread>

namespace lx {

std::recursive_mutex gLock;
Instance* gInstance = nullptr;
std::atomic<int> gScreenW{2400}, gScreenH{1080};
std::atomic<bool> gExitRequested{false};

// ------------------------------------------------------------------------- device input --
static std::mutex gInputMutex;
static DeviceInput gInput = [] {
    DeviceInput d{};
    d.head[3] = 1;
    d.eye = 1.6f;
    d.ipd = 0.063f;
    d.fov = 70.f;
    d.scale = 1.f;
    for (int h = 0; h < 2; h++) {
        d.hand[h].pos[0] = h == 0 ? -0.2f : 0.2f;
        d.hand[h].pos[1] = -0.3f;
        d.hand[h].pos[2] = -0.45f;
    }
    return d;
}();

DeviceInput inputSnapshot() {
    std::lock_guard<std::mutex> g(gInputMutex);
    return gInput;
}

void inputFromFloats(const float* f, int n) {
    if (n < kInputFloats) return;
    std::lock_guard<std::mutex> g(gInputMutex);
    DeviceInput& d = gInput;
    memcpy(d.head, f, 4 * sizeof(float));
    d.eye = f[4];
    d.stereo = f[5];
    d.ipd = f[6];
    d.fov = f[7];
    d.scale = f[8];
    for (int h = 0; h < 2; h++) {
        const float* p = f + 9 + h * 23;
        HandInput& hi = d.hand[h];
        memcpy(hi.pos, p, 3 * sizeof(float));
        hi.pitch = p[3];
        hi.yaw = p[4];
        memcpy(hi.buttons, p + 5, 8 * sizeof(float));
        memcpy(hi.touched, p + 13, 8 * sizeof(float));
        memcpy(hi.stick, p + 21, 2 * sizeof(float));
    }
}

int64_t nowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1000000000ll + ts.tv_nsec;
}

// ---------------------------------------------------------------------------- math --
XrQuaternionf quatMul(const XrQuaternionf& a, const XrQuaternionf& b) {
    return {a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
            a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
            a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
            a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z};
}
XrQuaternionf quatAxis(float x, float y, float z, float angle) {
    float s = sinf(angle / 2);
    return {x * s, y * s, z * s, cosf(angle / 2)};
}
static XrQuaternionf quatNorm(XrQuaternionf q) {
    float l = sqrtf(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
    if (l < 1e-6f) return {0, 0, 0, 1};
    return {q.x / l, q.y / l, q.z / l, q.w / l};
}
XrVector3f quatRotate(const XrQuaternionf& q, const XrVector3f& v) {
    // v + 2w(q x v) + 2 q x (q x v)
    XrVector3f u{q.x, q.y, q.z};
    XrVector3f t{2 * (u.y * v.z - u.z * v.y), 2 * (u.z * v.x - u.x * v.z), 2 * (u.x * v.y - u.y * v.x)};
    return {v.x + q.w * t.x + (u.y * t.z - u.z * t.y),
            v.y + q.w * t.y + (u.z * t.x - u.x * t.z),
            v.z + q.w * t.z + (u.x * t.y - u.y * t.x)};
}
XrPosef poseMul(const XrPosef& a, const XrPosef& b) {
    XrPosef r;
    XrVector3f p = quatRotate(a.orientation, b.position);
    r.position = {a.position.x + p.x, a.position.y + p.y, a.position.z + p.z};
    r.orientation = quatNorm(quatMul(a.orientation, b.orientation));
    return r;
}
XrPosef poseInv(const XrPosef& a) {
    XrPosef r;
    r.orientation = {-a.orientation.x, -a.orientation.y, -a.orientation.z, a.orientation.w};
    XrVector3f p = quatRotate(r.orientation, a.position);
    r.position = {-p.x, -p.y, -p.z};
    return r;
}

XrPosef headPose(const DeviceInput& in) {
    XrPosef p;
    p.orientation = quatNorm({in.head[0], in.head[1], in.head[2], in.head[3]});
    p.position = {0, in.eye, 0};
    return p;
}

// The touch "hand" pose is where the controller points (aim). The grip pose is tilted the way
// a hand holds a Touch-style controller when pointing straight ahead.
XrPosef handPose(const DeviceInput& in, int hand, bool aim) {
    const HandInput& h = in.hand[hand];
    XrPosef local;
    local.position = {h.pos[0], h.pos[1], h.pos[2]};
    local.orientation = quatMul(quatAxis(0, 1, 0, h.yaw), quatAxis(1, 0, 0, h.pitch));
    XrPosef p = poseMul(headPose(in), local);
    if (aim) return p;
    XrPosef gripFromAim;
    gripFromAim.orientation = quatAxis(1, 0, 0, 0.6f);
    gripFromAim.position = {0, -0.02f, 0.04f};
    return poseMul(p, gripFromAim);
}

void eyeSetup(const DeviceInput& in, int eye, XrPosef* eyeInHead, XrFovf* fov) {
    bool stereo = in.stereo > 0.5f;
    float w = float(std::max(1, gScreenW.load())), h = float(std::max(1, gScreenH.load()));
    float aspect = stereo ? (w / 2) / h : w / h;
    float halfV = std::clamp(in.fov, 30.f, 130.f) * float(M_PI) / 360.f;
    float tanV = tanf(halfV), tanH = tanV * aspect;
    fov->angleUp = halfV;
    fov->angleDown = -halfV;
    fov->angleRight = atanf(tanH);
    fov->angleLeft = -atanf(tanH);
    *eyeInHead = identityPose();
    if (stereo) eyeInHead->position.x = (eye == 0 ? -0.5f : 0.5f) * in.ipd;
}

// ------------------------------------------------------------------------- logging --
std::string formatv(const char* fmt, va_list ap) {
    char buf[1024];
    vsnprintf(buf, sizeof buf, fmt, ap);
    return buf;
}

// -------------------------------------------------------------------------- paths --
XrPath pathFor(Instance* inst, const std::string& s) {
    auto it = inst->pathIds.find(s);
    if (it != inst->pathIds.end()) return it->second;
    XrPath id = inst->paths.size();
    inst->paths.push_back(s);
    inst->pathIds[s] = id;
    return id;
}
const std::string& pathString(Instance* inst, XrPath p) {
    static const std::string empty;
    if (p == XR_NULL_PATH || p >= inst->paths.size()) return empty;
    return inst->paths[p];
}
int handOfPath(Instance* inst, XrPath p) {
    const std::string& s = pathString(inst, p);
    if (s.rfind("/user/hand/left", 0) == 0) return 0;
    if (s.rfind("/user/hand/right", 0) == 0) return 1;
    return -1;
}

bool Instance::hasExt(const char* name) const {
    for (auto& e : enabledExtensions) if (e == name) return true;
    return false;
}

// ------------------------------------------------------------------------- events --
void queueEvent(Instance* inst, const void* event, size_t size) {
    XrEventDataBuffer b{};
    memcpy(&b, event, std::min(size, sizeof b));
    inst->events.push_back(b);
    if (inst->events.size() > 64) inst->events.pop_front();
}

void setSessionState(Session* s, XrSessionState st) {
    if (s->state == st) return;
    s->state = st;
    XrEventDataSessionStateChanged e{XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED};
    e.session = reinterpret_cast<XrSession>(s);
    e.state = st;
    e.time = nowNs();
    queueEvent(s->inst, &e, sizeof e);
    LXLOG("session state -> %d", int(st));
}

// ------------------------------------------------------------------------ helpers --
template <class T>
static T* cast(uint64_t handle, uint64_t magic) {
    T* p = reinterpret_cast<T*>(handle);
    if (!p || p->magic != magic) return nullptr;
    return p;
}
#define GET_INSTANCE(h) Instance* inst = cast<Instance>((uint64_t)(h), 0x4c58494e5354414eull); \
    if (!inst || inst != gInstance) return XR_ERROR_HANDLE_INVALID
#define GET_SESSION(h) Session* s = cast<Session>((uint64_t)(h), 0x4c5853455353494full); \
    if (!s || !gInstance || s != gInstance->session) return XR_ERROR_HANDLE_INVALID
#define LOCK std::lock_guard<std::recursive_mutex> lock_(gLock)

template <class T>
static XrResult fillArray(uint32_t cap, uint32_t* count, T* out, const std::vector<T>& v) {
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = uint32_t(v.size());
    if (cap == 0) return XR_SUCCESS;
    if (cap < v.size()) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!out) return XR_ERROR_VALIDATION_FAILURE;
    for (size_t i = 0; i < v.size(); i++) out[i] = v[i];
    return XR_SUCCESS;
}
static XrResult fillString(uint32_t cap, uint32_t* count, char* out, const std::string& s) {
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = uint32_t(s.size() + 1);
    if (cap == 0) return XR_SUCCESS;
    if (cap < s.size() + 1) return XR_ERROR_SIZE_INSUFFICIENT;
    memcpy(out, s.c_str(), s.size() + 1);
    return XR_SUCCESS;
}
static const void* findNext(const void* next, XrStructureType type) {
    auto* b = reinterpret_cast<const XrBaseInStructure*>(next);
    while (b) {
        if (b->type == type) return b;
        b = b->next;
    }
    return nullptr;
}

static const XrSystemId kSystemId = 0x4c58;

// ------------------------------------------------------------------ extensions --
struct ExtInfo { const char* name; uint32_t version; };
static const ExtInfo kExtensions[] = {
    {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, 3},
    {XR_KHR_LOADER_INIT_EXTENSION_NAME, 2},
    {XR_KHR_LOADER_INIT_ANDROID_EXTENSION_NAME, 1},
    {XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME, 8},
    {XR_KHR_VULKAN_ENABLE_EXTENSION_NAME, 8},
    {XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME, 2},
    {XR_KHR_COMPOSITION_LAYER_DEPTH_EXTENSION_NAME, 6},
    {XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME, 1},
    {XR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME, 6},
    {XR_EXT_LOCAL_FLOOR_EXTENSION_NAME, 1},
    {XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME, 4},
    {XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME, 1},
    {XR_FB_COLOR_SPACE_EXTENSION_NAME, 3},
    {XR_META_TOUCH_CONTROLLER_PLUS_EXTENSION_NAME, 1},
    {XR_KHR_LOCATE_SPACES_EXTENSION_NAME, 1},
    {XR_KHR_MAINTENANCE1_EXTENSION_NAME, 1},
};

static bool extensionSupported(const char* name) {
    for (auto& e : kExtensions) if (strcmp(e.name, name) == 0) return true;
    return false;
}

// ====================================================================== instance ==

static XrResult XRAPI_CALL xrEnumerateApiLayerProperties(uint32_t cap, uint32_t* count, XrApiLayerProperties*) {
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = 0;
    (void)cap;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrEnumerateInstanceExtensionProperties(const char* layer, uint32_t cap, uint32_t* count, XrExtensionProperties* props) {
    if (layer && layer[0]) return XR_ERROR_API_LAYER_NOT_PRESENT;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    uint32_t n = sizeof kExtensions / sizeof kExtensions[0];
    *count = n;
    if (cap == 0) return XR_SUCCESS;
    if (cap < n) return XR_ERROR_SIZE_INSUFFICIENT;
    for (uint32_t i = 0; i < n; i++) {
        strncpy(props[i].extensionName, kExtensions[i].name, XR_MAX_EXTENSION_NAME_SIZE - 1);
        props[i].extensionName[XR_MAX_EXTENSION_NAME_SIZE - 1] = 0;
        props[i].extensionVersion = kExtensions[i].version;
    }
    return XR_SUCCESS;
}

static JavaVM* gLoaderVM = nullptr;

static XrResult XRAPI_CALL xrInitializeLoaderKHR(const XrLoaderInitInfoBaseHeaderKHR* info) {
    if (!info) return XR_ERROR_VALIDATION_FAILURE;
    if (info->type == XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR) {
        auto* a = reinterpret_cast<const XrLoaderInitInfoAndroidKHR*>(info);
        gLoaderVM = reinterpret_cast<JavaVM*>(a->applicationVM);
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrCreateInstance(const XrInstanceCreateInfo* info, XrInstance* out) {
    LOCK;
    if (!info || !out || info->type != XR_TYPE_INSTANCE_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    XrVersion v = info->applicationInfo.apiVersion;
    if (XR_VERSION_MAJOR(v) != 1 || XR_VERSION_MINOR(v) > 1) {
        LXLOG("app wants unsupported API version %d.%d", int(XR_VERSION_MAJOR(v)), int(XR_VERSION_MINOR(v)));
        return XR_ERROR_API_VERSION_UNSUPPORTED;
    }
    if (info->enabledApiLayerCount > 0) return XR_ERROR_API_LAYER_NOT_PRESENT;
    for (uint32_t i = 0; i < info->enabledExtensionCount; i++) {
        if (!extensionSupported(info->enabledExtensionNames[i])) {
            LXLOG("app requires unsupported extension %s", info->enabledExtensionNames[i]);
            return XR_ERROR_EXTENSION_NOT_PRESENT;
        }
    }
    if (gInstance) return XR_ERROR_LIMIT_REACHED;
    auto* inst = new Instance();
    inst->apiVersion = v;
    inst->appName = info->applicationInfo.applicationName;
    for (uint32_t i = 0; i < info->enabledExtensionCount; i++) inst->enabledExtensions.push_back(info->enabledExtensionNames[i]);
    gInstance = inst;
    LXLOG("xrCreateInstance: app '%s' engine '%s' api %d.%d, %u extensions",
          info->applicationInfo.applicationName, info->applicationInfo.engineName,
          int(XR_VERSION_MAJOR(v)), int(XR_VERSION_MINOR(v)), info->enabledExtensionCount);
    for (auto& e : inst->enabledExtensions) LXLOG("  ext %s", e.c_str());

    auto* android = static_cast<const XrInstanceCreateInfoAndroidKHR*>(findNext(info->next, XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR));
    JavaVM* vm = android ? reinterpret_cast<JavaVM*>(android->applicationVM) : gLoaderVM;
    jobject activity = android ? reinterpret_cast<jobject>(android->applicationActivity) : nullptr;
    platformInit(vm, activity);
    *out = reinterpret_cast<XrInstance>(inst);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrDestroyInstance(XrInstance instance) {
    LOCK;
    GET_INSTANCE(instance);
    if (inst->session) return XR_ERROR_VALIDATION_FAILURE;
    for (auto* set : inst->actionSets) {
        for (auto* a : set->actions) delete a;
        delete set;
    }
    gInstance = nullptr;
    inst->magic = 0;
    delete inst;
    platformShutdown();
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrGetInstanceProperties(XrInstance instance, XrInstanceProperties* props) {
    LOCK;
    GET_INSTANCE(instance);
    if (!props) return XR_ERROR_VALIDATION_FAILURE;
    props->runtimeVersion = XR_MAKE_VERSION(0, 2, 0);
    strncpy(props->runtimeName, "Limosa XR", XR_MAX_RUNTIME_NAME_SIZE);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrPollEvent(XrInstance instance, XrEventDataBuffer* ev) {
    LOCK;
    GET_INSTANCE(instance);
    if (!ev) return XR_ERROR_VALIDATION_FAILURE;
    Session* s = inst->session;
    if (s && gExitRequested.exchange(false) && s->running && !s->exitRequested) {
        LXLOG("exit requested from the overlay");
        s->exitRequested = true;
        setSessionState(s, XR_SESSION_STATE_VISIBLE);
        setSessionState(s, XR_SESSION_STATE_SYNCHRONIZED);
        setSessionState(s, XR_SESSION_STATE_STOPPING);
    }
    if (inst->events.empty()) return XR_EVENT_UNAVAILABLE;
    *ev = inst->events.front();
    inst->events.pop_front();
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrResultToString(XrInstance instance, XrResult value, char buffer[XR_MAX_RESULT_STRING_SIZE]) {
    (void)instance;
    snprintf(buffer, XR_MAX_RESULT_STRING_SIZE, value >= 0 ? "XR_UNKNOWN_SUCCESS_%d" : "XR_UNKNOWN_FAILURE_%d", int(value));
    switch (value) {
        case XR_SUCCESS: strcpy(buffer, "XR_SUCCESS"); break;
        case XR_EVENT_UNAVAILABLE: strcpy(buffer, "XR_EVENT_UNAVAILABLE"); break;
        case XR_ERROR_VALIDATION_FAILURE: strcpy(buffer, "XR_ERROR_VALIDATION_FAILURE"); break;
        case XR_ERROR_HANDLE_INVALID: strcpy(buffer, "XR_ERROR_HANDLE_INVALID"); break;
        case XR_ERROR_FUNCTION_UNSUPPORTED: strcpy(buffer, "XR_ERROR_FUNCTION_UNSUPPORTED"); break;
        case XR_ERROR_SIZE_INSUFFICIENT: strcpy(buffer, "XR_ERROR_SIZE_INSUFFICIENT"); break;
        default: break;
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrStructureTypeToString(XrInstance instance, XrStructureType value, char buffer[XR_MAX_STRUCTURE_NAME_SIZE]) {
    (void)instance;
    snprintf(buffer, XR_MAX_STRUCTURE_NAME_SIZE, "XR_UNKNOWN_STRUCTURE_TYPE_%d", int(value));
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrStringToPath(XrInstance instance, const char* str, XrPath* out) {
    LOCK;
    GET_INSTANCE(instance);
    if (!str || !out || str[0] != '/') return XR_ERROR_PATH_FORMAT_INVALID;
    size_t n = strlen(str);
    if (n >= XR_MAX_PATH_LENGTH || (n > 1 && str[n - 1] == '/')) return XR_ERROR_PATH_FORMAT_INVALID;
    *out = pathFor(inst, str);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrPathToString(XrInstance instance, XrPath path, uint32_t cap, uint32_t* count, char* buffer) {
    LOCK;
    GET_INSTANCE(instance);
    if (path == XR_NULL_PATH || path >= inst->paths.size()) return XR_ERROR_PATH_INVALID;
    return fillString(cap, count, buffer, inst->paths[path]);
}

// ======================================================================== system ==

static XrResult XRAPI_CALL xrGetSystem(XrInstance instance, const XrSystemGetInfo* info, XrSystemId* id) {
    LOCK;
    GET_INSTANCE(instance);
    if (!info || !id) return XR_ERROR_VALIDATION_FAILURE;
    if (info->formFactor != XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY) return XR_ERROR_FORM_FACTOR_UNSUPPORTED;
    *id = kSystemId;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrGetSystemProperties(XrInstance instance, XrSystemId id, XrSystemProperties* props) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (!props) return XR_ERROR_VALIDATION_FAILURE;
    props->systemId = kSystemId;
    props->vendorId = 0x2833;  // the vendor games expect for Touch-style controllers
    strncpy(props->systemName, "Oculus Quest2", XR_MAX_SYSTEM_NAME_SIZE);
    props->graphicsProperties.maxSwapchainImageWidth = 4096;
    props->graphicsProperties.maxSwapchainImageHeight = 4096;
    props->graphicsProperties.maxLayerCount = 16;
    props->trackingProperties.orientationTracking = XR_TRUE;
    props->trackingProperties.positionTracking = XR_TRUE;
    // Zero the extension structs we know about; we support none of their features.
    auto* b = reinterpret_cast<XrBaseOutStructure*>(props->next);
    while (b) {
        if (b->type == XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT)
            reinterpret_cast<XrSystemHandTrackingPropertiesEXT*>(b)->supportsHandTracking = XR_FALSE;
        b = b->next;
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrEnumerateEnvironmentBlendModes(XrInstance instance, XrSystemId id, XrViewConfigurationType vct,
                                                             uint32_t cap, uint32_t* count, XrEnvironmentBlendMode* modes) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (vct != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    return fillArray<XrEnvironmentBlendMode>(cap, count, modes, {XR_ENVIRONMENT_BLEND_MODE_OPAQUE});
}

static XrResult XRAPI_CALL xrEnumerateViewConfigurations(XrInstance instance, XrSystemId id, uint32_t cap, uint32_t* count, XrViewConfigurationType* types) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    return fillArray<XrViewConfigurationType>(cap, count, types, {XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO});
}

static XrResult XRAPI_CALL xrGetViewConfigurationProperties(XrInstance instance, XrSystemId id, XrViewConfigurationType vct, XrViewConfigurationProperties* props) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (vct != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    props->viewConfigurationType = vct;
    props->fovMutable = XR_FALSE;
    return XR_SUCCESS;
}

static void recommendedSize(uint32_t* w, uint32_t* h) {
    // One eye image is sized for the whole screen (flat view shows just one eye full screen),
    // capped so phones are not asked to render more than they can.
    DeviceInput in = inputSnapshot();
    float sw = float(std::max(1, gScreenW.load())), sh = float(std::max(1, gScreenH.load()));
    float scale = std::clamp(in.scale, 0.25f, 1.f);
    float cap = 1600.f / std::max(sw, sh);
    float k = std::min(1.f, cap) * scale;
    *w = std::max(64u, uint32_t(sw * k) & ~7u);
    *h = std::max(64u, uint32_t(sh * k) & ~7u);
}

static XrResult XRAPI_CALL xrEnumerateViewConfigurationViews(XrInstance instance, XrSystemId id, XrViewConfigurationType vct,
                                                              uint32_t cap, uint32_t* count, XrViewConfigurationView* views) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (vct != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = 2;
    if (cap == 0) return XR_SUCCESS;
    if (cap < 2) return XR_ERROR_SIZE_INSUFFICIENT;
    uint32_t w, h;
    recommendedSize(&w, &h);
    for (int i = 0; i < 2; i++) {
        views[i].recommendedImageRectWidth = w;
        views[i].recommendedImageRectHeight = h;
        views[i].maxImageRectWidth = 4096;
        views[i].maxImageRectHeight = 4096;
        views[i].recommendedSwapchainSampleCount = 1;
        views[i].maxSwapchainSampleCount = 1;
    }
    return XR_SUCCESS;
}

// ========================================================= graphics requirements ==

static XrResult XRAPI_CALL xrGetOpenGLESGraphicsRequirementsKHR(XrInstance instance, XrSystemId id, XrGraphicsRequirementsOpenGLESKHR* req) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    req->minApiVersionSupported = XR_MAKE_VERSION(3, 0, 0);
    req->maxApiVersionSupported = XR_MAKE_VERSION(3, 2, 0);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrGetVulkanGraphicsRequirementsKHR(XrInstance instance, XrSystemId id, XrGraphicsRequirementsVulkanKHR* req) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    req->minApiVersionSupported = XR_MAKE_VERSION(1, 0, 0);
    req->maxApiVersionSupported = XR_MAKE_VERSION(1, 3, 0);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrGetVulkanInstanceExtensionsKHR(XrInstance instance, XrSystemId id, uint32_t cap, uint32_t* count, char* buffer) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    std::string s;
    vkGetInstanceExtensions(&s);
    return fillString(cap, count, buffer, s);
}

static XrResult XRAPI_CALL xrGetVulkanDeviceExtensionsKHR(XrInstance instance, XrSystemId id, uint32_t cap, uint32_t* count, char* buffer) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    std::string s;
    vkGetDeviceExtensions(&s);
    return fillString(cap, count, buffer, s);
}

static XrResult XRAPI_CALL xrGetVulkanGraphicsDeviceKHR(XrInstance instance, XrSystemId id, VkInstance vki, VkPhysicalDevice* out) {
    LOCK;
    GET_INSTANCE(instance);
    if (id != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    return vkPickPhysicalDevice(vki, nullptr, out);
}

static XrResult XRAPI_CALL xrGetVulkanGraphicsDevice2KHR(XrInstance instance, const XrVulkanGraphicsDeviceGetInfoKHR* info, VkPhysicalDevice* out) {
    LOCK;
    GET_INSTANCE(instance);
    if (!info || info->systemId != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    return vkPickPhysicalDevice(info->vulkanInstance, nullptr, out);
}

static XrResult XRAPI_CALL xrCreateVulkanInstanceKHR(XrInstance instance, const XrVulkanInstanceCreateInfoKHR* info, VkInstance* out, VkResult* vr) {
    LOCK;
    GET_INSTANCE(instance);
    if (!info || info->systemId != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    return vkCreateInstanceForApp(info, out, vr);
}

static XrResult XRAPI_CALL xrCreateVulkanDeviceKHR(XrInstance instance, const XrVulkanDeviceCreateInfoKHR* info, VkDevice* out, VkResult* vr) {
    LOCK;
    GET_INSTANCE(instance);
    if (!info || info->systemId != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    return vkCreateDeviceForApp(info, out, vr);
}

// ======================================================================= session ==

static XrResult XRAPI_CALL xrCreateSession(XrInstance instance, const XrSessionCreateInfo* info, XrSession* out) {
    LOCK;
    GET_INSTANCE(instance);
    if (!info || !out) return XR_ERROR_VALIDATION_FAILURE;
    if (info->systemId != kSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (inst->session) return XR_ERROR_LIMIT_REACHED;
    auto* s = new Session();
    s->inst = inst;
    if (auto* gl = static_cast<const XrGraphicsBindingOpenGLESAndroidKHR*>(findNext(info->next, XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR))) {
        s->gfx = Gfx::GLES;
        s->eglDisplay = gl->display;
        s->eglConfig = gl->config;
        s->eglContext = gl->context;
        LXLOG("session: OpenGL ES");
    } else if (auto* vk = static_cast<const XrGraphicsBindingVulkanKHR*>(findNext(info->next, XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR))) {
        s->gfx = Gfx::Vulkan;
        s->vkInstance = vk->instance;
        s->vkPhys = vk->physicalDevice;
        s->vkDevice = vk->device;
        s->vkQueueFamily = vk->queueFamilyIndex;
        s->vkQueueIndex = vk->queueIndex;
        XrResult r = vkInitSession(s);
        if (XR_FAILED(r)) {
            delete s;
            return r;
        }
        LXLOG("session: Vulkan");
    } else {
        LXLOG("session: no supported graphics binding");
        delete s;
        return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    }
    inst->session = s;
    *out = reinterpret_cast<XrSession>(s);
    setSessionState(s, XR_SESSION_STATE_IDLE);
    setSessionState(s, XR_SESSION_STATE_READY);
    return XR_SUCCESS;
}

static XrResult destroySwapchainLocked(Swapchain* sc);

static XrResult XRAPI_CALL xrDestroySession(XrSession session) {
    LOCK;
    GET_SESSION(session);
    for (auto* sp : s->spaces) { sp->magic = 0; delete sp; }
    while (!s->swapchains.empty()) destroySwapchainLocked(s->swapchains.back());
    compositorShutdown();
    if (s->gfx == Gfx::Vulkan) vkDestroySession(s);
    for (auto* set : s->attachedSets) set->attached = false;
    s->inst->session = nullptr;
    s->magic = 0;
    delete s;
    LXLOG("session destroyed");
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrBeginSession(XrSession session, const XrSessionBeginInfo* info) {
    LOCK;
    GET_SESSION(session);
    if (!info) return XR_ERROR_VALIDATION_FAILURE;
    if (info->primaryViewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    if (s->running) return XR_ERROR_SESSION_RUNNING;
    if (s->state != XR_SESSION_STATE_READY) return XR_ERROR_SESSION_NOT_READY;
    s->running = true;
    s->nextFrame = 0;
    setSessionState(s, XR_SESSION_STATE_SYNCHRONIZED);
    setSessionState(s, XR_SESSION_STATE_VISIBLE);
    setSessionState(s, XR_SESSION_STATE_FOCUSED);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrEndSession(XrSession session) {
    LOCK;
    GET_SESSION(session);
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (s->state != XR_SESSION_STATE_STOPPING) return XR_ERROR_SESSION_NOT_STOPPING;
    s->running = false;
    s->frameBegun = false;
    setSessionState(s, XR_SESSION_STATE_IDLE);
    if (s->exitRequested) setSessionState(s, XR_SESSION_STATE_EXITING);
    else setSessionState(s, XR_SESSION_STATE_READY);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrRequestExitSession(XrSession session) {
    LOCK;
    GET_SESSION(session);
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    s->exitRequested = true;
    if (s->state == XR_SESSION_STATE_FOCUSED) setSessionState(s, XR_SESSION_STATE_VISIBLE);
    if (s->state == XR_SESSION_STATE_VISIBLE) setSessionState(s, XR_SESSION_STATE_SYNCHRONIZED);
    setSessionState(s, XR_SESSION_STATE_STOPPING);
    return XR_SUCCESS;
}

// ======================================================================== spaces ==

static bool refSpaceSupported(XrReferenceSpaceType t) {
    return t == XR_REFERENCE_SPACE_TYPE_VIEW || t == XR_REFERENCE_SPACE_TYPE_LOCAL || t == XR_REFERENCE_SPACE_TYPE_STAGE ||
           t == XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR;
}

static XrResult XRAPI_CALL xrEnumerateReferenceSpaces(XrSession session, uint32_t cap, uint32_t* count, XrReferenceSpaceType* types) {
    LOCK;
    GET_SESSION(session);
    std::vector<XrReferenceSpaceType> v{XR_REFERENCE_SPACE_TYPE_VIEW, XR_REFERENCE_SPACE_TYPE_LOCAL, XR_REFERENCE_SPACE_TYPE_STAGE};
    if (s->inst->hasExt(XR_EXT_LOCAL_FLOOR_EXTENSION_NAME) || s->inst->apiVersion >= XR_API_VERSION_1_1)
        v.push_back(XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR);
    return fillArray(cap, count, types, v);
}

static XrResult XRAPI_CALL xrCreateReferenceSpace(XrSession session, const XrReferenceSpaceCreateInfo* info, XrSpace* out) {
    LOCK;
    GET_SESSION(session);
    if (!info || !out) return XR_ERROR_VALIDATION_FAILURE;
    if (!refSpaceSupported(info->referenceSpaceType)) return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    auto* sp = new Space();
    sp->session = s;
    sp->kind = SpaceKind::Reference;
    sp->refType = info->referenceSpaceType;
    sp->offset = info->poseInReferenceSpace;
    s->spaces.push_back(sp);
    *out = reinterpret_cast<XrSpace>(sp);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrGetReferenceSpaceBoundsRect(XrSession session, XrReferenceSpaceType type, XrExtent2Df* bounds) {
    LOCK;
    GET_SESSION(session);
    if (!refSpaceSupported(type)) return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    if (type == XR_REFERENCE_SPACE_TYPE_STAGE) {
        *bounds = {4.f, 4.f};
        return XR_SUCCESS;
    }
    *bounds = {0, 0};
    return XR_SPACE_BOUNDS_UNAVAILABLE;
}

static XrResult XRAPI_CALL xrCreateActionSpace(XrSession session, const XrActionSpaceCreateInfo* info, XrSpace* out) {
    LOCK;
    GET_SESSION(session);
    if (!info || !out) return XR_ERROR_VALIDATION_FAILURE;
    auto* a = reinterpret_cast<Action*>(info->action);
    if (!a || a->magic != 0x4c58414354494f4eull) return XR_ERROR_HANDLE_INVALID;
    if (a->type != XR_ACTION_TYPE_POSE_INPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    auto* sp = new Space();
    sp->session = s;
    sp->kind = SpaceKind::Action;
    sp->action = a;
    sp->subaction = info->subactionPath;
    sp->offset = info->poseInActionSpace;
    s->spaces.push_back(sp);
    *out = reinterpret_cast<XrSpace>(sp);
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrDestroySpace(XrSpace space) {
    LOCK;
    auto* sp = cast<Space>((uint64_t)space, 0x4c58535041434500ull);
    if (!sp) return XR_ERROR_HANDLE_INVALID;
    auto& v = sp->session->spaces;
    v.erase(std::remove(v.begin(), v.end(), sp), v.end());
    sp->magic = 0;
    delete sp;
    return XR_SUCCESS;
}

// Which pose an action space follows: the hand and whether it is the aim or grip pose.
static bool actionSpaceSource(Space* sp, int* hand, bool* aim) {
    Session* s = sp->session;
    auto it = s->bindings.find(sp->action);
    if (it == s->bindings.end()) return false;
    for (XrPath b : it->second) {
        int h = handOfPath(s->inst, b);
        if (h < 0) continue;
        if (sp->subaction != XR_NULL_PATH && handOfPath(s->inst, sp->subaction) != h) continue;
        const std::string& str = pathString(s->inst, b);
        if (str.find("/pose") == std::string::npos) continue;
        *hand = h;
        *aim = str.find("/aim/") != std::string::npos;
        return true;
    }
    return false;
}

bool locateInStage(Space* sp, const DeviceInput& in, XrPosef* out) {
    XrPosef base = identityPose();
    if (sp->kind == SpaceKind::Reference) {
        switch (sp->refType) {
            case XR_REFERENCE_SPACE_TYPE_VIEW: base = headPose(in); break;
            case XR_REFERENCE_SPACE_TYPE_LOCAL: base.position.y = in.eye; break;
            default: break;  // STAGE and LOCAL_FLOOR: the floor under the start position
        }
    } else {
        int hand;
        bool aim;
        if (!actionSpaceSource(sp, &hand, &aim)) return false;
        base = handPose(in, hand, aim);
    }
    *out = poseMul(base, sp->offset);
    return true;
}

static XrResult locateSpaceImpl(Space* sp, Space* base, const DeviceInput& in, XrSpaceLocation* loc) {
    XrPosef a, b;
    if (!locateInStage(sp, in, &a) || !locateInStage(base, in, &b)) {
        loc->locationFlags = 0;
        loc->pose = identityPose();
        return XR_SUCCESS;
    }
    loc->pose = poseMul(poseInv(b), a);
    loc->locationFlags = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT |
                         XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
    auto* vel = static_cast<XrSpaceVelocity*>(const_cast<void*>(findNext(loc->next, XR_TYPE_SPACE_VELOCITY)));
    if (vel) {
        vel->velocityFlags = XR_SPACE_VELOCITY_LINEAR_VALID_BIT | XR_SPACE_VELOCITY_ANGULAR_VALID_BIT;
        vel->linearVelocity = {0, 0, 0};
        vel->angularVelocity = {0, 0, 0};
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrLocateSpace(XrSpace space, XrSpace baseSpace, XrTime time, XrSpaceLocation* loc) {
    LOCK;
    auto* sp = cast<Space>((uint64_t)space, 0x4c58535041434500ull);
    auto* base = cast<Space>((uint64_t)baseSpace, 0x4c58535041434500ull);
    if (!sp || !base) return XR_ERROR_HANDLE_INVALID;
    if (!loc) return XR_ERROR_VALIDATION_FAILURE;
    if (time <= 0) return XR_ERROR_TIME_INVALID;
    return locateSpaceImpl(sp, base, inputSnapshot(), loc);
}

static XrResult XRAPI_CALL xrLocateSpaces(XrSession session, const XrSpacesLocateInfo* info, XrSpaceLocations* locs) {
    LOCK;
    GET_SESSION(session);
    if (!info || !locs || info->spaceCount != locs->locationCount) return XR_ERROR_VALIDATION_FAILURE;
    auto* base = cast<Space>((uint64_t)info->baseSpace, 0x4c58535041434500ull);
    if (!base) return XR_ERROR_HANDLE_INVALID;
    DeviceInput in = inputSnapshot();
    auto* vels = static_cast<XrSpaceVelocities*>(const_cast<void*>(findNext(locs->next, XR_TYPE_SPACE_VELOCITIES)));
    for (uint32_t i = 0; i < info->spaceCount; i++) {
        auto* sp = cast<Space>((uint64_t)info->spaces[i], 0x4c58535041434500ull);
        if (!sp) return XR_ERROR_HANDLE_INVALID;
        XrSpaceLocation l{XR_TYPE_SPACE_LOCATION};
        locateSpaceImpl(sp, base, in, &l);
        locs->locations[i].locationFlags = l.locationFlags;
        locs->locations[i].pose = l.pose;
        if (vels && i < vels->velocityCount) {
            vels->velocities[i].velocityFlags = l.locationFlags ? (XR_SPACE_VELOCITY_LINEAR_VALID_BIT | XR_SPACE_VELOCITY_ANGULAR_VALID_BIT) : 0;
            vels->velocities[i].linearVelocity = {0, 0, 0};
            vels->velocities[i].angularVelocity = {0, 0, 0};
        }
    }
    (void)s;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrLocateViews(XrSession session, const XrViewLocateInfo* info, XrViewState* state, uint32_t cap, uint32_t* count, XrView* views) {
    LOCK;
    GET_SESSION(session);
    if (!info || !state || !count) return XR_ERROR_VALIDATION_FAILURE;
    if (info->viewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    auto* base = cast<Space>((uint64_t)info->space, 0x4c58535041434500ull);
    if (!base) return XR_ERROR_HANDLE_INVALID;
    *count = 2;
    if (cap == 0) return XR_SUCCESS;
    if (cap < 2) return XR_ERROR_SIZE_INSUFFICIENT;
    DeviceInput in = inputSnapshot();
    XrPosef basePose;
    if (!locateInStage(base, in, &basePose)) {
        state->viewStateFlags = 0;
        return XR_SUCCESS;
    }
    XrPosef head = poseMul(poseInv(basePose), headPose(in));
    for (int e = 0; e < 2; e++) {
        XrPosef eyeInHead;
        eyeSetup(in, e, &eyeInHead, &views[e].fov);
        views[e].pose = poseMul(head, eyeInHead);
    }
    state->viewStateFlags = XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT |
                            XR_VIEW_STATE_ORIENTATION_TRACKED_BIT | XR_VIEW_STATE_POSITION_TRACKED_BIT;
    (void)s;
    return XR_SUCCESS;
}

// ===================================================================== swapchains ==

static XrResult XRAPI_CALL xrEnumerateSwapchainFormats(XrSession session, uint32_t cap, uint32_t* count, int64_t* formats) {
    LOCK;
    GET_SESSION(session);
    std::vector<int64_t> v = s->gfx == Gfx::GLES ? glesFormats() : vkFormats(s);
    return fillArray(cap, count, formats, v);
}

static XrResult XRAPI_CALL xrCreateSwapchain(XrSession session, const XrSwapchainCreateInfo* info, XrSwapchain* out) {
    LOCK;
    GET_SESSION(session);
    if (!info || !out) return XR_ERROR_VALIDATION_FAILURE;
    if (info->faceCount != 1) return XR_ERROR_FEATURE_UNSUPPORTED;
    if (info->sampleCount > 1) return XR_ERROR_FEATURE_UNSUPPORTED;
    if (info->width == 0 || info->height == 0 || info->width > 4096 || info->height > 4096 || info->arraySize == 0)
        return XR_ERROR_VALIDATION_FAILURE;
    std::vector<int64_t> fmts = s->gfx == Gfx::GLES ? glesFormats() : vkFormats(s);
    if (std::find(fmts.begin(), fmts.end(), info->format) == fmts.end()) return XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED;
    auto* sc = new Swapchain();
    sc->session = s;
    sc->info = *info;
    sc->info.next = nullptr;
    sc->imageCount = (info->createFlags & XR_SWAPCHAIN_CREATE_STATIC_IMAGE_BIT) ? 1 : 3;
    XrResult r = s->gfx == Gfx::GLES ? glesCreateSwapchain(s, sc) : vkCreateSwapchainImages(s, sc);
    if (XR_FAILED(r)) {
        LXLOG("xrCreateSwapchain failed: %d", int(r));
        delete sc;
        return r;
    }
    LXLOG("swapchain %ux%u x%u format %lld", info->width, info->height, info->arraySize, (long long)info->format);
    s->swapchains.push_back(sc);
    *out = reinterpret_cast<XrSwapchain>(sc);
    return XR_SUCCESS;
}

static XrResult destroySwapchainLocked(Swapchain* sc) {
    Session* s = sc->session;
    if (s->gfx == Gfx::GLES) glesDestroySwapchain(s, sc);
    else vkDestroySwapchain(s, sc);
    s->swapchains.erase(std::remove(s->swapchains.begin(), s->swapchains.end(), sc), s->swapchains.end());
    sc->magic = 0;
    delete sc;
    return XR_SUCCESS;
}

#define GET_SWAPCHAIN(h) Swapchain* sc = cast<Swapchain>((uint64_t)(h), 0x4c58535750434841ull); \
    if (!sc) return XR_ERROR_HANDLE_INVALID

static XrResult XRAPI_CALL xrDestroySwapchain(XrSwapchain swapchain) {
    LOCK;
    GET_SWAPCHAIN(swapchain);
    return destroySwapchainLocked(sc);
}

static XrResult XRAPI_CALL xrEnumerateSwapchainImages(XrSwapchain swapchain, uint32_t cap, uint32_t* count, XrSwapchainImageBaseHeader* images) {
    LOCK;
    GET_SWAPCHAIN(swapchain);
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = sc->imageCount;
    if (cap == 0) return XR_SUCCESS;
    if (cap < sc->imageCount) return XR_ERROR_SIZE_INSUFFICIENT;
    for (uint32_t i = 0; i < sc->imageCount; i++) {
        if (sc->session->gfx == Gfx::GLES) {
            auto* img = reinterpret_cast<XrSwapchainImageOpenGLESKHR*>(images);
            if (img[i].type != XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR) return XR_ERROR_VALIDATION_FAILURE;
            img[i].image = sc->glTex[i];
        } else {
            auto* img = reinterpret_cast<XrSwapchainImageVulkanKHR*>(images);
            if (img[i].type != XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR) return XR_ERROR_VALIDATION_FAILURE;
            img[i].image = sc->vkImages[i];
        }
    }
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrAcquireSwapchainImage(XrSwapchain swapchain, const XrSwapchainImageAcquireInfo*, uint32_t* index) {
    LOCK;
    GET_SWAPCHAIN(swapchain);
    if (!index) return XR_ERROR_VALIDATION_FAILURE;
    if (sc->acquired.size() >= sc->imageCount) return XR_ERROR_CALL_ORDER_INVALID;
    if (sc->imageCount == 1 && sc->lastReleased >= 0) return XR_ERROR_CALL_ORDER_INVALID;  // static image
    uint32_t i = sc->nextIndex;
    sc->nextIndex = (sc->nextIndex + 1) % sc->imageCount;
    sc->acquired.push_back(i);
    *index = i;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrWaitSwapchainImage(XrSwapchain swapchain, const XrSwapchainImageWaitInfo*) {
    LOCK;
    GET_SWAPCHAIN(swapchain);
    if (sc->acquired.empty() || sc->waited) return XR_ERROR_CALL_ORDER_INVALID;
    sc->waited = true;
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrReleaseSwapchainImage(XrSwapchain swapchain, const XrSwapchainImageReleaseInfo*) {
    LOCK;
    GET_SWAPCHAIN(swapchain);
    if (sc->acquired.empty() || !sc->waited) return XR_ERROR_CALL_ORDER_INVALID;
    sc->lastReleased = int(sc->acquired.front());
    sc->acquired.pop_front();
    sc->waited = false;
    if (sc->session->gfx == Gfx::GLES) glFlush();
    return XR_SUCCESS;
}

// ======================================================================= frames ==

static XrResult XRAPI_CALL xrWaitFrame(XrSession session, const XrFrameWaitInfo*, XrFrameState* fs) {
    int64_t target, period;
    {
        LOCK;
        GET_SESSION(session);
        if (!fs) return XR_ERROR_VALIDATION_FAILURE;
        if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
        int64_t now = nowNs();
        period = s->period;
        if (s->nextFrame == 0 || s->nextFrame < now - period) s->nextFrame = now;
        target = s->nextFrame;
        s->nextFrame = target + period;
        fs->predictedDisplayPeriod = period;
        fs->predictedDisplayTime = target + period;
        fs->shouldRender = (s->state == XR_SESSION_STATE_VISIBLE || s->state == XR_SESSION_STATE_FOCUSED) ? XR_TRUE : XR_FALSE;
        s->lastWaitedDisplayTime = fs->predictedDisplayTime;
    }
    int64_t wait = target - nowNs();
    if (wait > 0) std::this_thread::sleep_for(std::chrono::nanoseconds(wait));
    return XR_SUCCESS;
}

static XrResult XRAPI_CALL xrBeginFrame(XrSession session, const XrFrameBeginInfo*) {
    LOCK;
    GET_SESSION(session);
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (s->frameBegun) return XR_FRAME_DISCARDED;
    s->frameBegun = true;
    return XR_SUCCESS;
}

static void cornersFull(DrawItem& d) {
    const float c[4][3] = {{-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}};
    memcpy(d.clip, c, sizeof c);
}

// Texture coordinates for an image rect. Vulkan images are stored top row first, so their
// rows are flipped relative to GL's bottom-up convention.
static void rectUv(DrawItem& d, const Swapchain* sc, const XrRect2Di& r) {
    float W = float(sc->info.width), H = float(sc->info.height);
    float u0 = r.offset.x / W, u1 = (r.offset.x + r.extent.width) / W;
    float v0 = r.offset.y / H, v1 = (r.offset.y + r.extent.height) / H;
    bool flip = sc->session->gfx == Gfx::Vulkan;
    float bottom = flip ? v1 : v0, top = flip ? v0 : v1;
    const float uv[4][2] = {{u0, bottom}, {u1, bottom}, {u1, top}, {u0, top}};
    memcpy(d.uv, uv, sizeof uv);
}

static bool textureFor(Swapchain* sc, uint32_t layer, DrawItem& d) {
    if (sc->lastReleased < 0 || sc->depth) return false;
    if (layer >= sc->info.arraySize) layer = 0;
    if (sc->session->gfx == Gfx::GLES) {
        d.tex = sc->glTex[sc->lastReleased];
        d.target = sc->glTarget;
        d.layer = float(layer);
        d.encode = sc->srgb;  // sampling decodes sRGB; the window expects encoded values
        d.swizzle = false;
    } else {
        if (!vkBridgeTexture(sc, layer, &d.tex)) return false;
        d.target = GL_TEXTURE_2D;
        d.layer = 0;
        d.encode = false;  // bridges hold the image's bytes unchanged
        d.swizzle = sc->bgra;
    }
    return true;
}

static void composeFrame(Session* s, const XrFrameEndInfo* info) {
    DeviceInput in = inputSnapshot();
    int eyes = in.stereo > 0.5f ? 2 : 1;

    // Vulkan images first have to be copied somewhere GL can read them.
    if (s->gfx == Gfx::Vulkan) {
        std::vector<std::pair<Swapchain*, uint32_t>> used;
        for (uint32_t i = 0; i < info->layerCount; i++) {
            const XrCompositionLayerBaseHeader* l = info->layers[i];
            if (!l) continue;
            if (l->type == XR_TYPE_COMPOSITION_LAYER_PROJECTION) {
                auto* p = reinterpret_cast<const XrCompositionLayerProjection*>(l);
                for (int e = 0; e < eyes && e < int(p->viewCount); e++) {
                    auto* sc = reinterpret_cast<Swapchain*>(p->views[e].subImage.swapchain);
                    used.push_back({sc, p->views[e].subImage.imageArrayIndex});
                }
            } else if (l->type == XR_TYPE_COMPOSITION_LAYER_QUAD) {
                auto* q = reinterpret_cast<const XrCompositionLayerQuad*>(l);
                used.push_back({reinterpret_cast<Swapchain*>(q->subImage.swapchain), q->subImage.imageArrayIndex});
            }
        }
        vkPrepareForCompositing(s, used);
    }

    if (!compositorBegin(s)) return;
    XrPosef head = headPose(in);
    for (uint32_t i = 0; i < info->layerCount; i++) {
        const XrCompositionLayerBaseHeader* l = info->layers[i];
        if (!l) continue;
        bool blend = (l->layerFlags & XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT) != 0;
        bool premul = (l->layerFlags & XR_COMPOSITION_LAYER_UNPREMULTIPLIED_ALPHA_BIT) == 0;
        if (l->type == XR_TYPE_COMPOSITION_LAYER_PROJECTION) {
            auto* p = reinterpret_cast<const XrCompositionLayerProjection*>(l);
            for (int e = 0; e < eyes && p->viewCount > 0; e++) {
                const XrCompositionLayerProjectionView& v = p->views[std::min<uint32_t>(e, p->viewCount - 1)];
                auto* sc = reinterpret_cast<Swapchain*>(v.subImage.swapchain);
                if (!sc || sc->magic != 0x4c58535750434841ull) continue;
                DrawItem d;
                if (!textureFor(sc, v.subImage.imageArrayIndex, d)) continue;
                d.eye = e;
                d.blend = blend && i > 0;
                d.premultiplied = premul;
                cornersFull(d);
                rectUv(d, sc, v.subImage.imageRect);
                compositorDraw(d);
            }
        } else if (l->type == XR_TYPE_COMPOSITION_LAYER_QUAD) {
            auto* q = reinterpret_cast<const XrCompositionLayerQuad*>(l);
            auto* sc = reinterpret_cast<Swapchain*>(q->subImage.swapchain);
            auto* sp = reinterpret_cast<Space*>(q->space);
            if (!sc || sc->magic != 0x4c58535750434841ull || !sp || sp->magic != 0x4c58535041434500ull) continue;
            XrPosef spacePose;
            if (!locateInStage(sp, in, &spacePose)) continue;
            XrPosef quadPose = poseMul(spacePose, q->pose);
            for (int e = 0; e < eyes; e++) {
                if (eyes == 2 && q->eyeVisibility == XR_EYE_VISIBILITY_LEFT && e == 1) continue;
                if (eyes == 2 && q->eyeVisibility == XR_EYE_VISIBILITY_RIGHT && e == 0) continue;
                XrPosef eyeInHead;
                XrFovf fov;
                eyeSetup(in, e, &eyeInHead, &fov);
                XrPosef toEye = poseMul(poseInv(poseMul(head, eyeInHead)), quadPose);
                float tl = tanf(fov.angleLeft), tr = tanf(fov.angleRight), td = tanf(fov.angleDown), tu = tanf(fov.angleUp);
                const float sx[4] = {-0.5f, 0.5f, 0.5f, -0.5f}, sy[4] = {-0.5f, -0.5f, 0.5f, 0.5f};
                DrawItem d;
                if (!textureFor(sc, q->subImage.imageArrayIndex, d)) break;
                bool ok = true;
                for (int c = 0; c < 4; c++) {
                    XrVector3f pc = quatRotate(toEye.orientation, {sx[c] * q->size.width, sy[c] * q->size.height, 0});
                    float x = pc.x + toEye.position.x, y = pc.y + toEye.position.y, z = pc.z + toEye.position.z;
                    float w = -z;
                    if (w < 0.01f) { ok = false; break; }
                    float ndcX = (2 * (x / w) - (tr + tl)) / (tr - tl);
                    float ndcY = (2 * (y / w) - (tu + td)) / (tu - td);
                    d.clip[c][0] = ndcX * w;
                    d.clip[c][1] = ndcY * w;
                    d.clip[c][2] = w;
                }
                if (!ok) continue;
                d.eye = e;
                d.blend = blend;
                d.premultiplied = premul;
                rectUv(d, sc, q->subImage.imageRect);
                compositorDraw(d);
            }
        }
    }
    compositorEnd(s);
}

static XrResult XRAPI_CALL xrEndFrame(XrSession session, const XrFrameEndInfo* info) {
    LOCK;
    GET_SESSION(session);
    if (!info) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (!s->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    s->frameBegun = false;
    if (info->displayTime <= 0) return XR_ERROR_TIME_INVALID;
    if (info->environmentBlendMode != XR_ENVIRONMENT_BLEND_MODE_OPAQUE) return XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED;
    if (info->layerCount > 16) return XR_ERROR_LAYER_LIMIT_EXCEEDED;
    for (uint32_t i = 0; i < info->layerCount; i++) {
        if (!info->layers[i]) return XR_ERROR_LAYER_INVALID;
    }
    composeFrame(s, info);
    return XR_SUCCESS;
}

// ================================================================ small extensions ==

static XrResult XRAPI_CALL xrConvertTimespecTimeToTimeKHR(XrInstance instance, const struct timespec* ts, XrTime* t) {
    LOCK;
    GET_INSTANCE(instance);
    *t = int64_t(ts->tv_sec) * 1000000000ll + ts->tv_nsec;
    return XR_SUCCESS;
}
static XrResult XRAPI_CALL xrConvertTimeToTimespecTimeKHR(XrInstance instance, XrTime t, struct timespec* ts) {
    LOCK;
    GET_INSTANCE(instance);
    ts->tv_sec = t / 1000000000ll;
    ts->tv_nsec = t % 1000000000ll;
    return XR_SUCCESS;
}
static XrResult XRAPI_CALL xrSetAndroidApplicationThreadKHR(XrSession session, XrAndroidThreadTypeKHR, uint32_t) {
    LOCK;
    GET_SESSION(session);
    (void)s;
    return XR_SUCCESS;
}
static XrResult XRAPI_CALL xrPerfSettingsSetPerformanceLevelEXT(XrSession session, XrPerfSettingsDomainEXT, XrPerfSettingsLevelEXT) {
    LOCK;
    GET_SESSION(session);
    (void)s;
    return XR_SUCCESS;
}
static XrResult XRAPI_CALL xrEnumerateDisplayRefreshRatesFB(XrSession session, uint32_t cap, uint32_t* count, float* rates) {
    LOCK;
    GET_SESSION(session);
    return fillArray<float>(cap, count, rates, {60.f, 72.f, 90.f});
}
static XrResult XRAPI_CALL xrGetDisplayRefreshRateFB(XrSession session, float* rate) {
    LOCK;
    GET_SESSION(session);
    *rate = s->refreshRate;
    return XR_SUCCESS;
}
static XrResult XRAPI_CALL xrRequestDisplayRefreshRateFB(XrSession session, float rate) {
    LOCK;
    GET_SESSION(session);
    if (rate == 0) rate = 60.f;
    if (rate != 60.f && rate != 72.f && rate != 90.f) return XR_ERROR_DISPLAY_REFRESH_RATE_UNSUPPORTED_FB;
    float old = s->refreshRate;
    s->refreshRate = rate;
    s->period = int64_t(1e9 / rate);
    if (old != rate) {
        XrEventDataDisplayRefreshRateChangedFB e{XR_TYPE_EVENT_DATA_DISPLAY_REFRESH_RATE_CHANGED_FB};
        e.fromDisplayRefreshRate = old;
        e.toDisplayRefreshRate = rate;
        queueEvent(s->inst, &e, sizeof e);
    }
    return XR_SUCCESS;
}
static XrResult XRAPI_CALL xrEnumerateColorSpacesFB(XrSession session, uint32_t cap, uint32_t* count, XrColorSpaceFB* spaces) {
    LOCK;
    GET_SESSION(session);
    return fillArray<XrColorSpaceFB>(cap, count, spaces, {XR_COLOR_SPACE_REC709_FB, XR_COLOR_SPACE_RIFT_CV1_FB, XR_COLOR_SPACE_QUEST_FB});
}
static XrResult XRAPI_CALL xrSetColorSpaceFB(XrSession session, XrColorSpaceFB) {
    LOCK;
    GET_SESSION(session);
    return XR_SUCCESS;
}

// =========================================================================== input ==
// Implemented in input.cpp
XrResult XRAPI_CALL lxCreateActionSet(XrInstance, const XrActionSetCreateInfo*, XrActionSet*);
XrResult XRAPI_CALL lxDestroyActionSet(XrActionSet);
XrResult XRAPI_CALL lxCreateAction(XrActionSet, const XrActionCreateInfo*, XrAction*);
XrResult XRAPI_CALL lxDestroyAction(XrAction);
XrResult XRAPI_CALL lxSuggestInteractionProfileBindings(XrInstance, const XrInteractionProfileSuggestedBinding*);
XrResult XRAPI_CALL lxAttachSessionActionSets(XrSession, const XrSessionActionSetsAttachInfo*);
XrResult XRAPI_CALL lxGetCurrentInteractionProfile(XrSession, XrPath, XrInteractionProfileState*);
XrResult XRAPI_CALL lxGetActionStateBoolean(XrSession, const XrActionStateGetInfo*, XrActionStateBoolean*);
XrResult XRAPI_CALL lxGetActionStateFloat(XrSession, const XrActionStateGetInfo*, XrActionStateFloat*);
XrResult XRAPI_CALL lxGetActionStateVector2f(XrSession, const XrActionStateGetInfo*, XrActionStateVector2f*);
XrResult XRAPI_CALL lxGetActionStatePose(XrSession, const XrActionStateGetInfo*, XrActionStatePose*);
XrResult XRAPI_CALL lxSyncActions(XrSession, const XrActionsSyncInfo*);
XrResult XRAPI_CALL lxEnumerateBoundSourcesForAction(XrSession, const XrBoundSourcesForActionEnumerateInfo*, uint32_t, uint32_t*, XrPath*);
XrResult XRAPI_CALL lxGetInputSourceLocalizedName(XrSession, const XrInputSourceLocalizedNameGetInfo*, uint32_t, uint32_t*, char*);
XrResult XRAPI_CALL lxApplyHapticFeedback(XrSession, const XrHapticActionInfo*, const XrHapticBaseHeader*);
XrResult XRAPI_CALL lxStopHapticFeedback(XrSession, const XrHapticActionInfo*);

// ======================================================================== dispatch ==

static XrResult XRAPI_CALL xrGetInstanceProcAddr(XrInstance instance, const char* name, PFN_xrVoidFunction* function);

struct ProcEntry {
    const char* name;
    PFN_xrVoidFunction fn;
    const char* ext;  // required extension, or null for core
};
#define CORE(n) {#n, reinterpret_cast<PFN_xrVoidFunction>(n), nullptr}
#define INPUT(n) {"xr" #n, reinterpret_cast<PFN_xrVoidFunction>(lx##n), nullptr}
#define EXT(n, e) {#n, reinterpret_cast<PFN_xrVoidFunction>(n), e}

static const ProcEntry kProcs[] = {
    CORE(xrGetInstanceProcAddr),
    CORE(xrEnumerateApiLayerProperties),
    CORE(xrEnumerateInstanceExtensionProperties),
    CORE(xrCreateInstance),
    CORE(xrDestroyInstance),
    CORE(xrGetInstanceProperties),
    CORE(xrPollEvent),
    CORE(xrResultToString),
    CORE(xrStructureTypeToString),
    CORE(xrGetSystem),
    CORE(xrGetSystemProperties),
    CORE(xrEnumerateEnvironmentBlendModes),
    CORE(xrCreateSession),
    CORE(xrDestroySession),
    CORE(xrEnumerateReferenceSpaces),
    CORE(xrCreateReferenceSpace),
    CORE(xrGetReferenceSpaceBoundsRect),
    CORE(xrCreateActionSpace),
    CORE(xrLocateSpace),
    CORE(xrDestroySpace),
    CORE(xrEnumerateViewConfigurations),
    CORE(xrGetViewConfigurationProperties),
    CORE(xrEnumerateViewConfigurationViews),
    CORE(xrEnumerateSwapchainFormats),
    CORE(xrCreateSwapchain),
    CORE(xrDestroySwapchain),
    CORE(xrEnumerateSwapchainImages),
    CORE(xrAcquireSwapchainImage),
    CORE(xrWaitSwapchainImage),
    CORE(xrReleaseSwapchainImage),
    CORE(xrBeginSession),
    CORE(xrEndSession),
    CORE(xrRequestExitSession),
    CORE(xrWaitFrame),
    CORE(xrBeginFrame),
    CORE(xrEndFrame),
    CORE(xrLocateViews),
    CORE(xrStringToPath),
    CORE(xrPathToString),
    INPUT(CreateActionSet),
    INPUT(DestroyActionSet),
    INPUT(CreateAction),
    INPUT(DestroyAction),
    INPUT(SuggestInteractionProfileBindings),
    INPUT(AttachSessionActionSets),
    INPUT(GetCurrentInteractionProfile),
    INPUT(GetActionStateBoolean),
    INPUT(GetActionStateFloat),
    INPUT(GetActionStateVector2f),
    INPUT(GetActionStatePose),
    INPUT(SyncActions),
    INPUT(EnumerateBoundSourcesForAction),
    INPUT(GetInputSourceLocalizedName),
    INPUT(ApplyHapticFeedback),
    INPUT(StopHapticFeedback),
    CORE(xrLocateSpaces),  // OpenXR 1.1 core (also XR_KHR_locate_spaces, below)
    {"xrLocateSpacesKHR", reinterpret_cast<PFN_xrVoidFunction>(xrLocateSpaces), XR_KHR_LOCATE_SPACES_EXTENSION_NAME},
    EXT(xrInitializeLoaderKHR, nullptr),
    EXT(xrGetOpenGLESGraphicsRequirementsKHR, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME),
    EXT(xrGetVulkanGraphicsRequirementsKHR, XR_KHR_VULKAN_ENABLE_EXTENSION_NAME),
    EXT(xrGetVulkanInstanceExtensionsKHR, XR_KHR_VULKAN_ENABLE_EXTENSION_NAME),
    EXT(xrGetVulkanDeviceExtensionsKHR, XR_KHR_VULKAN_ENABLE_EXTENSION_NAME),
    EXT(xrGetVulkanGraphicsDeviceKHR, XR_KHR_VULKAN_ENABLE_EXTENSION_NAME),
    {"xrGetVulkanGraphicsRequirements2KHR", reinterpret_cast<PFN_xrVoidFunction>(xrGetVulkanGraphicsRequirementsKHR), XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME},
    EXT(xrCreateVulkanInstanceKHR, XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME),
    EXT(xrCreateVulkanDeviceKHR, XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME),
    EXT(xrGetVulkanGraphicsDevice2KHR, XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME),
    EXT(xrConvertTimespecTimeToTimeKHR, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME),
    EXT(xrConvertTimeToTimespecTimeKHR, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME),
    EXT(xrSetAndroidApplicationThreadKHR, XR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME),
    EXT(xrPerfSettingsSetPerformanceLevelEXT, XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME),
    EXT(xrEnumerateDisplayRefreshRatesFB, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME),
    EXT(xrGetDisplayRefreshRateFB, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME),
    EXT(xrRequestDisplayRefreshRateFB, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME),
    EXT(xrEnumerateColorSpacesFB, XR_FB_COLOR_SPACE_EXTENSION_NAME),
    EXT(xrSetColorSpaceFB, XR_FB_COLOR_SPACE_EXTENSION_NAME),
};

static XrResult XRAPI_CALL xrGetInstanceProcAddr(XrInstance instance, const char* name, PFN_xrVoidFunction* function) {
    if (!name || !function) return XR_ERROR_VALIDATION_FAILURE;
    *function = nullptr;
    if (instance == XR_NULL_HANDLE) {
        static const char* const kPreInstance[] = {"xrEnumerateApiLayerProperties", "xrEnumerateInstanceExtensionProperties",
                                                   "xrCreateInstance", "xrInitializeLoaderKHR", "xrGetInstanceProcAddr"};
        bool ok = false;
        for (auto* n : kPreInstance) ok = ok || strcmp(n, name) == 0;
        if (!ok) return XR_ERROR_HANDLE_INVALID;
    }
    Instance* inst = reinterpret_cast<Instance*>(instance);
    for (auto& e : kProcs) {
        if (strcmp(e.name, name) != 0) continue;
        if (e.ext && inst && !inst->hasExt(e.ext)) return XR_ERROR_FUNCTION_UNSUPPORTED;
        if (strcmp(name, "xrLocateSpaces") == 0 && inst && inst->apiVersion < XR_API_VERSION_1_1)
            return XR_ERROR_FUNCTION_UNSUPPORTED;
        *function = e.fn;
        return XR_SUCCESS;
    }
    return XR_ERROR_FUNCTION_UNSUPPORTED;
}

}  // namespace lx

extern "C" __attribute__((visibility("default"))) XrResult XRAPI_CALL
xrNegotiateLoaderRuntimeInterface(const XrNegotiateLoaderInfo* loaderInfo, XrNegotiateRuntimeRequest* req) {
    if (!loaderInfo || !req || loaderInfo->structType != XR_LOADER_INTERFACE_STRUCT_LOADER_INFO ||
        loaderInfo->structVersion != XR_LOADER_INFO_STRUCT_VERSION || loaderInfo->structSize != sizeof(XrNegotiateLoaderInfo) ||
        req->structType != XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST || req->structVersion != XR_RUNTIME_INFO_STRUCT_VERSION ||
        req->structSize != sizeof(XrNegotiateRuntimeRequest)) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    if (loaderInfo->minInterfaceVersion > 1 || loaderInfo->maxInterfaceVersion < 1) return XR_ERROR_INITIALIZATION_FAILED;
    if (loaderInfo->minApiVersion > XR_API_VERSION_1_1) return XR_ERROR_INITIALIZATION_FAILED;
    req->runtimeInterfaceVersion = 1;
    req->runtimeApiVersion = loaderInfo->maxApiVersion >= XR_API_VERSION_1_1 ? XR_API_VERSION_1_1 : XR_API_VERSION_1_0;
    req->getInstanceProcAddr = lx::xrGetInstanceProcAddr;
    LXLOG("negotiated with the OpenXR loader");
    return XR_SUCCESS;
}
