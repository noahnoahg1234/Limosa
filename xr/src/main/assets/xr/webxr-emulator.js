/*
 * Limosa XR - WebXR Device API emulation for Android WebView.
 *
 * Injected at document start into every frame. It installs navigator.xr and the XR* classes,
 * and emulates a standalone headset with two tracked "touch" style controllers. The device
 * state (head orientation, hand offsets, buttons, sticks) comes from the native app through
 * the `QuestXR` JavaScript interface, polled once per frame. Rendering goes to the page's
 * own WebGL canvas (XRWebGLLayer.framebuffer === null), which is made full-screen while an
 * immersive session runs.
 */
(function () {
  'use strict';
  if (window.__limosaXR) return;

  // ---------------------------------------------------------------- math (column-major) --
  function quatMul(a, b) {
    return [
      a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
      a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
      a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
      a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    ];
  }
  function quatAxis(x, y, z, angle) {
    var s = Math.sin(angle / 2);
    return [x * s, y * s, z * s, Math.cos(angle / 2)];
  }
  function quatNorm(q) {
    var l = Math.hypot(q[0], q[1], q[2], q[3]) || 1;
    return [q[0] / l, q[1] / l, q[2] / l, q[3] / l];
  }
  function quatRotate(q, v) {
    var x = q[0], y = q[1], z = q[2], w = q[3];
    var ix = w * v[0] + y * v[2] - z * v[1];
    var iy = w * v[1] + z * v[0] - x * v[2];
    var iz = w * v[2] + x * v[1] - y * v[0];
    var iw = -x * v[0] - y * v[1] - z * v[2];
    return [
      ix * w + iw * -x + iy * -z - iz * -y,
      iy * w + iw * -y + iz * -x - ix * -z,
      iz * w + iw * -z + ix * -y - iy * -x,
    ];
  }
  function mat4FromPose(p, q) {
    var x = q[0], y = q[1], z = q[2], w = q[3];
    var x2 = x + x, y2 = y + y, z2 = z + z;
    var xx = x * x2, xy = x * y2, xz = x * z2, yy = y * y2, yz = y * z2, zz = z * z2;
    var wx = w * x2, wy = w * y2, wz = w * z2;
    return new Float32Array([
      1 - (yy + zz), xy + wz, xz - wy, 0,
      xy - wz, 1 - (xx + zz), yz + wx, 0,
      xz + wy, yz - wx, 1 - (xx + yy), 0,
      p[0], p[1], p[2], 1,
    ]);
  }
  function mat4Mul(a, b) {
    var o = new Float32Array(16);
    for (var c = 0; c < 4; c++) for (var r = 0; r < 4; r++) {
      o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
    }
    return o;
  }
  // Pose (rigid transform) helpers: {p:[x,y,z], q:[x,y,z,w]}
  function poseMul(a, b) { // a * b
    var rp = quatRotate(a.q, b.p);
    return { p: [a.p[0] + rp[0], a.p[1] + rp[1], a.p[2] + rp[2]], q: quatNorm(quatMul(a.q, b.q)) };
  }
  function poseInv(a) {
    var qi = [-a.q[0], -a.q[1], -a.q[2], a.q[3]];
    var p = quatRotate(qi, a.p);
    return { p: [-p[0], -p[1], -p[2]], q: qi };
  }
  function poseFromMatrix(m) {
    var t = m[0] + m[5] + m[10], q;
    if (t > 0) {
      var s = 0.5 / Math.sqrt(t + 1);
      q = [(m[6] - m[9]) * s, (m[8] - m[2]) * s, (m[1] - m[4]) * s, 0.25 / s];
    } else if (m[0] > m[5] && m[0] > m[10]) {
      var s1 = 2 * Math.sqrt(1 + m[0] - m[5] - m[10]);
      q = [0.25 * s1, (m[4] + m[1]) / s1, (m[8] + m[2]) / s1, (m[6] - m[9]) / s1];
    } else if (m[5] > m[10]) {
      var s2 = 2 * Math.sqrt(1 + m[5] - m[0] - m[10]);
      q = [(m[4] + m[1]) / s2, 0.25 * s2, (m[9] + m[6]) / s2, (m[8] - m[2]) / s2];
    } else {
      var s3 = 2 * Math.sqrt(1 + m[10] - m[0] - m[5]);
      q = [(m[8] + m[2]) / s3, (m[9] + m[6]) / s3, 0.25 * s3, (m[1] - m[4]) / s3];
    }
    return { p: [m[12], m[13], m[14]], q: quatNorm(q) };
  }
  var IDENTITY = { p: [0, 0, 0], q: [0, 0, 0, 1] };

  function perspective(fovy, aspect, near, far) {
    var f = 1 / Math.tan(fovy / 2), nf = 1 / (near - far);
    return new Float32Array([
      f / aspect, 0, 0, 0,
      0, f, 0, 0,
      0, 0, (far + near) * nf, -1,
      0, 0, 2 * far * near * nf, 0,
    ]);
  }

  // ------------------------------------------------------------------ geometry classes --
  var DOMPointRO = window.DOMPointReadOnly || function (x, y, z, w) {
    this.x = x || 0; this.y = y || 0; this.z = z || 0; this.w = w === undefined ? 1 : w;
  };
  function dp(x, y, z, w) { return new DOMPointRO(x, y, z, w); }

  function XRRigidTransform(position, orientation) {
    if (position instanceof Float32Array) { // internal: from matrix
      var pm = poseFromMatrix(position);
      this._pose = pm;
    } else if (position && position.p) { // internal: from pose
      this._pose = { p: position.p.slice(), q: position.q.slice() };
    } else {
      var p = position || {}, o = orientation || {};
      this._pose = {
        p: [p.x || 0, p.y || 0, p.z || 0],
        q: quatNorm([o.x || 0, o.y || 0, o.z || 0, o.w === undefined ? 1 : o.w]),
      };
    }
    this.position = dp(this._pose.p[0], this._pose.p[1], this._pose.p[2], 1);
    this.orientation = dp(this._pose.q[0], this._pose.q[1], this._pose.q[2], this._pose.q[3]);
    this._matrix = null;
    this._inverse = null;
  }
  Object.defineProperty(XRRigidTransform.prototype, 'matrix', {
    get: function () { return this._matrix || (this._matrix = mat4FromPose(this._pose.p, this._pose.q)); },
  });
  Object.defineProperty(XRRigidTransform.prototype, 'inverse', {
    get: function () {
      if (!this._inverse) { this._inverse = new XRRigidTransform({ p: poseInv(this._pose).p, q: poseInv(this._pose).q }); this._inverse._inverse = this; }
      return this._inverse;
    },
  });

  function XRRay(origin, direction) {
    if (origin instanceof XRRigidTransform) {
      var t = origin;
      origin = dp(t._pose.p[0], t._pose.p[1], t._pose.p[2], 1);
      var d = quatRotate(t._pose.q, [0, 0, -1]);
      direction = dp(d[0], d[1], d[2], 0);
    }
    origin = origin || dp(0, 0, 0, 1);
    direction = direction || dp(0, 0, -1, 0);
    var l = Math.hypot(direction.x, direction.y, direction.z) || 1;
    this.origin = dp(origin.x, origin.y, origin.z, 1);
    this.direction = dp(direction.x / l, direction.y / l, direction.z / l, 0);
    var q = quatFromTo([0, 0, -1], [this.direction.x, this.direction.y, this.direction.z]);
    this.matrix = mat4FromPose([this.origin.x, this.origin.y, this.origin.z], q);
  }
  function quatFromTo(a, b) {
    var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    if (d < -0.999999) return [0, 1, 0, 0];
    var c = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
    return quatNorm([c[0], c[1], c[2], 1 + d]);
  }

  // ------------------------------------------------------------------- native bridge --
  var bridge = window.QuestXR || null;
  var lastState = null;
  var DEFAULT_STATE = {
    head: [0, 0, 0, 1], eye: 1.6, pos: [0, 0, 0], stereo: false, ipd: 0.063, fov: 80, scale: 1,
    L: { p: [-0.2, -0.3, -0.45], r: [0, 0], b: [0, 0, 0, 0, 0, 0, 0], t: [0, 0, 0, 0, 0, 0, 0], a: [0, 0] },
    R: { p: [0.2, -0.3, -0.45], r: [0, 0], b: [0, 0, 0, 0, 0, 0, 0], t: [0, 0, 0, 0, 0, 0, 0], a: [0, 0] },
  };
  function pollState() {
    if (bridge && bridge.poll) {
      try { lastState = JSON.parse(bridge.poll()); } catch (e) { /* keep previous */ }
    }
    return lastState || DEFAULT_STATE;
  }
  function notify(method, arg) {
    try { if (bridge && bridge[method]) arg === undefined ? bridge[method]() : bridge[method](arg); } catch (e) {}
  }

  // Head and controller poses in the floor-level space, computed once per frame.
  var device = { head: IDENTITY, L: IDENTITY, R: IDENTITY, state: DEFAULT_STATE };
  function updateDevice() {
    var s = pollState();
    var pos = s.pos || [0, 0, 0];
    var head = { p: [pos[0], s.eye + pos[1], pos[2]], q: quatNorm(s.head) };
    device.state = s;
    device.head = head;
    ['L', 'R'].forEach(function (h) {
      var hs = s[h];
      // Hand offsets are relative to the head, so the hands stay in view as you look around.
      var rot = quatMul(quatAxis(0, 1, 0, hs.r[1]), quatAxis(1, 0, 0, hs.r[0]));
      device[h] = poseMul(head, { p: hs.p, q: rot });
    });
  }

  // ------------------------------------------------------------------------- spaces --
  function XRSpace() { EventTarget.call(this); }
  XRSpace.prototype = Object.create(EventTarget.prototype);
  XRSpace.prototype.constructor = XRSpace;
  // _origin(): this space's origin expressed in the floor space.
  function makeSpace(proto, originFn) {
    var s;
    try { s = new EventTarget(); Object.setPrototypeOf(s, proto); } catch (e) { s = Object.create(proto); }
    s._origin = originFn;
    return s;
  }

  function XRReferenceSpace() { throw new TypeError('Illegal constructor'); }
  XRReferenceSpace.prototype = Object.create(XRSpace.prototype);
  XRReferenceSpace.prototype.constructor = XRReferenceSpace;
  XRReferenceSpace.prototype.getOffsetReferenceSpace = function (originOffset) {
    var parent = this;
    var off = originOffset._pose;
    var proto = Object.getPrototypeOf(this);
    var s = makeSpace(proto, function () { return poseMul(parent._origin(), off); });
    s._type = this._type;
    if (this.boundsGeometry) s.boundsGeometry = this.boundsGeometry;
    return s;
  };
  function XRBoundedReferenceSpace() { throw new TypeError('Illegal constructor'); }
  XRBoundedReferenceSpace.prototype = Object.create(XRReferenceSpace.prototype);
  XRBoundedReferenceSpace.prototype.constructor = XRBoundedReferenceSpace;

  function getRelativePose(space, base) {
    return poseMul(poseInv(base._origin()), space._origin());
  }

  // ------------------------------------------------------------------------- events --
  function mkEvent(type, props) {
    var e = new Event(type);
    for (var k in props) Object.defineProperty(e, k, { value: props[k], enumerable: true });
    return e;
  }
  function XRSessionEvent(type, init) { return mkEvent(type, { session: init && init.session }); }
  function XRInputSourceEvent(type, init) { return mkEvent(type, { frame: init.frame, inputSource: init.inputSource }); }
  function XRInputSourcesChangeEvent(type, init) {
    return mkEvent(type, { session: init.session, added: init.added || [], removed: init.removed || [] });
  }
  function XRReferenceSpaceEvent(type, init) {
    return mkEvent(type, { referenceSpace: init.referenceSpace, transform: init.transform || null });
  }
  function fire(target, event) {
    var h = target['on' + event.type];
    target.dispatchEvent(event);
    if (typeof h === 'function') { try { h.call(target, event); } catch (e) { console.error(e); } }
  }

  // ----------------------------------------------------------------------- gamepads --
  // xr-standard: 0 trigger, 1 squeeze, 2 touchpad (none), 3 thumbstick, 4 A/X, 5 B/Y, 6 thumbrest
  function makeGamepad(hand) {
    var buttons = [];
    for (var i = 0; i < 7; i++) buttons.push({ pressed: false, touched: false, value: 0 });
    var actuator = {
      type: 'vibration',
      pulse: function (value, duration) { notify('vibrate', JSON.stringify([hand, value, duration])); return Promise.resolve(true); },
      playEffect: function (type, p) {
        p = p || {};
        notify('vibrate', JSON.stringify([hand, Math.max(p.strongMagnitude || 0, p.weakMagnitude || 0), p.duration || 100]));
        return Promise.resolve('complete');
      },
      reset: function () { return Promise.resolve('complete'); },
    };
    return {
      id: '', index: -1, connected: true, mapping: 'xr-standard', timestamp: performance.now(),
      buttons: buttons, axes: [0, 0, 0, 0], hapticActuators: [actuator], vibrationActuator: actuator,
    };
  }

  function XRInputSource(handedness, session) {
    this.handedness = handedness;
    this.targetRayMode = 'tracked-pointer';
    this.profiles = ['meta-quest-touch-plus', 'oculus-touch-v3', 'oculus-touch', 'generic-trigger-squeeze-thumbstick'];
    this.hand = null;
    this.skeleton = null;
    this.gamepad = makeGamepad(handedness);
    var key = handedness === 'left' ? 'L' : 'R';
    this.gripSpace = makeSpace(XRSpace.prototype, function () { return device[key]; });
    // The pointing ray leaves the front of the controller along its axis, which is easiest to aim by touch.
    var rayOffset = { p: [0, 0, -0.06], q: [0, 0, 0, 1] };
    this.targetRaySpace = makeSpace(XRSpace.prototype, function () { return poseMul(device[key], rayOffset); });
    this._key = key;
    this._session = session;
    this._was = { trigger: false, squeeze: false };
  }

  // ------------------------------------------------------------------------- layers --
  function XRWebGLLayer(session, gl, init) {
    if (!session || !(session instanceof XRSession)) throw new TypeError('XRWebGLLayer requires an XRSession');
    if (session._ended) throw new DOMException('Session ended', 'InvalidStateError');
    init = init || {};
    this._session = session;
    this.context = gl;
    this.antialias = init.antialias !== false;
    this.ignoreDepthValues = true;
    this.fixedFoveation = null;
    this.framebuffer = null; // render straight into the page's canvas
    this._scale = init.framebufferScaleFactor || 1;
    this._viewports = {};
  }
  XRWebGLLayer.getNativeFramebufferScaleFactor = function () { return 1; };
  Object.defineProperty(XRWebGLLayer.prototype, 'framebufferWidth', {
    get: function () { return this._session._immersive ? screenPixels().w : this.context.drawingBufferWidth; },
  });
  Object.defineProperty(XRWebGLLayer.prototype, 'framebufferHeight', {
    get: function () { return this._session._immersive ? screenPixels().h : this.context.drawingBufferHeight; },
  });
  XRWebGLLayer.prototype.getViewport = function (view) {
    var w = this.framebufferWidth, h = this.framebufferHeight;
    var vp;
    if (view.eye === 'none' || view._count === 1) vp = { x: 0, y: 0, width: w, height: h };
    else {
      var half = Math.floor(w / 2);
      vp = view.eye === 'left' ? { x: 0, y: 0, width: half, height: h } : { x: half, y: 0, width: w - half, height: h };
    }
    var key = view.eye + view._count;
    var cached = this._viewports[key];
    if (cached && cached.width === vp.width && cached.height === vp.height && cached.x === vp.x) return cached;
    return (this._viewports[key] = Object.freeze(vp));
  };

  function screenPixels() {
    var dpr = window.devicePixelRatio || 1;
    var scale = (device.state && device.state.scale) || 1;
    return {
      w: Math.max(2, Math.round(window.innerWidth * dpr * scale)),
      h: Math.max(2, Math.round(window.innerHeight * dpr * scale)),
    };
  }

  // ------------------------------------------------------------------------ views --
  function makeView(eye, count, viewMatrixPose, projection) {
    return {
      eye: eye, _count: count,
      projectionMatrix: projection,
      transform: new XRRigidTransform(viewMatrixPose),
      recommendedViewportScale: null,
      requestViewportScale: function () {},
      isFirstPersonObserver: false,
    };
  }

  // --------------------------------------------------------------------- XRFrame --
  function XRFrame(session, time) {
    this.session = session;
    this.predictedDisplayTime = time;
    this._active = true;
    this.trackedAnchors = new Set();
  }
  XRFrame.prototype.getPose = function (space, baseSpace) {
    if (!this._active) throw new DOMException('Frame is not active', 'InvalidStateError');
    if (!space || !baseSpace) return null;
    var p = getRelativePose(space, baseSpace);
    return { transform: new XRRigidTransform(p), emulatedPosition: false, linearVelocity: null, angularVelocity: null };
  };
  XRFrame.prototype.getViewerPose = function (refSpace) {
    if (!this._active) throw new DOMException('Frame is not active', 'InvalidStateError');
    var session = this.session;
    var viewer = getRelativePose(session._viewerSpace, refSpace);
    var s = device.state;
    var px = screenPixels();
    var views = [];
    if (!session._immersive) {
      var c = session._canvasSize();
      var proj = perspective(session.renderState.inlineVerticalFieldOfView || Math.PI / 2, c.w / c.h, session.renderState.depthNear, session.renderState.depthFar);
      views.push(makeView('none', 1, viewer, proj));
    } else if (s.stereo) {
      var aspect = (px.w / 2) / px.h;
      var fovy = (s.fov || 80) * Math.PI / 180;
      var pr = perspective(fovy, aspect, session.renderState.depthNear, session.renderState.depthFar);
      var half = (s.ipd || 0.063) / 2;
      views.push(makeView('left', 2, poseMul(viewer, { p: [-half, 0, 0], q: [0, 0, 0, 1] }), pr));
      views.push(makeView('right', 2, poseMul(viewer, { p: [half, 0, 0], q: [0, 0, 0, 1] }), pr));
    } else {
      var fovm = (s.fov || 80) * Math.PI / 180;
      views.push(makeView('none', 1, viewer, perspective(fovm, px.w / px.h, session.renderState.depthNear, session.renderState.depthFar)));
    }
    return { transform: new XRRigidTransform(viewer), views: views, emulatedPosition: false };
  };
  XRFrame.prototype.getJointPose = function () { return null; };
  XRFrame.prototype.fillJointRadii = function () { return false; };
  XRFrame.prototype.fillPoses = function () { return false; };
  XRFrame.prototype.getHitTestResults = function () { return []; };
  XRFrame.prototype.getHitTestResultsForTransientInput = function () { return []; };

  // ------------------------------------------------------------------- XRSession --
  var SUPPORTED_FEATURES = ['viewer', 'local', 'local-floor', 'bounded-floor', 'unbounded'];
  var activeImmersive = null;

  function XRSession() { throw new TypeError('Illegal constructor'); }
  XRSession.prototype = Object.create(EventTarget.prototype);
  XRSession.prototype.constructor = XRSession;

  function createSession(mode, features) {
    var session;
    try { session = new EventTarget(); Object.setPrototypeOf(session, XRSession.prototype); } catch (e) { session = Object.create(XRSession.prototype); }
    session._mode = mode;
    session._immersive = mode !== 'inline';
    session._ended = false;
    session._callbacks = [];
    session._handle = 0;
    session._raf = 0;
    session._sourcesAnnounced = false;
    session.enabledFeatures = features;
    session.environmentBlendMode = 'opaque';
    session.interactionMode = 'world-space';
    session.visibilityState = 'visible';
    session.supportedFrameRates = new Float32Array([60, 72, 90]);
    session.frameRate = 60;
    session.isSystemKeyboardSupported = false;
    session.preferredReflectionFormat = 'srgba8';
    session.domOverlayState = null;
    session.renderState = { baseLayer: null, depthNear: 0.1, depthFar: 1000, inlineVerticalFieldOfView: session._immersive ? null : Math.PI / 2, layers: undefined, passthroughFullyObscured: false };
    session._viewerSpace = makeSpace(XRReferenceSpace.prototype, function () { return device.head; });
    session._viewerSpace._type = 'viewer';
    session.inputSources = session._immersive ? [] : [];
    session._sources = session._immersive ? [new XRInputSource('left', session), new XRInputSource('right', session)] : [];
    return session;
  }

  XRSession.prototype.updateRenderState = function (state) {
    if (this._ended) throw new DOMException('Session ended', 'InvalidStateError');
    state = state || {};
    var rs = Object.assign({}, this.renderState);
    if (state.baseLayer !== undefined) rs.baseLayer = state.baseLayer;
    if (state.depthNear !== undefined) rs.depthNear = state.depthNear;
    if (state.depthFar !== undefined) rs.depthFar = state.depthFar;
    if (state.inlineVerticalFieldOfView !== undefined && !this._immersive) rs.inlineVerticalFieldOfView = state.inlineVerticalFieldOfView;
    if (state.layers !== undefined) {
      if (state.layers && state.layers.length) throw new DOMException('Composition layers are not supported', 'NotSupportedError');
    }
    this.renderState = rs;
    if (this._immersive && rs.baseLayer) presentCanvas(this, rs.baseLayer.context.canvas);
  };
  XRSession.prototype.updateTargetFrameRate = function (rate) { this.frameRate = rate; return Promise.resolve(); };
  XRSession.prototype.requestReferenceSpace = function (type) {
    var self = this;
    if (SUPPORTED_FEATURES.indexOf(type) < 0) return Promise.reject(new DOMException('Unsupported reference space: ' + type, 'NotSupportedError'));
    if (type !== 'viewer' && type !== 'local' && this.enabledFeatures.indexOf(type) < 0 && this._immersive) {
      // Real browsers require the feature to be requested; we are lenient but keep it consistent.
      this.enabledFeatures.push(type);
    }
    if (!this._immersive && type !== 'viewer') return Promise.reject(new DOMException('Not supported for inline sessions', 'NotSupportedError'));
    var space;
    if (type === 'viewer') space = this._viewerSpace;
    else {
      var proto = type === 'bounded-floor' ? XRBoundedReferenceSpace.prototype : XRReferenceSpace.prototype;
      // local: origin at the initial eye position; floor spaces: origin on the floor below it.
      var origin = type === 'local' ? { p: [0, device.state.eye || 1.6, 0], q: [0, 0, 0, 1] } : IDENTITY;
      space = makeSpace(proto, function () { return origin; });
      space._type = type;
      if (type === 'bounded-floor') {
        space.boundsGeometry = [dp(-2, 0, -2, 1), dp(2, 0, -2, 1), dp(2, 0, 2, 1), dp(-2, 0, 2, 1)];
      }
    }
    void self;
    return Promise.resolve(space);
  };
  XRSession.prototype.requestAnimationFrame = function (cb) {
    if (this._ended) return 0;
    var id = ++this._handle;
    this._callbacks.push({ id: id, cb: cb, cancelled: false });
    if (!this._raf && !this._inTick) this._schedule();
    return id;
  };
  XRSession.prototype.cancelAnimationFrame = function (id) {
    this._callbacks.forEach(function (c) { if (c.id === id) c.cancelled = true; });
  };
  XRSession.prototype._schedule = function () {
    var self = this;
    this._raf = window.requestAnimationFrame(function (t) { self._raf = 0; self._tick(t); });
  };
  XRSession.prototype._canvasSize = function () {
    var layer = this.renderState.baseLayer;
    var gl = layer && layer.context;
    return gl ? { w: gl.drawingBufferWidth || 1, h: gl.drawingBufferHeight || 1 } : { w: 1, h: 1 };
  };
  XRSession.prototype._tick = function (time) {
    if (this._ended) return;
    updateDevice();
    if (this._immersive) keepCanvasSized(this);
    var frame = new XRFrame(this, time);
    if (this._immersive) this._updateInputs(frame);
    var cbs = this._callbacks;
    this._callbacks = [];
    this._inTick = true;
    for (var i = 0; i < cbs.length; i++) {
      if (cbs[i].cancelled) continue;
      try { cbs[i].cb(time, frame); } catch (e) { console.error(e); }
    }
    frame._active = false;
    this._inTick = false;
    if (this._callbacks.length && !this._ended && !this._raf) this._schedule();
  };
  XRSession.prototype._updateInputs = function (frame) {
    var self = this;
    if (!this._sourcesAnnounced) {
      this._sourcesAnnounced = true;
      this._sources.forEach(function (s) { self.inputSources.push(s); });
      fire(this, XRInputSourcesChangeEvent('inputsourceschange', { session: this, added: this._sources.slice(), removed: [] }));
    }
    var now = performance.now();
    this._sources.forEach(function (src) {
      var hs = device.state[src._key];
      var gp = src.gamepad;
      gp.timestamp = now;
      for (var i = 0; i < gp.buttons.length; i++) {
        var v = hs.b[i] || 0;
        gp.buttons[i].value = v;
        gp.buttons[i].pressed = v > 0.5;
        gp.buttons[i].touched = v > 0 || !!(hs.t && hs.t[i]);
      }
      gp.axes[2] = hs.a[0] || 0;
      gp.axes[3] = hs.a[1] || 0;
      var trig = gp.buttons[0].pressed, sq = gp.buttons[1].pressed;
      edge(self, frame, src, trig, src._was.trigger, 'select');
      edge(self, frame, src, sq, src._was.squeeze, 'squeeze');
      src._was.trigger = trig;
      src._was.squeeze = sq;
    });
  };
  function edge(session, frame, src, now, was, name) {
    if (now && !was) fire(session, XRInputSourceEvent(name + 'start', { frame: frame, inputSource: src }));
    if (!now && was) {
      fire(session, XRInputSourceEvent(name, { frame: frame, inputSource: src }));
      fire(session, XRInputSourceEvent(name + 'end', { frame: frame, inputSource: src }));
    }
  }
  XRSession.prototype.end = function () {
    if (this._ended) return Promise.resolve();
    var self = this;
    this._ended = true;
    if (this._raf) window.cancelAnimationFrame(this._raf);
    if (this._immersive) {
      // Release anything still held so apps do not keep a stuck grab.
      var frame = new XRFrame(this, performance.now());
      this._sources.forEach(function (src) {
        if (src._was.trigger) fire(self, XRInputSourceEvent('selectend', { frame: frame, inputSource: src }));
        if (src._was.squeeze) fire(self, XRInputSourceEvent('squeezeend', { frame: frame, inputSource: src }));
      });
      frame._active = false;
      restoreCanvas();
      if (activeImmersive === this) activeImmersive = null;
      notify('sessionEnded');
    }
    return new Promise(function (resolve) {
      setTimeout(function () { fire(self, XRSessionEvent('end', { session: self })); resolve(); }, 0);
    });
  };
  XRSession.prototype.requestHitTestSource = function () { return Promise.reject(new DOMException('hit-test not supported', 'NotSupportedError')); };
  XRSession.prototype.requestHitTestSourceForTransientInput = XRSession.prototype.requestHitTestSource;
  XRSession.prototype.initiateRoomCapture = function () { return Promise.resolve(); };

  // ------------------------------------------------------- full-screen canvas handling --
  var presented = null;
  function presentCanvas(session, canvas) {
    if (!canvas || !canvas.style) return; // OffscreenCanvas: the app has to blit itself
    if (presented && presented.canvas === canvas) return;
    if (presented) restoreCanvas();
    presented = {
      canvas: canvas, style: canvas.getAttribute('style'), width: canvas.width, height: canvas.height,
      parent: canvas.parentNode, next: canvas.nextSibling,
      bodyOverflow: document.body ? document.body.style.overflow : '',
    };
    // Move the canvas to <html> so transformed or hidden ancestors cannot clip it.
    document.documentElement.appendChild(canvas);
    canvas.style.cssText = 'position:fixed!important;left:0!important;top:0!important;width:100vw!important;' +
      'height:100vh!important;margin:0!important;padding:0!important;border:0!important;display:block!important;' +
      'visibility:visible!important;opacity:1!important;transform:none!important;z-index:2147483647!important;background:#000;';
    if (document.body) document.body.style.overflow = 'hidden';
    keepCanvasSized(session);
  }
  function keepCanvasSized(session) {
    if (!presented) return;
    var px = screenPixels();
    var c = presented.canvas;
    if (c.width !== px.w) c.width = px.w;
    if (c.height !== px.h) c.height = px.h;
    void session;
  }
  function restoreCanvas() {
    if (!presented) return;
    var p = presented;
    presented = null;
    var c = p.canvas;
    if (p.style === null) c.removeAttribute('style'); else c.setAttribute('style', p.style);
    c.width = p.width;
    c.height = p.height;
    if (p.parent) {
      try { p.parent.insertBefore(c, p.next && p.next.parentNode === p.parent ? p.next : null); } catch (e) { p.parent.appendChild(c); }
    }
    if (document.body) document.body.style.overflow = p.bodyOverflow;
    try { window.dispatchEvent(new Event('resize')); } catch (e) {}
  }

  // ------------------------------------------------------------------ XRSystem --
  function XRSystem() { throw new TypeError('Illegal constructor'); }
  XRSystem.prototype = Object.create(EventTarget.prototype);
  XRSystem.prototype.constructor = XRSystem;
  XRSystem.prototype.isSessionSupported = function (mode) {
    return Promise.resolve(mode === 'immersive-vr' || mode === 'inline');
  };
  XRSystem.prototype.requestSession = function (mode, options) {
    options = options || {};
    if (mode !== 'immersive-vr' && mode !== 'inline') {
      return Promise.reject(new DOMException('Session mode ' + mode + ' is not supported', 'NotSupportedError'));
    }
    if (mode === 'immersive-vr' && activeImmersive) {
      return Promise.reject(new DOMException('An immersive session is already active', 'InvalidStateError'));
    }
    var required = options.requiredFeatures || [];
    var optional = options.optionalFeatures || [];
    var enabled = ['viewer'];
    if (mode === 'immersive-vr') enabled.push('local');
    for (var i = 0; i < required.length; i++) {
      if (SUPPORTED_FEATURES.indexOf(required[i]) < 0) {
        return Promise.reject(new DOMException('Required feature not supported: ' + required[i], 'NotSupportedError'));
      }
      if (enabled.indexOf(required[i]) < 0) enabled.push(required[i]);
    }
    for (var j = 0; j < optional.length; j++) {
      if (SUPPORTED_FEATURES.indexOf(optional[j]) >= 0 && enabled.indexOf(optional[j]) < 0) enabled.push(optional[j]);
    }
    updateDevice();
    var session = createSession(mode, enabled);
    if (mode === 'immersive-vr') {
      activeImmersive = session;
      notify('sessionStarted', mode);
    }
    return Promise.resolve(session);
  };

  var xr;
  try { xr = new EventTarget(); Object.setPrototypeOf(xr, XRSystem.prototype); } catch (e) { xr = Object.create(XRSystem.prototype); }

  // ------------------------------------------------------------------ install --
  try {
    Object.defineProperty(Navigator.prototype, 'xr', { get: function () { return xr; }, configurable: true, enumerable: true });
  } catch (e) {
    try { Object.defineProperty(navigator, 'xr', { value: xr, configurable: true }); } catch (e2) {}
  }
  var globals = {
    XRSystem: XRSystem, XRSession: XRSession, XRFrame: XRFrame, XRSpace: XRSpace, XRReferenceSpace: XRReferenceSpace,
    XRBoundedReferenceSpace: XRBoundedReferenceSpace, XRRigidTransform: XRRigidTransform, XRRay: XRRay,
    XRWebGLLayer: XRWebGLLayer, XRInputSource: XRInputSource, XRSessionEvent: XRSessionEvent,
    XRInputSourceEvent: XRInputSourceEvent, XRInputSourcesChangeEvent: XRInputSourcesChangeEvent,
    XRReferenceSpaceEvent: XRReferenceSpaceEvent,
  };
  Object.keys(globals).forEach(function (k) {
    try { Object.defineProperty(window, k, { value: globals[k], configurable: true, writable: true }); } catch (e) {}
  });
  // Composition layers are not emulated; hide any native binding so frameworks use XRWebGLLayer.
  ['XRWebGLBinding', 'XRProjectionLayer', 'XRQuadLayer', 'XRCylinderLayer', 'XREquirectLayer', 'XRCubeLayer', 'XRMediaBinding'].forEach(function (k) {
    try { delete window[k]; } catch (e) {}
    try { if (window[k]) Object.defineProperty(window, k, { value: undefined, configurable: true, writable: true }); } catch (e) {}
  });
  ['WebGLRenderingContext', 'WebGL2RenderingContext'].forEach(function (k) {
    var C = window[k];
    if (C && C.prototype) C.prototype.makeXRCompatible = function () { return Promise.resolve(); };
  });

  // Hooks used by the native app.
  window.__limosaXR = {
    version: 1,
    endSession: function () { if (activeImmersive) activeImmersive.end(); return !!activeImmersive; },
    isPresenting: function () { return !!activeImmersive; },
    // Clicks the page's own "Enter VR" button for the common frameworks.
    enterVR: function () {
      var sel = ['#VRButton', 'button#VRButton', '.a-enter-vr-button', '#enter-vr', '#enterVR', '.enter-vr',
        '[data-enter-vr]', 'button[aria-label*="VR" i]', '.babylonVRicon', '#xr-button', '.webvr-ui-button'];
      for (var i = 0; i < sel.length; i++) {
        var el;
        try { el = document.querySelector(sel[i]); } catch (e) { el = null; }
        if (el) { el.click(); return sel[i]; }
      }
      var buttons = document.querySelectorAll('button, a, [role=button]');
      for (var j = 0; j < buttons.length; j++) {
        var t = (buttons[j].textContent || '').trim().toUpperCase();
        if (/^(ENTER VR|START VR|VR|PLAY IN VR|ENTER XR|START XR)$/.test(t)) { buttons[j].click(); return 'text'; }
      }
      var frames = document.querySelectorAll('iframe');
      for (var k = 0; k < frames.length; k++) {
        try { var r = frames[k].contentWindow.__limosaXR && frames[k].contentWindow.__limosaXR.enterVR(); if (r) return r; } catch (e) {}
      }
      return null;
    },
    _debug: { device: device, poseMul: poseMul },
  };
})();
