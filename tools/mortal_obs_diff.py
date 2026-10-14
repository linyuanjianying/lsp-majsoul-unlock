#!/usr/bin/env python3
"""Diff the Rust live-state Mortal encoder against the libriichi reference.

Both sides describe the *same* position: this script builds a `State` for
`mortal_obs_ref.py`, and emits a JSON description the Rust test rebuilds into a
`MortalSeatView`. The Rust encoder then produces its own buffer and the two are
compared channel by channel, so a drifted cursor is caught at the exact channel
rather than as a vague shape mismatch.

Usage:
    python3 tools/mortal_obs_diff.py --emit  <out.json>   # write the fixture
    python3 tools/mortal_obs_diff.py --report <rust.json> # compare
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from mortal_obs_ref import (  # noqa: E402
    KawaItem,
    State,
    TILES,
    encode_obs,
    obs_channels,
)


def build_state() -> State:
    """A mid-game 4p position that touches most regions of the layout."""
    st = State()
    st.tehai = [0] * TILES
    for t in [0, 0, 0, 4, 8, 12, 16, 20, 27, 28, 28, 31, 33]:
        st.tehai[t] += 1

    st.akas_in_hand = [True, False, False]
    st.scores = [25000, 32000, 18000, 25000]
    st.rank = 0
    st.kyoku = 2
    st.honba = 1
    st.kyotaku = 0
    st.bakaze = 27
    st.jikaze = 27
    st.tiles_left = 42

    st.dora_indicators = [0, 8]
    st.doras_seen = 1
    st.doras_owned = [1, 0, 2, 0]

    st.kawa[0] = [
        KawaItem(tile=4, is_tedashi=True),
        KawaItem(tile=12, is_tedashi=True),
        KawaItem(tile=20, is_tedashi=False, is_tsumogiri=True),
        KawaItem(tile=28, is_tedashi=True),
    ]
    st.kawa[1] = [
        KawaItem(tile=0, is_tedashi=True),
        KawaItem(tile=8, is_tedashi=True, is_dora=True),
        KawaItem(tile=27, is_tedashi=True, is_riichi=True),
    ]
    st.kawa[2] = [
        KawaItem(tile=31, is_tedashi=False, is_tsumogiri=True),
        KawaItem(tile=33, is_tedashi=True),
    ]
    st.kawa[3] = []

    st.kawa_overview = [
        [4, 12, 20, 28],
        [0, 8, 27],
        [31, 33],
        [],
    ]

    st.tiles_seen = [1] * TILES
    st.waits = [False] * TILES
    st.waits[4] = True
    st.waits[7] = True
    st.shanten = 1

    st.riichi_declared = [False, True, False, False]
    st.riichi_accepted = [False, True, False, False]

    st.can_discard = True
    st.discard_candidates = [0, 4, 8]
    st.can_riichi = True
    st.can_chi_low = True
    st.can_pon = True

    st.sp_tables_unavailable = True
    return st


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--emit', type=pathlib.Path)
    ap.add_argument('--report', type=pathlib.Path)
    args = ap.parse_args()

    st = build_state()
    ref = encode_obs(st, 4)
    assert len(ref) == obs_channels(4) * TILES

    if args.emit:
        payload = {
            'channels': obs_channels(4),
            'tiles': TILES,
            # The view the Rust test should rebuild.
            'tehai': st.tehai,
            'akas_in_hand': st.akas_in_hand,
            'scores': st.scores,
            'rank': st.rank,
            'kyoku': st.kyoku,
            'honba': st.honba,
            'kyotaku': st.kyotaku,
            'round_wind': 0,
            'seat_wind': 0,
            'tiles_left': st.tiles_left,
            'dora_indicators': st.dora_indicators,
            'doras_seen': st.doras_seen,
            'doras_owned': st.doras_owned,
            'waits': st.waits,
            'shanten': st.shanten,
            'riichi_declared': st.riichi_declared,
            'riichi_accepted': st.riichi_accepted,
            'discard_candidates': st.discard_candidates,
            'can_chi_low': st.can_chi_low,
            'ref': [round(v, 7) for v in ref],
        }
        args.emit.write_text(json.dumps(payload))
        print(f'wrote {args.emit} ({args.emit.stat().st_size} bytes)')
        return

    if args.report:
        rust = json.loads(args.report.read_text())
        got = rust['obs']
        if len(got) != len(ref):
            print(f'LENGTH MISMATCH: rust {len(got)} vs ref {len(ref)}')
            return
        diff = [i for i, (a, b) in enumerate(zip(got, ref)) if abs(a - b) > 1e-5]
        if not diff:
            print('IDENTICAL: all {} floats match'.format(len(ref)))
            return
        print(f'{len(diff)} of {len(ref)} floats differ')
        shown = 0
        for i in diff[:40]:
            ch, tile = divmod(i, TILES)
            print(f'  ch {ch:4d} tile {tile:2d}: rust {got[i]:.6f}  ref {ref[i]:.6f}')
            shown += 1
        if len(diff) > shown:
            print(f'  ... (+{len(diff) - shown} more)')
        return

    ap.error('pass --emit or --report')


if __name__ == '__main__':
    main()
