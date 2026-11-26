// Limosa XR OpenXR runtime: the compositor.
//
// Runs inside xrEndFrame on the game's render thread. It switches to its own GL ES 3 context
// (shared with the game's context for GL games, so their swapchain textures are visible),
// draws the submitted layers onto the full-screen window and switches back.
#include "runtime.h"

#include <algorithm>

namespace lx {

static const char* kVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;  // x, y, w
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = vec4(aPos.xy, 0.0, aPos.z); }
)";

static const char* kFsCommon = R"(
uniform int uEncode;
uniform int uSwizzle;
in vec2 vUv;
out vec4 oColor;
vec3 encodeSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}
vec4 finish(vec4 c) {
    if (uSwizzle == 1) c = c.bgra;
    if (uEncode == 1) c.rgb = encodeSrgb(c.rgb);
    return c;
}
)";

static const char* kFs2D = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
%COMMON%
void main() { oColor = finish(texture(uTex, vUv)); }
)";

static const char* kFsArray = R"(#version 300 es
precision mediump float;
precision mediump sampler2DArray;
uniform sampler2DArray uTex;
uniform float uLayer;
%COMMON%
void main() { oColor = finish(texture(uTex, vec3(vUv, uLayer))); }
)";

struct Program {
    GLuint id = 0;
    GLint encode = -1, swizzle = -1, layer = -1, tex = -1;
};

static struct {
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLConfig cfg = nullptr;
    EGLContext ctx = EGL_NO_CONTEXT;
    EGLContext sharedWith = EGL_NO_CONTEXT;
    EGLSurface surf = EGL_NO_SURFACE;
    void* surfWindow = nullptr;
    Program p2d, pArray;
    GLuint sampler = 0;
    int generation = 0;
    int width = 0, height = 0, eyes = 1;
    // What was current before we switched.
    EGLDisplay savedDpy = EGL_NO_DISPLAY;
    EGLContext savedCtx = EGL_NO_CONTEXT;
    EGLSurface savedDraw = EGL_NO_SURFACE, savedRead = EGL_NO_SURFACE;
    std::unique_lock<std::mutex> windowLock;
    std::vector<std::pair<GLuint, EGLImageKHR>> pendingDelete;
} C;

int compositorGeneration() { return C.generation; }

void compositorDeleteLater(GLuint tex, EGLImageKHR image) {
    C.pendingDelete.push_back({tex, image});
}

static GLuint compile(GLenum type, const std::string& src) {
    GLuint sh = glCreateShader(type);
    const char* p = src.c_str();
    glShaderSource(sh, 1, &p, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(sh, sizeof log, nullptr, log);
        LXLOG("shader compile failed: %s", log);
    }
    return sh;
}

static Program makeProgram(const char* fsTemplate) {
    std::string fs = fsTemplate;
    fs.replace(fs.find("%COMMON%"), 8, kFsCommon);
    Program p;
    p.id = glCreateProgram();
    GLuint vs = compile(GL_VERTEX_SHADER, kVs), f = compile(GL_FRAGMENT_SHADER, fs);
    glAttachShader(p.id, vs);
    glAttachShader(p.id, f);
    glLinkProgram(p.id);
    GLint ok = 0;
    glGetProgramiv(p.id, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(p.id, sizeof log, nullptr, log);
        LXLOG("program link failed: %s", log);
    }
    glDeleteShader(vs);
    glDeleteShader(f);
    p.encode = glGetUniformLocation(p.id, "uEncode");
    p.swizzle = glGetUniformLocation(p.id, "uSwizzle");
    p.layer = glGetUniformLocation(p.id, "uLayer");
    p.tex = glGetUniformLocation(p.id, "uTex");
    return p;
}

static void destroyContext() {
    if (C.dpy == EGL_NO_DISPLAY) return;
    if (C.surf != EGL_NO_SURFACE) eglDestroySurface(C.dpy, C.surf);
    if (C.ctx != EGL_NO_CONTEXT) eglDestroyContext(C.dpy, C.ctx);
    C.surf = EGL_NO_SURFACE;
    C.surfWindow = nullptr;
    C.ctx = EGL_NO_CONTEXT;
    C.sharedWith = EGL_NO_CONTEXT;
    C.p2d = Program();
    C.pArray = Program();
    C.sampler = 0;
    C.pendingDelete.clear();  // textures die with the context; EGLImages are owned by the bridges
    C.generation++;
}

static bool ensureContext(Session* s) {
    EGLDisplay dpy = s->gfx == Gfx::GLES ? s->eglDisplay : platformEglDisplay();
    EGLContext share = s->gfx == Gfx::GLES ? s->eglContext : EGL_NO_CONTEXT;
    if (C.ctx != EGL_NO_CONTEXT && C.dpy == dpy && C.sharedWith == share) return true;
    destroyContext();
    if (dpy == EGL_NO_DISPLAY) return false;
    EGLint major, minor;
    if (!eglInitialize(dpy, &major, &minor)) {
        LXLOG("eglInitialize failed: 0x%x", eglGetError());
        return false;
    }
    const EGLint attrs[] = {EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL_SURFACE_TYPE, platformSurfaceType(),
                            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig cfg = nullptr;
    EGLint n = 0;
    if (!eglChooseConfig(dpy, attrs, &cfg, 1, &n) || n == 0) {
        LXLOG("no EGL config for the compositor: 0x%x", eglGetError());
        return false;
    }
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, share, ctxAttrs);
    if (ctx == EGL_NO_CONTEXT) {
        LXLOG("compositor context failed: 0x%x", eglGetError());
        return false;
    }
    C.dpy = dpy;
    C.cfg = cfg;
    C.ctx = ctx;
    C.sharedWith = share;
    C.generation++;
    LXLOG("compositor context ready (EGL %d.%d, %s)", major, minor, share ? "shared with the game" : "own");
    return true;
}

void compositorDropSurface() {
    // Called by the platform with the window lock held, when the window goes away.
    if (C.surf != EGL_NO_SURFACE && C.dpy != EGL_NO_DISPLAY) eglDestroySurface(C.dpy, C.surf);
    C.surf = EGL_NO_SURFACE;
    C.surfWindow = nullptr;
}

bool compositorBegin(Session* s) {
    C.windowLock = std::unique_lock<std::mutex>(platformWindowMutex());
    void* win = platformWindow();
    if (!win || !ensureContext(s)) {
        C.windowLock.unlock();
        return false;
    }
    if (C.surfWindow != win) {
        if (C.surf != EGL_NO_SURFACE) eglDestroySurface(C.dpy, C.surf);
        C.surf = platformCreateSurface(C.dpy, C.cfg, win);
        C.surfWindow = C.surf != EGL_NO_SURFACE ? win : nullptr;
        if (C.surf == EGL_NO_SURFACE) {
            LXLOG("window surface failed: 0x%x", eglGetError());
            C.windowLock.unlock();
            return false;
        }
    }

    // A GL game's commands must be done before our context samples its textures.
    GLsync fence = nullptr;
    if (s->gfx == Gfx::GLES && eglGetCurrentContext() != EGL_NO_CONTEXT) {
        fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glFlush();
    }
    C.savedDpy = eglGetCurrentDisplay();
    C.savedCtx = eglGetCurrentContext();
    C.savedDraw = eglGetCurrentSurface(EGL_DRAW);
    C.savedRead = eglGetCurrentSurface(EGL_READ);
    if (!eglMakeCurrent(C.dpy, C.surf, C.surf, C.ctx)) {
        LXLOG("eglMakeCurrent failed: 0x%x", eglGetError());
        if (fence) glDeleteSync(fence);
        C.windowLock.unlock();
        return false;
    }
    if (fence) {
        glWaitSync(fence, 0, GL_TIMEOUT_IGNORED);
        glDeleteSync(fence);
    }
    if (!C.p2d.id) {
        C.p2d = makeProgram(kFs2D);
        C.pArray = makeProgram(kFsArray);
        glGenSamplers(1, &C.sampler);
        glSamplerParameteri(C.sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(C.sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(C.sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(C.sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        eglSwapInterval(C.dpy, 0);  // xrWaitFrame already paces the game
    }
    for (auto& d : C.pendingDelete) if (d.first) glDeleteTextures(1, &d.first);
    C.pendingDelete.clear();

    EGLint w = 0, h = 0;
    eglQuerySurface(C.dpy, C.surf, EGL_WIDTH, &w);
    eglQuerySurface(C.dpy, C.surf, EGL_HEIGHT, &h);
    C.width = w;
    C.height = h;
    if (w > 0 && h > 0) {
        gScreenW = std::max(w, h);
        gScreenH = std::min(w, h);
    }
    C.eyes = inputSnapshot().stereo > 0.5f ? 2 : 1;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, w, h);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    return true;
}

void compositorDraw(const DrawItem& d) {
    if (!d.tex) return;
    int x = 0, w = C.width;
    if (C.eyes == 2) {
        w = C.width / 2;
        x = d.eye == 1 ? C.width - w : 0;
    }
    glViewport(x, 0, w, C.height);
    const Program& p = d.target == GL_TEXTURE_2D_ARRAY ? C.pArray : C.p2d;
    glUseProgram(p.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(d.target, d.tex);
    glBindSampler(0, C.sampler);
    glUniform1i(p.tex, 0);
    glUniform1i(p.encode, d.encode ? 1 : 0);
    glUniform1i(p.swizzle, d.swizzle ? 1 : 0);
    if (p.layer >= 0) glUniform1f(p.layer, d.layer);
    if (d.blend) {
        glEnable(GL_BLEND);
        glBlendFunc(d.premultiplied ? GL_ONE : GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    } else {
        glDisable(GL_BLEND);
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, d.clip);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, d.uv);
    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
    glBindTexture(d.target, 0);
}

void compositorEnd(Session* s) {
    (void)s;
    glDisable(GL_BLEND);
    if (!eglSwapBuffers(C.dpy, C.surf)) LXLOG("eglSwapBuffers failed: 0x%x", eglGetError());
    if (C.savedCtx != EGL_NO_CONTEXT) eglMakeCurrent(C.savedDpy, C.savedDraw, C.savedRead, C.savedCtx);
    else eglMakeCurrent(C.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    C.windowLock.unlock();
}

void compositorShutdown() {
    std::lock_guard<std::mutex> g(platformWindowMutex());
    destroyContext();
}

}  // namespace lx

#ifdef LIMOSA_HOST_TEST
// Test hook: reads one pixel of the last composited frame (bottom-left origin).
extern "C" __attribute__((visibility("default"))) bool limosa_test_read_pixel(int x, int y, unsigned char* rgba) {
    using namespace lx;
    if (C.ctx == EGL_NO_CONTEXT || C.surf == EGL_NO_SURFACE) return false;
    EGLDisplay d = eglGetCurrentDisplay();
    EGLContext c = eglGetCurrentContext();
    EGLSurface dr = eglGetCurrentSurface(EGL_DRAW), rd = eglGetCurrentSurface(EGL_READ);
    eglMakeCurrent(C.dpy, C.surf, C.surf, C.ctx);
    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    if (c != EGL_NO_CONTEXT) eglMakeCurrent(d, dr, rd, c);
    else eglMakeCurrent(C.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return true;
}
extern "C" __attribute__((visibility("default"))) void limosa_test_size(int* w, int* h) {
    *w = lx::C.width;
    *h = lx::C.height;
}
#endif
