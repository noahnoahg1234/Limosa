// Limosa XR OpenXR runtime: OpenGL ES swapchains.
//
// Swapchain textures are created in the game's own context. The compositor's context shares
// with it, so it can sample them directly.
#include "runtime.h"

namespace lx {

std::vector<int64_t> glesFormats() {
    return {GL_SRGB8_ALPHA8, GL_RGBA8, GL_DEPTH24_STENCIL8, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT16,
            GL_DEPTH_COMPONENT32F, GL_DEPTH32F_STENCIL8};
}

static bool isDepth(int64_t f) {
    return f == GL_DEPTH24_STENCIL8 || f == GL_DEPTH_COMPONENT24 || f == GL_DEPTH_COMPONENT16 ||
           f == GL_DEPTH_COMPONENT32F || f == GL_DEPTH32F_STENCIL8;
}

// Runs fn with the game's context current, borrowing it briefly if this thread has none.
template <class F>
static bool withGameContext(Session* s, F fn) {
    if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
        fn();
        return true;
    }
    if (!eglMakeCurrent(s->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, s->eglContext)) {
        LXLOG("cannot make the game's GL context current: 0x%x", eglGetError());
        return false;
    }
    fn();
    eglMakeCurrent(s->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return true;
}

XrResult glesCreateSwapchain(Session* s, Swapchain* sc) {
    const XrSwapchainCreateInfo& ci = sc->info;
    sc->depth = isDepth(ci.format);
    sc->srgb = ci.format == GL_SRGB8_ALPHA8;
    sc->glTarget = ci.arraySize > 1 ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_2D;
    sc->glTex.assign(sc->imageCount, 0);
    GLenum err = GL_NO_ERROR;
    bool ok = withGameContext(s, [&] {
        while (glGetError() != GL_NO_ERROR) {}
        GLint prev = 0;
        glGetIntegerv(sc->glTarget == GL_TEXTURE_2D ? GL_TEXTURE_BINDING_2D : GL_TEXTURE_BINDING_2D_ARRAY, &prev);
        glGenTextures(GLsizei(sc->imageCount), sc->glTex.data());
        for (GLuint t : sc->glTex) {
            glBindTexture(sc->glTarget, t);
            GLsizei mips = GLsizei(ci.mipCount ? ci.mipCount : 1);
            if (sc->glTarget == GL_TEXTURE_2D)
                glTexStorage2D(GL_TEXTURE_2D, mips, GLenum(ci.format), GLsizei(ci.width), GLsizei(ci.height));
            else
                glTexStorage3D(GL_TEXTURE_2D_ARRAY, mips, GLenum(ci.format), GLsizei(ci.width), GLsizei(ci.height), GLsizei(ci.arraySize));
            glTexParameteri(sc->glTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(sc->glTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(sc->glTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(sc->glTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        glBindTexture(sc->glTarget, GLuint(prev));
        err = glGetError();
    });
    if (!ok) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    if (err != GL_NO_ERROR) {
        LXLOG("GL swapchain creation failed: 0x%x", err);
        return XR_ERROR_RUNTIME_FAILURE;
    }
    return XR_SUCCESS;
}

void glesDestroySwapchain(Session* s, Swapchain* sc) {
    if (sc->glTex.empty()) return;
    withGameContext(s, [&] { glDeleteTextures(GLsizei(sc->glTex.size()), sc->glTex.data()); });
    sc->glTex.clear();
}

}  // namespace lx
