// Limosa XR OpenXR runtime: the action system.
//
// Games describe their input as actions bound to controller paths for one or more
// interaction profiles. We pick the profile that best matches a pair of Touch-style controllers
// and evaluate each binding path ("/user/hand/right/input/trigger/value", ...) against the
// emulated buttons, sticks and poses.
#include "runtime.h"

#include <algorithm>
#include <cmath>

namespace lx {

#define LOCK std::lock_guard<std::recursive_mutex> lock_(gLock)

static Instance* instanceOf(XrInstance h) {
    auto* i = reinterpret_cast<Instance*>(h);
    return (i && i == gInstance && i->magic == 0x4c58494e5354414eull) ? i : nullptr;
}
static Session* sessionOf(XrSession h) {
    auto* s = reinterpret_cast<Session*>(h);
    return (s && gInstance && s == gInstance->session && s->magic == 0x4c5853455353494full) ? s : nullptr;
}
static ActionSet* setOf(XrActionSet h) {
    auto* s = reinterpret_cast<ActionSet*>(h);
    return (s && s->magic == 0x4c58414354534554ull) ? s : nullptr;
}
static Action* actionOf(XrAction h) {
    auto* a = reinterpret_cast<Action*>(h);
    return (a && a->magic == 0x4c58414354494f4eull) ? a : nullptr;
}

// Profiles in order of preference: closest to the emulated controllers first.
static const char* const kProfiles[] = {
    "/interaction_profiles/oculus/touch_controller",
    "/interaction_profiles/meta/touch_controller_plus",
    "/interaction_profiles/meta/touch_plus_controller",
    "/interaction_profiles/meta/touch_controller_quest_2",
    "/interaction_profiles/meta/touch_controller_quest_1_rift_s",
    "/interaction_profiles/meta/touch_pro_controller",
    "/interaction_profiles/facebook/touch_controller_pro",
    "/interaction_profiles/khr/simple_controller",
    "/interaction_profiles/valve/index_controller",
    "/interaction_profiles/htc/vive_controller",
    "/interaction_profiles/microsoft/motion_controller",
    "/interaction_profiles/bytedance/pico4_controller",
    "/interaction_profiles/hp/mixed_reality_controller",
};

XrResult XRAPI_CALL lxCreateActionSet(XrInstance instance, const XrActionSetCreateInfo* info, XrActionSet* out) {
    LOCK;
    Instance* inst = instanceOf(instance);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!info || !out) return XR_ERROR_VALIDATION_FAILURE;
    if (!info->actionSetName[0]) return XR_ERROR_NAME_INVALID;
    for (auto* s : inst->actionSets) if (s->name == info->actionSetName) return XR_ERROR_NAME_DUPLICATED;
    auto* set = new ActionSet();
    set->inst = inst;
    set->name = info->actionSetName;
    set->priority = info->priority;
    inst->actionSets.push_back(set);
    *out = reinterpret_cast<XrActionSet>(set);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxDestroyActionSet(XrActionSet h) {
    LOCK;
    ActionSet* set = setOf(h);
    if (!set) return XR_ERROR_HANDLE_INVALID;
    Instance* inst = set->inst;
    if (inst && inst->session) {
        auto& v = inst->session->attachedSets;
        v.erase(std::remove(v.begin(), v.end(), set), v.end());
        for (auto* a : set->actions) inst->session->bindings.erase(a);
    }
    if (inst) {
        for (auto& kv : inst->suggested) {
            auto& v = kv.second;
            v.erase(std::remove_if(v.begin(), v.end(), [&](auto& p) { return p.first->set == set; }), v.end());
        }
        inst->actionSets.erase(std::remove(inst->actionSets.begin(), inst->actionSets.end(), set), inst->actionSets.end());
    }
    for (auto* a : set->actions) { a->magic = 0; delete a; }
    set->magic = 0;
    delete set;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxCreateAction(XrActionSet h, const XrActionCreateInfo* info, XrAction* out) {
    LOCK;
    ActionSet* set = setOf(h);
    if (!set) return XR_ERROR_HANDLE_INVALID;
    if (!info || !out) return XR_ERROR_VALIDATION_FAILURE;
    if (set->attached) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
    if (!info->actionName[0]) return XR_ERROR_NAME_INVALID;
    for (auto* a : set->actions) if (a->name == info->actionName) return XR_ERROR_NAME_DUPLICATED;
    auto* a = new Action();
    a->set = set;
    a->name = info->actionName;
    a->type = info->actionType;
    for (uint32_t i = 0; i < info->countSubactionPaths; i++) {
        if (handOfPath(set->inst, info->subactionPaths[i]) < 0) {
            delete a;
            return XR_ERROR_PATH_UNSUPPORTED;
        }
        a->subactions.push_back(info->subactionPaths[i]);
    }
    set->actions.push_back(a);
    *out = reinterpret_cast<XrAction>(a);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxDestroyAction(XrAction h) {
    LOCK;
    Action* a = actionOf(h);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    // Actions live until their set is destroyed (the session may still reference them).
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxSuggestInteractionProfileBindings(XrInstance instance, const XrInteractionProfileSuggestedBinding* sb) {
    LOCK;
    Instance* inst = instanceOf(instance);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!sb || sb->countSuggestedBindings == 0) return XR_ERROR_VALIDATION_FAILURE;
    const std::string& profile = pathString(inst, sb->interactionProfile);
    if (profile.rfind("/interaction_profiles/", 0) != 0) return XR_ERROR_PATH_UNSUPPORTED;
    std::vector<std::pair<Action*, XrPath>> list;
    for (uint32_t i = 0; i < sb->countSuggestedBindings; i++) {
        Action* a = actionOf(sb->suggestedBindings[i].action);
        if (!a) return XR_ERROR_HANDLE_INVALID;
        if (a->set->attached) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
        list.push_back({a, sb->suggestedBindings[i].binding});
    }
    inst->suggested[sb->interactionProfile] = list;  // replaces earlier suggestions for this profile
    LXLOG("bindings for %s: %u", profile.c_str(), sb->countSuggestedBindings);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxAttachSessionActionSets(XrSession session, const XrSessionActionSetsAttachInfo* info) {
    LOCK;
    Session* s = sessionOf(session);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->attachedSets.empty()) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
    Instance* inst = s->inst;
    for (uint32_t i = 0; i < info->countActionSets; i++) {
        ActionSet* set = setOf(info->actionSets[i]);
        if (!set) return XR_ERROR_HANDLE_INVALID;
        set->attached = true;
        s->attachedSets.push_back(set);
    }
    // Choose the interaction profile.
    XrPath chosen = XR_NULL_PATH;
    for (auto* name : kProfiles) {
        auto it = inst->pathIds.find(name);
        if (it != inst->pathIds.end() && inst->suggested.count(it->second)) { chosen = it->second; break; }
    }
    if (chosen == XR_NULL_PATH && !inst->suggested.empty()) chosen = inst->suggested.begin()->first;
    s->profile = chosen;
    s->bindings.clear();
    if (chosen != XR_NULL_PATH) {
        for (auto& p : inst->suggested[chosen]) {
            if (std::find(s->attachedSets.begin(), s->attachedSets.end(), p.first->set) != s->attachedSets.end())
                s->bindings[p.first].push_back(p.second);
        }
    }
    LXLOG("interaction profile: %s (%zu bound actions)", pathString(inst, chosen).c_str(), s->bindings.size());
    if (chosen != XR_NULL_PATH) {
        XrEventDataInteractionProfileChanged e{XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED};
        e.session = session;
        queueEvent(inst, &e, sizeof e);
    }
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxGetCurrentInteractionProfile(XrSession session, XrPath topLevel, XrInteractionProfileState* st) {
    LOCK;
    Session* s = sessionOf(session);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!st) return XR_ERROR_VALIDATION_FAILURE;
    if (s->attachedSets.empty()) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    int h = handOfPath(s->inst, topLevel);
    const std::string& t = pathString(s->inst, topLevel);
    if (h < 0 && t != "/user/head" && t != "/user/gamepad") return XR_ERROR_PATH_UNSUPPORTED;
    st->interactionProfile = h >= 0 ? s->profile : XR_NULL_PATH;
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ evaluation --
struct Value {
    bool valid = false;
    bool isPose = false;
    float f = 0, x = 0, y = 0;  // f for scalar/bool; x,y for vec2
};

// Evaluates a binding path like "/user/hand/left/input/trigger/value" for a given action type.
static Value evaluate(const std::string& path, XrActionType type, const DeviceInput& in) {
    Value v;
    int hand;
    std::string rest;
    if (path.rfind("/user/hand/left/input/", 0) == 0) { hand = 0; rest = path.substr(22); }
    else if (path.rfind("/user/hand/right/input/", 0) == 0) { hand = 1; rest = path.substr(23); }
    else return v;
    std::string comp = rest, feat;
    size_t slash = rest.find('/');
    if (slash != std::string::npos) { comp = rest.substr(0, slash); feat = rest.substr(slash + 1); }
    const HandInput& h = in.hand[hand];
    bool wantBool = type == XR_ACTION_TYPE_BOOLEAN_INPUT;
    auto scalar = [&](float value, float touch) {
        v.valid = true;
        if (feat == "touch") v.f = touch;
        else if (feat == "click") v.f = value > 0.5f ? 1.f : 0.f;
        else if (feat.empty()) v.f = wantBool ? (value > 0.5f ? 1.f : 0.f) : value;
        else v.f = value;  // value, force, proximity...
    };
    auto button = [&](int i) {
        v.valid = true;
        v.f = feat == "touch" ? std::max(h.touched[i], h.buttons[i]) : h.buttons[i];
    };
    if (comp == "trigger") scalar(h.buttons[0], std::max(h.touched[0], h.buttons[0] > 0 ? 1.f : 0.f));
    else if (comp == "squeeze") scalar(h.buttons[1], std::max(h.touched[1], h.buttons[1] > 0 ? 1.f : 0.f));
    else if (comp == "select") scalar(h.buttons[0], 0);
    else if (comp == "thumbstick" || comp == "joystick" || comp == "trackpad") {
        v.valid = true;
        float sx = h.stick[0], sy = -h.stick[1];  // OpenXR: +y is forward/up
        if (feat.empty()) {
            if (type == XR_ACTION_TYPE_VECTOR2F_INPUT) { v.x = sx; v.y = sy; }
            else v.f = std::max(fabsf(sx), fabsf(sy));
        } else if (feat == "x") v.f = sx;
        else if (feat == "y") v.f = sy;
        else if (feat == "click") v.f = h.buttons[3];
        else if (feat == "touch") v.f = std::max(h.touched[3], (sx != 0 || sy != 0) ? 1.f : 0.f);
        else if (feat == "force" || feat == "value") v.f = h.buttons[3];
        else v.valid = false;
        if (comp == "trackpad" && type == XR_ACTION_TYPE_VECTOR2F_INPUT && feat.empty()) { v.x = sx; v.y = sy; }
    } else if ((comp == "x" && hand == 0) || (comp == "a" && hand == 1)) button(4);
    else if ((comp == "y" && hand == 0) || (comp == "b" && hand == 1)) button(5);
    else if (comp == "menu") button(7);
    else if (comp == "thumbrest") { v.valid = true; v.f = h.touched[6]; }
    else if (comp == "system") { v.valid = true; v.f = 0; }
    else if (comp == "grip" || comp == "aim" || comp == "grip_surface" || comp == "palm_ext" || comp == "palm") {
        v.valid = true;
        v.isPose = true;
    }
    return v;
}

static int handIndexForSubaction(Instance* inst, XrPath p) {
    if (p == XR_NULL_PATH) return 2;
    return handOfPath(inst, p);
}

void inputSync(Session* s, const XrActionsSyncInfo* info) {
    DeviceInput in = inputSnapshot();
    XrTime now = nowNs();
    std::vector<ActionSet*> active;
    for (uint32_t i = 0; i < info->countActiveActionSets; i++) {
        ActionSet* set = setOf(info->activeActionSets[i].actionSet);
        if (set) active.push_back(set);
    }
    for (ActionSet* set : s->attachedSets) {
        bool isActive = std::find(active.begin(), active.end(), set) != active.end();
        for (Action* a : set->actions) {
            Action::State next[3];
            auto it = s->bindings.find(a);
            if (isActive && it != s->bindings.end()) {
                for (XrPath b : it->second) {
                    int h = handOfPath(s->inst, b);
                    if (h < 0) continue;
                    Value v = evaluate(pathString(s->inst, b), a->type, in);
                    if (!v.valid) continue;
                    for (int slot : {h, 2}) {
                        Action::State& st = next[slot];
                        st.active = true;
                        switch (a->type) {
                            case XR_ACTION_TYPE_BOOLEAN_INPUT: st.b = st.b || v.f > 0.5f; break;
                            case XR_ACTION_TYPE_FLOAT_INPUT: if (fabsf(v.f) > fabsf(st.f)) st.f = v.f; break;
                            case XR_ACTION_TYPE_VECTOR2F_INPUT:
                                if (v.x * v.x + v.y * v.y > st.x * st.x + st.y * st.y) { st.x = v.x; st.y = v.y; }
                                break;
                            default: break;
                        }
                    }
                }
            }
            for (int k = 0; k < 3; k++) {
                Action::State& cur = a->state[k];
                bool changed = cur.active && next[k].active &&
                               (cur.b != next[k].b || cur.f != next[k].f || cur.x != next[k].x || cur.y != next[k].y);
                next[k].changed = changed;
                next[k].lastChange = changed ? now : cur.lastChange;
                if (!next[k].active) next[k].lastChange = 0;
                cur = next[k];
            }
        }
    }
}

XrResult XRAPI_CALL lxSyncActions(XrSession session, const XrActionsSyncInfo* info) {
    LOCK;
    Session* s = sessionOf(session);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info) return XR_ERROR_VALIDATION_FAILURE;
    for (uint32_t i = 0; i < info->countActiveActionSets; i++) {
        ActionSet* set = setOf(info->activeActionSets[i].actionSet);
        if (!set) return XR_ERROR_HANDLE_INVALID;
        if (!set->attached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    if (!s->running) return XR_SESSION_NOT_FOCUSED;
    inputSync(s, info);
    return s->state == XR_SESSION_STATE_FOCUSED ? XR_SUCCESS : XR_SESSION_NOT_FOCUSED;
}

static XrResult stateFor(XrSession session, const XrActionStateGetInfo* info, XrActionType type, Action::State** out) {
    Session* s = sessionOf(session);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = actionOf(info->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (!a->set->attached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    if (a->type != type) return XR_ERROR_ACTION_TYPE_MISMATCH;
    if (info->subactionPath != XR_NULL_PATH &&
        std::find(a->subactions.begin(), a->subactions.end(), info->subactionPath) == a->subactions.end())
        return XR_ERROR_PATH_UNSUPPORTED;
    int k = handIndexForSubaction(s->inst, info->subactionPath);
    if (k < 0) return XR_ERROR_PATH_UNSUPPORTED;
    *out = &a->state[k];
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxGetActionStateBoolean(XrSession session, const XrActionStateGetInfo* info, XrActionStateBoolean* out) {
    LOCK;
    Action::State* st;
    XrResult r = stateFor(session, info, XR_ACTION_TYPE_BOOLEAN_INPUT, &st);
    if (XR_FAILED(r)) return r;
    out->currentState = st->b;
    out->changedSinceLastSync = st->changed;
    out->lastChangeTime = st->lastChange;
    out->isActive = st->active;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxGetActionStateFloat(XrSession session, const XrActionStateGetInfo* info, XrActionStateFloat* out) {
    LOCK;
    Action::State* st;
    XrResult r = stateFor(session, info, XR_ACTION_TYPE_FLOAT_INPUT, &st);
    if (XR_FAILED(r)) return r;
    out->currentState = st->f;
    out->changedSinceLastSync = st->changed;
    out->lastChangeTime = st->lastChange;
    out->isActive = st->active;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxGetActionStateVector2f(XrSession session, const XrActionStateGetInfo* info, XrActionStateVector2f* out) {
    LOCK;
    Action::State* st;
    XrResult r = stateFor(session, info, XR_ACTION_TYPE_VECTOR2F_INPUT, &st);
    if (XR_FAILED(r)) return r;
    out->currentState = {st->x, st->y};
    out->changedSinceLastSync = st->changed;
    out->lastChangeTime = st->lastChange;
    out->isActive = st->active;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxGetActionStatePose(XrSession session, const XrActionStateGetInfo* info, XrActionStatePose* out) {
    LOCK;
    Session* s = sessionOf(session);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || !out) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = actionOf(info->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (!a->set->attached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    if (a->type != XR_ACTION_TYPE_POSE_INPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    if (info->subactionPath != XR_NULL_PATH &&
        std::find(a->subactions.begin(), a->subactions.end(), info->subactionPath) == a->subactions.end())
        return XR_ERROR_PATH_UNSUPPORTED;
    int want = info->subactionPath == XR_NULL_PATH ? -1 : handOfPath(s->inst, info->subactionPath);
    out->isActive = XR_FALSE;
    auto it = s->bindings.find(a);
    if (it != s->bindings.end()) {
        for (XrPath b : it->second) {
            int h = handOfPath(s->inst, b);
            if (h >= 0 && (want < 0 || want == h)) out->isActive = XR_TRUE;
        }
    }
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxEnumerateBoundSourcesForAction(XrSession session, const XrBoundSourcesForActionEnumerateInfo* info,
                                                     uint32_t cap, uint32_t* count, XrPath* sources) {
    LOCK;
    Session* s = sessionOf(session);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || !count) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = actionOf(info->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (!a->set->attached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    std::vector<XrPath> v;
    auto it = s->bindings.find(a);
    if (it != s->bindings.end()) v = it->second;
    *count = uint32_t(v.size());
    if (cap == 0) return XR_SUCCESS;
    if (cap < v.size()) return XR_ERROR_SIZE_INSUFFICIENT;
    for (size_t i = 0; i < v.size(); i++) sources[i] = v[i];
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxGetInputSourceLocalizedName(XrSession session, const XrInputSourceLocalizedNameGetInfo* info,
                                                  uint32_t cap, uint32_t* count, char* buffer) {
    LOCK;
    Session* s = sessionOf(session);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || !count) return XR_ERROR_VALIDATION_FAILURE;
    const std::string& p = pathString(s->inst, info->sourcePath);
    std::string name;
    if (info->whichComponents & XR_INPUT_SOURCE_LOCALIZED_NAME_USER_PATH_BIT)
        name += p.find("/left") != std::string::npos ? "Left Hand " : "Right Hand ";
    if (info->whichComponents & XR_INPUT_SOURCE_LOCALIZED_NAME_INTERACTION_PROFILE_BIT) name += "Touch Controller ";
    if (info->whichComponents & XR_INPUT_SOURCE_LOCALIZED_NAME_COMPONENT_BIT) {
        size_t in = p.find("/input/");
        if (in != std::string::npos) name += p.substr(in + 7);
    }
    while (!name.empty() && name.back() == ' ') name.pop_back();
    *count = uint32_t(name.size() + 1);
    if (cap == 0) return XR_SUCCESS;
    if (cap < name.size() + 1) return XR_ERROR_SIZE_INSUFFICIENT;
    memcpy(buffer, name.c_str(), name.size() + 1);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxApplyHapticFeedback(XrSession session, const XrHapticActionInfo* info, const XrHapticBaseHeader* haptic) {
    LOCK;
    Session* s = sessionOf(session);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || !haptic) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = actionOf(info->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (a->type != XR_ACTION_TYPE_VIBRATION_OUTPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    if (!s->running || s->state != XR_SESSION_STATE_FOCUSED) return XR_SESSION_NOT_FOCUSED;
    if (haptic->type != XR_TYPE_HAPTIC_VIBRATION) return XR_SUCCESS;
    auto* vib = reinterpret_cast<const XrHapticVibration*>(haptic);
    float seconds = vib->duration <= 0 ? 0.03f : std::min(2.0f, float(vib->duration) / 1e9f);
    int want = info->subactionPath == XR_NULL_PATH ? -1 : handOfPath(s->inst, info->subactionPath);
    auto it = s->bindings.find(a);
    if (it == s->bindings.end()) return XR_SUCCESS;
    for (XrPath b : it->second) {
        int h = handOfPath(s->inst, b);
        if (h >= 0 && (want < 0 || want == h)) {
            pushHaptic(h, vib->amplitude, seconds);
            break;  // one phone motor: one pulse is enough
        }
    }
    return XR_SUCCESS;
}

XrResult XRAPI_CALL lxStopHapticFeedback(XrSession session, const XrHapticActionInfo*) {
    LOCK;
    Session* s = sessionOf(session);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    return XR_SUCCESS;
}

}  // namespace lx
