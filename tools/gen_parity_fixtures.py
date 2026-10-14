#!/usr/bin/env python3
"""Generate Mortal observation parity fixtures for the Rust encoder tests.

Writes `external/Akagi/native_bot/weights/mortal_obs_parity.json` containing a
set of deterministic `State` fixtures and the reference observation produced by
`/workspace/mortal_obs_ref.py`.

The Rust side (`tests/mortal_obs_parity.rs`) rebuilds the same states, runs its
own encoder, and asserts the two buffers agree within a tight tolerance. This is
the check that catches "loads fine but plays badly" regressions: the model and
the layout are a matched pair, so a silently drifted channel produces plausible
logits from wrong inputs.
"""
from __future__ import annotations

import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from mortal_obs_ref import (  # noqa: E402
    Candidate,
    FuuroSet,
    KawaItem,
    State,
    TILES,
    encode_obs,
    obs_channels,
)


def fixture_empty() -> State:
    """A default state: everything zeroed, no kawa, no melds."""
    return State()


def fixture_midgame() -> State:
    """A plausible mid-game 4p state with discards and a called meld."""
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
        KawaItem(tile=4, is_tedashi=True, is_tsumogiri=False),
        KawaItem(tile=12, is_tedashi=True),
        KawaItem(tile=20, is_tedashi=False, is_tsumogiri=True),
        KawaItem(tile=28, is_tedashi=True),
    ]
    st.kawa[1] = [
        KawaItem(tile=0, is_tedashi=True),
        KawaItem(tile=8, is_tedashi=True, is_dora=True),
        KawaItem(tile=27, is_riichi=True, is_tedashi=True),
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
    st.fuuro_overview[1] = [
        FuuroSet(tiles=[KawaItem(tile=0), KawaItem(tile=1), KawaItem(tile=2)]),
    ]
    st.ankan_overview[2] = [5]

    st.tiles_seen = [1] * TILES
    st.last_tedashis = [
        KawaItem(tile=28, is_tedashi=True),
        KawaItem(tile=27, is_tedashi=True),
        KawaItem(tile=33, is_tedashi=True),
        None,
    ]
    st.riichi_sutehais = [None, KawaItem(tile=27, is_riichi=True), None, None]
    st.riichi_declared = [False, True, False, False]
    st.riichi_accepted = [False, True, False, False]
    st.waits = [False] * TILES
    st.waits[4] = True
    st.waits[7] = True
    st.at_furiten = False
    st.shanten = 1

    st.can_discard = True
    st.discard_candidates = [0, 4, 8]
    st.keep_shanten_discards = [4]
    st.next_shanten_discards = [0]
    st.unconditional_tenpai_discards = [4]
    st.can_riichi = True
    st.can_chi_low = True
    st.can_pon = True
    st.can_agari = False

    st.sp_tables_unavailable = True
    st.min_tsumo_agari = 1200.0
    return st


def fixture_deep_shanten() -> State:
    """Shanten >= 4: the SP table is unavailable and the tail is skipped."""
    st = State()
    st.tehai = [0] * TILES
    for t in [0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 29, 30, 32]:
        st.tehai[t] += 1
    st.scores = [25000] * 4
    st.kyoku = 0
    st.tiles_left = 69
    st.shanten = 5
    st.can_discard = True
    st.discard_candidates = [0, 3, 6, 9]
    st.sp_tables_unavailable = True
    return st


def fixture_call_window() -> State:
    """A call window: can_pass with a last kawa tile, chi/pon available."""
    st = State()
    st.tehai = [0] * TILES
    for t in [4, 4, 5, 6, 10, 11, 12, 20, 21, 22, 30, 30, 30]:
        st.tehai[t] += 1
    st.scores = [25000, 25000, 25000, 25000]
    st.tiles_left = 30
    st.shanten = 2
    st.kawa[3] = [KawaItem(tile=5, is_tedashi=True)]
    st.kawa_overview[3] = [5]
    st.tiles_seen = [2] * TILES

    st.can_pass = True
    st.last_kawa_tile = KawaItem(tile=5, is_tedashi=True)
    st.dora_factor[5] = 1

    st.can_discard = False
    st.can_chi_low = True
    st.can_chi_mid = True
    st.can_chi_high = True
    st.can_pon = True
    st.can_agari = True
    st.sp_tables_unavailable = True
    st.min_tsumo_agari = 3900.0
    return st


def fixture_with_sp_table() -> State:
    """A state that exercises the v4 SP-table / required-tiles channels."""
    st = fixture_midgame()
    st.sp_tables_unavailable = False
    st.max_ev = 0.85
    c1 = Candidate(
        tile=4,
        shanten_down=False,
        tenpai_probs=[0.3, 0.25, 0.2],
        win_probs=[0.1, 0.09, 0.08],
        exp_values=[0.8, 0.7, 0.6],
        required_tiles=[5, 6, 7],
    )
    c2 = Candidate(
        tile=8,
        shanten_down=True,
        tenpai_probs=[0.2, 0.15],
        win_probs=[0.05, 0.04],
        exp_values=[0.5, 0.4],
        required_tiles=[9, 10],
    )
    st.max_ev_table = [c1, c2]
    return st


FIXTURES = {
    'empty': fixture_empty,
    'midgame': fixture_midgame,
    'deep_shanten': fixture_deep_shanten,
    'call_window': fixture_call_window,
    'with_sp_table': fixture_with_sp_table,
}


def main():
    out = pathlib.Path(__file__).resolve().parent.parent / \
        'external/Akagi/native_bot/weights/mortal_obs_parity.json'
    if len(sys.argv) > 1:
        out = pathlib.Path(sys.argv[1])

    payload = {
        'version': 4,
        'channels': obs_channels(4),
        'tiles': TILES,
        'cases': [],
    }
    for name, build in FIXTURES.items():
        st = build()
        obs = encode_obs(st, 4)
        assert len(obs) == obs_channels(4) * TILES, name
        payload['cases'].append({
            'name': name,
            'obs': [round(v, 7) for v in obs],
        })
        nonzero = sum(1 for v in obs if v != 0.0)
        print(f'  {name:16s} {len(obs):6d} floats, {nonzero:6d} non-zero')

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload))
    print(f'\nwrote {out} ({out.stat().st_size} bytes)')


if __name__ == '__main__':
    main()
