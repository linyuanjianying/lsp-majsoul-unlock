#!/usr/bin/env python3
"""Byte-level scan of a PyTorch .pth for tensor keys and shapes.

Works without torch and without a full pickle VM: it locates each
`_rebuild_tensor_v2` call site, recovers the preceding key string, and reads the
size tuple straight out of the argument encoding. That makes it robust against
opcodes a hand-rolled VM might not implement (the CUDA-saved checkpoints, for
instance, use opcodes that trip a naive reader).

Usage:
    python3 scan_ckpt.py <file.pth> [--filter REGEX] [--limit N] [--summary]
"""
from __future__ import annotations

import argparse
import re
import struct
import sys
import zipfile
from collections import Counter

# Opcodes that terminate an int tuple in the pickle stream.
TUPLE_END = {b'\x85', b'\x86', b'\x87', b'\x88'}


def scan(path: str):
    z = zipfile.ZipFile(path)
    pkls = [n for n in z.namelist() if n.endswith('data.pkl')]
    if not pkls:
        raise SystemExit(f'{path}: no data.pkl')
    buf = z.read(pkls[0])

    out = []
    i = 0
    n_total = len(buf)
    while i < n_total:
        i = buf.find(b'X', i)
        if i < 0:
            break
        if i + 5 > n_total:
            break
        n = struct.unpack('<I', buf[i + 1:i + 5])[0]
        if 1 <= n <= 200 and i + 5 + n <= n_total:
            try:
                s = buf[i + 5:i + 5 + n].decode('utf-8')
            except UnicodeDecodeError:
                i += 1
                continue
            if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.]*', s) and '.' in s:
                after = buf[i + 5 + n:i + 5 + n + 40]
                # Only the first entry spells out `ctorch._utils`; the rest
                # reference it through the memo, e.g. `q\x10h\x05((h\x06h\x07`.
                looks_like_tensor = (
                    b'ctorch._utils' in after
                    or b'((h' in after
                    or b'((X' in after
                    or b'((c' in after
                )
                if looks_like_tensor:
                    j = buf.find(b'QK\x00', i + 5 + n)
                    if 0 < j and (j - (i + 5 + n)) < 400:
                        k = j + 3
                        vals = []
                        while k < n_total:
                            c = buf[k:k + 1]
                            if c == b'K':
                                vals.append(buf[k + 1])
                                k += 2
                            elif c == b'M':
                                vals.append(struct.unpack('<H', buf[k + 1:k + 3])[0])
                                k += 3
                            elif c == b'J':
                                vals.append(struct.unpack('<i', buf[k + 1:k + 5])[0])
                                k += 5
                            elif c in TUPLE_END:
                                break
                            else:
                                break
                        if vals:
                            out.append((s, tuple(vals)))
                            i = i + 5 + n
                            continue
        i += 1
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('path')
    ap.add_argument('--filter', default=None)
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--summary', action='store_true')
    args = ap.parse_args()

    entries = scan(args.path)
    print(f'{args.path}: {len(entries)} tensors')

    if args.summary:
        roots = Counter(k.split('.')[0] for k, _ in entries)
        print('\ntop-level keys:')
        for r, c in roots.most_common():
            print(f'  {r}: {c}')

        # Entry convolution -> in_channels.
        for k, v in entries:
            if k.endswith('.weight') and len(v) == 3 and 'net.0' in k:
                print(f'\nentry conv: {k} {v}')
                print(f'  -> in_channels = {v[1]}, conv_channels = {v[0]}')
                break

        # Trunk module index range.
        idxs = set()
        for k, _ in entries:
            m = re.search(r'encoder\.net\.(\d+)\.', k)
            if m:
                idxs.add(int(m.group(1)))
        if idxs:
            print(f'\ntrunk indices: {len(idxs)} distinct, max {max(idxs)}')

        # Block count = contiguous res_unit run.
        keyset = {k for k, _ in entries}
        blocks = 0
        seq = 1
        while f'encoder.net.{seq}.res_unit.2.weight' in keyset:
            blocks += 1
            seq += 1
        print(f'res blocks  : {blocks}')

        # Head.
        print('\nhead:')
        for k, v in entries:
            if 'dqn' in k or re.search(r'\.net\.(weight|bias)$', k):
                print(f'  {k} {v}')
        return

    keys = sorted(entries)
    if args.filter:
        rx = re.compile(args.filter)
        keys = [(k, v) for k, v in keys if rx.search(k)]
    for i, (k, v) in enumerate(keys):
        if args.limit and i >= args.limit:
            print(f'  ... (+{len(keys) - args.limit} more)')
            break
        print(f'  {k:65s} {v}')


if __name__ == '__main__':
    sys.exit(main())
