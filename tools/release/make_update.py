#!/usr/bin/env python3
"""Prepare the files the in-app updater downloads.

    tools/release/make_update.py --from dist/Entropy-v1.0.25.apk --from-code 55 \
        --to dist/Entropy-v1.0.26.apk --to-code 56 --to-name 1.0.26

Writes next to the new APK:
    patch-<from>-<to>.bin   BSDIFF40 patch, applied on the phone by BsPatch.kt
    manifest.json           what UpdateController fetches
    SHA256SUMS.txt

The patch is applied here before anything is written, and must reproduce the
new APK byte for byte.
"""
import argparse
import bz2
import hashlib
import json
import struct
from pathlib import Path

import numpy as np

KEY = 32      # bytes that must agree before two regions count as the same
STEP = 64     # the old file is indexed at every STEP-th offset
CHUNK = 1 << 20


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def common_prefix(a: np.ndarray, ai: int, b: np.ndarray, bi: int) -> int:
    limit = min(len(a) - ai, len(b) - bi)
    done = 0
    while done < limit:
        size = min(CHUNK, limit - done)
        differs = np.flatnonzero(a[ai + done:ai + done + size] != b[bi + done:bi + done + size])
        if differs.size:
            return done + int(differs[0])
        done += size
    return limit


def find_matches(old: bytes, new: bytes):
    """Yields (new_start, old_start, length) for regions of `new` that also occur in `old`, in order."""
    index = {}
    for offset in range(0, len(old) - KEY + 1, STEP):
        index.setdefault(old[offset:offset + KEY], offset)
    old_a = np.frombuffer(old, dtype=np.uint8)
    new_a = np.frombuffer(new, dtype=np.uint8)

    position = 0
    floor = 0  # everything before this offset in `new` is already covered
    last = len(new) - KEY
    while position <= last:
        hit = index.get(new[position:position + KEY])
        if hit is None:
            position += 1
            continue
        start, source = position, hit
        while start > floor and source > 0 and new[start - 1] == old[source - 1]:
            start -= 1
            source -= 1
        length = common_prefix(new_a, start, old_a, source)
        yield start, source, length
        position = floor = start + length


def offtout(value: int) -> bytes:
    """bsdiff's integer encoding: 8 bytes little-endian magnitude, sign in the top bit."""
    raw = bytearray(struct.pack('<Q', abs(value)))
    if value < 0:
        raw[7] |= 0x80
    return bytes(raw)


def offtin(raw: bytes) -> int:
    value = struct.unpack('<Q', raw)[0]
    return -(value & ~(1 << 63)) if value >> 63 else value


def make_patch(old: bytes, new: bytes) -> bytes:
    control = bytearray()
    extra = bytearray()
    matched = 0

    # A bsdiff step is: add `add` bytes from old, copy `extra` literal bytes, seek old by `seek`.
    steps = [[0, 0, 0]]  # the first step only carries the literal bytes before the first match
    old_cursor = 0
    new_cursor = 0
    for new_start, old_start, length in find_matches(old, new):
        literal = new[new_cursor:new_start]
        extra += literal
        steps[-1][1] = len(literal)
        steps[-1][2] = old_start - old_cursor
        steps.append([length, 0, 0])
        matched += length
        old_cursor = old_start + length
        new_cursor = new_start + length
    tail = new[new_cursor:]
    extra += tail
    steps[-1][1] = len(tail)

    for add, copy, seek in steps:
        control += offtout(add) + offtout(copy) + offtout(seek)

    # Matched regions are byte-identical, so every diff byte is zero.
    control_z = bz2.compress(bytes(control))
    diff_z = bz2.compress(bytes(matched))
    extra_z = bz2.compress(bytes(extra))
    header = b'BSDIFF40' + offtout(len(control_z)) + offtout(len(diff_z)) + offtout(len(new))
    return header + control_z + diff_z + extra_z


def apply_patch(old: bytes, patch: bytes) -> bytes:
    """The same procedure as BsPatch.kt, used to prove the patch before it ships."""
    assert patch[:8] == b'BSDIFF40'
    control_len, diff_len, new_size = (offtin(patch[i:i + 8]) for i in (8, 16, 24))
    control = bz2.decompress(patch[32:32 + control_len])
    diff = bz2.decompress(patch[32 + control_len:32 + control_len + diff_len])
    extra = bz2.decompress(patch[32 + control_len + diff_len:])
    out = bytearray()
    old_pos = diff_pos = extra_pos = 0
    for at in range(0, len(control), 24):
        add, copy, seek = (offtin(control[at + i:at + i + 8]) for i in (0, 8, 16))
        block = np.frombuffer(old, dtype=np.uint8, count=add, offset=old_pos)
        delta = np.frombuffer(diff, dtype=np.uint8, count=add, offset=diff_pos)
        out += (block + delta).tobytes()
        out += extra[extra_pos:extra_pos + copy]
        diff_pos += add
        extra_pos += copy
        old_pos += add + seek
    assert len(out) == new_size
    return bytes(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--from', dest='old', required=True, type=Path, help='APK installed on the phone')
    parser.add_argument('--from-code', required=True, type=int)
    parser.add_argument('--to', dest='new', required=True, type=Path, help='APK to update to')
    parser.add_argument('--to-code', required=True, type=int)
    parser.add_argument('--to-name', required=True)
    args = parser.parse_args()
    if args.to_code <= args.from_code:
        parser.error('--to-code must be greater than --from-code')

    old = args.old.read_bytes()
    new = args.new.read_bytes()
    out_dir = args.new.parent

    patch = make_patch(old, new)
    if apply_patch(old, patch) != new:
        raise SystemExit('patch does not reproduce the new APK; nothing written')

    patch_path = out_dir / f'patch-{args.from_code}-{args.to_code}.bin'
    patch_path.write_bytes(patch)
    manifest = {
        'versionCode': args.to_code,
        'versionName': args.to_name,
        'apk': args.new.name,
        'sha256': sha256(new),
        'patch': {
            'fromVersionCode': args.from_code,
            'apk': patch_path.name,
            'sha256': sha256(patch),
        },
    }
    (out_dir / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (out_dir / 'SHA256SUMS.txt').write_text(
        f'{sha256(new)}  {args.new.name}\n{sha256(patch)}  {patch_path.name}\n'
    )
    print(f'{patch_path.name}: {len(patch) / 1e6:.1f} MB (full APK {len(new) / 1e6:.1f} MB)')
    print(f'manifest: {out_dir / "manifest.json"}')


if __name__ == '__main__':
    main()
