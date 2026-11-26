// Limosa XR OpenXR runtime: shared declarations.
//
// The runtime is loaded by the standard Khronos OpenXR loader inside a VR game's own process.
// It emulates a two-controller headset: poses and buttons come from the touch overlay
// (platform_android.cpp), and the game's eye images are composited with OpenGL ES onto a
// full-screen window above the game (compositor.cpp).
#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <time.h>

#define XR_USE_PLATFORM_ANDROID 1
#define XR_USE_GRAPHICS_API_OPENGL_ES 1
#define XR_USE_GRAPHICS_API_VULKAN 1
#define XR_USE_TIMESPEC 1
#ifdef __ANDROID__
#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <android/hardware_buffer.h>
#endif
#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <vulkan/vulkan.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <openxr/openxr_loader_negotiation.h>

namespace lx {

// ------------------------------------------------------------------------------ logging --
void log(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
#define LXLOG(...) ::lx::log(__VA_ARGS__)

// ------------------------------------------------------------------------- device input --
// Written by the platform (touch overlay, gyro), read by the runtime every frame.
struct HandInput {
    float pos[3];      // offset from the head, metres (x right, y up, -z forward)
    float pitch, yaw;  // radians
    float buttons[8];  // trigger, squeeze, (touchpad), thumbstick click, A/X, B/Y, thumbrest, menu
    float touched[8];
    float stick[2];    // x right, y down (screen convention)
};
struct DeviceInput {
    float head[4];  // quaternion x y z w
    float eye;      // eye height above the floor
    float stereo;   // 1 = side-by-side
    float ipd;
    float fov;      // vertical field of view, degrees
    float scale;    // render resolution scale
    HandInput hand[2];
};
constexpr int kInputFloats = 9 + 2 * 23;
DeviceInput inputSnapshot();
void inputFromFloats(const float* f, int n);

// Display size (landscape), haptics, exit requests: shared with the platform layer.
extern std::atomic<int> gScreenW, gScreenH;
extern std::atomic<bool> gExitRequested;
void pushHaptic(int hand, float amplitude, float seconds);

// ---------------------------------------------------------------------------- math --
XrPosef poseMul(const XrPosef& a, const XrPosef& b);
XrPosef poseInv(const XrPosef& a);
XrQuaternionf quatMul(const XrQuaternionf& a, const XrQuaternionf& b);
XrQuaternionf quatAxis(float x, float y, float z, float angle);
XrVector3f quatRotate(const XrQuaternionf& q, const XrVector3f& v);
inline XrPosef identityPose() { XrPosef p{}; p.orientation.w = 1; return p; }

int64_t nowNs();

// ------------------------------------------------------------------------- objects --
struct Instance;
struct Session;

struct ActionSet;
struct Action {
    uint64_t magic = 0x4c58414354494f4eull;
    ActionSet* set = nullptr;
    std::string name;
    XrActionType type = XR_ACTION_TYPE_BOOLEAN_INPUT;
    std::vector<XrPath> subactions;  // /user/hand/left and/or right
    // Current state per hand (0 left, 1 right, 2 combined); filled by xrSyncActions.
    struct State {
        float f = 0, x = 0, y = 0;
        bool b = false, active = false, changed = false;
        XrTime lastChange = 0;
    } state[3];
};
struct ActionSet {
    uint64_t magic = 0x4c58414354534554ull;
    Instance* inst = nullptr;
    std::string name;
    uint32_t priority = 0;
    bool attached = false;
    std::vector<Action*> actions;
};

enum class SpaceKind { Reference, Action };
struct Space {
    uint64_t magic = 0x4c58535041434500ull;
    Session* session = nullptr;
    SpaceKind kind = SpaceKind::Reference;
    XrReferenceSpaceType refType = XR_REFERENCE_SPACE_TYPE_STAGE;
    Action* action = nullptr;
    XrPath subaction = XR_NULL_PATH;
    XrPosef offset = identityPose();
};

enum class Gfx { None, GLES, Vulkan };

struct VkFuncs;
struct VkBridge;
struct Swapchain {
    uint64_t magic = 0x4c58535750434841ull;
    Session* session = nullptr;
    XrSwapchainCreateInfo info{};
    uint32_t imageCount = 3;
    std::deque<uint32_t> acquired;
    uint32_t nextIndex = 0;
    int lastReleased = -1;
    bool waited = false;
    bool srgb = false, bgra = false, depth = false;
    // GLES
    std::vector<GLuint> glTex;
    GLenum glTarget = GL_TEXTURE_2D;
    // Vulkan
    std::vector<VkImage> vkImages;
    std::vector<VkDeviceMemory> vkMem;
    std::vector<VkBridge*> bridges;  // one per array layer, for compositing (owned by gfx_vulkan.cpp)
};

struct Session {
    uint64_t magic = 0x4c5853455353494full;
    Instance* inst = nullptr;
    Gfx gfx = Gfx::None;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    bool running = false, exitRequested = false;
    // GLES binding
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLConfig eglConfig = nullptr;
    EGLContext eglContext = EGL_NO_CONTEXT;
    // Vulkan binding
    VkInstance vkInstance = VK_NULL_HANDLE;
    VkPhysicalDevice vkPhys = VK_NULL_HANDLE;
    VkDevice vkDevice = VK_NULL_HANDLE;
    uint32_t vkQueueFamily = 0, vkQueueIndex = 0;
    VkFuncs* vk = nullptr;  // owned by gfx_vulkan.cpp
    // Actions
    std::vector<ActionSet*> attachedSets;
    XrPath profile = XR_NULL_PATH;  // chosen interaction profile
    std::map<Action*, std::vector<XrPath>> bindings;  // action -> bound input paths
    // Frame loop
    int64_t nextFrame = 0;
    int64_t period = 16666667;
    float refreshRate = 60.f;
    bool frameBegun = false;
    int64_t lastWaitedDisplayTime = 0;
    std::vector<Space*> spaces;
    std::vector<Swapchain*> swapchains;
};

struct Instance {
    uint64_t magic = 0x4c58494e5354414eull;
    XrVersion apiVersion = XR_API_VERSION_1_0;
    std::vector<std::string> enabledExtensions;
    bool hasExt(const char* name) const;
    std::vector<std::string> paths{""};  // XrPath -> string (index 0 unused)
    std::map<std::string, XrPath> pathIds;
    // interaction profile -> list of (action, binding path)
    std::map<XrPath, std::vector<std::pair<Action*, XrPath>>> suggested;
    std::vector<ActionSet*> actionSets;
    Session* session = nullptr;
    std::deque<XrEventDataBuffer> events;
    std::string appName;
};

extern std::recursive_mutex gLock;  // guards all runtime state
extern Instance* gInstance;

XrPath pathFor(Instance* inst, const std::string& s);
const std::string& pathString(Instance* inst, XrPath p);
void queueEvent(Instance* inst, const void* event, size_t size);
void setSessionState(Session* s, XrSessionState st);

// Poses in the stage (floor) space at the current moment.
XrPosef headPose(const DeviceInput& in);
XrPosef handPose(const DeviceInput& in, int hand, bool aim);
// Eye poses (in head space) and field of view as shown on screen right now.
void eyeSetup(const DeviceInput& in, int eye, XrPosef* eyeInHead, XrFovf* fov);
bool locateInStage(Space* s, const DeviceInput& in, XrPosef* out);

// Input (input.cpp)
void inputSync(Session* s, const XrActionsSyncInfo* info);
int handOfPath(Instance* inst, XrPath p);  // 0 left, 1 right, -1 other

// Graphics (gfx_gles.cpp, gfx_vulkan.cpp)
XrResult glesCreateSwapchain(Session* s, Swapchain* sc);
void glesDestroySwapchain(Session* s, Swapchain* sc);
std::vector<int64_t> glesFormats();

std::vector<int64_t> vkFormats(Session* s);
XrResult vkInitSession(Session* s);
void vkDestroySession(Session* s);
XrResult vkCreateSwapchainImages(Session* s, Swapchain* sc);
void vkDestroySwapchain(Session* s, Swapchain* sc);
// Copies the given layer of the released image into its bridge so GL can read it.
bool vkPrepareForCompositing(Session* s, const std::vector<std::pair<Swapchain*, uint32_t>>& used);
bool vkBridgeTexture(Swapchain* sc, uint32_t layer, GLuint* tex);  // call with compositor context current

XrResult vkGetInstanceExtensions(std::string* out);
XrResult vkGetDeviceExtensions(std::string* out);
XrResult vkCreateInstanceForApp(const XrVulkanInstanceCreateInfoKHR* info, VkInstance* out, VkResult* vr);
XrResult vkPickPhysicalDevice(VkInstance inst, PFN_vkGetInstanceProcAddr gipa, VkPhysicalDevice* out);
XrResult vkCreateDeviceForApp(const XrVulkanDeviceCreateInfoKHR* info, VkDevice* out, VkResult* vr);

// Compositor (compositor.cpp)
struct DrawItem {
    GLuint tex = 0;
    GLenum target = GL_TEXTURE_2D;
    float layer = 0;
    bool encode = false;   // linear values that must be sRGB-encoded for display
    bool swizzle = false;  // BGRA stored as RGBA
    bool blend = false, premultiplied = true;
    int eye = 0;           // viewport: 0 = left/full, 1 = right
    float clip[4][3];      // corners (bl, br, tr, tl): x, y, w in clip space
    float uv[4][2];
};
bool compositorBegin(Session* s);  // makes the compositor context current; false = nothing to draw on
void compositorDraw(const DrawItem& d);
void compositorEnd(Session* s);
void compositorShutdown();
int compositorGeneration();  // changes whenever the compositor's GL context is recreated
void compositorDeleteLater(GLuint tex, EGLImageKHR image);
void compositorDropSurface();  // platform: window gone (call with the window lock held)

// Platform (platform_android.cpp or the host test build)
void platformInit(JavaVM* vm, jobject activity);
void platformShutdown();
std::mutex& platformWindowMutex();   // held while the compositor draws on the window
void* platformWindow();              // ANativeWindow* on Android; may be null
EGLDisplay platformEglDisplay();     // display for Vulkan games (GL games use their own)
EGLint platformSurfaceType();        // EGL_WINDOW_BIT on Android
EGLSurface platformCreateSurface(EGLDisplay dpy, EGLConfig cfg, void* window);

}  // namespace lx
