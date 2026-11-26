// Desktop test for the Limosa XR OpenXR runtime. Drives it the way a game does, with Vulkan
// and with OpenGL ES, and checks the composited pixels, input actions and session lifecycle.
//
//   cmake -S xr/runtime-tests -B build/rt && cmake --build build/rt && build/rt/test_runtime build/rt/runtime/liblimosaxr_runtime.so
#define XR_USE_PLATFORM_ANDROID 1
#define XR_USE_GRAPHICS_API_OPENGL_ES 1
#define XR_USE_GRAPHICS_API_VULKAN 1
#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <vulkan/vulkan.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <openxr/openxr_loader_negotiation.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <string>
#include <vector>

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define XR(call) do { XrResult r_ = (call); if (XR_FAILED(r_)) { printf("FAIL %s -> %d (line %d)\n", #call, int(r_), __LINE__); exit(1); } } while (0)

static PFN_xrGetInstanceProcAddr gipa;
static void* lib;
template <class T> static T fn(XrInstance inst, const char* name) {
    PFN_xrVoidFunction f = nullptr;
    gipa(inst, name, &f);
    if (!f) { printf("FAIL: no %s\n", name); exit(1); }
    return reinterpret_cast<T>(f);
}
#define F(inst, name) auto name = fn<PFN_##name>(inst, #name)

static bool (*readPixel)(int, int, unsigned char*);
static void (*setInput)(const float*, int);
static int (*hapticCount)();
static EGLDisplay (*testDisplay)();

// Input layout shared with the runtime (see inputFromFloats).
static std::vector<float> input(bool stereo, float rightTrigger = 0, float stickX = 0, float stickY = 0, float aButton = 0) {
    std::vector<float> f(9 + 46, 0.f);
    f[3] = 1; f[4] = 1.6f; f[5] = stereo ? 1.f : 0.f; f[6] = 0.063f; f[7] = 70.f; f[8] = 1.f;
    for (int h = 0; h < 2; h++) {
        float* p = &f[9 + h * 23];
        p[0] = h == 0 ? -0.2f : 0.2f; p[1] = -0.3f; p[2] = -0.45f;
    }
    float* r = &f[9 + 23];
    r[5 + 0] = rightTrigger;
    r[5 + 4] = aButton;
    r[21] = stickX; r[22] = stickY;
    return f;
}

static void pixel(int x, int y, unsigned char* p) {
    memset(p, 0, 4);
    if (!readPixel(x, y, p)) printf("  (no pixel)\n");
}
static bool near(const unsigned char* p, int r, int g, int b) {
    return abs(p[0] - r) < 24 && abs(p[1] - g) < 24 && abs(p[2] - b) < 24;
}

struct Common {
    XrInstance inst = XR_NULL_HANDLE;
    XrSystemId sys = 0;
    XrSession session = XR_NULL_HANDLE;
    XrSpace stage = XR_NULL_HANDLE, view = XR_NULL_HANDLE, rightAim = XR_NULL_HANDLE;
    XrActionSet set = XR_NULL_HANDLE;
    XrAction trigger, grab, move, aButton, aim, haptic;
    XrPath left, right;
};

static XrInstance createInstance(const char* gfxExt) {
    F(XR_NULL_HANDLE, xrCreateInstance);
    F(XR_NULL_HANDLE, xrEnumerateInstanceExtensionProperties);
    uint32_t n = 0;
    XR(xrEnumerateInstanceExtensionProperties(nullptr, 0, &n, nullptr));
    std::vector<XrExtensionProperties> props(n, {XR_TYPE_EXTENSION_PROPERTIES});
    XR(xrEnumerateInstanceExtensionProperties(nullptr, n, &n, props.data()));
    bool found = false;
    for (auto& p : props) found = found || strcmp(p.extensionName, gfxExt) == 0;
    CHECK(found, "extension %s not offered", gfxExt);
    const char* exts[] = {gfxExt, "XR_KHR_android_create_instance"};
    XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = &android;
    strcpy(ci.applicationInfo.applicationName, "runtime-test");
    strcpy(ci.applicationInfo.engineName, "none");
    ci.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    ci.enabledExtensionCount = 2;
    ci.enabledExtensionNames = exts;
    XrInstance inst;
    XR(xrCreateInstance(&ci, &inst));
    // Unknown extensions must be refused.
    const char* bad[] = {"XR_FB_passthrough"};
    ci.enabledExtensionNames = bad;
    ci.enabledExtensionCount = 1;
    XrInstance inst2;
    CHECK(xrCreateInstance(&ci, &inst2) == XR_ERROR_EXTENSION_NOT_PRESENT, "unsupported extension accepted");
    return inst;
}

static void setupCommon(Common& c) {
    F(c.inst, xrStringToPath);
    F(c.inst, xrCreateActionSet);
    F(c.inst, xrCreateAction);
    F(c.inst, xrSuggestInteractionProfileBindings);
    F(c.inst, xrAttachSessionActionSets);
    F(c.inst, xrCreateReferenceSpace);
    F(c.inst, xrCreateActionSpace);
    XR(xrStringToPath(c.inst, "/user/hand/left", &c.left));
    XR(xrStringToPath(c.inst, "/user/hand/right", &c.right));
    XrActionSetCreateInfo sci{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(sci.actionSetName, "gameplay");
    strcpy(sci.localizedActionSetName, "Gameplay");
    XR(xrCreateActionSet(c.inst, &sci, &c.set));
    XrPath hands[2] = {c.left, c.right};
    auto mk = [&](const char* name, XrActionType t, XrAction* out) {
        XrActionCreateInfo ai{XR_TYPE_ACTION_CREATE_INFO};
        strcpy(ai.actionName, name);
        strcpy(ai.localizedActionName, name);
        ai.actionType = t;
        ai.countSubactionPaths = 2;
        ai.subactionPaths = hands;
        XR(xrCreateAction(c.set, &ai, out));
    };
    mk("trigger", XR_ACTION_TYPE_FLOAT_INPUT, &c.trigger);
    mk("grab", XR_ACTION_TYPE_BOOLEAN_INPUT, &c.grab);
    mk("move", XR_ACTION_TYPE_VECTOR2F_INPUT, &c.move);
    mk("a_button", XR_ACTION_TYPE_BOOLEAN_INPUT, &c.aButton);
    mk("aim", XR_ACTION_TYPE_POSE_INPUT, &c.aim);
    mk("haptic", XR_ACTION_TYPE_VIBRATION_OUTPUT, &c.haptic);
    auto path = [&](const char* s) { XrPath p; XR(xrStringToPath(c.inst, s, &p)); return p; };
    std::vector<XrActionSuggestedBinding> touch = {
        {c.trigger, path("/user/hand/left/input/trigger/value")}, {c.trigger, path("/user/hand/right/input/trigger/value")},
        {c.grab, path("/user/hand/right/input/trigger")},  // bool from a float source
        {c.move, path("/user/hand/left/input/thumbstick")}, {c.move, path("/user/hand/right/input/thumbstick")},
        {c.aButton, path("/user/hand/right/input/a/click")},
        {c.aim, path("/user/hand/left/input/aim/pose")}, {c.aim, path("/user/hand/right/input/aim/pose")},
        {c.haptic, path("/user/hand/right/output/haptic")},
    };
    XrInteractionProfileSuggestedBinding sb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    sb.interactionProfile = path("/interaction_profiles/oculus/touch_controller");
    sb.countSuggestedBindings = uint32_t(touch.size());
    sb.suggestedBindings = touch.data();
    XR(xrSuggestInteractionProfileBindings(c.inst, &sb));
    std::vector<XrActionSuggestedBinding> simple = {{c.trigger, path("/user/hand/right/input/select/click")}};
    sb.interactionProfile = path("/interaction_profiles/khr/simple_controller");
    sb.countSuggestedBindings = 1;
    sb.suggestedBindings = simple.data();
    XR(xrSuggestInteractionProfileBindings(c.inst, &sb));

    XrSessionActionSetsAttachInfo at{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    at.countActionSets = 1;
    at.actionSets = &c.set;
    XR(xrAttachSessionActionSets(c.session, &at));

    XrReferenceSpaceCreateInfo rci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rci.poseInReferenceSpace.orientation.w = 1;
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
    XR(xrCreateReferenceSpace(c.session, &rci, &c.stage));
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    XR(xrCreateReferenceSpace(c.session, &rci, &c.view));
    XrActionSpaceCreateInfo asci{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    asci.action = c.aim;
    asci.subactionPath = c.right;
    asci.poseInActionSpace.orientation.w = 1;
    XR(xrCreateActionSpace(c.session, &asci, &c.rightAim));
}

static void waitForState(Common& c, XrSessionState want) {
    F(c.inst, xrPollEvent);
    for (int i = 0; i < 50; i++) {
        XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
        if (xrPollEvent(c.inst, &ev) != XR_SUCCESS) continue;
        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED &&
            reinterpret_cast<XrEventDataSessionStateChanged*>(&ev)->state == want) return;
    }
    CHECK(false, "never reached session state %d", int(want));
}

// Renders one frame. fill(swapchainImageIndex) draws into the eye swapchain.
template <class Fill>
static void frame(Common& c, XrSwapchain eyes, uint32_t w, uint32_t h, Fill fill, XrSwapchain quad = XR_NULL_HANDLE) {
    F(c.inst, xrWaitFrame);
    F(c.inst, xrBeginFrame);
    F(c.inst, xrEndFrame);
    F(c.inst, xrLocateViews);
    F(c.inst, xrAcquireSwapchainImage);
    F(c.inst, xrWaitSwapchainImage);
    F(c.inst, xrReleaseSwapchainImage);
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    XR(xrWaitFrame(c.session, nullptr, &fs));
    XR(xrBeginFrame(c.session, nullptr));
    XrViewLocateInfo vli{XR_TYPE_VIEW_LOCATE_INFO};
    vli.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    vli.displayTime = fs.predictedDisplayTime;
    vli.space = c.stage;
    XrViewState vs{XR_TYPE_VIEW_STATE};
    XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    uint32_t n;
    XR(xrLocateViews(c.session, &vli, &vs, 2, &n, views));
    uint32_t idx;
    XR(xrAcquireSwapchainImage(eyes, nullptr, &idx));
    XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wi.timeout = XR_INFINITE_DURATION;
    XR(xrWaitSwapchainImage(eyes, &wi));
    fill(idx);
    XR(xrReleaseSwapchainImage(eyes, nullptr));
    XrCompositionLayerProjectionView pv[2] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    for (int e = 0; e < 2; e++) {
        pv[e].pose = views[e].pose;
        pv[e].fov = views[e].fov;
        pv[e].subImage.swapchain = eyes;
        pv[e].subImage.imageRect = {{0, 0}, {int32_t(w), int32_t(h)}};
        pv[e].subImage.imageArrayIndex = uint32_t(e);
    }
    XrCompositionLayerProjection proj{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    proj.space = c.stage;
    proj.viewCount = 2;
    proj.views = pv;
    XrCompositionLayerQuad q{XR_TYPE_COMPOSITION_LAYER_QUAD};
    q.space = c.view;
    q.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    q.subImage.swapchain = quad;
    q.subImage.imageRect = {{0, 0}, {16, 16}};
    q.pose.orientation.w = 1;
    q.pose.position.z = -1;
    q.size = {0.3f, 0.3f};
    const XrCompositionLayerBaseHeader* layers[2] = {reinterpret_cast<XrCompositionLayerBaseHeader*>(&proj),
                                                     reinterpret_cast<XrCompositionLayerBaseHeader*>(&q)};
    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.displayTime = fs.predictedDisplayTime;
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    ei.layerCount = quad ? 2 : 1;
    ei.layers = layers;
    XR(xrEndFrame(c.session, &ei));
}

static void checkInput(Common& c) {
    F(c.inst, xrSyncActions);
    F(c.inst, xrGetActionStateFloat);
    F(c.inst, xrGetActionStateBoolean);
    F(c.inst, xrGetActionStateVector2f);
    F(c.inst, xrGetActionStatePose);
    F(c.inst, xrLocateSpace);
    F(c.inst, xrApplyHapticFeedback);
    F(c.inst, xrGetCurrentInteractionProfile);
    F(c.inst, xrPathToString);
    auto in = input(false, 1.0f, 0.5f, -1.0f, 1.0f);  // stick pushed forward (screen up)
    setInput(in.data(), int(in.size()));
    XrActiveActionSet aas{c.set, XR_NULL_PATH};
    XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO};
    si.countActiveActionSets = 1;
    si.activeActionSets = &aas;
    XR(xrSyncActions(c.session, &si));
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = c.trigger;
    gi.subactionPath = c.right;
    XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
    XR(xrGetActionStateFloat(c.session, &gi, &f));
    CHECK(f.isActive && fabsf(f.currentState - 1) < 1e-3f, "right trigger %f active %d", f.currentState, f.isActive);
    gi.subactionPath = c.left;
    XR(xrGetActionStateFloat(c.session, &gi, &f));
    CHECK(f.isActive && f.currentState == 0, "left trigger %f", f.currentState);
    gi.action = c.grab;
    gi.subactionPath = XR_NULL_PATH;
    XrActionStateBoolean b{XR_TYPE_ACTION_STATE_BOOLEAN};
    XR(xrGetActionStateBoolean(c.session, &gi, &b));
    CHECK(b.currentState && b.isActive, "grab (trigger as bool)");
    gi.action = c.aButton;
    XR(xrGetActionStateBoolean(c.session, &gi, &b));
    CHECK(b.currentState, "A button");
    gi.action = c.move;
    gi.subactionPath = c.right;
    XrActionStateVector2f v{XR_TYPE_ACTION_STATE_VECTOR2F};
    XR(xrGetActionStateVector2f(c.session, &gi, &v));
    CHECK(fabsf(v.currentState.x - 0.5f) < 1e-3f && fabsf(v.currentState.y - 1.0f) < 1e-3f, "stick %f %f", v.currentState.x, v.currentState.y);
    gi.action = c.trigger;
    XrActionStateBoolean wrong{XR_TYPE_ACTION_STATE_BOOLEAN};
    CHECK(xrGetActionStateBoolean(c.session, &gi, &wrong) == XR_ERROR_ACTION_TYPE_MISMATCH, "type mismatch not reported");
    gi.action = c.aim;
    XrActionStatePose ps{XR_TYPE_ACTION_STATE_POSE};
    XR(xrGetActionStatePose(c.session, &gi, &ps));
    CHECK(ps.isActive, "aim pose inactive");
    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    XR(xrLocateSpace(c.rightAim, c.stage, 1, &loc));
    CHECK((loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) && fabsf(loc.pose.position.x - 0.2f) < 1e-3f &&
              fabsf(loc.pose.position.y - 1.3f) < 1e-3f && fabsf(loc.pose.position.z + 0.45f) < 1e-3f,
          "right aim at %f %f %f", loc.pose.position.x, loc.pose.position.y, loc.pose.position.z);
    XrInteractionProfileState ip{XR_TYPE_INTERACTION_PROFILE_STATE};
    XR(xrGetCurrentInteractionProfile(c.session, c.right, &ip));
    char buf[256];
    uint32_t len;
    XR(xrPathToString(c.inst, ip.interactionProfile, sizeof buf, &len, buf));
    CHECK(strcmp(buf, "/interaction_profiles/oculus/touch_controller") == 0, "profile %s", buf);
    XrHapticVibration vib{XR_TYPE_HAPTIC_VIBRATION};
    vib.amplitude = 0.8f;
    vib.duration = 50000000;
    XrHapticActionInfo hi{XR_TYPE_HAPTIC_ACTION_INFO};
    hi.action = c.haptic;
    int before = hapticCount();
    XR(xrApplyHapticFeedback(c.session, &hi, reinterpret_cast<XrHapticBaseHeader*>(&vib)));
    CHECK(hapticCount() == before + 1, "haptic not delivered");
    auto rest = input(false);
    setInput(rest.data(), int(rest.size()));
}

static void endSession(Common& c) {
    F(c.inst, xrRequestExitSession);
    F(c.inst, xrEndSession);
    F(c.inst, xrDestroySession);
    F(c.inst, xrDestroyInstance);
    XR(xrRequestExitSession(c.session));
    waitForState(c, XR_SESSION_STATE_STOPPING);
    XR(xrEndSession(c.session));
    waitForState(c, XR_SESSION_STATE_EXITING);
    XR(xrDestroySession(c.session));
    XR(xrDestroyInstance(c.inst));
}

// Checks the screen: top half / bottom half colours of each eye's area.
static void checkScreen(const char* what, bool stereo, const int top[2][3], const int bottom[2][3]) {
    for (int e = 0; e < (stereo ? 2 : 1); e++) {
        int x = stereo ? (e == 0 ? 200 : 600) : 400;
        unsigned char p[4];
        pixel(x, 300, p);  // GL origin is bottom-left: y=300 is the upper half
        CHECK(near(p, top[e][0], top[e][1], top[e][2]), "%s eye %d top = %d %d %d", what, e, p[0], p[1], p[2]);
        pixel(x, 100, p);
        CHECK(near(p, bottom[e][0], bottom[e][1], bottom[e][2]), "%s eye %d bottom = %d %d %d", what, e, p[0], p[1], p[2]);
    }
}

// --------------------------------------------------------------------------- Vulkan --
static void testVulkan() {
    printf("== Vulkan\n");
    Common c;
    c.inst = createInstance(XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME);
    F(c.inst, xrGetSystem);
    F(c.inst, xrEnumerateViewConfigurationViews);
    F(c.inst, xrGetVulkanGraphicsRequirements2KHR);
    F(c.inst, xrCreateVulkanInstanceKHR);
    F(c.inst, xrGetVulkanGraphicsDevice2KHR);
    F(c.inst, xrCreateVulkanDeviceKHR);
    F(c.inst, xrCreateSession);
    F(c.inst, xrBeginSession);
    F(c.inst, xrCreateSwapchain);
    F(c.inst, xrEnumerateSwapchainImages);
    F(c.inst, xrEnumerateSwapchainFormats);
    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XR(xrGetSystem(c.inst, &sgi, &c.sys));
    XrViewConfigurationView vcv[2] = {{XR_TYPE_VIEW_CONFIGURATION_VIEW}, {XR_TYPE_VIEW_CONFIGURATION_VIEW}};
    uint32_t n;
    XR(xrEnumerateViewConfigurationViews(c.inst, c.sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 2, &n, vcv));
    uint32_t W = vcv[0].recommendedImageRectWidth, H = vcv[0].recommendedImageRectHeight;
    printf("  eye images %ux%u\n", W, H);
    XrGraphicsRequirementsVulkanKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN_KHR};
    XR(xrGetVulkanGraphicsRequirements2KHR(c.inst, c.sys, &req));

    auto vkGipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(dlopen("libvulkan.so.1", RTLD_NOW), "vkGetInstanceProcAddr"));
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    XrVulkanInstanceCreateInfoKHR xvi{XR_TYPE_VULKAN_INSTANCE_CREATE_INFO_KHR};
    xvi.systemId = c.sys;
    xvi.pfnGetInstanceProcAddr = vkGipa;
    xvi.vulkanCreateInfo = &ici;
    VkInstance vki;
    VkResult vr;
    XR(xrCreateVulkanInstanceKHR(c.inst, &xvi, &vki, &vr));
    CHECK(vr == VK_SUCCESS, "vkCreateInstance %d", vr);
    XrVulkanGraphicsDeviceGetInfoKHR gdi{XR_TYPE_VULKAN_GRAPHICS_DEVICE_GET_INFO_KHR};
    gdi.systemId = c.sys;
    gdi.vulkanInstance = vki;
    VkPhysicalDevice phys;
    XR(xrGetVulkanGraphicsDevice2KHR(c.inst, &gdi, &phys));
    auto getQ = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(vkGipa(vki, "vkGetPhysicalDeviceQueueFamilyProperties"));
    uint32_t qn = 0;
    getQ(phys, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    getQ(phys, &qn, qf.data());
    uint32_t family = 0;
    for (uint32_t i = 0; i < qn; i++) if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { family = i; break; }
    float prio = 1;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    XrVulkanDeviceCreateInfoKHR xdi{XR_TYPE_VULKAN_DEVICE_CREATE_INFO_KHR};
    xdi.systemId = c.sys;
    xdi.pfnGetInstanceProcAddr = vkGipa;
    xdi.vulkanPhysicalDevice = phys;
    xdi.vulkanCreateInfo = &dci;
    VkDevice dev;
    XR(xrCreateVulkanDeviceKHR(c.inst, &xdi, &dev, &vr));
    CHECK(vr == VK_SUCCESS, "vkCreateDevice %d", vr);
    auto gdpa = reinterpret_cast<PFN_vkGetDeviceProcAddr>(vkGipa(vki, "vkGetDeviceProcAddr"));
#define DF(name) auto name = reinterpret_cast<PFN_##name>(gdpa(dev, #name))
    DF(vkGetDeviceQueue); DF(vkCreateCommandPool); DF(vkAllocateCommandBuffers); DF(vkBeginCommandBuffer); DF(vkEndCommandBuffer);
    DF(vkCmdPipelineBarrier); DF(vkCmdCopyBufferToImage); DF(vkCmdClearColorImage); DF(vkQueueSubmit); DF(vkQueueWaitIdle);
    DF(vkCreateBuffer); DF(vkGetBufferMemoryRequirements); DF(vkAllocateMemory); DF(vkBindBufferMemory); DF(vkMapMemory);
    VkQueue queue;
    vkGetDeviceQueue(dev, family, 0, &queue);

    XrGraphicsBindingVulkanKHR bind{XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR};
    bind.instance = vki;
    bind.physicalDevice = phys;
    bind.device = dev;
    bind.queueFamilyIndex = family;
    bind.queueIndex = 0;
    XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO};
    sci.next = &bind;
    sci.systemId = c.sys;
    XR(xrCreateSession(c.inst, &sci, &c.session));
    setupCommon(c);
    waitForState(c, XR_SESSION_STATE_READY);
    XrSessionBeginInfo sbi{XR_TYPE_SESSION_BEGIN_INFO};
    sbi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    XR(xrBeginSession(c.session, &sbi));

    int64_t fmts[16];
    XR(xrEnumerateSwapchainFormats(c.session, 16, &n, fmts));
    CHECK(n > 0 && fmts[0] == VK_FORMAT_R8G8B8A8_SRGB, "first format %lld", (long long)fmts[0]);
    XrSwapchainCreateInfo sw{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    sw.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT;
    sw.format = VK_FORMAT_R8G8B8A8_SRGB;
    sw.sampleCount = 1;
    sw.width = W;
    sw.height = H;
    sw.faceCount = 1;
    sw.arraySize = 2;
    sw.mipCount = 1;
    XrSwapchain eyes;
    XR(xrCreateSwapchain(c.session, &sw, &eyes));
    XR(xrEnumerateSwapchainImages(eyes, 0, &n, nullptr));
    std::vector<XrSwapchainImageVulkanKHR> imgs(n, {XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR});
    XR(xrEnumerateSwapchainImages(eyes, n, &n, reinterpret_cast<XrSwapchainImageBaseHeader*>(imgs.data())));
    sw.width = sw.height = 16;
    sw.arraySize = 1;
    XrSwapchain quad;
    XR(xrCreateSwapchain(c.session, &sw, &quad));
    uint32_t qnImgs;
    XR(xrEnumerateSwapchainImages(quad, 0, &qnImgs, nullptr));
    std::vector<XrSwapchainImageVulkanKHR> qimgs(qnImgs, {XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR});
    XR(xrEnumerateSwapchainImages(quad, qnImgs, &qnImgs, reinterpret_cast<XrSwapchainImageBaseHeader*>(qimgs.data())));

    // Staging buffer: per layer, top half one colour and bottom half another (rows top-first).
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = VkDeviceSize(W) * H * 4 * 2;
    bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    VkBuffer staging;
    vkCreateBuffer(dev, &bci, nullptr, &staging);
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(dev, staging, &mr);
    auto memProps = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(vkGipa(vki, "vkGetPhysicalDeviceMemoryProperties"));
    VkPhysicalDeviceMemoryProperties mp;
    memProps(phys, &mp);
    uint32_t type = 0;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((mr.memoryTypeBits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                                                   (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { type = i; break; }
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = mr.size;
    mai.memoryTypeIndex = type;
    VkDeviceMemory smem;
    vkAllocateMemory(dev, &mai, nullptr, &smem);
    vkBindBufferMemory(dev, staging, smem, 0);
    unsigned char* px;
    vkMapMemory(dev, smem, 0, VK_WHOLE_SIZE, 0, reinterpret_cast<void**>(&px));
    const unsigned char colors[2][2][4] = {{{255, 0, 0, 255}, {0, 0, 255, 255}},    // left eye: red top, blue bottom
                                           {{0, 255, 0, 255}, {255, 255, 0, 255}}};  // right eye: green top, yellow bottom
    for (int l = 0; l < 2; l++)
        for (uint32_t y = 0; y < H; y++)
            for (uint32_t x = 0; x < W; x++) memcpy(px + ((size_t(l) * H + y) * W + x) * 4, colors[l][y < H / 2 ? 0 : 1], 4);

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.queueFamilyIndex = family;
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    VkCommandPool pool;
    vkCreateCommandPool(dev, &pci, nullptr, &pool);
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = pool;
    cai.commandBufferCount = 1;
    VkCommandBuffer cmd;
    vkAllocateCommandBuffers(dev, &cai, &cmd);
    auto barrier = [&](VkImage img, uint32_t layers, VkImageLayout a, VkImageLayout b) {
        VkImageMemoryBarrier ib{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        ib.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        ib.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        ib.oldLayout = a;
        ib.newLayout = b;
        ib.srcQueueFamilyIndex = ib.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ib.image = img;
        ib.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, layers};
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &ib);
    };
    auto submit = [&] {
        vkEndCommandBuffer(cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkQueueSubmit(queue, 1, &si, VK_NULL_HANDLE);
        vkQueueWaitIdle(queue);
    };
    // Fill every quad image with cyan once.
    {
        VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        vkBeginCommandBuffer(cmd, &cbi);
        for (auto& qi : qimgs) {
            barrier(qi.image, 1, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkClearColorValue cyan = {{0, 1, 1, 1}};
            VkImageSubresourceRange rr{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdClearColorImage(cmd, qi.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &cyan, 1, &rr);
            barrier(qi.image, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        submit();
    }
    auto fill = [&](uint32_t idx) {
        VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        vkBeginCommandBuffer(cmd, &cbi);
        barrier(imgs[idx].image, 2, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkBufferImageCopy regions[2] = {};
        for (int l = 0; l < 2; l++) {
            regions[l].bufferOffset = VkDeviceSize(l) * W * H * 4;
            regions[l].imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, uint32_t(l), 1};
            regions[l].imageExtent = {W, H, 1};
        }
        vkCmdCopyBufferToImage(cmd, staging, imgs[idx].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 2, regions);
        barrier(imgs[idx].image, 2, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        submit();
    };
    // The quad swapchain needs one release before it can be shown.
    {
        F(c.inst, xrAcquireSwapchainImage);
        F(c.inst, xrWaitSwapchainImage);
        F(c.inst, xrReleaseSwapchainImage);
        uint32_t qi;
        XR(xrAcquireSwapchainImage(quad, nullptr, &qi));
        XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        wi.timeout = XR_INFINITE_DURATION;
        XR(xrWaitSwapchainImage(quad, &wi));
        XR(xrReleaseSwapchainImage(quad, nullptr));
    }

    auto flat = input(false);
    setInput(flat.data(), int(flat.size()));
    for (int i = 0; i < 3; i++) frame(c, eyes, W, H, fill);
    const int topF[2][3] = {{255, 0, 0}, {255, 0, 0}}, botF[2][3] = {{0, 0, 255}, {0, 0, 255}};
    checkScreen("vulkan flat", false, topF, botF);

    auto st = input(true);
    setInput(st.data(), int(st.size()));
    frame(c, eyes, W, H, fill);
    const int topS[2][3] = {{255, 0, 0}, {0, 255, 0}}, botS[2][3] = {{0, 0, 255}, {255, 255, 0}};
    checkScreen("vulkan stereo", true, topS, botS);

    setInput(flat.data(), int(flat.size()));
    frame(c, eyes, W, H, fill, quad);
    unsigned char p[4];
    pixel(400, 200, p);
    CHECK(near(p, 0, 255, 255), "quad layer centre = %d %d %d", p[0], p[1], p[2]);
    pixel(400, 380, p);
    CHECK(near(p, 255, 0, 0), "outside the quad = %d %d %d", p[0], p[1], p[2]);

    checkInput(c);
    endSession(c);
}

// --------------------------------------------------------------------------- GL ES --
static void testGles() {
    printf("== OpenGL ES\n");
    EGLDisplay dpy = testDisplay();
    eglInitialize(dpy, nullptr, nullptr);
    const EGLint attrs[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint nc;
    eglChooseConfig(dpy, attrs, &cfg, 1, &nc);
    const EGLint ca[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ca);
    CHECK(ctx != EGL_NO_CONTEXT, "game GL context");
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx);

    Common c;
    c.inst = createInstance(XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME);
    F(c.inst, xrGetSystem);
    F(c.inst, xrGetOpenGLESGraphicsRequirementsKHR);
    F(c.inst, xrCreateSession);
    F(c.inst, xrBeginSession);
    F(c.inst, xrCreateSwapchain);
    F(c.inst, xrEnumerateSwapchainImages);
    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XR(xrGetSystem(c.inst, &sgi, &c.sys));
    XrGraphicsRequirementsOpenGLESKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    XR(xrGetOpenGLESGraphicsRequirementsKHR(c.inst, c.sys, &req));
    XrGraphicsBindingOpenGLESAndroidKHR bind{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    bind.display = dpy;
    bind.config = cfg;
    bind.context = ctx;
    XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO};
    sci.next = &bind;
    sci.systemId = c.sys;
    XR(xrCreateSession(c.inst, &sci, &c.session));
    setupCommon(c);
    waitForState(c, XR_SESSION_STATE_READY);
    XrSessionBeginInfo sbi{XR_TYPE_SESSION_BEGIN_INFO};
    sbi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    XR(xrBeginSession(c.session, &sbi));

    const uint32_t W = 256, H = 128;
    XrSwapchainCreateInfo sw{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    sw.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
    sw.format = GL_SRGB8_ALPHA8;
    sw.sampleCount = 1;
    sw.width = W;
    sw.height = H;
    sw.faceCount = 1;
    sw.arraySize = 2;
    sw.mipCount = 1;
    XrSwapchain eyes;
    XR(xrCreateSwapchain(c.session, &sw, &eyes));
    uint32_t n;
    XR(xrEnumerateSwapchainImages(eyes, 0, &n, nullptr));
    std::vector<XrSwapchainImageOpenGLESKHR> imgs(n, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
    XR(xrEnumerateSwapchainImages(eyes, n, &n, reinterpret_cast<XrSwapchainImageBaseHeader*>(imgs.data())));
    // GL rows start at the bottom: fill the first (bottom) half blue/yellow and the top red/green.
    std::vector<unsigned char> px(W * H * 4);
    const unsigned char colors[2][2][4] = {{{255, 0, 0, 255}, {0, 0, 255, 255}}, {{0, 255, 0, 255}, {255, 255, 0, 255}}};
    auto fill = [&](uint32_t idx) {
        glBindTexture(GL_TEXTURE_2D_ARRAY, imgs[idx].image);
        for (int l = 0; l < 2; l++) {
            for (uint32_t y = 0; y < H; y++)
                for (uint32_t x = 0; x < W; x++) memcpy(&px[(y * W + x) * 4], colors[l][y < H / 2 ? 1 : 0], 4);
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, l, W, H, 1, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
        }
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
    };
    auto flat = input(false);
    setInput(flat.data(), int(flat.size()));
    for (int i = 0; i < 3; i++) frame(c, eyes, W, H, fill);
    CHECK(eglGetCurrentContext() == ctx, "the game's GL context was not restored");
    const int topF[2][3] = {{255, 0, 0}, {255, 0, 0}}, botF[2][3] = {{0, 0, 255}, {0, 0, 255}};
    checkScreen("gles flat", false, topF, botF);
    auto st = input(true);
    setInput(st.data(), int(st.size()));
    frame(c, eyes, W, H, fill);
    const int topS[2][3] = {{255, 0, 0}, {0, 255, 0}}, botS[2][3] = {{0, 0, 255}, {255, 255, 0}};
    checkScreen("gles stereo", true, topS, botS);
    setInput(flat.data(), int(flat.size()));
    checkInput(c);
    endSession(c);
}

int main(int argc, char** argv) {
    if (argc < 2) { printf("usage: test_runtime liblimosaxr_runtime.so\n"); return 2; }
    lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { printf("dlopen: %s\n", dlerror()); return 1; }
    auto negotiate = reinterpret_cast<PFN_xrNegotiateLoaderRuntimeInterface>(dlsym(lib, "xrNegotiateLoaderRuntimeInterface"));
    readPixel = reinterpret_cast<bool (*)(int, int, unsigned char*)>(dlsym(lib, "limosa_test_read_pixel"));
    setInput = reinterpret_cast<void (*)(const float*, int)>(dlsym(lib, "limosa_test_set_input"));
    hapticCount = reinterpret_cast<int (*)()>(dlsym(lib, "limosa_test_haptic_count"));
    testDisplay = reinterpret_cast<EGLDisplay (*)()>(dlsym(lib, "limosa_test_display"));
    if (!negotiate || !readPixel || !setInput || !hapticCount || !testDisplay) { printf("missing exports\n"); return 1; }
    XrNegotiateLoaderInfo li{XR_LOADER_INTERFACE_STRUCT_LOADER_INFO, XR_LOADER_INFO_STRUCT_VERSION, sizeof(XrNegotiateLoaderInfo),
                             1, 1, XR_API_VERSION_1_0, XR_API_VERSION_1_1};
    XrNegotiateRuntimeRequest rr{XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST, XR_RUNTIME_INFO_STRUCT_VERSION, sizeof(XrNegotiateRuntimeRequest)};
    XR(negotiate(&li, &rr));
    gipa = rr.getInstanceProcAddr;
    bool vulkan = !getenv("SKIP_VULKAN"), gles = !getenv("SKIP_GLES");
    if (vulkan) testVulkan();
    if (gles) testGles();
    printf(failures ? "%d FAILURE(S)\n" : "ALL PASSED\n", failures);
    return failures ? 1 : 0;
}
