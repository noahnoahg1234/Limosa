#!/usr/bin/env python3
"""
Bundle proot into the KDE Home APK (the `kdehome` module) from Termux's prebuilt Android packages.

Android 10+ only lets an app execute files from its native library directory, so:
  bin/proot                 ->  kdehome/src/main/jniLibs/<abi>/libproot.so
  libexec/proot/loader      ->  kdehome/src/main/jniLibs/<abi>/libproot-loader.so   (PROOT_LOADER)
  libexec/proot/loader32    ->  kdehome/src/main/jniLibs/<abi>/libproot-loader32.so (PROOT_LOADER_32)
  lib/libtalloc.so.2        ->  kdehome/src/main/jniLibs/<abi>/libtalloc_2.so

Usage:
  python3 scripts/fetch_proot_termux.py [--abi arm64-v8a]

Needs readelf and patchelf. proot is GPL-2.0 and talloc is LGPL-3.0; if you distribute the APK,
also offer the corresponding sources (see Termux's termux-packages repository).
"""
import argparse
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fetch_qemu_termux import (  # noqa: E402  (shared helpers)
    ABI_TO_TERMUX, MIRROR, PREFIX, SYSTEM_LIBS, bundle, fetch, open_data_tar, parse_packages, read_ar, resolve,
)


def extract(tar, staging):
    """Copies bin/proot, libexec/proot/* and lib/*.so* (symlinks resolved) into staging."""
    members = {m.name.lstrip("./"): m for m in tar.getmembers()}
    for name, m in members.items():
        if not name.startswith(PREFIX):
            continue
        rel = name[len(PREFIX):]
        if not (rel == "bin/proot" or rel.startswith("libexec/proot/")
                or (rel.startswith("lib/") and "/" not in rel[4:] and ".so" in rel)):
            continue
        target, hops = m, 0
        while target is not None and target.issym() and hops < 8:
            link = os.path.normpath(os.path.join(os.path.dirname(target.name.lstrip("./")), target.linkname))
            target = members.get(link)
            hops += 1
        if target is None or not target.isfile():
            continue
        dest = os.path.join(staging, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with tar.extractfile(target) as src, open(dest, "wb") as out:
            shutil.copyfileobj(src, out)
        os.chmod(dest, 0o755)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--abi", default="arm64-v8a", choices=sorted(ABI_TO_TERMUX))
    ap.add_argument("--app", default=os.path.join(os.path.dirname(__file__), "..", "kdehome", "src", "main"))
    args = ap.parse_args()

    tarch = ABI_TO_TERMUX[args.abi]
    libdir = os.path.join(args.app, "jniLibs", args.abi)
    os.makedirs(libdir, exist_ok=True)

    print(f"Reading Termux package index for {tarch}…", file=sys.stderr)
    pkgs = parse_packages(fetch(f"{MIRROR}/dists/stable/main/binary-{tarch}/Packages").decode())
    plan = resolve(pkgs, ["proot"])
    print(f"{len(plan)} packages: {', '.join(p['Package'] for p in plan)}", file=sys.stderr)

    staging = tempfile.mkdtemp(prefix="kdehome-proot-")
    for info in plan:
        extract(open_data_tar(read_ar(fetch(f"{MIRROR}/{info['Filename']}"))), staging)

    proot = os.path.join(staging, "bin", "proot")
    if not os.path.exists(proot):
        sys.exit("ERROR: bin/proot was not found in the packages")
    # libproot.so plus its renamed shared libraries (libtalloc_2.so).
    bundle([proot], os.path.join(staging, "lib"), libdir, SYSTEM_LIBS)

    # The loaders are static executables: copy them as they are.
    found = 0
    for name in ("loader", "loader32"):
        src = os.path.join(staging, "libexec", "proot", name)
        if os.path.exists(src):
            dest = os.path.join(libdir, f"libproot-{name}.so")
            shutil.copyfile(src, dest)
            os.chmod(dest, 0o755)
            found += 1
    if not os.path.exists(os.path.join(libdir, "libproot-loader.so")):
        sys.exit("ERROR: libexec/proot/loader was not found in the proot package")
    shutil.rmtree(staging, ignore_errors=True)
    print(f"Done: {', '.join(sorted(os.listdir(libdir)))} in {libdir}", file=sys.stderr)


if __name__ == "__main__":
    main()
