#!/usr/bin/env python3
"""Inspect a Mortal .pth checkpoint, or export it to .onnx.

Two modes:

* `--inspect` (default): print the detected architecture (players, version,
  in_channels, conv_channels, block count) *and* the real key namespace, so
  that mismatches like `encoder.net.*` vs `entry.*` are immediately visible.
  This works without torch, by reading the checkpoint's zip/pickle directly.
* `--export`: build the Mortal network in PyTorch and write an ONNX file that
  follows the Akagi custom-model contract
  (`majmax.contract=akagi-policy-v1`, input `obs` float32, output logits).

Usage:
    python export_mortal_onnx.py --inspect model.pth
    python export_mortal_onnx.py --export  model.pth --output policy-4.onnx
"""
import argparse
import pathlib
import re
import struct
import sys
import zipfile

# Mortal observation widths -> (players, version, action space).
IN_CHANNEL_MAP = {
    1012: (4, 4, 46),
    948: (4, 3, 46),
    938: (4, 2, 46),
    450: (4, 1, 46),
    775: (3, 5, 44),
}
MORTAL_TILE_DIM = 34


# --------------------------------------------------------------------------
# torch-free checkpoint reader
# --------------------------------------------------------------------------

class Hashable:
    """Wrap unhashable pickle values so they can key a dict."""

    __slots__ = ('v',)

    def __init__(self, v):
        self.v = v

    def __hash__(self):
        return id(self.v)

    def __eq__(self, other):
        return isinstance(other, Hashable) and other.v is self.v


def _unhashable(x):
    try:
        hash(x)
        return False
    except TypeError:
        return True


def parse_pickle(buf):
    """Run a pickle stream and return (memo, stack)."""
    i = 0
    stack = []
    memo = {}
    marks = []

    def pop_mark():
        start = marks.pop()
        items = stack[start:]
        del stack[start:]
        return items

    n = len(buf)
    while i < n:
        op = buf[i]
        i += 1
        c = chr(op)

        if c == '\x80':
            i += 1
        elif c == '}':
            stack.append({})
        elif c == ']':
            stack.append([])
        elif c == ')':
            stack.append(())
        elif c == '(':
            marks.append(len(stack))
        elif c == 'q':
            memo[buf[i]] = stack[-1]
            i += 1
        elif c == 'r':
            memo[struct.unpack('<I', buf[i:i + 4])[0]] = stack[-1]
            i += 4
        elif c == 'h':
            stack.append(memo[buf[i]])
            i += 1
        elif c == 'j':
            stack.append(memo[struct.unpack('<I', buf[i:i + 4])[0]])
            i += 4
        elif c == 'K':
            stack.append(buf[i])
            i += 1
        elif c == 'M':
            stack.append(struct.unpack('<H', buf[i:i + 2])[0])
            i += 2
        elif c == 'J':
            stack.append(struct.unpack('<i', buf[i:i + 4])[0])
            i += 4
        elif c == 'X':
            ln = struct.unpack('<I', buf[i:i + 4])[0]
            i += 4
            stack.append(buf[i:i + ln].decode('utf-8', 'replace'))
            i += ln
        elif c == '\x8c':
            ln = struct.unpack('<I', buf[i:i + 4])[0]
            i += 4
            stack.append(buf[i:i + ln].decode('utf-8', 'replace'))
            i += ln
        elif c == 'U':
            ln = buf[i]
            i += 1
            stack.append(buf[i:i + ln].decode('latin-1'))
            i += ln
        elif c == 'N':
            stack.append(None)
        elif c == '\x88':
            stack.append(True)
        elif c == '\x89':
            stack.append(False)
        elif c == '\x85':
            stack.append((stack.pop(),))
        elif c == '\x86':
            b = stack.pop()
            a = stack.pop()
            stack.append((a, b))
        elif c == '\x87':
            cc = stack.pop()
            b = stack.pop()
            a = stack.pop()
            stack.append((a, b, cc))
        elif c == 't':
            stack.append(tuple(pop_mark()))
        elif c == 'l':
            stack.append(pop_mark())
        elif c == 'u':
            items = pop_mark()
            d = stack.pop() if stack else {}
            if not isinstance(d, dict):
                d = {}
            for k in range(0, len(items) - 1, 2):
                key = items[k]
                d[Hashable(key) if _unhashable(key) else key] = items[k + 1]
            stack.append(d)
        elif c == 's':
            v = stack.pop()
            k = stack.pop()
            if stack and isinstance(stack[-1], dict):
                stack[-1][Hashable(k) if _unhashable(k) else k] = v
            else:
                stack.append({k: v})
        elif c == 'b':
            # BUILD: args = stack.pop(), obj = stack[-1]. torch uses this to
            # attach `_metadata` to a state dict; the object itself is unchanged
            # for our purposes, so just drop the args.
            if stack:
                stack.pop()
        elif c == 'a':
            stack[-2].append(stack.pop())
        elif c == 'e':
            stack[-1].extend(pop_mark())
        elif c == 'R':
            args = stack.pop()
            fn = stack.pop()
            stack.append((fn, args))
        elif c == 'Q':
            stack.pop()
            stack.append(None)
        elif c == '2':
            stack.append(stack[-1])
        elif c == '0':
            stack.pop()
        elif c == '1':
            pop_mark()
        elif c == 'c':
            j1 = buf.find(b'\n', i)
            j2 = buf.find(b'\n', j1 + 1)
            stack.append(buf[i:j1].decode('latin-1') + '.' + buf[j1 + 1:j2].decode('latin-1'))
            i = j2 + 1
        elif c == 'G':
            stack.append(struct.unpack('>d', buf[i:i + 8])[0])
            i += 8
        elif c == '\x8a':
            ln = buf[i]
            i += 1
            stack.append(int.from_bytes(buf[i:i + ln], 'little', signed=True))
            i += ln
        elif c == 'I':
            j = buf.find(b'\n', i)
            stack.append(int(buf[i:j] or b'0'))
            i = j + 1
        elif c == 'L':
            j = buf.find(b'\n', i)
            stack.append(int(buf[i:j].rstrip(b'L')))
            i = j + 1
        elif c == '.':
            break
    return memo, stack


def unwrap(x):
    return x.v if isinstance(x, Hashable) else x


def tensor_index(node, prefix=''):
    """Walk a state dict and return {key: shape} for every tensor."""
    out = {}

    def first_size(args):
        found = []

        def dig(x):
            x = unwrap(x)
            if isinstance(x, tuple):
                if x and all(isinstance(y, int) for y in x):
                    found.append(tuple(x))
                else:
                    for y in x:
                        dig(y)
        dig(args)
        if not found:
            return None
        found.sort(key=len, reverse=True)
        return found[0]

    def walk(cur, pre):
        cur = unwrap(cur)
        if isinstance(cur, dict):
            for k, v in cur.items():
                k = unwrap(k)
                if not isinstance(k, str) or k == '_metadata':
                    continue
                walk(v, (pre + '.' + k) if pre else k)
        elif isinstance(cur, tuple) and len(cur) == 2 and isinstance(cur[0], str) and isinstance(cur[1], tuple):
            sz = first_size(cur[1])
            if sz:
                out[pre] = sz
    walk(node, prefix)
    return out


def read_checkpoint(path):
    """Return a flat {key: shape} map for the whole checkpoint."""
    z = zipfile.ZipFile(path)
    pkls = [n for n in z.namelist() if n.endswith('data.pkl')]
    if not pkls:
        raise SystemExit(f'{path}: no data.pkl found (not a torch zip checkpoint?)')
    _, stack = parse_pickle(z.read(pkls[0]))
    root = None
    for obj in stack:
        if isinstance(obj, dict) and obj:
            root = obj
    if root is None:
        raise SystemExit(f'{path}: could not locate a state dict')

    flat = {}
    for k, v in root.items():
        ku = unwrap(k)
        if not isinstance(ku, str):
            continue
        sub = tensor_index(v)
        for kk, vv in sub.items():
            flat[kk if kk.startswith(ku + '.') else f'{ku}.{kk}'] = vv
    return flat


# --------------------------------------------------------------------------
# inspection
# --------------------------------------------------------------------------

def describe(flat):
    """Derive the Mortal architecture from a flat key map."""
    entry = None
    for k, v in flat.items():
        if k.endswith('.weight') and len(v) == 3 and re.search(r'(^|\.)(encoder\.net\.0|net\.0|entry\.0)\.weight$', k):
            entry = (k, v)
            break
    if entry is None:
        for k, v in flat.items():
            if k.endswith('.weight') and len(v) == 3:
                entry = (k, v)
                break
    if entry is None:
        raise SystemExit('no 3-D convolution weight found; not a Mortal checkpoint?')
    key, (conv_channels, in_channels, ksize) = entry

    players, version, action_dim = IN_CHANNEL_MAP.get(in_channels, (4, 4, 46))

    blocks = 0
    seq = 1
    while True:
        cand = [f'encoder.net.{seq}.res_unit.2.weight',
                f'mortal.encoder.net.{seq}.res_unit.2.weight',
                f'net.{seq}.res_unit.2.weight',
                f'blocks.{seq}.conv1.0.weight']
        if any(c in flat for c in cand):
            blocks += 1
            seq += 1
        else:
            break

    # A conv at net.<n> is the entry (n=0); res blocks occupy net.1..
    deepest = 0
    for k in flat:
        m = re.search(r'encoder\.net\.(\d+)\.', k)
        if m:
            deepest = max(deepest, int(m.group(1)))

    return {
        'entry_key': key,
        'conv_channels': conv_channels,
        'in_channels': in_channels,
        'kernel_size': ksize,
        'players': players,
        'version': version,
        'action_space': action_dim,
        'blocks': blocks,
        'last_module_index': deepest,
    }


def cmd_inspect(args):
    flat = read_checkpoint(args.input)
    info = describe(flat)

    print(f'checkpoint : {args.input}')
    print(f'tensors    : {len(flat)}')
    print()
    print('detected architecture')
    print(f'  players       : {info["players"]}')
    print(f'  version       : {info["version"]}')
    print(f'  in_channels   : {info["in_channels"]}')
    print(f'  conv_channels : {info["conv_channels"]}')
    print(f'  kernel_size   : {info["kernel_size"]}')
    print(f'  res blocks    : {info["blocks"]}')
    print(f'  action space  : {info["action_space"]}')
    print(f'  entry conv    : {info["entry_key"]}  {flat[info["entry_key"]]}')
    print()

    roots = {}
    for k in flat:
        roots.setdefault(k.split('.')[0], []).append(k)
    print('key namespace (top level)')
    for r in sorted(roots):
        print(f'  {r}: {len(roots[r])} tensors')

    print()
    print('trunk layout (first module of each index)')
    idxs = {}
    for k in flat:
        m = re.search(r'(?:^|\.)encoder\.net\.(\d+)\.(.+)$', k)
        if m:
            idxs.setdefault(int(m.group(1)), set()).add(m.group(2).split('.')[0])
    for i in sorted(idxs)[:12]:
        print(f'  net.{i}: {sorted(idxs[i])}')
    if len(idxs) > 12:
        print(f'  ... (+{len(idxs) - 12} more indices, max net.{max(idxs)})')

    print()
    print('tail modules')
    for i in sorted(idxs)[-4:]:
        mods = sorted(m for m in idxs[i])
        print(f'  net.{i}: {mods}')
        for k in sorted(flat):
            if re.search(rf'(?:^|\.)encoder\.net\.{i}\.', k):
                print(f'      {k}: {flat[k]}')

    print()
    print('head')
    for k in sorted(flat):
        if k.startswith('current_dqn') or re.search(r'\.net\.(weight|bias)$', k):
            print(f'  {k}: {flat[k]}')

    # Sanity hints for the Rust loader.
    print()
    print('loader cross-check')
    checks = [
        ('mortal.encoder.net.0.weight', 'entry conv (real Mortal)'),
        ('mortal.encoder.net.1.res_unit.0.weight', 'first block BatchNorm'),
        ('mortal.encoder.net.1.res_unit.2.weight', 'first block conv1'),
        ('mortal.encoder.net.1.ca.shared_mlp.0.weight', 'channel attention (sibling of res_unit)'),
        ('current_dqn.net.weight', 'policy head'),
    ]
    for k, label in checks:
        mark = 'OK ' if k in flat else '-- '
        print(f'  [{mark}] {k}   ({label})')


# --------------------------------------------------------------------------
# ONNX export
# --------------------------------------------------------------------------

def cmd_export(args):
    try:
        import torch
    except ImportError:
        raise SystemExit(
            'torch is required for --export. Install it, or use --inspect '
            '(which needs no dependencies).'
        )

    from tools.mortal_net import build_mortal_from_checkpoint, export_onnx

    ckpt = torch.load(str(args.input), map_location='cpu', weights_only=True)
    net, meta = build_mortal_from_checkpoint(ckpt)
    export_onnx(net, meta, args.output)
    print(f'exported {args.output} (players={meta["players"]}, '
          f'in_channels={meta["in_channels"]}, action_space={meta["action_space"]})')


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('input', type=pathlib.Path, help='path to a Mortal .pth')
    ap.add_argument('--export', action='store_true',
                    help='export to ONNX instead of inspecting')
    ap.add_argument('--output', type=pathlib.Path, help='ONNX output path')
    args = ap.parse_args()

    if not args.input.exists():
        raise SystemExit(f'{args.input}: not found')

    if args.export:
        if not args.output:
            raise SystemExit('--export requires --output')
        cmd_export(args)
    else:
        cmd_inspect(args)


if __name__ == '__main__':
    sys.exit(main())
