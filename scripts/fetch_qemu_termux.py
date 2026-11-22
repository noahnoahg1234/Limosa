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


def extract_package(tar, staging, assetdir):
    """Copies bin/qemu-system-*, every lib/ file (symlinks resolved) and share/qemu/ out of a package."""
    members = {m.name.lstrip("./"): m for m in tar.getmembers()}
    for name, m in members.items():
        if not name.startswith(PREFIX):
            continue
        rel = name[len(PREFIX):]
        target, hops = m, 0
        while target is not None and target.issym() and hops < 8:
            link = os.path.normpath(os.path.join(os.path.dirname(target.name.lstrip("./")), target.linkname))
            target = members.get(link)
            hops += 1
        if target is None or not target.isfile():
            continue
        if rel.startswith("bin/qemu-system-"):
            dest = os.path.join(staging, rel)
        elif rel.startswith("lib/") and "/" not in rel[4:] and ".so" in rel:
            dest = os.path.join(staging, rel)
        elif rel.startswith("share/qemu/"):
            dest = os.path.join(assetdir, rel[len("share/qemu/"):])
        else:
            continue
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with tar.extractfile(target) as src, open(dest, "wb") as out:
            shutil.copyfileobj(src, out)
        os.chmod(dest, 0o755)


def android_name(soname):
    """libz.so.1 -> libz_1.so; libfoo.so stays. Android only packages and loads plain *.so names."""
    base, _, ver = soname.partition(".so")
    ver = ver.lstrip(".")
    return soname if not ver else f"{base}_{ver.replace('.', '_')}.so"


def bundle(roots, libsrc, libdir, system_libs):
    """Copies the binaries and the transitive closure of their NEEDED libraries into libdir,
    renaming versioned libraries and rewriting NEEDED/SONAME entries with patchelf."""
    if shutil.which("patchelf") is None or shutil.which("readelf") is None:
        sys.exit("ERROR: patchelf and readelf are required (apt-get install patchelf binutils)")
    files = {}      # original soname -> source path
    queue = list(roots)
    missing = {}
    seen = set()
    while queue:
        path = queue.pop()
        for lib in needed(path) or []:
            if lib in system_libs or lib in seen:
                continue
            seen.add(lib)
            src = os.path.join(libsrc, lib)
            if not os.path.isfile(src):
                missing.setdefault(lib, []).append(os.path.basename(path))
                continue
            files[lib] = src
            queue.append(src)
    if missing:
        print("ERROR: these libraries are needed but are in no downloaded package:", file=sys.stderr)
        for lib, users in sorted(missing.items()):
            print(f"  {lib}  (needed by {', '.join(users[:3])})", file=sys.stderr)
        sys.exit(2)

    outputs = []
    for r in roots:
        dest = os.path.join(libdir, "lib" + os.path.basename(r) + ".so")
        shutil.copyfile(r, dest)
        outputs.append(dest)
    for soname, src in files.items():
        dest = os.path.join(libdir, android_name(soname))
        shutil.copyfile(src, dest)
        subprocess.run(["patchelf", "--set-soname", android_name(soname), dest], check=True)
        outputs.append(dest)
    for dest in outputs:
        os.chmod(dest, 0o755)
        args = []
        for lib in needed(dest) or []:
            if lib in files and android_name(lib) != lib:
                args += ["--replace-needed", lib, android_name(lib)]
        if args:
            subprocess.run(["patchelf", *args, dest], check=True)
        # The Termux library path does not exist inside the app.
        subprocess.run(["patchelf", "--remove-rpath", dest], check=True)
    print(f"Bundled {len(outputs)} files ({sum(os.path.getsize(o) for o in outputs) // (1 << 20)} MiB)", file=sys.stderr)


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

    import tempfile
    staging = tempfile.mkdtemp(prefix="limosa-qemu-")
    for info in plan:
        deb = fetch(f"{MIRROR}/{info['Filename']}")
        extract_package(open_data_tar(read_ar(deb)), staging, assetdir)

    roots = [os.path.join(staging, "bin", "qemu-system-" + t) for t in targets]
    for r in roots:
        if not os.path.exists(r):
            sys.exit(f"ERROR: {os.path.basename(r)} was not found in the packages")
    bundle(roots, os.path.join(staging, "lib"), libdir, SYSTEM_LIBS)
    shutil.rmtree(staging, ignore_errors=True)

    bins = [f for f in os.listdir(libdir) if f.startswith("libqemu-system-")]
    print(f"Done: {', '.join(sorted(bins))} in {libdir}; firmware in {assetdir}", file=sys.stderr)


if __name__ == "__main__":
    main()
