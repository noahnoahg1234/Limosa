# KDE Home: Debian + KDE Plasma as your Android home screen

**[⬇ Download kdehome.apk](https://github.com/noahnoahg1234/Limosa/releases/download/kdehome-latest/kdehome.apk)** (arm64 Android 8+)

KDE Home is an Android **launcher** that installs Debian Linux with the
**KDE Plasma** desktop and then shows KDE as your home screen. Your Android apps are shortcuts inside KDE.

1. Install `kdehome.apk` and open **KDE Home**.
2. Tap **Allow access to internal storage**. Linux gets the folder **Internal storage › Linux**
   (`/storage/emulated/0/Linux`).
3. Tap **Install Debian + KDE Plasma**. It downloads Debian (proot-distro's root filesystem, Debian 12 when
   available) and then KDE Plasma with apt. That is about 1.5 GB of downloads and 4 GB on disk, and it takes
   20–60 minutes. You can leave the app; the notification shows progress.
4. Tap **Set as default home screen** and choose KDE Home. From then on, the Home button shows KDE.

**Updating:** install a newer `kdehome.apk` over the old one. Linux stays installed; the next start updates it in
place if the new version needs extra packages. (Builds before this one were signed with a different key each time,
so if Android says the app can't be installed, uninstall the old build once. After that, updates just install.)

## Android apps in KDE

- KDE's app menu gets an **Android** section with every app on the phone.
- The KDE desktop has an **Android Apps** folder, and the phone, messages, camera, browser, Play Store and
  Settings are placed on it directly. In the Android app drawer, long-press an app and choose **Add to KDE desktop**.
- **Swipe in from the right edge** (to the left) for the menu: Android apps, PC keyboard, clipboard paste, desktop
  size, resource saving, Restart KDE, Stop Linux.
- **Swipe in from the left edge** (to the right) for the **PC keyboard**: Esc, F1–F12, number row, Tab, Caps, Shift,
  Ctrl, Alt, ❖, arrows, Home/End/PgUp/PgDn. Tap Shift/Ctrl/Alt once for the next key, twice to lock them.
  KDE shrinks to fit above the keyboard. Swipe again or tap ▼ to hide it.
- The PC keyboard also works in every other Android app: menu › **Use the PC keyboard in all apps**, then turn on
  *KDE Home PC Keyboard*.
- Shortcuts update by themselves when you install or remove apps.
- In a Linux terminal: `android-launch com.android.chrome`, `android-open https://kde.org`. Linux programs that
  open links (`BROWSER`) open them on Android.

Touch input: tap = click, long press = right-click, drag = drag, two-finger drag = scroll. A Bluetooth/USB mouse
and keyboard work directly.

## Where things are stored

| Inside Linux | On Android | |
|---|---|---|
| `/home/user/Linux` | Internal storage › Linux | Shared folder, visible in the Files app |
| `/sdcard` | Internal storage | All of internal storage |
| `/` (the Debian system) | the app's private internal storage | |

Internal storage › Linux also holds `installer/` (the downloaded Debian archive, reused if you reinstall) and
`logs/` (`install.log`, `session.log`). The Debian system files can't go in that shared folder: Android's shared
storage has no symlinks, Unix permissions or executable files, and Debian needs all three. So they sit in the
app's own internal storage, which Android removes when you uninstall the app.

## How it works

```
HomeActivity (HOME launcher) ── VncView ◄── RFB 127.0.0.1:5901 ── Xvnc :1 ◄── KDE Plasma (startplasma-x11)
       │ edge swipes: menu / PC keyboard                              ▲
LinuxService (foreground) ── proot (libproot.so) ── Debian rootfs ────┘
       └─ AndroidBridge 127.0.0.1:7790 ◄── android-launch / .desktop shortcuts (token-protected)
```

- proot (from Termux's packages, bundled by `scripts/fetch_proot_termux.py`) runs Debian without root. The
  installer runs as fake root, and the desktop runs as `user` (uid 1000), because KDE apps refuse to run as root.
- KDE runs with no compositor, screen locker or file indexer, since none of them help on a phone.
- **Resource saving** (on by default, in the menu): when KDE isn't on screen (screen off, or another app open), every
  Linux program is paused after 20 seconds and uses no CPU, then continues as soon as you're back. The display
  is limited to 30 frames a second. Turn it off if something in Linux should keep running in the background.
- To stay light, KDE runs without animations, splash screen, session restore and the background services that do
  nothing on a phone (Discover notifier, accessibility, PipeWire, power management…). If Android kills the session
  anyway, KDE Home restarts it once by itself.
- **Android 12+** may kill Linux programs (the "phantom process" limit). If KDE keeps closing, turn on
  *Developer options › Disable child process restrictions* (Android 14+), or run
  `adb shell device_config put activity_manager max_phantom_processes 2147483647`.
- Without a GPU in proot, KDE draws in software. It works, but expect it to be slower than a native launcher.
  Use **Desktop size…** to trade sharpness for speed.

## Building

Requirements: JDK 17+, the Android SDK (`ANDROID_HOME` or `local.properties`), `readelf` and `patchelf`.

```sh
python3 scripts/fetch_proot_termux.py --abi arm64-v8a   # bundles proot into kdehome/src/main/jniLibs
./gradlew :kdehome:assembleDebug                         # -> kdehome/build/outputs/apk/debug/kdehome-debug.apk
```

| Path | What it is |
|---|---|
| `kdehome/` | The app: installer, proot runner, VNC view, Android app drawer and KDE shortcuts |
| `scripts/fetch_proot_termux.py` | Bundles proot (and libtalloc) from Termux's Android packages |
| `.github/workflows/android.yml` | CI: builds the APK and publishes the KDE Home release |

proot is GPL-2.0 and talloc is LGPL-3.0. If you distribute an APK with them, also offer the matching sources.

This repository also holds an unrelated VM app; see [VM-APP.md](VM-APP.md).
