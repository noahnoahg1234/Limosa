// Limosa XR OpenXR runtime: Vulkan swapchains.
//
// Swapchain images are ordinary images on the game's device. At xrEndFrame the layers being
// shown are copied into "bridges" that the GL compositor can sample:
//  - on Android, images backed by AHardwareBuffers, imported into GL as EGLImages (fast), or
//  - a host-visible buffer read back and uploaded with glTexSubImage2D (works everywhere).
#include "runtime.h"

#include <algorithm>
#include <dlfcn.h>
#include <set>

#ifndef EGL_NATIVE_BUFFER_ANDROID
#define EGL_NATIVE_BUFFER_ANDROID 0x3140
#endif

namespace lx {

#define VK_FUNCS(X)                                \
    X(vkGetPhysicalDeviceMemoryProperties)        \
    X(vkGetPhysicalDeviceFormatProperties)        \
    X(vkGetDeviceProcAddr)                        \
    X(vkEnumeratePhysicalDevices)

#define VK_DEVICE_FUNCS(X)                         \
    X(vkGetDeviceQueue)                            \
    X(vkCreateImage)                               \
    X(vkDestroyImage)                              \
    X(vkGetImageMemoryRequirements)                \
    X(vkAllocateMemory)                            \
    X(vkFreeMemory)                                \
    X(vkBindImageMemory)                           \
    X(vkCreateBuffer)                              \
    X(vkDestroyBuffer)                             \
    X(vkGetBufferMemoryRequirements)               \
    X(vkBindBufferMemory)                          \
    X(vkMapMemory)                                 \
    X(vkUnmapMemory)                               \
    X(vkInvalidateMappedMemoryRanges)              \
    X(vkCreateCommandPool)                         \
    X(vkDestroyCommandPool)                        \
    X(vkAllocateCommandBuffers)                    \
    X(vkBeginCommandBuffer)                        \
    X(vkEndCommandBuffer)                          \
    X(vkResetCommandBuffer)                        \
    X(vkCmdPipelineBarrier)                        \
    X(vkCmdCopyImage)                              \
    X(vkCmdCopyImageToBuffer)                      \
    X(vkQueueSubmit)                               \
    X(vkQueueWaitIdle)                             \
    X(vkCreateFence)                               \
    X(vkDestroyFence)                              \
    X(vkWaitForFences)                             \
    X(vkResetFences)

struct VkFuncs {
    PFN_vkGetInstanceProcAddr gipa = nullptr;
#define DECL(n) PFN_##n n = nullptr;
    VK_FUNCS(DECL)
    VK_DEVICE_FUNCS(DECL)
#undef DECL
#ifdef __ANDROID__
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID vkGetAndroidHardwareBufferPropertiesANDROID = nullptr;
#endif
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties mem{};
    bool useAhb = false;
};

struct VkBridge {
    uint32_t w = 0, h = 0;
    bool ahbMode = false;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory imageMem = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory bufferMem = VK_NULL_HANDLE;
    void* mapped = nullptr;
    bool coherent = true;
#ifdef __ANDROID__
    AHardwareBuffer* ahb = nullptr;
#endif
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    GLuint tex = 0;
    int generation = -1;
    bool fresh = false;  // new pixels waiting to be uploaded (readback mode)
};

// ------------------------------------------------------------------------ loading --
static PFN_vkGetInstanceProcAddr systemGipa() {
    static PFN_vkGetInstanceProcAddr gipa = [] {
        void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) lib = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
        return lib ? reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr")) : nullptr;
    }();
    return gipa;
}

static VkInstance gAppVkInstance = VK_NULL_HANDLE;           // from xrCreateVulkanInstanceKHR
static PFN_vkGetInstanceProcAddr gAppGipa = nullptr;

// Instance extensions the AHardwareBuffer path needs on Vulkan 1.0 instances.
static const char* const kWantedInstanceExts[] = {
#ifdef __ANDROID__
    "VK_KHR_get_physical_device_properties2",
    "VK_KHR_external_memory_capabilities",
#endif
    nullptr};
static const char* const kAhbDeviceExts[] = {
    "VK_ANDROID_external_memory_android_hardware_buffer", "VK_EXT_queue_family_foreign", "VK_KHR_external_memory",
    "VK_KHR_sampler_ycbcr_conversion", "VK_KHR_dedicated_allocation", "VK_KHR_get_memory_requirements2",
    "VK_KHR_bind_memory2", "VK_KHR_maintenance1"};

static std::vector<std::string> availableInstanceExts(PFN_vkGetInstanceProcAddr gipa) {
    std::vector<std::string> out;
    if (!gipa) return out;
    auto enumExt = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(gipa(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties"));
    if (!enumExt) return out;
    uint32_t n = 0;
    enumExt(nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> props(n);
    enumExt(nullptr, &n, props.data());
    for (auto& p : props) {
        for (const char* const* w = kWantedInstanceExts; *w; w++)
            if (strcmp(p.extensionName, *w) == 0) out.push_back(*w);
    }
    return out;
}

static std::vector<std::string> deviceExtsFor(PFN_vkGetInstanceProcAddr gipa, VkInstance inst, VkPhysicalDevice phys) {
    std::vector<std::string> out;
#ifdef __ANDROID__
    auto enumDev = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(gipa(inst, "vkEnumerateDeviceExtensionProperties"));
    if (!enumDev) return out;
    uint32_t n = 0;
    enumDev(phys, nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> props(n);
    enumDev(phys, nullptr, &n, props.data());
    std::set<std::string> have;
    for (auto& p : props) have.insert(p.extensionName);
    // The AHardwareBuffer extension and the first three dependencies are all required;
    // the rest are only listed when present (they are core in Vulkan 1.1).
    for (int i = 0; i < 4; i++) if (!have.count(kAhbDeviceExts[i])) return out;
    for (const char* e : kAhbDeviceExts) if (have.count(e)) out.push_back(e);
#else
    (void)gipa; (void)inst; (void)phys;
#endif
    return out;
}

XrResult vkGetInstanceExtensions(std::string* out) {
    out->clear();
    for (auto& e : availableInstanceExts(systemGipa())) {
        if (!out->empty()) *out += ' ';
        *out += e;
    }
    return XR_SUCCESS;
}

XrResult vkGetDeviceExtensions(std::string* out) {
    out->clear();
    static std::string cached;
    static bool done = false;
    if (!done) {
        done = true;
        // Use a throwaway instance to see what the GPU supports.
        PFN_vkGetInstanceProcAddr gipa = systemGipa();
        auto create = gipa ? reinterpret_cast<PFN_vkCreateInstance>(gipa(VK_NULL_HANDLE, "vkCreateInstance")) : nullptr;
        if (create) {
            VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
            app.apiVersion = VK_API_VERSION_1_0;
            VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
            ci.pApplicationInfo = &app;
            VkInstance inst = VK_NULL_HANDLE;
            if (create(&ci, nullptr, &inst) == VK_SUCCESS) {
                auto enumPhys = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gipa(inst, "vkEnumeratePhysicalDevices"));
                uint32_t n = 1;
                VkPhysicalDevice phys = VK_NULL_HANDLE;
                if (enumPhys && enumPhys(inst, &n, &phys) >= 0 && n > 0) {
                    for (auto& e : deviceExtsFor(gipa, inst, phys)) {
                        if (!cached.empty()) cached += ' ';
                        cached += e;
                    }
                }
                reinterpret_cast<PFN_vkDestroyInstance>(gipa(inst, "vkDestroyInstance"))(inst, nullptr);
            }
        }
        LXLOG("Vulkan device extensions requested from games: '%s'", cached.c_str());
    }
    *out = cached;
    return XR_SUCCESS;
}

XrResult vkCreateInstanceForApp(const XrVulkanInstanceCreateInfoKHR* info, VkInstance* out, VkResult* vr) {
    PFN_vkGetInstanceProcAddr gipa = info->pfnGetInstanceProcAddr ? info->pfnGetInstanceProcAddr : systemGipa();
    auto create = reinterpret_cast<PFN_vkCreateInstance>(gipa(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!create) return XR_ERROR_RUNTIME_FAILURE;
    VkInstanceCreateInfo ci = *info->vulkanCreateInfo;
    std::vector<const char*> exts(ci.ppEnabledExtensionNames, ci.ppEnabledExtensionNames + ci.enabledExtensionCount);
    std::vector<std::string> ours = availableInstanceExts(gipa);
    for (auto& e : ours) {
        bool present = false;
        for (auto* x : exts) present = present || e == x;
        if (!present) exts.push_back(e.c_str());
    }
    ci.enabledExtensionCount = uint32_t(exts.size());
    ci.ppEnabledExtensionNames = exts.data();
    *vr = create(&ci, info->vulkanAllocator, out);
    if (*vr == VK_SUCCESS) {
        gAppVkInstance = *out;
        gAppGipa = gipa;
    }
    return XR_SUCCESS;
}

XrResult vkPickPhysicalDevice(VkInstance inst, PFN_vkGetInstanceProcAddr gipa, VkPhysicalDevice* out) {
    if (!gipa) gipa = (inst == gAppVkInstance && gAppGipa) ? gAppGipa : systemGipa();
    if (!gipa) return XR_ERROR_RUNTIME_FAILURE;
    auto enumPhys = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gipa(inst, "vkEnumeratePhysicalDevices"));
    if (!enumPhys) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    uint32_t n = 0;
    enumPhys(inst, &n, nullptr);
    if (n == 0) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    std::vector<VkPhysicalDevice> devs(n);
    enumPhys(inst, &n, devs.data());
    *out = devs[0];
    return XR_SUCCESS;
}

XrResult vkCreateDeviceForApp(const XrVulkanDeviceCreateInfoKHR* info, VkDevice* out, VkResult* vr) {
    PFN_vkGetInstanceProcAddr gipa = info->pfnGetInstanceProcAddr ? info->pfnGetInstanceProcAddr : systemGipa();
    VkInstance inst = gAppVkInstance;
    auto create = reinterpret_cast<PFN_vkCreateDevice>(gipa(inst, "vkCreateDevice"));
    if (!create) return XR_ERROR_RUNTIME_FAILURE;
    VkDeviceCreateInfo ci = *info->vulkanCreateInfo;
    std::vector<const char*> exts(ci.ppEnabledExtensionNames, ci.ppEnabledExtensionNames + ci.enabledExtensionCount);
    std::vector<std::string> ours = deviceExtsFor(gipa, inst, info->vulkanPhysicalDevice);
    for (auto& e : ours) {
        bool present = false;
        for (auto* x : exts) present = present || e == x;
        if (!present) exts.push_back(e.c_str());
    }
    ci.enabledExtensionCount = uint32_t(exts.size());
    ci.ppEnabledExtensionNames = exts.data();
    *vr = create(info->vulkanPhysicalDevice, &ci, info->vulkanAllocator, out);
    return XR_SUCCESS;
}

// ------------------------------------------------------------------------ session --
XrResult vkInitSession(Session* s) {
    auto f = std::make_unique<VkFuncs>();
    f->gipa = (s->vkInstance == gAppVkInstance && gAppGipa) ? gAppGipa : systemGipa();
    if (!f->gipa) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
#define LOAD_I(n) f->n = reinterpret_cast<PFN_##n>(f->gipa(s->vkInstance, #n));
    VK_FUNCS(LOAD_I)
#undef LOAD_I
    if (!f->vkGetDeviceProcAddr) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
#define LOAD_D(n) f->n = reinterpret_cast<PFN_##n>(f->vkGetDeviceProcAddr(s->vkDevice, #n)); \
    if (!f->n) { LXLOG("missing Vulkan function " #n); return XR_ERROR_GRAPHICS_DEVICE_INVALID; }
    VK_DEVICE_FUNCS(LOAD_D)
#undef LOAD_D
#ifdef __ANDROID__
    f->vkGetAndroidHardwareBufferPropertiesANDROID = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        f->vkGetDeviceProcAddr(s->vkDevice, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    f->useAhb = f->vkGetAndroidHardwareBufferPropertiesANDROID != nullptr;
#endif
    f->vkGetPhysicalDeviceMemoryProperties(s->vkPhys, &f->mem);
    f->vkGetDeviceQueue(s->vkDevice, s->vkQueueFamily, s->vkQueueIndex, &f->queue);
    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = s->vkQueueFamily;
    if (f->vkCreateCommandPool(s->vkDevice, &pci, nullptr, &f->pool) != VK_SUCCESS) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = f->pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    f->vkAllocateCommandBuffers(s->vkDevice, &ai, &f->cmd);
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    f->vkCreateFence(s->vkDevice, &fci, nullptr, &f->fence);
    LXLOG("Vulkan session: bridging with %s", f->useAhb ? "AHardwareBuffer" : "readback");
    s->vk = f.release();
    return XR_SUCCESS;
}

void vkDestroySession(Session* s) {
    if (!s->vk) return;
    VkFuncs& f = *s->vk;
    f.vkQueueWaitIdle(f.queue);
    f.vkDestroyFence(s->vkDevice, f.fence, nullptr);
    f.vkDestroyCommandPool(s->vkDevice, f.pool, nullptr);
    delete s->vk;
    s->vk = nullptr;
}

// ------------------------------------------------------------------------ helpers --
static int memoryType(const VkFuncs& f, uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < f.mem.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (f.mem.memoryTypes[i].propertyFlags & want) == want) return int(i);
    return -1;
}

static bool isDepthFormat(VkFormat f) {
    return f == VK_FORMAT_D16_UNORM || f == VK_FORMAT_D24_UNORM_S8_UINT || f == VK_FORMAT_D32_SFLOAT ||
           f == VK_FORMAT_D32_SFLOAT_S8_UINT || f == VK_FORMAT_X8_D24_UNORM_PACK32;
}
static bool hasStencil(VkFormat f) { return f == VK_FORMAT_D24_UNORM_S8_UINT || f == VK_FORMAT_D32_SFLOAT_S8_UINT; }

std::vector<int64_t> vkFormats(Session* s) {
    static const VkFormat kColor[] = {VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM};
    static const VkFormat kDepth[] = {VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT, VK_FORMAT_D16_UNORM, VK_FORMAT_D32_SFLOAT_S8_UINT};
    std::vector<int64_t> out;
    VkFuncs& f = *s->vk;
    for (VkFormat fmt : kColor) {
        VkFormatProperties p{};
        f.vkGetPhysicalDeviceFormatProperties(s->vkPhys, fmt, &p);
        if (p.optimalTilingFeatures & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) out.push_back(fmt);
    }
    for (VkFormat fmt : kDepth) {
        VkFormatProperties p{};
        f.vkGetPhysicalDeviceFormatProperties(s->vkPhys, fmt, &p);
        if (p.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) out.push_back(fmt);
    }
    return out;
}

// Records into f.cmd, submits and waits.
template <class Fn>
static bool submitAndWait(Session* s, Fn record) {
    VkFuncs& f = *s->vk;
    f.vkResetCommandBuffer(f.cmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    f.vkBeginCommandBuffer(f.cmd, &bi);
    record(f.cmd);
    f.vkEndCommandBuffer(f.cmd);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &f.cmd;
    VkResult r = f.vkQueueSubmit(f.queue, 1, &si, f.fence);
    if (r != VK_SUCCESS) {
        LXLOG("vkQueueSubmit failed: %d", int(r));
        return false;
    }
    f.vkWaitForFences(s->vkDevice, 1, &f.fence, VK_TRUE, 2000000000ull);
    f.vkResetFences(s->vkDevice, 1, &f.fence);
    return true;
}

static void imageBarrier(VkFuncs& f, VkCommandBuffer cmd, VkImage img, VkImageAspectFlags aspect, uint32_t baseLayer, uint32_t layers,
                         VkImageLayout from, VkImageLayout to, VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                         VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage, uint32_t mips = VK_REMAINING_MIP_LEVELS) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = srcAccess;
    b.dstAccessMask = dstAccess;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img;
    b.subresourceRange = {aspect, 0, mips, baseLayer, layers};
    f.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

// ------------------------------------------------------------------------- bridges --
static void destroyBridge(Session* s, VkBridge* b) {
    VkFuncs& f = *s->vk;
    if (b->tex || b->eglImage != EGL_NO_IMAGE_KHR) {
        if (b->eglImage != EGL_NO_IMAGE_KHR) {
            auto destroy = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
            EGLDisplay dpy = platformEglDisplay();
            if (destroy && dpy != EGL_NO_DISPLAY) destroy(dpy, b->eglImage);
        }
        if (b->generation == compositorGeneration()) compositorDeleteLater(b->tex, EGL_NO_IMAGE_KHR);
    }
    if (b->image) f.vkDestroyImage(s->vkDevice, b->image, nullptr);
    if (b->imageMem) f.vkFreeMemory(s->vkDevice, b->imageMem, nullptr);
    if (b->mapped) f.vkUnmapMemory(s->vkDevice, b->bufferMem);
    if (b->buffer) f.vkDestroyBuffer(s->vkDevice, b->buffer, nullptr);
    if (b->bufferMem) f.vkFreeMemory(s->vkDevice, b->bufferMem, nullptr);
#ifdef __ANDROID__
    if (b->ahb) AHardwareBuffer_release(b->ahb);
#endif
}

#ifdef __ANDROID__
static bool createAhbBridge(Session* s, VkBridge* b) {
    VkFuncs& f = *s->vk;
    AHardwareBuffer_Desc d{};
    d.width = b->w;
    d.height = b->h;
    d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&d, &b->ahb) != 0) return false;
    VkAndroidHardwareBufferFormatPropertiesANDROID fp{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    props.pNext = &fp;
    if (f.vkGetAndroidHardwareBufferPropertiesANDROID(s->vkDevice, b->ahb, &props) != VK_SUCCESS) return false;
    VkExternalMemoryImageCreateInfo ext{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.pNext = &ext;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = VK_FORMAT_R8G8B8A8_UNORM;
    ci.extent = {b->w, b->h, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (f.vkCreateImage(s->vkDevice, &ci, nullptr, &b->image) != VK_SUCCESS) return false;
    VkImportAndroidHardwareBufferInfoANDROID imp{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    imp.buffer = b->ahb;
    VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    ded.pNext = &imp;
    ded.image = b->image;
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.pNext = &ded;
    ai.allocationSize = props.allocationSize;
    int type = memoryType(f, props.memoryTypeBits, 0);
    if (type < 0) return false;
    ai.memoryTypeIndex = uint32_t(type);
    if (f.vkAllocateMemory(s->vkDevice, &ai, nullptr, &b->imageMem) != VK_SUCCESS) return false;
    if (f.vkBindImageMemory(s->vkDevice, b->image, b->imageMem, 0) != VK_SUCCESS) return false;
    b->ahbMode = true;
    return true;
}
#endif

static bool createReadbackBridge(Session* s, VkBridge* b) {
    VkFuncs& f = *s->vk;
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = VkDeviceSize(b->w) * b->h * 4;
    bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (f.vkCreateBuffer(s->vkDevice, &bi, nullptr, &b->buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements req;
    f.vkGetBufferMemoryRequirements(s->vkDevice, b->buffer, &req);
    int type = memoryType(f, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
    b->coherent = true;
    if (type < 0) type = memoryType(f, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT), b->coherent = false;
    if (type < 0) type = memoryType(f, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT), b->coherent = true;
    if (type < 0) return false;
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = uint32_t(type);
    if (f.vkAllocateMemory(s->vkDevice, &ai, nullptr, &b->bufferMem) != VK_SUCCESS) return false;
    f.vkBindBufferMemory(s->vkDevice, b->buffer, b->bufferMem, 0);
    if (f.vkMapMemory(s->vkDevice, b->bufferMem, 0, VK_WHOLE_SIZE, 0, &b->mapped) != VK_SUCCESS) return false;
    b->ahbMode = false;
    return true;
}

// ---------------------------------------------------------------------- swapchains --
XrResult vkCreateSwapchainImages(Session* s, Swapchain* sc) {
    VkFuncs& f = *s->vk;
    const XrSwapchainCreateInfo& xi = sc->info;
    VkFormat fmt = VkFormat(xi.format);
    sc->depth = isDepthFormat(fmt);
    sc->srgb = fmt == VK_FORMAT_R8G8B8A8_SRGB || fmt == VK_FORMAT_B8G8R8A8_SRGB;
    sc->bgra = fmt == VK_FORMAT_B8G8R8A8_SRGB || fmt == VK_FORMAT_B8G8R8A8_UNORM;
    VkImageUsageFlags usage = 0;
    if (sc->depth) usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
    else usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    if (xi.usageFlags & XR_SWAPCHAIN_USAGE_SAMPLED_BIT) usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
    if (xi.usageFlags & XR_SWAPCHAIN_USAGE_TRANSFER_SRC_BIT) usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    if (xi.usageFlags & XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT) usage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (xi.usageFlags & XR_SWAPCHAIN_USAGE_UNORDERED_ACCESS_BIT) usage |= VK_IMAGE_USAGE_STORAGE_BIT;
    if (xi.usageFlags & XR_SWAPCHAIN_USAGE_INPUT_ATTACHMENT_BIT_KHR) usage |= VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
    VkImageAspectFlags aspect = sc->depth ? (VK_IMAGE_ASPECT_DEPTH_BIT | (hasStencil(fmt) ? VK_IMAGE_ASPECT_STENCIL_BIT : 0))
                                          : VK_IMAGE_ASPECT_COLOR_BIT;

    for (uint32_t i = 0; i < sc->imageCount; i++) {
        VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        if (xi.usageFlags & XR_SWAPCHAIN_USAGE_MUTABLE_FORMAT_BIT) ci.flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
        ci.imageType = VK_IMAGE_TYPE_2D;
        ci.format = fmt;
        ci.extent = {xi.width, xi.height, 1};
        ci.mipLevels = xi.mipCount ? xi.mipCount : 1;
        ci.arrayLayers = xi.arraySize;
        ci.samples = VK_SAMPLE_COUNT_1_BIT;
        ci.tiling = VK_IMAGE_TILING_OPTIMAL;
        ci.usage = usage;
        ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        VkImage img;
        if (f.vkCreateImage(s->vkDevice, &ci, nullptr, &img) != VK_SUCCESS) return XR_ERROR_RUNTIME_FAILURE;
        sc->vkImages.push_back(img);
        VkMemoryRequirements req;
        f.vkGetImageMemoryRequirements(s->vkDevice, img, &req);
        int type = memoryType(f, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type < 0) type = memoryType(f, req.memoryTypeBits, 0);
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize = req.size;
        ai.memoryTypeIndex = uint32_t(type);
        VkDeviceMemory mem;
        if (type < 0 || f.vkAllocateMemory(s->vkDevice, &ai, nullptr, &mem) != VK_SUCCESS) return XR_ERROR_RUNTIME_FAILURE;
        sc->vkMem.push_back(mem);
        f.vkBindImageMemory(s->vkDevice, img, mem, 0);
    }

    // Images start in the layout games expect after xrWaitSwapchainImage.
    VkImageLayout layout = sc->depth ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    submitAndWait(s, [&](VkCommandBuffer cmd) {
        for (VkImage img : sc->vkImages)
            imageBarrier(f, cmd, img, aspect, 0, xi.arraySize, VK_IMAGE_LAYOUT_UNDEFINED, layout, 0,
                         VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    });

    if (!sc->depth) {
        for (uint32_t l = 0; l < xi.arraySize; l++) {
            auto b = std::make_unique<VkBridge>();
            b->w = xi.width;
            b->h = xi.height;
            bool ok = false;
#ifdef __ANDROID__
            if (f.useAhb) {
                ok = createAhbBridge(s, b.get());
                if (!ok) {
                    LXLOG("AHardwareBuffer bridge failed; using readback");
                    destroyBridge(s, b.get());
                    b = std::make_unique<VkBridge>();
                    b->w = xi.width;
                    b->h = xi.height;
                    f.useAhb = false;
                }
            }
#endif
            if (!ok) ok = createReadbackBridge(s, b.get());
            if (!ok) {
                LXLOG("could not create a compositing bridge");
                return XR_ERROR_RUNTIME_FAILURE;
            }
            sc->bridges.push_back(b.release());
        }
    }
    return XR_SUCCESS;
}

void vkDestroySwapchain(Session* s, Swapchain* sc) {
    if (!s->vk) return;
    VkFuncs& f = *s->vk;
    f.vkQueueWaitIdle(f.queue);
    for (VkBridge* b : sc->bridges) {
        destroyBridge(s, b);
        delete b;
    }
    sc->bridges.clear();
    for (VkImage img : sc->vkImages) f.vkDestroyImage(s->vkDevice, img, nullptr);
    for (VkDeviceMemory m : sc->vkMem) f.vkFreeMemory(s->vkDevice, m, nullptr);
    sc->vkImages.clear();
    sc->vkMem.clear();
}

bool vkPrepareForCompositing(Session* s, const std::vector<std::pair<Swapchain*, uint32_t>>& usedIn) {
    if (!s->vk) return false;
    VkFuncs& f = *s->vk;
    std::vector<std::pair<Swapchain*, uint32_t>> used;
    for (auto& u : usedIn) {
        Swapchain* sc = u.first;
        if (!sc || sc->magic != 0x4c58535750434841ull || sc->depth || sc->lastReleased < 0) continue;
        uint32_t layer = u.second < sc->info.arraySize ? u.second : 0;
        if (std::find(used.begin(), used.end(), std::make_pair(sc, layer)) == used.end()) used.push_back({sc, layer});
    }
    if (used.empty()) return true;
    bool ok = submitAndWait(s, [&](VkCommandBuffer cmd) {
        for (auto& u : used) {
            Swapchain* sc = u.first;
            uint32_t layer = u.second;
            VkImage src = sc->vkImages[sc->lastReleased];
            VkBridge* b = sc->bridges[layer];
            imageBarrier(f, cmd, src, VK_IMAGE_ASPECT_COLOR_BIT, layer, 1, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                         VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 1);
            if (b->ahbMode) {
                imageBarrier(f, cmd, b->image, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, VK_IMAGE_LAYOUT_UNDEFINED,
                             VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                             VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkImageCopy region{};
                region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, layer, 1};
                region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                region.extent = {b->w, b->h, 1};
                f.vkCmdCopyImage(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, b->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
                imageBarrier(f, cmd, b->image, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                             VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            } else {
                VkBufferImageCopy region{};
                region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, layer, 1};
                region.imageExtent = {b->w, b->h, 1};
                f.vkCmdCopyImageToBuffer(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, b->buffer, 1, &region);
                VkBufferMemoryBarrier bb{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
                bb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                bb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
                bb.srcQueueFamilyIndex = bb.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                bb.buffer = b->buffer;
                bb.size = VK_WHOLE_SIZE;
                f.vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &bb, 0, nullptr);
            }
            imageBarrier(f, cmd, src, VK_IMAGE_ASPECT_COLOR_BIT, layer, 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_ACCESS_TRANSFER_READ_BIT,
                         VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 1);
        }
    });
    if (!ok) return false;
    for (auto& u : used) {
        VkBridge* b = u.first->bridges[u.second];
        if (!b->ahbMode) {
            if (!b->coherent) {
                VkMappedMemoryRange r{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
                r.memory = b->bufferMem;
                r.size = VK_WHOLE_SIZE;
                f.vkInvalidateMappedMemoryRanges(s->vkDevice, 1, &r);
            }
            b->fresh = true;
        }
    }
    return true;
}

bool vkBridgeTexture(Swapchain* sc, uint32_t layer, GLuint* tex) {
    if (layer >= sc->bridges.size()) return false;
    VkBridge* b = sc->bridges[layer];
    if (b->generation != compositorGeneration()) {
        // The compositor context is new: make a texture in it.
        b->tex = 0;
        b->generation = compositorGeneration();
        glGenTextures(1, &b->tex);
        glBindTexture(GL_TEXTURE_2D, b->tex);
        if (b->ahbMode) {
#ifdef __ANDROID__
            if (b->eglImage == EGL_NO_IMAGE_KHR) {
                auto getClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
                auto createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
                if (!getClientBuffer || !createImage) return false;
                const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
                b->eglImage = createImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, getClientBuffer(b->ahb), attrs);
                if (b->eglImage == EGL_NO_IMAGE_KHR) {
                    LXLOG("eglCreateImageKHR failed: 0x%x", eglGetError());
                    return false;
                }
            }
            auto target = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
            if (!target) return false;
            target(GL_TEXTURE_2D, b->eglImage);
#endif
        } else {
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, GLsizei(b->w), GLsizei(b->h));
            b->fresh = true;
        }
    }
    if (!b->ahbMode && b->fresh) {
        glBindTexture(GL_TEXTURE_2D, b->tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, GLsizei(b->w), GLsizei(b->h), GL_RGBA, GL_UNSIGNED_BYTE, b->mapped);
        b->fresh = false;
    }
    *tex = b->tex;
    return true;
}

}  // namespace lx
