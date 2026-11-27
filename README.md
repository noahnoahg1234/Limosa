# Limosa: virtual machines on Android

**[⬇ Download limosa.apk](https://github.com/noahnoahg1234/Limosa/releases/latest/download/limosa.apk)** (latest build, for arm64 Android 8+) · [all releases](https://github.com/noahnoahg1234/Limosa/releases)

To install, open the link on your phone, open the downloaded file and allow installing apps from your browser when Android asks.

Limosa runs QEMU virtual machines on an Android phone. It has two features to save space and power:

1. **Fake storage from a localhost site.** VM disks are not big image files. They live in a
   thin-provisioned, deduplicating, compressing block store, which the app serves on
   `127.0.0.1`:
   - an **NBD server** (`nbd://127.0.0.1:10809/<disk>`) that QEMU attaches disks from, and
   - a **localhost website** (`http://127.0.0.1:8765`) with the dashboard, the JSON API and raw disk
     access (`/disk/<name>`, with HTTP Range support).

   A 64 GB disk takes **0 bytes** until the guest writes to it. All-zero blocks are dropped.
   Guest TRIM/discard frees space for real. Identical 64 KiB blocks are stored **once across
   all disks**, so two VMs with the same OS share most of their data and "Clone" is instant.
   Each block is also deflate-compressed when that helps.

2. **Auto-pause when the OS is inactive.** When the screen has been off for 30 s (configurable),
   or the phone enters Doze, or the battery is low (and optionally while it is locked or in
   Battery Saver), Limosa pauses every VM that allows it. It stops the vCPUs over QMP and then
   suspends the whole QEMU process with `SIGSTOP`, so a paused VM uses **no CPU**. When you
   come back, only the VMs that Limosa paused are resumed. A VM you paused yourself stays paused.

```
┌──────────── Android app (com.limosa.vm) ─────────────┐
│ MainActivity: WebView ─► http://127.0.0.1:8765        │
│ VmService (foreground)                                │
│  ├─ ChunkStore      dedup + thin + compressed disks   │
│  ├─ NbdServer       127.0.0.1:10809 ◄── QEMU -drive   │
│  ├─ HttpServer      dashboard, API, /disk/<name>      │
│  ├─ VmManager       QEMU processes, QMP stop/cont     │
│  └─ IdleController  ◄── IdleSignals (screen, Doze,    │
│                         lock, battery saver, battery) │
│ QEMU ── VNC websocket ─► noVNC console in the WebView │
└───────────────────────────────────────────────────────┘
```

## KDE Home: Debian + KDE Plasma as your Android home screen

**[⬇ Download kdehome.apk](https://github.com/noahnoahg1234/Limosa/releases/download/kdehome-latest/kdehome.apk)** (arm64 Android 8+)

`kdehome/` is a second app in this repo. It is an Android **launcher** that installs Debian Linux with the
**KDE Plasma** desktop and then shows KDE as your home screen. Your Android apps are shortcuts inside KDE.

1. Install `kdehome.apk` and open **KDE Home**.
2. Tap **Allow access to internal storage**. Linux gets the folder **Internal storage › Linux**
   (`/storage/emulated/0/Linux`).
3. Tap **Install Debian + KDE Plasma**. It downloads Debian (proot-distro's root filesystem, Debian 12 when
   available) and then KDE Plasma with apt. That is about 1.5 GB of downloads and 4 GB on disk, and it takes
   20–60 minutes. You can leave the app; the notification shows progress.
4. Tap **Set as default home screen** and choose KDE Home. From then on, the Home button shows KDE.

### Android apps in KDE

- KDE's app menu gets an **Android** section with every app on the phone.
- The KDE desktop has an **Android Apps** folder, and the phone, messages, camera, browser, Play Store and
  Settings are placed on it directly. In the ▦ drawer, long-press an app and choose **Add to KDE desktop** to add more.
- The ▦ button on the right edge opens the Android app drawer, the keyboard (with Esc, Tab, Ctrl, Alt, ❖, arrows
  and F-keys), clipboard paste, the desktop size setting, Restart KDE and Stop Linux. Drag it up or down to move it.
- Shortcuts update by themselves when you install or remove apps.
- In a Linux terminal: `android-launch com.android.chrome`, `android-open https://kde.org`. Linux programs that
  open links (`BROWSER`) open them on Android.

Touch input: tap = click, long press = right-click, drag = drag, two-finger drag = scroll. A Bluetooth/USB mouse
and keyboard work directly.

### Where things are stored

| Inside Linux | On Android | |
|---|---|---|
| `/home/user/Linux` | Internal storage › Linux | Shared folder, visible in the Files app |
| `/sdcard` | Internal storage | All of internal storage |
| `/` (the Debian system) | the app's private internal storage | |

Internal storage › Linux also holds `installer/` (the downloaded Debian archive, reused if you reinstall) and
`logs/` (`install.log`, `session.log`). The Debian system files can't go in that shared folder: Android's shared
storage has no symlinks, Unix permissions or executable files, and Debian needs all three. So they sit in the
app's own internal storage, which Android removes when you uninstall the app.

### How it works

```
HomeActivity (HOME launcher) ── VncView ◄── RFB 127.0.0.1:5901 ── Xvnc :1 ◄── KDE Plasma (startplasma-x11)
       │ ▦ drawer / keys                                              ▲
LinuxService (foreground) ── proot (libproot.so) ── Debian rootfs ────┘
       └─ AndroidBridge 127.0.0.1:7790 ◄── android-launch / .desktop shortcuts (token-protected)
```

- proot (from Termux's packages, bundled by `scripts/fetch_proot_termux.py`) runs Debian without root. The
  installer runs as fake root, and the desktop runs as `user` (uid 1000), because KDE apps refuse to run as root.
- KDE runs with no compositor, screen locker or file indexer, since none of them help on a phone.
- **Android 12+** may kill Linux programs (the "phantom process" limit). If KDE keeps closing, turn on
  *Developer options › Disable child process restrictions* (Android 14+), or run
  `adb shell device_config put activity_manager max_phantom_processes 2147483647`.
- Without a GPU in proot, KDE draws in software. It works, but expect it to be slower than a native launcher.
  Use **Desktop size…** to trade sharpness for speed.

## Project layout

| Path | What it is |
|---|---|
| `core/` | Pure Kotlin/JVM, no Android dependencies, unit tested: storage, NBD, HTTP API, QEMU/QMP, idle policy |
| `app/` | The Android app: foreground service, idle signal receiver, WebView, file-picker ISO import |
| `app/src/main/assets/web/` | The localhost dashboard and the VNC console (noVNC 1.7.0, MPL-2.0, vendored) |
| `scripts/fetch_qemu_termux.py` | Bundles QEMU into the APK from Termux's Android packages |
| `kdehome/` | KDE Home: the Debian + KDE Plasma home screen app (installer, proot, VNC view, Android shortcuts) |
| `scripts/fetch_proot_termux.py` | Bundles proot into KDE Home from Termux's Android packages |
| `.github/workflows/android.yml` | CI: core tests, QEMU and proot bundling, APK builds |

## Building

Requirements: JDK 17+ and the Android SDK (`ANDROID_HOME` or `local.properties`).

```sh
./gradlew :core:test            # storage / NBD / idle / API tests (NBD tests use qemu-io if installed)
python3 scripts/fetch_qemu_termux.py --abi arm64-v8a --targets x86_64,aarch64   # optional, see below
./gradlew :app:assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
```

Without an Android SDK, `settings.gradle.kts` builds only `:core`.

GitHub Actions builds the APK on every push and publishes it as a GitHub Release, which is
what the download link at the top points to. The release notes say whether QEMU was bundled.

### Bundling QEMU

Android 10+ only lets apps run binaries from their native library folder. So each emulator ships as
`app/src/main/jniLibs/<abi>/libqemu-system-<arch>.so`, with its shared libraries next to it and its
firmware in `app/src/main/assets/qemu/`. `scripts/fetch_qemu_termux.py` fills these folders from
Termux's prebuilt `qemu-system-*-headless` packages. It copies only the libraries QEMU needs and
renames versioned ones (`libz.so.1` becomes `libz_1.so`, patched with `patchelf`), because Android
only packages plain `*.so` files. It needs `readelf` and `patchelf`. Any other Android build of QEMU works too if
you place the files the same way. QEMU must be built with NBD and VNC-websocket support, which are
on by default.

Without QEMU the app still runs (dashboard, disks, ISO import, storage server), and the dashboard
says that VMs can't start yet.

QEMU is GPL software. If you distribute an APK with it, also offer the matching sources.

## Using it

1. Open Limosa. A notification shows that the VM service is running.
2. **Import ISO**: pick an installer image with Android's file picker. It goes straight into the store
   (read-only, deduplicated).
3. **New VM**: choose the architecture, RAM and CPUs. Let it create a thin disk (e.g. 64 GB) and select the ISO.
4. **Start**, then **Console** opens a touch-friendly VNC view (⌨ opens the keyboard, plus Ctrl+Alt+Del).
5. Turn the screen off: after the delay the VM is frozen. Turn it on and it continues.
   The notification shows what is auto-paused and why.

On x86 guests, disks are attached as SATA with `discard=unmap,detect-zeroes=unmap`. On ARM64
guests they use virtio. Either way, a guest that TRIMs (`fstrim`, Windows "Optimize drives")
gives space back to the phone.

### Desktop development

The whole engine runs on Linux/macOS with the system QEMU, which is handy for working on the dashboard:

```sh
./gradlew :core:installDist
core/build/install/core/bin/core /tmp/limosa-data app/src/main/assets/web
# prints: Limosa dashboard: http://127.0.0.1:8765/?t=<token>
```

## API (localhost)

Every request needs the token: the `limosa` cookie (set when you open `/?t=<token>`) or an
`X-Limosa-Token` header.

| Method & path | |
|---|---|
| `GET /api/status` | storage stats (virtual / logical / physical bytes), idle state, NBD info |
| `GET/POST /api/disks` | list / create `{name, sizeGb}` |
| `PUT /api/disks/<name>?readOnly=true` | upload an image (raw body) |
| `POST /api/disks/<name>/clone` | `{target}`: instant copy-on-write clone |
| `DELETE /api/disks/<name>` | delete |
| `GET/HEAD /disk/<name>` | raw disk bytes, supports `Range` |
| `GET/POST /api/vms` | list / save a VM config |
| `POST /api/vms/<name>/start\|stop\|kill\|pause\|resume` | lifecycle |
| `GET /api/vms/<name>/log`, `/command` | QEMU output, generated command line |
| `GET/POST /api/settings` | auto-pause settings |

## Limits

- Speed: on phones without KVM (almost all), x86 guests run under TCG emulation, which is slow.
  Light Linux distributions work best. ARM64 guests on ARM64 phones are faster, and use KVM
  where the kernel exposes `/dev/kvm`.
- Security: the servers listen on loopback only. The dashboard and API need a random token. NBD
  and VNC have no authentication, so other apps on the same phone could reach them while a VM is
  running.
- Android may still stop the app under extreme memory pressure. The store syncs to disk every 5 s
  and on every guest flush, so a crash loses at most the guest's unflushed writes, like a power cut.
