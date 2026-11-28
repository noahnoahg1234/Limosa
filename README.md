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

**Debian updates itself:** on the first start after installing, and then once a week, KDE Home updates all of
Debian before KDE starts (it never removes packages). Menu › **Update Linux** does it now.

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

## Backing up Debian to the Linux folder

Menu › **Back up Debian to the Linux folder** saves the whole Debian system (KDE, your apps, settings and files)
as one file: **Internal storage › Linux › debian-backup.tar.gz**. KDE keeps running meanwhile. It survives
uninstalling KDE Home and can be copied to another phone: on a fresh install the setup screen offers
**Restore Debian from the backup** (no download), and menu › **Restore Debian from backup…** puts an older state back.

## Root mode (no proot)

On a **rooted** phone (Magisk), menu › **Root mode (no proot)…** moves Debian into one image file,
**Internal storage › Linux › debian.img** (its current size plus 8 GB of room; the file only uses space as it fills),
and runs it with `chroot` and real root instead of proot:

- faster (no system-call tracing), and the real `sudo` works;
- Linux programs are started through `su`, outside the app, so Android's process limit doesn't kill them;
- KDE Home's shortcuts, settings, sound, keyboard and app drawer work the same: the image is mounted where proot
  mode keeps Debian, labelled as the app's own data, and the desktop user has the app's Android user ID.

The proot copy is kept until you delete it (same menu), so you can switch back. In Magisk's settings, Mount Namespace
Mode must be **Inherit** (the default) so the app can see the mounted image. Without root, Debian has to stay in the
app's private storage under proot: Android doesn't let apps run programs from shared storage, and shared storage can't
hold Linux permissions or symlinks.

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
- **The main user is an administrator**: `sudo apt install …`, `sudo -i`, `su -` and `pkexec` work in Konsole,
  with no password. Real sudo needs setuid, which proot can't provide, so KDE Home's `sudo` runs commands as root
  with fakeroot (every Linux file belongs to the app's Android user anyway). The user is in the `sudo` group.
- **Sound**: PulseAudio runs inside Linux and plays into a virtual "Android" output, which KDE Home streams
  to the phone's speaker or headphones. Use KDE's volume control or the phone's volume buttons.
- **Discover** (KDE's app store) works through PackageKit: KDE Home starts the system D-Bus, polkit (with no
  password prompts) and PackageKit itself, because systemd isn't there to do it.
  If Discover won't install something, use the menu's **Install Linux apps…** (type package names, e.g.
  `vlc gimp`) or **Linux terminal as root** (`apt install …`). Discover's service messages are in
  Internal storage › Linux › logs › `services.log`.
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
