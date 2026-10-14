#!/usr/bin/env python3
"""Diagnostic helper: list the `self.idx += ...` advances in Mortal's encoder.

This walks `libriichi/src/state/obs_repr.rs` and prints every channel advance in
document order with its surrounding context, so you can see the layout as the
upstream source expresses it.

**It is not the authority on the channel count.** It cannot tell which arm of an
`if`/`match` a given version takes, so it counts both branches of the
`if cans.can_discard { ... } else { ... }` tail and its total is wrong. Use it to
read the structure, not to total it.

The authoritative check is `tools/mortal_obs_ref.py --budget`, which executes a
faithful port of the encoder and asserts the cursor lands on `obs_shape(4)`,
matching libriichi's own `assert_eq!(self.idx, self.arr.rows())`.
"""
import re
import sys

VERSION = 4
MAX_NUM_TURNS = 17
SELF_KAWA_ITEM_CHANNELS = 4
KAWA_ITEM_CHANNELS = 8

# Loop bounds for an empty/default state: nothing is in kawa, no fuuro, etc.
N_PLAYERS = 4
KAWA0_LEN = 0
KAWA_OTHER_LEN = 0
FUuro_SETS = 0


def label(lines, i):
    ctx = [x.strip() for x in lines[max(0, i - 6):i] if x.strip()]
    return ' | '.join(ctx)[-90:]


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else 'libriichi/src/state/obs_repr.rs'
    src = open(path).read()
    start = src.index('fn encode_obs(mut self)')
    end = src.index('fn encode_ev', start)
    body = src[start:end]
    lines = body.split('\n')

    env = {
        'SELF_KAWA_ITEM_CHANNELS': SELF_KAWA_ITEM_CHANNELS,
        'KAWA_ITEM_CHANNELS': KAWA_ITEM_CHANNELS,
        'MAX_NUM_TURNS': MAX_NUM_TURNS,
        'state': type('S', (), {'kawa': [[{} for _ in range(KAWA0_LEN)]] * N_PLAYERS})(),
    }

    idx = 0
    print(f'{"+adv":>8}  {"total":>6}  source')
    print('-' * 90)
    for i, ln in enumerate(lines):
        m = re.search(r'self\.idx \+= (.+?);', ln)
        if not m:
            continue
        expr = m.group(1).strip()

        # Evaluate only the loop-carried quantities we know for the default
        # state; anything else is reported verbatim.
        def ev(e):
            e = e.replace('state.kawa[0].len()', 'KAWA0_LEN')
            e = e.replace('player_kawa.len()', 'KAWA_OTHER_LEN')
            e = e.replace('player_fuuro.len()', 'FUuro_SETS')
            try:
                return int(eval(e, {'__builtins__': {}}, {
                    'SELF_KAWA_ITEM_CHANNELS': SELF_KAWA_ITEM_CHANNELS,
                    'KAWA_ITEM_CHANNELS': KAWA_ITEM_CHANNELS,
                    'MAX_NUM_TURNS': MAX_NUM_TURNS,
                    'KAWA0_LEN': KAWA0_LEN,
                    'KAWA_OTHER_LEN': KAWA_OTHER_LEN,
                    'FUuro_SETS': FUuro_SETS,
                }))
            except Exception:
                return None

        v = ev(expr)
        if v is None:
            print(f'{"?":>8}  {"?":>6}  idx += {expr}   <- {label(lines, i)}')
            continue
        # Sections gated on `version` only apply for v4.
        idx += v
        print(f'{v:>8}  {idx:>6}  {label(lines, i)}')

    print('-' * 90)
    print(f'sum of listed advances: {idx}  (NOT the channel count - see module doc)')
    print(f'authoritative total  : run `tools/mortal_obs_ref.py --budget`')


if __name__ == '__main__':
    main()
