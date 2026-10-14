#!/usr/bin/env python3
"""Inspect an ONNX model's interface without the onnxruntime dependency.

Reads the ModelProto protobuf directly and reports ir_version, opset,
producer, metadata, graph inputs/outputs with their shapes, node op counts, and
a summary of the initializers. That is enough to tell what a model expects
(observation width, action space, dueling head vs combined) without pulling in
a multi-hundred-MB runtime.

Usage:
    python3 scan_onnx.py model.onnx [--initials] [--limit N]
"""
from __future__ import annotations

import argparse
import collections
import sys


# --- minimal protobuf reader ------------------------------------------------

def read_varint(buf, i):
    shift = 0
    result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, i
        shift += 7
        if shift > 63:
            raise ValueError('varint too long')


def fields(buf, start, end):
    """Yield (field_number, wire_type, value) for one message."""
    i = start
    while i < end:
        key, i = read_varint(buf, i)
        fn = key >> 3
        wt = key & 7
        if wt == 0:
            v, i = read_varint(buf, i)
            yield fn, wt, v
        elif wt == 2:
            ln, i = read_varint(buf, i)
            if i + ln > end:
                raise ValueError('length overruns container')
            yield fn, wt, buf[i:i + ln]
            i += ln
        elif wt == 5:
            yield fn, wt, buf[i:i + 4]
            i += 4
        elif wt == 1:
            yield fn, wt, buf[i:i + 8]
            i += 8
        else:
            raise ValueError(f'unsupported wire type {wt}')


def decode_text(b):
    return b.decode('utf-8', 'replace')


# --- ONNX-specific walkers --------------------------------------------------

ELEM_TYPE = {
    1: 'float32', 2: 'uint8', 3: 'int8', 4: 'uint16', 5: 'int16',
    6: 'int32', 7: 'int64', 8: 'string', 9: 'bool',
    10: 'float16', 11: 'float64', 12: 'uint32', 13: 'uint64',
}


def value_info(b):
    """Return (name, elem_type, shape) for a ValueInfoProto."""
    name = None
    elem = None
    shape = None
    for fn, wt, v in fields(b, 0, len(b)):
        if fn == 1 and wt == 2:
            name = decode_text(v)
        elif fn == 2 and wt == 2:
            # TypeProto -> tensor_type (1)
            for tfn, twt, tv in fields(v, 0, len(v)):
                if tfn == 1 and twt == 2:
                    for tfn2, twt2, tv2 in fields(tv, 0, len(tv)):
                        if tfn2 == 1 and twt2 == 0:
                            elem = ELEM_TYPE.get(tv2, f'#{tv2}')
                        elif tfn2 == 2 and twt2 == 2:
                            dims = []
                            for dfn, dwt, dv in fields(tv2, 0, len(tv2)):
                                if dfn == 1 and dwt == 2:
                                    dval = None
                                    for xfn, xwt, xv in fields(dv, 0, len(dv)):
                                        if xfn == 1 and xwt == 0:
                                            dval = xv
                                        elif xfn == 2 and xwt == 2:
                                            dval = decode_text(xv)
                                    dims.append(dval if dval is not None else '?')
                            shape = dims
    return name, elem, shape


def tensor_proto(b):
    """Return (name, elem_type, dims) for a TensorProto initializer."""
    name = None
    elem = None
    dims = []
    for fn, wt, v in fields(b, 0, len(b)):
        if fn == 1 and wt == 2:
            dims.append(v)
        elif fn == 2 and wt == 0:
            elem = ELEM_TYPE.get(v, f'#{v}')
        elif fn == 8 and wt == 2:
            name = decode_text(v)
    return name, elem, dims


def node_op(b):
    for fn, wt, v in fields(b, 0, len(b)):
        if fn == 4 and wt == 2:
            return decode_text(v)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('path')
    ap.add_argument('--limit', type=int, default=40)
    ap.add_argument('--initials', action='store_true')
    args = ap.parse_args()

    buf = open(args.path, 'rb').read()
    print(f'{args.path}: {len(buf)} bytes')

    graph = None
    meta = []
    for fn, wt, v in fields(buf, 0, len(buf)):
        if fn == 1 and wt == 0:
            print(f'ir_version      : {v}')
        elif fn == 2 and wt == 2:
            ver = None
            dom = ''
            for ofn, owt, ov in fields(v, 0, len(v)):
                if ofn == 1 and owt == 2:
                    dom = decode_text(ov)
                elif ofn == 2 and owt == 0:
                    ver = ov
            print(f'opset           : {dom or "ai.onnx"} v{ver}')
        elif fn == 3 and wt == 2:
            print(f'producer_name   : {decode_text(v)}')
        elif fn == 4 and wt == 2:
            print(f'producer_version: {decode_text(v)}')
        elif fn == 6 and wt == 0:
            print(f'model_version   : {v}')
        elif fn == 7 and wt == 2:
            print(f'doc_string      : {decode_text(v)[:200]}')
        elif fn == 8 and wt == 2:
            graph = v
        elif fn == 14 and wt == 2:
            k = val = None
            for mfn, mwt, mv in fields(v, 0, len(v)):
                if mfn == 1 and mwt == 2:
                    k = decode_text(mv)
                elif mfn == 2 and mwt == 2:
                    val = decode_text(mv)
            meta.append((k, val))

    if meta:
        print('\nmetadata_props:')
        for k, v in meta:
            print(f'  {k} = {v}')

    if graph is None:
        print('\nno graph')
        return

    print('\n--- graph ---')
    inputs = []
    outputs = []
    inits = []
    ops = collections.Counter()
    n_nodes = 0
    for fn, wt, v in fields(graph, 0, len(graph)):
        if fn == 1 and wt == 2:
            n_nodes += 1
            op = node_op(v)
            if op:
                ops[op] += 1
        elif fn == 5 and wt == 2:
            inits.append(tensor_proto(v))
        elif fn == 11 and wt == 2:
            inputs.append(value_info(v))
        elif fn == 12 and wt == 2:
            outputs.append(value_info(v))
        elif fn == 2 and wt == 2:
            print(f'graph name      : {decode_text(v)}')

    print(f'nodes           : {n_nodes}')
    print(f'initializers    : {len(inits)}')

    print('\ninputs:')
    for name, elem, shape in inputs:
        print(f'  {name}: {elem} {shape}')
    print('outputs:')
    for name, elem, shape in outputs:
        print(f'  {name}: {elem} {shape}')

    print('\nop histogram (top 15):')
    for op, c in ops.most_common(15):
        print(f'  {op:24s} {c}')

    if args.initials:
        print(f'\ninitializers (first {args.limit}):')
        for name, elem, dims in inits[:args.limit]:
            print(f'  {name:55s} {elem} {dims}')


if __name__ == '__main__':
    sys.exit(main())
