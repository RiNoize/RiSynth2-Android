#!/usr/bin/env python3
"""Extract Synth1 patches from Unify (.unify) presets as .sy1 files.

Usage: tools/unify2sy1.py <file.unify | folder>... -o <out folder>

.unify = JUCE binary XML ("VC2!" + u32 size + UTF-8 XML). Each <Layer> holds
an <Instrument stateInformation="<size>.<JUCE base64>"> whose bytes wrap the
plugin's own state (VST2 fxBank / fxProgram for the VST). Synth1's state is
the "Synth1 VST Chunk Data" blob (tools/s1ref/README.md): int32 LE words,
word 140 = version, param k = pair (1, native) at words 142 + 2k. The
version is written as is, so legacy patches get the usual conversion on load.

Output = one bank folder per library (Unify's `Patches/` level dropped,
"Unified - " / "Synth1 " stripped), files `NNN_<preset>.sy1` in name order; a
library of more than 128 patches is split into "<bank> (1)", "<bank> (2)", ...
"""
import argparse
import re
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MARKER = b"Synth1 VST Chunk Data"
PARAMS = 99
SLOTS = 128
JUCE64 = ".ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+"


def juce_base64(text):
    size, _, enc = text.partition(".")
    out, bit = bytearray(int(size)), 0
    for c in enc:
        v = JUCE64.index(c)
        for i in range(6):
            if v >> i & 1 and bit >> 3 < len(out):
                out[bit >> 3] |= 1 << (bit & 7)
            bit += 1
    return bytes(out)


def unify_xml(data):
    if data[:4] == b"VC2!":
        (size,) = struct.unpack_from("<I", data, 4)
        data = data[8 : 8 + size]
    return ET.fromstring(data.rstrip(b"\0").decode("utf-8"))


def synth1_values(state):
    at = state.find(MARKER)
    if at < 0:
        return None
    words = struct.unpack_from("<%di" % (142 + 2 * PARAMS), state, at)
    return words[140], [words[143 + 2 * k] for k in range(PARAMS)]


def patches(path):
    root = unify_xml(path.read_bytes())
    meta = root.find("PresetMetadata")
    preset = meta.get("name", "") if meta is not None else ""
    for layer in root.iter("Layer"):
        inst = layer.find("Instrument")
        if inst is None or not inst.get("stateInformation"):
            continue
        found = synth1_values(juce_base64(inst.get("stateInformation")))
        if found:
            yield (layer.get("layerTitle") or preset or path.stem), *found


def sy1(name, version, values):
    return f"{name}\ncolor=default\nver={version}\n" + "".join(f"{k},{v}\n" for k, v in enumerate(values))


def safe(name):
    return re.sub(r'[/\\:*?"<>|\x00-\x1f]', "_", name).strip(" .") or "patch"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("inputs", nargs="+", type=Path)
    ap.add_argument("-o", "--out", type=Path, required=True)
    args = ap.parse_args()
    banks, files, skipped = {}, 0, 0
    for root in args.inputs:
        for f in sorted(root.rglob("*.unify")) if root.is_dir() else [root]:
            files += 1
            try:
                found = list(patches(f))
            except Exception as e:
                print(f"{f}: {e}", file=sys.stderr)
                found = []
            skipped += not found
            bank = Path(*(safe(p.removeprefix("Unified - ").removeprefix("Synth1 ")) for p in f.relative_to(root).parent.parts if p != "Patches"))
            for i, patch in enumerate(found):
                banks.setdefault(bank, []).append((safe(f.stem) + (f" {i + 1}" if len(found) > 1 else ""), patch))
    written = 0
    for bank, items in banks.items():
        items.sort(key=lambda it: it[0].lower())
        for part in range(0, len(items), SLOTS):
            dest = args.out / (bank.with_name(f"{bank.name} ({part // SLOTS + 1})") if len(items) > SLOTS else bank)
            dest.mkdir(parents=True, exist_ok=True)
            for slot, (stem, patch) in enumerate(items[part : part + SLOTS], 1):
                (dest / f"{slot:03d}_{stem}.sy1").write_text(sy1(*patch))
                written += 1
    print(f"{written} patches from {files - skipped} / {files} files, {len(banks)} banks")


if __name__ == "__main__":
    main()
