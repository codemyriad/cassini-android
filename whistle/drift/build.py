#!/usr/bin/env python3
"""Build lab-only dispatch/math variants from the pinned Needle archive and pinned musl source."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import platform
import re
import subprocess
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "whistle/drift"
MATH = ["expf", "logf", "sinf", "cosf", "powf", "sincosf", "exp", "log", "log10", "sin", "cos", "sincos"]


def run(*args):
    result = subprocess.run([str(x) for x in args], check=True, capture_output=True, text=True)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--musl", type=Path)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location("native", ROOT / "scripts/build-whistle-runtime.py")
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    properties = (args.ndk / "source.properties").read_text()
    if not re.search(rf"^Pkg\.Revision\s*=\s*{re.escape(native.NDK_VERSION)}\s*$", properties, re.MULTILINE):
        raise ValueError("Unexpected NDK revision for controlled experiment")
    pins = json.loads((ROOT / "whistle/pins.json").read_text())
    for name, pin in pins["arm64-v8a"].items():
        native.checked(args.inputs / name, pin)
    pin = json.loads((SOURCE / "pins.json").read_text())["musl"]
    args.musl = args.musl or out / "musl-1.2.5.tar.gz"
    native.fetch(args.musl, pin, args.offline)
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}[platform.system()]
    tools = args.ndk / "toolchains/llvm/prebuilt" / host / "bin"
    clang = tools / "aarch64-linux-android26-clang"
    mathdir = out / "musl"
    mathdir.mkdir(exist_ok=True)
    selected = {}
    with tarfile.open(args.musl) as archive:
        for item in archive.getmembers():
            relative = Path(item.name).relative_to("musl-1.2.5")
            keep = (relative.parent == Path("src/math") and relative.suffix in (".c", ".h")) or str(relative) in (
                "src/internal/libm.h",
                "arch/aarch64/fp_arch.h",
                "COPYRIGHT",
            )
            if not item.isfile() or not keep or ".." in relative.parts:
                continue
            target = mathdir / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            data = archive.extractfile(item).read()
            target.write_bytes(data)
            selected[str(relative)] = hashlib.sha256(data).hexdigest()
    # Compile the closure of the imported math roots. Link-time namespacing also rewrites internal
    # references, so a private helper cannot accidentally resolve to Bionic.
    compiled = {}
    pending = [name + ".c" for name in MATH]
    flags = [
        "-std=c11",
        "-O2",
        "-fPIC",
        "-fno-builtin",
        "-ffp-contract=off",
        "-fno-fast-math",
        "-fvisibility=hidden",
        "-include",
        SOURCE / "port.h",
        "-I",
        mathdir / "src/internal",
        "-I",
        mathdir / "src/math",
        "-I",
        mathdir / "arch/aarch64",
    ]
    while pending:
        filename = pending.pop()
        if filename in compiled:
            continue
        obj = out / (filename + ".o")
        run(clang, *flags, "-c", mathdir / "src/math" / filename, "-o", obj)
        compiled[filename] = obj
        for line in run(tools / "llvm-nm", "--undefined-only", "--format=posix", obj).splitlines():
            symbol = line.split()[0]
            provider = "powf_data" if symbol == "__powf_log2_data" else symbol.removeprefix("__")
            for candidate in [symbol + ".c", provider + ".c"]:
                if (mathdir / "src/math" / candidate).is_file() and candidate not in compiled:
                    pending.append(candidate)
    names = set()
    for obj in compiled.values():
        for line in run(tools / "llvm-nm", "--defined-only", "--extern-only", "--format=posix", obj).splitlines():
            names.add(line.split()[0])
    mapping = out / "math-symbols.txt"
    mapping.write_text("".join(f"{name} cassini_musl_{name}\n" for name in sorted(names)))
    private_objects = []
    for filename, obj in sorted(compiled.items()):
        target = out / ("private-" + filename + ".o")
        run(tools / "llvm-objcopy", f"--redefine-syms={mapping}", obj, target)
        private_objects.append(target)
    musllib = out / "libcassini_musl.a"
    if musllib.exists():
        musllib.unlink()
    run(tools / "llvm-ar", "rcs", musllib, *private_objects)
    exports = out / "exports.map"
    exports.write_text(
        (ROOT / "whistle/exports.map").read_text().replace("global:", "global:\n    cassini_whistle_probe;")
    )
    for dispatch_name, dispatch in [("natural", -1), ("off", 0), ("on", 1)]:
        for math_name, pinned_math in [("system", 0), ("musl", 1)]:
            name = dispatch_name + "-" + math_name
            directory = out / name
            directory.mkdir(exist_ok=True)
            wrapper = directory / "wrap.o"
            run(
                clang,
                "-std=c11",
                "-O2",
                "-fPIC",
                "-fno-builtin",
                "-Wall",
                "-Wextra",
                "-Werror",
                f"-DDISPATCH={dispatch}",
                f"-DPINNED_MATH={pinned_math}",
                "-c",
                SOURCE / "wrap.c",
                "-o",
                wrapper,
            )
            library = directory / "libcassini_needle.so"
            command = [
                tools / "aarch64-linux-android26-clang++",
                "-std=c++17",
                "-O2",
                "-shared",
                "-fPIC",
                ROOT / "whistle/abi-check.cpp",
                "-I",
                args.inputs,
                "-Wl,--whole-archive",
                args.inputs / "libneedle.a",
                "-Wl,--no-whole-archive",
                wrapper,
            ]
            if pinned_math:
                command.append(musllib)
            command += [f"-Wl,--wrap={symbol}" for symbol in ["getauxval"] + MATH]
            command += [
                "-static-libstdc++",
                "-Wl,--no-undefined",
                "-Wl,-z,text",
                "-Wl,-z,now",
                "-Wl,-z,relro",
                "-Wl,-z,max-page-size=16384",
                "-Wl,-z,common-page-size=16384",
                f"-Wl,--version-script={exports}",
                "-Wl,-soname,libcassini_needle.so",
                "-o",
                library,
            ]
            run(*command)
            run(tools / "llvm-strip", "--strip-unneeded", library)
            dynamic = run(tools / "llvm-readelf", "-d", library)
            needed = set(re.findall(r"\(NEEDED\).*\[([^]]+)\]", dynamic))
            if not {"libc.so", "libdl.so"} <= needed <= native.SYSTEM_LIBRARIES:
                raise ValueError(f"Unexpected dependency set: {needed}")
            report = native.verify_elf(
                library,
                tools / "llvm-readelf",
                exports_expected=native.EXPORTS | {"cassini_whistle_probe"},
                libraries_expected=needed,
            )
            if pinned_math and any(x.split("@")[0] in MATH for x in report["imports"]):
                raise ValueError("Pinned math still imports a substituted root from system libm")
            report.update(
                size=library.stat().st_size,
                sha256=hashlib.sha256(library.read_bytes()).hexdigest(),
                dispatch=dispatch_name,
                math=math_name,
                engineRevision=pins["engineRevision"],
                ndk=native.NDK_VERSION,
                compiler=run(clang, "--version").splitlines()[0],
                inputSha256=pins["arm64-v8a"]["libneedle.a"]["sha256"],
                musl=pin if pinned_math else None,
                mathSources={name: selected["src/math/" + name] for name in compiled} if pinned_math else {},
            )
            (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n")
            print(name, report["sha256"], sorted(needed), flush=True)
    run(
        clang,
        "-std=c11",
        "-O2",
        "-fPIE",
        "-pie",
        SOURCE / "dotprod-smoke.c",
        "-Wl,-z,max-page-size=16384",
        "-o",
        out / "dotprod-smoke",
    )
    (out / "math-source-manifest.json").write_text(
        json.dumps(
            {
                "pin": pin,
                "files": {name: selected["src/math/" + name] for name in sorted(compiled)},
                "definedSymbols": sorted(names),
                "compilerFlags": [str(x) for x in flags],
            },
            indent=2,
        )
        + "\n"
    )


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stderr, file=sys.stderr)
        raise
