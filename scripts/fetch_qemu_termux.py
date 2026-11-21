#!/usr/bin/env python3
"""
Bundle QEMU into the Limosa APK using Termux's prebuilt Android packages.

Android 10+ only lets an app execute files from its native library directory, so:
  bin/qemu-system-<arch>  ->  app/src/main/jniLibs/<abi>/libqemu-system-<arch>.so
  lib/*.so (deps)         ->  app/src/main/jniLibs/<abi>/
  share/qemu/*            ->  app/src/main/assets/qemu/      (BIOS, VGA ROMs, keymaps)

Usage:
  python3 scripts/fetch_qemu_termux.py [--abi arm64-v8a] [--targets x86_64,aarch64,i386]

Only the Python standard library is used. QEMU and its libraries are GPL/LGPL software;
if you distribute the APK, you must also offer the corresponding sources (see Termux's
termux-packages repository).
"""
import argparse
import io
import lzma
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request

MIRROR = "https://packages-cf.termux.dev/apt/termux-main"
ABI_TO_TERMUX = {"arm64-v8a": "aarch64", "x86_64": "x86_64", "armeabi-v7a": "arm"}
TARGET_PKG = {
    "x86_64": "qemu-system-x86-64-headless",
    "i386": "qemu-system-i386-headless",
    "aarch64": "qemu-system-aarch64-headless",
}
PREFIX = "data/data/com.termux/files/usr/"
# Libraries Android itself provides to every app.
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
    "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libmediandk.so",
}
# Never needed by the headless emulator and pull in a lot.
SKIP_PACKAGES = {"termux-tools", "termux-exec", "dash", "bash", "coreutils", "python", "perl"}


def fetch(url):
    print("  GET", url, file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "limosa-build"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def parse_packages(text):
    pkgs = {}
    for stanza in text.split("\n\n"):
        fields, key = {}, None
        for line in stanza.splitlines():
            if line.startswith(" ") and key:
                fields[key] += "\n" + line.strip()
            elif ":" in line:
                key, value = line.split(":", 1)
                fields[key] = value.strip()
        if "Package" in fields:
            pkgs[fields["Package"]] = fields
            for p in fields.get("Provides", "").split(","):
                name = p.strip().split(" ")[0]
                if name and name not in pkgs:
                    pkgs.setdefault("__provides__" + name, fields)
    return pkgs


def resolve(pkgs, roots):
    order, seen = [], set()
    stack = list(roots)
    while stack:
        name = stack.pop()
        if name in seen or name in SKIP_PACKAGES:
            continue
        info = pkgs.get(name) or pkgs.get("__provides__" + name)
        if info is None:
            print(f"  warning: package '{name}' not found, skipping", file=sys.stderr)
            seen.add(name)
            continue
        seen.add(name)
        seen.add(info["Package"])
        order.append(info)
        for dep in info.get("Depends", "").split(","):
            dep = dep.strip()
            if not dep:
                continue
            first = dep.split("|")[0].strip().split(" ")[0]
            stack.append(first)
    return order


def read_ar(data):
    if not data.startswith(b"!<arch>\n"):
        raise ValueError("not a .deb (ar) file")
    pos, members = 8, {}
    while pos + 60 <= len(data):
        header = data[pos:pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        members[name] = data[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
    return members


def open_data_tar(members):
    for name, blob in members.items():
        if name.startswith("data.tar"):
            if name.endswith(".xz"):
                return tarfile.open(fileobj=io.BytesIO(lzma.decompress(blob)))
            return tarfile.open(fileobj=io.BytesIO(blob))
    raise ValueError("no data.tar in package")


def needed(path):
    try:
        out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    libs = []
    for line in out.splitlines():
        if "(NEEDED)" in line and "[" in line:
            libs.append(line.split("[", 1)[1].split("]", 1)[0])
    return libs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--abi", default="arm64-v8a", choices=sorted(ABI_TO_TERMUX))
    ap.add_argument("--targets", default="x86_64,aarch64")
    ap.add_argument("--app", default=os.path.join(os.path.dirname(__file__), "..", "app", "src", "main"))
    args = ap.parse_args()

    targets = [t.strip() for t in args.targets.split(",") if t.strip()]
    for t in targets:
        if t not in TARGET_PKG:
            sys.exit(f"unknown target {t}; choose from {', '.join(TARGET_PKG)}")
    tarch = ABI_TO_TERMUX[args.abi]
    libdir = os.path.join(args.app, "jniLibs", args.abi)
    assetdir = os.path.join(args.app, "assets", "qemu")
    os.makedirs(libdir, exist_ok=True)
    os.makedirs(assetdir, exist_ok=True)

    print(f"Reading Termux package index for {tarch}…", file=sys.stderr)
    index = fetch(f"{MIRROR}/dists/stable/main/binary-{tarch}/Packages").decode()
    pkgs = parse_packages(index)
    plan = resolve(pkgs, [TARGET_PKG[t] for t in targets])
    print(f"{len(plan)} packages: {', '.join(p['Package'] for p in plan)}", file=sys.stderr)

    for info in plan:
        deb = fetch(f"{MIRROR}/{info['Filename']}")
        tar = open_data_tar(read_ar(deb))
        members = {m.name.lstrip("./"): m for m in tar.getmembers()}
        for name, m in members.items():
            if not name.startswith(PREFIX):
                continue
            rel = name[len(PREFIX):]
            # Follow symlinks inside the package (e.g. libfoo.so -> libfoo.so.1.2.3).
            target = m
            hops = 0
            while target.issym() and hops < 8:
                link = os.path.normpath(os.path.join(os.path.dirname(target.name.lstrip("./")), target.linkname))
                target = members.get(link)
                hops += 1
                if target is None:
                    break
            if target is None or not target.isfile():
                continue

            dest = None
            if rel.startswith("bin/qemu-system-"):
                dest = os.path.join(libdir, "lib" + os.path.basename(rel) + ".so")
            elif rel.startswith("lib/") and "/" not in rel[4:] and rel.endswith(".so"):
                dest = os.path.join(libdir, os.path.basename(rel))
            elif rel.startswith("share/qemu/"):
                dest = os.path.join(assetdir, rel[len("share/qemu/"):])
            if dest is None:
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with tar.extractfile(target) as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
            os.chmod(dest, 0o755)

    # Sanity check: every NEEDED library must be bundled or provided by Android.
    have = set(os.listdir(libdir)) | SYSTEM_LIBS
    missing = {}
    checked = False
    for f in sorted(os.listdir(libdir)):
        libs = needed(os.path.join(libdir, f))
        if libs is None:
            continue
        checked = True
        for lib in libs:
            if lib not in have:
                missing.setdefault(lib, []).append(f)
    if not checked:
        print("note: readelf not found, skipped the dependency check", file=sys.stderr)
    elif missing:
        print("ERROR: these libraries are needed but could not be bundled:", file=sys.stderr)
        for lib, users in sorted(missing.items()):
            print(f"  {lib}  (needed by {', '.join(users[:3])})", file=sys.stderr)
        sys.exit(2)

    bins = [f for f in os.listdir(libdir) if f.startswith("libqemu-system-")]
    print(f"Done: {', '.join(sorted(bins))} in {libdir}; firmware in {assetdir}", file=sys.stderr)


if __name__ == "__main__":
    main()
