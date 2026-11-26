package com.limosa.xr.runtime;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.limosa.xr.ControllerOverlay;
import com.limosa.xr.HeadTracker;
import com.limosa.xr.XrState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Java half of the Limosa XR OpenXR runtime. It is loaded from the Limosa XR APK into a VR
 * game's own process by the native runtime (platform_android.cpp), which registers the native
 * methods below before calling {@link #start}.
 *
 * It puts a full-screen window over the game with a SurfaceView, where the runtime shows the
 * game's eye images, and the same touch controls as the WebXR browser. Controller state goes to
 * the runtime 60 times a second; haptics and log lines come back.
 *
 * Everything here runs inside the game, with the game's permissions, so it only uses APIs that
 * need none (the vibrator falls back to haptic feedback on a view).
 */
public final class RuntimeUi implements ControllerOverlay.Listener {
    private static final String TAG = "LimosaXR-UI";
    private static final Uri RUNTIME_URI = Uri.parse("content://com.limosa.xr.runtime");

    static native void nativeSetState(float[] state);
    static native void nativeSetSurface(android.view.Surface surface);
    static native void nativeSetScreen(int width, int height);
    static native void nativeRequestExit();
    static native float[] nativeTakeHaptics();
    static native String nativeTakeLog();

    private static RuntimeUi instance;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final XrState state = new XrState();
    private final float[] floats = new float[XrState.FLOATS];
    private HeadTracker head;
    private ControllerOverlay controls;
    private FrameLayout root;
    private boolean attached, stopped;
    private int attachTries;
    private long lastLogFlush;

    private RuntimeUi(Activity activity) {
        this.activity = activity;
    }

    /** Called by the native runtime from xrCreateInstance, on one of the game's threads. */
    public static void start(Activity activity, Context own) {
        if (instance != null) instance.stopNow();
        RuntimeUi ui = new RuntimeUi(activity);
        instance = ui;
        Point size = new Point();
        try {
            activity.getWindowManager().getDefaultDisplay().getRealSize(size);
        } catch (Exception ignored) {
        }
        nativeSetScreen(Math.max(size.x, size.y), Math.min(size.x, size.y));
        ui.loadSettings();
        ui.pushState();
        ui.log("Game " + activity.getPackageName() + " started the Limosa XR runtime");
        ui.main.post(ui::attach);
    }

    /** Called by the native runtime from xrDestroyInstance. */
    public static void stop() {
        RuntimeUi ui = instance;
        instance = null;
        if (ui != null) ui.main.post(ui::stopNow);
    }

    private void loadSettings() {
        boolean gyro = false;
        try (Cursor c = activity.getContentResolver().query(Uri.withAppendedPath(RUNTIME_URI, "settings"), null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                synchronized (state) {
                    state.setStereo(c.getInt(c.getColumnIndexOrThrow("stereo")) != 0);
                    state.setEyeHeight(c.getFloat(c.getColumnIndexOrThrow("eyeHeight")));
                    state.setFovDegrees(c.getFloat(c.getColumnIndexOrThrow("fov")));
                    state.setIpd(c.getFloat(c.getColumnIndexOrThrow("ipd")));
                    state.setRenderScale(c.getFloat(c.getColumnIndexOrThrow("renderScale")));
                }
                gyro = c.getInt(c.getColumnIndexOrThrow("gyro")) != 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "settings unavailable", e);
        }
        gyroWanted = gyro;
    }

    private boolean gyroWanted;

    private void attach() {
        if (stopped || attached) return;
        if (activity.isFinishing()) return;
        try {
            root = new FrameLayout(activity) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                        if (e.getAction() == KeyEvent.ACTION_UP) onSystemAction("exit");
                        return true;
                    }
                    return super.dispatchKeyEvent(e);
                }
            };
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            SurfaceView view = new SurfaceView(activity);
            view.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) { nativeSetSurface(h.getSurface()); }
                @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
                    nativeSetScreen(Math.max(w, hgt), Math.min(w, hgt));
                    nativeSetSurface(h.getSurface());
                }
                @Override public void surfaceDestroyed(SurfaceHolder h) { nativeSetSurface(null); }
            });
            controls = new ControllerOverlay(activity, state);
            controls.setListener(this);
            controls.setShowMenu(true);
            controls.setStereoOn(state.getStereo());
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(controls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            lp.setTitle("Limosa XR");
            activity.getWindowManager().addView(root, lp);
            attached = true;
        } catch (Exception e) {
            // The game's window may not be ready yet.
            root = null;
            if (++attachTries < 40) {
                main.postDelayed(this::attach, 250);
            } else {
                log("Could not show the VR view: " + e);
            }
            return;
        }
        hideSystemBars();
        root.requestFocus();
        head = new HeadTracker(activity, state);
        head.setEnabled(gyroWanted);
        controls.setGyroOn(head.getEnabled());
        controls.showHint();
        log("VR view attached");
        main.post(tick);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (root == null) return;
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            pushState();
            float[] h = nativeTakeHaptics();
            if (h != null) for (int i = 0; i + 2 < h.length; i += 3) vibrate(h[i + 1], h[i + 2]);
            long now = System.currentTimeMillis();
            if (now - lastLogFlush > 1000) {
                lastLogFlush = now;
                String lines = nativeTakeLog();
                if (lines != null) log(lines.trim());
            }
            main.postDelayed(this, 16);
        }
    };

    private void pushState() {
        state.toFloats(floats);
        nativeSetState(floats);
    }

    private void vibrate(float amplitude, float seconds) {
        long ms = Math.max(10, Math.min(2000, (long) (seconds * 1000)));
        try {
            Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                int amp = Math.max(1, Math.min(255, (int) (amplitude * 255)));
                v.vibrate(VibrationEffect.createOneShot(ms, v.hasAmplitudeControl() ? amp : VibrationEffect.DEFAULT_AMPLITUDE));
                return;
            }
        } catch (SecurityException e) {
            // The game does not hold VIBRATE: fall back to a haptic click.
        } catch (Exception ignored) {
        }
        if (controls != null) {
            controls.performHapticFeedback(amplitude > 0.5f ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    @Override
    public void onSystemAction(String action) {
        switch (action) {
            case "exit":
                nativeRequestExit();
                // If the game does not quit by itself, close it.
                main.postDelayed(() -> { if (!activity.isFinishing()) activity.finish(); }, 2500);
                break;
            case "home":
                nativeRequestExit();
                try {
                    Intent i = new Intent().setComponent(new ComponentName("com.limosa.xr", "com.limosa.xr.MainActivity"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Exception e) {
                    log("Could not open Limosa XR: " + e);
                }
                main.postDelayed(() -> { if (!activity.isFinishing()) activity.finish(); }, 1500);
                break;
            case "gyro":
                if (head != null) {
                    head.setEnabled(!head.getEnabled());
                    controls.setGyroOn(head.getEnabled());
                }
                break;
            case "stereo":
                synchronized (state) { state.setStereo(!state.getStereo()); }
                controls.setStereoOn(state.getStereo());
                break;
            case "center":
                if (head != null) head.recenter();
                break;
            default:
                break;
        }
    }

    private void stopNow() {
        if (stopped) return;
        stopped = true;
        main.removeCallbacks(tick);
        if (head != null) head.setEnabled(false);
        if (attached && root != null) {
            try {
                activity.getWindowManager().removeViewImmediate(root);
            } catch (Exception ignored) {
            }
        }
        attached = false;
        String lines = nativeTakeLog();
        if (lines != null) log(lines.trim());
        io.shutdown();
    }

    /** Sends runtime log lines to the Limosa XR app, where its home page shows them. */
    private void log(String text) {
        Log.i(TAG, text);
        final String pkg = activity.getPackageName();
        try {
            io.execute(() -> {
                try {
                    ContentValues v = new ContentValues();
                    v.put("package", pkg);
                    v.put("text", text);
                    activity.getContentResolver().insert(Uri.withAppendedPath(RUNTIME_URI, "log"), v);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
