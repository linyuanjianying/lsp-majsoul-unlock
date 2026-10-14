#!/usr/bin/env python3
"""Reference implementation of Mortal's v4 observation encoding (libriichi).

Ported from `libriichi/src/state/obs_repr.rs`:
`ObsEncoderContext::{encode_obs, encode_ev, encode_sp_table, encode_tile_set}`.

The model weights and this layout are a matched pair: Mortal emits exactly
`obs_shape(4) = (1012, 34)` floats, flattened channel-major as
`buf[ch * 34 + tile]`. Any divergence silently degrades play strength instead of
raising an error, so this module doubles as the executable specification the
Rust encoder in `external/Akagi/native_bot/src/mortal.rs` is checked against.

Verified channel budget for version 4 (see `budget()`):

   tehai thresholds                    4
   akas in hand                        3
   score plane 1 (0..100k / 100k)      4
   score plane 2 (0..30k / 30k)        4
   rank one-hot                        4
   kyoku one-hot                       4
   honba   (rescale /10)               1
   kyotaku (rescale /10)               1
   bakaze + jikaze one-hot             2
   round progress ((bakaze-E)*4+kyoku)/7  1
   dora indicators (tile_set = 7)      7
   self kawa first 6  x 4             24
   self kawa last 18 x 4              72
   self kawa alpha-weighted            1
   others kawa 3 x (6*8 + 18*8 + 3)  585
   tiles_left / 69                     1
   doras_owned  x4 (rescale /12)       4
   doras_unseen (rescale /23)          1
   kawa overview 4 x 7                28
   fuuro overview 4 x 4 x 5           80
   ankan overview                      4
   tiles_seen / 4                      1
   last tedashi x3 (tile/aka/dora)     9
   riichi sutehai x3                   9
   others riichi declared              3
   others riichi accepted              3
   waits one-hot                       1
   at_furiten                          1
   shanten one-hot (cap 6)             7
   self riichi accepted                1
   at_kan_select                       1
   call-context last kawa              3
   --- decision flags (v4) ---
   can_discard block                   5
   can_riichi                          1
   can_chi x3                          3
   can_pon                             1
   can_daiminkan                       1
   can_ankan                           1
   can_kakan                           1
   can_agari                           1
   can_ryukyoku                        1
   --- v4 single-player-table tail ---
   required tiles (2*34)              68
   max required-tiles discard          2
   SP table (3 x 17)                  51
   encode_ev                           2
   -----------------------------------------
   total                            1012
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

TILES = 34
NUM_PLAYERS = 4
MAX_VERSION = 4
MAX_NUM_TURNS = 17

#: `obs_shape(version)` from libriichi/src/consts.rs.
OBS_SHAPE = {1: 938, 2: 942, 3: 934, 4: 1012}

SELF_KAWA_ITEM_CHANNELS = 4
KAWA_ITEM_CHANNELS = 8
TILE_SET_CHANNELS = 7          # 4 count rows + 3 aka rows

HONBA_CAP_V4 = 10
SHANTEN_CAP = 6


def obs_channels(version: int = 4) -> int:
    return OBS_SHAPE[version]


def _deaka(tile: int) -> int:
    """Raw 0..135 tile id -> 0..33 index, merging the red five."""
    return tile // 4


def _is_aka_tile_id(tile: int) -> bool:
    return tile in (16, 52, 88)     # 5mr, 5pr, 5sr


class Plane:
    """The `(channels, 34)` accumulator, mirroring `Simple2DArray<34, f32>`."""

    def __init__(self, channels: int = 1012):
        self.channels = channels
        self.buf = [[0.0] * TILES for _ in range(channels)]
        self.idx = 0

    def assign(self, ch: int, tile: int, value: float) -> None:
        if 0 <= ch < self.channels and 0 <= tile < TILES:
            self.buf[ch][tile] = value

    def fill(self, ch: int, value: float) -> None:
        """`fill(row, v)` — set every tile column in one channel."""
        if 0 <= ch < self.channels:
            self.buf[ch] = [value] * TILES

    def fill_rows(self, base: int, rows: int, value: float) -> None:
        """`fill_rows(idx, n, v)` — set channels [base, base+rows) entirely."""
        for ch in range(base, base + rows):
            self.fill(ch, value)

    def assign_rows(self, base: int, tile: int, rows: int, value: float) -> None:
        """`assign_rows(idx, tile, n, v)` — column `tile` in n consecutive rows."""
        for ch in range(base, base + rows):
            self.assign(ch, tile, value)

    def advance(self, n: int) -> None:
        self.idx += n

    def take(self) -> list[float]:
        return [v for row in self.buf for v in row]


@dataclass
class KawaItem:
    """One discard, as `encode_self_kawa` / `encode_kawa` see it."""
    tile: int                       # de-aka'd 0..33
    is_aka: bool = False
    is_riichi: bool = False
    is_tedashi: bool = False
    is_tsumogiri: bool = False
    is_dora: bool = False
    #: tiles of the meld declared off this discard (chi/pon), de-aka'd
    chi_pon: tuple[int, int] | None = None
    #: kan tiles, if this kawa slot holds a kan
    kan: tuple[int, ...] = ()


@dataclass
class FuuroSet:
    """One called meld for `fuuro_overview`."""
    tiles: list[KawaItem] = field(default_factory=list)


@dataclass
class Candidate:
    """One row of the single-player-table for `encode_sp_table`."""
    tile: int                                   # de-aka'd discard candidate
    shanten_down: bool = False
    tenpai_probs: list[float] = field(default_factory=list)
    win_probs: list[float] = field(default_factory=list)
    exp_values: list[float] = field(default_factory=list)
    required_tiles: list[int] = field(default_factory=list)


@dataclass
class State:
    """The subset of `PlayerState` the v4 encoder reads.

    All per-seat collections are in *self-relative* order: index 0 is the
    deciding player, then shimocha / toimen / kamicha.
    """

    tehai: list[int] = field(default_factory=lambda: [0] * TILES)
    akas_in_hand: list[bool] = field(default_factory=lambda: [False] * 3)
    scores: list[int] = field(default_factory=lambda: [25000] * NUM_PLAYERS)
    rank: int = 0
    kyoku: int = 0
    honba: int = 0
    kyotaku: int = 0
    bakaze: int = 27                 # 27=E 28=S 29=W 30=N on the 34 axis
    jikaze: int = 27

    dora_indicators: list[int] = field(default_factory=list)
    kawa: list[list[KawaItem | None]] = field(
        default_factory=lambda: [[] for _ in range(NUM_PLAYERS)]
    )
    tiles_left: int = 69
    doras_owned: list[int] = field(default_factory=lambda: [0] * NUM_PLAYERS)
    doras_seen: int = 0
    kawa_overview: list[list[int]] = field(
        default_factory=lambda: [[] for _ in range(NUM_PLAYERS)]
    )
    fuuro_overview: list[list[FuuroSet]] = field(
        default_factory=lambda: [[] for _ in range(NUM_PLAYERS)]
    )
    ankan_overview: list[list[int]] = field(
        default_factory=lambda: [[] for _ in range(NUM_PLAYERS)]
    )
    tiles_seen: list[int] = field(default_factory=lambda: [0] * TILES)
    last_tedashis: list[KawaItem | None] = field(
        default_factory=lambda: [None] * NUM_PLAYERS
    )
    riichi_sutehais: list[KawaItem | None] = field(
        default_factory=lambda: [None] * NUM_PLAYERS
    )
    riichi_declared: list[bool] = field(default_factory=lambda: [False] * NUM_PLAYERS)
    riichi_accepted: list[bool] = field(default_factory=lambda: [False] * NUM_PLAYERS)
    waits: list[bool] = field(default_factory=lambda: [False] * TILES)
    at_furiten: bool = False
    shanten: int = 6

    # -- decision-context extras ------------------------------------------
    at_kan_select: bool = False
    can_pass: bool = False
    last_kawa_tile: KawaItem | None = None
    dora_factor: list[int] = field(default_factory=lambda: [0] * TILES)

    can_discard: bool = True
    discard_candidates: list[int] = field(default_factory=list)      # 0..33
    keep_shanten_discards: list[int] = field(default_factory=list)
    next_shanten_discards: list[int] = field(default_factory=list)
    unconditional_tenpai_discards: list[int] = field(default_factory=list)
    can_riichi: bool = False
    can_chi_low: bool = False
    can_chi_mid: bool = False
    can_chi_high: bool = False
    can_pon: bool = False
    can_daiminkan: bool = False
    can_ankan: bool = False
    ankan_candidates: list[int] = field(default_factory=list)
    can_kakan: bool = False
    kakan_candidates: list[int] = field(default_factory=list)
    can_agari: bool = False
    can_ryukyoku: bool = False

    # -- v4 single-player-table -------------------------------------------
    max_ev_table: list[Candidate] = field(default_factory=list)
    max_ev: float = 0.0
    #: set when the SP table is unavailable (shanten too high / no table)
    sp_tables_unavailable: bool = False
    min_tsumo_agari: float = 0.0


# ---------------------------------------------------------------------------
# encoder
# ---------------------------------------------------------------------------

def _encode_tile_set(p: Plane, tiles: list[int]) -> None:
    """`encode_tile_set`: 7 channels (4 counts + 3 aka positions)."""
    counts = [0] * TILES
    for raw in tiles:
        tid = _deaka(raw)
        i = counts[tid]
        p.assign(p.idx + i, tid, 1.0)
        counts[tid] += 1
        if _is_aka_tile_id(raw):
            i = raw - 16
            p.fill(p.idx + 4 + i, 1.0)
    p.advance(TILE_SET_CHANNELS)


def _encode_self_kawa(p: Plane, item: KawaItem | None) -> None:
    """`encode_self_kawa`: 4 channels (kan, tile, aka, dora)."""
    if item is not None:
        for kan in item.kan:
            p.assign(p.idx, _deaka(kan), 1.0)
        p.assign(p.idx + 1, item.tile, 1.0)
        if item.is_aka:
            p.fill(p.idx + 2, 1.0)
        if item.is_dora:
            p.fill(p.idx + 3, 1.0)
    p.advance(SELF_KAWA_ITEM_CHANNELS)


def _encode_kawa(p: Plane, item: KawaItem | None) -> None:
    """`encode_kawa`: 8 channels for the other players' discards."""
    if item is not None:
        if item.chi_pon is not None:
            a, b = item.chi_pon
            lo, hi = min(a, b), max(a, b)
            p.assign(p.idx, hi, 1.0)        # highest consumed tile
            p.assign(p.idx + 1, lo, 1.0)    # lowest consumed tile
        p.assign(p.idx + 2, item.tile, 1.0)
        if item.is_aka:
            p.fill(p.idx + 3, 1.0)
        if item.is_dora:
            p.fill(p.idx + 4, 1.0)
        if item.is_riichi:
            p.fill(p.idx + 5, 1.0)
        if item.is_tedashi:
            p.fill(p.idx + 6, 1.0)
        if item.is_tsumogiri:
            p.fill(p.idx + 7, 1.0)
    p.advance(KAWA_ITEM_CHANNELS)


def _encode_ev(p: Plane, value: float) -> None:
    """`encode_ev`: two rescaled planes."""
    p.fill(p.idx, max(0.0, min(value, 100_000.0)) / 100_000.0)
    p.fill(p.idx + 1, max(0.0, min(value, 30_000.0)) / 30_000.0)
    p.advance(2)


def _encode_sp_table(p: Plane, cands: list[Candidate], can_discard: bool,
                     ev_scale: float) -> None:
    """`encode_sp_table`: 3 planes of MAX_NUM_TURNS, keyed by discard."""
    first = next(
        (c for c in cands if c.tenpai_probs and c.tenpai_probs[0] > 0.0),
        None,
    )
    if first is None:
        p.advance(3 * MAX_NUM_TURNS)
        return

    def emit(c: Candidate) -> None:
        for turn, (tp, wp, ev) in enumerate(
            zip(c.tenpai_probs, c.win_probs, c.exp_values)
        ):
            if tp <= 0.0:
                break
            base = p.idx + turn
            p.assign(base, c.tile, tp)
            p.assign(base + MAX_NUM_TURNS, c.tile, wp)
            p.assign(base + 2 * MAX_NUM_TURNS, c.tile, min(ev * ev_scale, 1.0))

    if can_discard:
        for c in cands:
            emit(c)
    else:
        for turn, (tp, wp, ev) in enumerate(
            zip(first.tenpai_probs, first.win_probs, first.exp_values)
        ):
            if tp <= 0.0:
                break
            base = p.idx + turn
            p.fill(base, tp)
            p.fill(base + MAX_NUM_TURNS, wp)
            p.fill(base + 2 * MAX_NUM_TURNS, min(ev * ev_scale, 1.0))

    p.advance(3 * MAX_NUM_TURNS)


def encode_obs(state: State, version: int = 4) -> list[float]:
    """Encode `state` into Mortal's flattened `(1012, 34)` observation."""
    if version not in (1, 2, 3, 4):
        raise ValueError(f'unsupported version {version}')
    if version != 4:
        raise NotImplementedError('only version 4 is ported')

    p = Plane(OBS_SHAPE[version])
    kawa = state.kawa
    max_kawa_len = max((len(k) for k in kawa), default=0)

    # --- self hand: 4 threshold rows ------------------------------------
    for tile in range(TILES):
        if state.tehai[tile] > 0:
            p.assign_rows(p.idx, tile, state.tehai[tile], 1.0)
    p.advance(4)

    # --- aka fives held --------------------------------------------------
    for i, has in enumerate(state.akas_in_hand):
        if has:
            p.fill(p.idx + i, 1.0)
    p.advance(3)

    # --- score, two planes per player ------------------------------------
    for score in state.scores:
        p.fill(p.idx, max(0, min(score, 100_000)) / 100_000.0)
        p.advance(1)
        p.fill(p.idx, max(0, min(score, 30_000)) / 30_000.0)
        p.advance(1)

    # --- rank / kyoku one-hot --------------------------------------------
    p.fill(p.idx + state.rank, 1.0)
    p.advance(4)
    p.fill(p.idx + state.kyoku, 1.0)
    p.advance(4)

    # --- honba / kyotaku (v4: rescale only, cap 10) -----------------------
    p.fill(p.idx, min(state.honba, HONBA_CAP_V4) / HONBA_CAP_V4)
    p.advance(1)
    p.fill(p.idx, min(state.kyotaku, HONBA_CAP_V4) / HONBA_CAP_V4)
    p.advance(1)

    # --- round / seat wind ------------------------------------------------
    p.assign(p.idx, state.bakaze, 1.0)
    p.assign(p.idx + 1, state.jikaze, 1.0)
    p.advance(2)

    # --- round progress (v2..v4) -----------------------------------------
    n = (state.bakaze - 27) * 4 + state.kyoku
    p.fill(p.idx, min(n, 7) / 7.0)
    p.advance(1)

    # --- dora indicators --------------------------------------------------
    _encode_tile_set(p, state.dora_indicators)

    # --- self kawa: first 6 ----------------------------------------------
    for item in kawa[0][:6]:
        _encode_self_kawa(p, item)
    p.advance((6 - min(len(kawa[0]), 6)) * SELF_KAWA_ITEM_CHANNELS)

    # --- self kawa: last 18 ----------------------------------------------
    for item in list(reversed(kawa[0]))[:18]:
        _encode_self_kawa(p, item)
    p.advance((18 - min(len(kawa[0]), 18)) * SELF_KAWA_ITEM_CHANNELS)

    # --- self kawa alpha-weighted (v3/v4) --------------------------------
    for turn, item in enumerate(kawa[0]):
        if item is not None:
            v = math.exp(-0.2 * (max_kawa_len - 1 - turn))
            p.assign(p.idx, item.tile, v)
    p.advance(1)

    # --- other players' kawa ---------------------------------------------
    for player_kawa in kawa[1:]:
        for item in player_kawa[:6]:
            _encode_kawa(p, item)
        p.advance((6 - min(len(player_kawa), 6)) * KAWA_ITEM_CHANNELS)

        for item in list(reversed(player_kawa))[:18]:
            _encode_kawa(p, item)
        p.advance((18 - min(len(player_kawa), 18)) * KAWA_ITEM_CHANNELS)

        # v3/v4 alpha-weighted trio (tile / tedashi / riichi)
        for turn, item in enumerate(player_kawa):
            if item is not None:
                v = math.exp(-0.2 * (max_kawa_len - 1 - turn))
                p.assign(p.idx, item.tile, v)
                if item.is_tedashi:
                    p.assign(p.idx + 1, item.tile, v)
                if item.is_riichi:
                    p.assign(p.idx + 2, item.tile, v)
        p.advance(3)

    # --- tiles left -------------------------------------------------------
    p.fill(p.idx, state.tiles_left / 69.0)
    p.advance(1)

    # --- doras owned (v4: rescale /12, one plane each) --------------------
    for count in state.doras_owned:
        p.fill(p.idx, min(count, 12) / 12.0)
        p.advance(1)

    # --- doras unseen (v4: rescale /23) ----------------------------------
    doras_unseen = len(state.dora_indicators) * 4 + 3 - state.doras_seen
    p.fill(p.idx, min(doras_unseen, 23) / 23.0)
    p.advance(1)

    # --- kawa overview (7 channels per player) ---------------------------
    for overview in state.kawa_overview:
        _encode_tile_set(p, overview)

    # --- fuuro overview: 4 sets x 5 channels per player ------------------
    for player_fuuro in state.fuuro_overview:
        for fuuro in player_fuuro[:4]:
            for item in fuuro.tiles:
                tid = item.tile
                i = next(i for i in range(4) if p.buf[p.idx + i][tid] == 0.0)
                p.assign(p.idx + i, tid, 1.0)
                if item.is_aka:
                    p.fill(p.idx + 4, 1.0)
            p.advance(5)
        p.advance((4 - min(len(player_fuuro), 4)) * 5)

    # --- ankan overview ---------------------------------------------------
    for player_ankan in state.ankan_overview:
        for tile in player_ankan:
            p.assign(p.idx, tile, 1.0)
        p.advance(1)

    # --- tiles seen / 4 ---------------------------------------------------
    for tile in range(TILES):
        p.assign(p.idx, tile, state.tiles_seen[tile] / 4.0)
    p.advance(1)

    # --- others' last tedashi (tile / aka / dora) -------------------------
    for item in state.last_tedashis[1:]:
        if item is not None:
            p.assign(p.idx, item.tile, 1.0)
            if item.is_aka:
                p.fill(p.idx + 1, 1.0)
            if item.is_dora:
                p.fill(p.idx + 2, 1.0)
        p.advance(3)

    # --- others' riichi sutehai ------------------------------------------
    for item in state.riichi_sutehais[1:]:
        if item is not None:
            p.assign(p.idx, item.tile, 1.0)
            if item.is_aka:
                p.fill(p.idx + 1, 1.0)
            if item.is_dora:
                p.fill(p.idx + 2, 1.0)
        p.advance(3)

    # --- others riichi declared / accepted --------------------------------
    for i, b in enumerate(state.riichi_declared[1:]):
        if b:
            p.fill(p.idx + i, 1.0)
    p.advance(3)
    for i, b in enumerate(state.riichi_accepted[1:]):
        if b:
            p.fill(p.idx + i, 1.0)
    p.advance(3)

    # --- self waits -------------------------------------------------------
    for tile in range(TILES):
        if state.waits[tile]:
            p.assign(p.idx, tile, 1.0)
    p.advance(1)

    # --- furiten ----------------------------------------------------------
    if state.at_furiten:
        p.fill(p.idx, 1.0)
    p.advance(1)

    # --- shanten one-hot, cap 6 -> 7 channels -----------------------------
    p.fill(p.idx + min(state.shanten, SHANTEN_CAP), 1.0)
    p.advance(SHANTEN_CAP + 1)

    # --- self riichi accepted / at kan select -----------------------------
    if state.riichi_accepted[0]:
        p.fill(p.idx, 1.0)
    p.advance(1)
    if state.at_kan_select:
        p.fill(p.idx, 1.0)
    p.advance(1)

    # --- call context --------------------------------------------------
    if state.can_pass and state.last_kawa_tile is not None:
        item = state.last_kawa_tile
        p.assign(p.idx, item.tile, 1.0)
        if item.is_aka:
            p.fill(p.idx + 1, 1.0)
        if state.dora_factor[item.tile] > 0:
            p.fill(p.idx + 2, 1.0)
    p.advance(3)

    # --- decision flags ---------------------------------------------------
    if state.can_discard:
        for tid in state.discard_candidates:
            p.assign(p.idx, tid, 1.0)
        for tid in state.keep_shanten_discards:
            p.assign(p.idx + 1, tid, 1.0)
        for tid in state.next_shanten_discards:
            p.assign(p.idx + 2, tid, 1.0)
        if state.shanten <= 1:
            for tid in state.unconditional_tenpai_discards:
                p.assign(p.idx + 3, tid, 1.0)
        if state.riichi_declared[0]:
            p.fill(p.idx + 4, 1.0)
    p.advance(5)

    if state.can_riichi:
        p.fill(p.idx, 1.0)
    p.advance(1)
    if state.can_chi_low:
        p.fill(p.idx, 1.0)
    if state.can_chi_mid:
        p.fill(p.idx + 1, 1.0)
    if state.can_chi_high:
        p.fill(p.idx + 2, 1.0)
    p.advance(3)
    if state.can_pon:
        p.fill(p.idx, 1.0)
    p.advance(1)
    if state.can_daiminkan:
        p.fill(p.idx, 1.0)
    p.advance(1)
    if state.can_ankan:
        for tile in state.ankan_candidates:
            p.assign(p.idx, tile, 1.0)
    p.advance(1)
    if state.can_kakan:
        for tile in state.kakan_candidates:
            p.assign(p.idx, tile, 1.0)
    p.advance(1)
    if state.can_agari:
        p.fill(p.idx, 1.0)
    p.advance(1)
    if state.can_ryukyoku:
        p.fill(p.idx, 1.0)
    p.advance(1)

    # --- v4 single-player-table tail -------------------------------------
    if not state.sp_tables_unavailable:
        _encode_ev(p, state.max_ev)
        if state.can_discard:
            for c in state.max_ev_table:
                for req in c.required_tiles:
                    if c.shanten_down:
                        p.assign(p.idx + TILES + c.tile, req, 1.0)
                    else:
                        p.assign(p.idx + c.tile, req, 1.0)
            p.advance(2 * TILES)

            best = max(
                state.max_ev_table,
                key=lambda c: (not c.shanten_down, len(c.required_tiles)),
                default=None,
            )
            if best is not None:
                p.assign(p.idx, best.tile, 1.0)
            p.advance(2)

            ev_scale = 0.0 if state.max_ev < 1.0 else 1.0 / state.max_ev
            _encode_sp_table(p, state.max_ev_table, True, ev_scale)
        else:
            p.advance(2 * TILES + 1)
            if state.max_ev_table:
                for req in state.max_ev_table[0].required_tiles:
                    p.assign(p.idx, req, 1.0)
            p.advance(1)
            ev_scale = 0.0 if state.max_ev < 1.0 else 1.0 / state.max_ev
            _encode_sp_table(p, state.max_ev_table, False, ev_scale)
    else:
        _encode_ev(p, state.min_tsumo_agari)
        p.advance(2 * TILES + 2 + 3 * MAX_NUM_TURNS)

    if p.idx != OBS_SHAPE[version]:
        raise AssertionError(
            f'channel cursor drift: consumed {p.idx}, expected {OBS_SHAPE[version]}'
        )
    return p.take()


def budget(version: int = 4) -> int:
    """Return the channel count the layout above consumes (for self-tests)."""

    def ie(cap: int, one_hot: bool = False, rescale: bool = False) -> int:
        # v4: no rbf expansion.
        return (cap + 1 if one_hot else 0) + (1 if rescale else 0)

    n = 0
    n += 4 + 3 + 4 * 2 + 4 + 4
    n += ie(HONBA_CAP_V4, rescale=True) * 2
    n += 2
    n += ie(7, rescale=True)
    n += TILE_SET_CHANNELS
    n += 6 * SELF_KAWA_ITEM_CHANNELS + 18 * SELF_KAWA_ITEM_CHANNELS + 1
    n += 3 * (6 * KAWA_ITEM_CHANNELS + 18 * KAWA_ITEM_CHANNELS + 3)
    n += 1
    n += 4 * ie(12, rescale=True)
    n += ie(23, rescale=True)
    n += 4 * TILE_SET_CHANNELS
    n += 4 * 4 * 5
    n += 4 + 1
    n += 3 * 3 + 3 * 3
    n += 3 + 3
    n += 1 + 1
    n += ie(SHANTEN_CAP, one_hot=True)
    n += 1 + 1 + 3
    n += 5 + 1 + 3 + 1 + 1 + 1 + 1 + 1 + 1
    n += 2 * TILES + 2 + 3 * MAX_NUM_TURNS + 2
    return n


if __name__ == '__main__':
    import argparse
    import json

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--state', help='JSON file describing a State')
    ap.add_argument('--budget', action='store_true',
                    help='print the derived channel budget and exit')
    ap.add_argument('--dump', action='store_true',
                    help='print per-channel non-zero counts')
    args = ap.parse_args()

    if args.budget:
        got = budget(4)
        print(f'derived budget : {got}')
        print(f'obs_shape(4)   : {OBS_SHAPE[4]}')
        print(f'match          : {got == OBS_SHAPE[4]}')
        raise SystemExit(0 if got == OBS_SHAPE[4] else 1)

    st = State()
    if args.state:
        raw = json.loads(open(args.state).read())
        for k, v in raw.items():
            if k == 'kawa':
                st.kawa = [
                    [KawaItem(**it) if it else None for it in row] for row in v
                ]
            elif k == 'max_ev_table':
                st.max_ev_table = [Candidate(**c) for c in v]
            else:
                setattr(st, k, v)

    obs = encode_obs(st)
    print(f'encoded {len(obs)} floats = {len(obs) // TILES} channels x {TILES}')
    if args.dump:
        for ch in range(len(obs) // TILES):
            row = obs[ch * TILES:(ch + 1) * TILES]
            nz = sum(1 for v in row if v != 0.0)
            if nz:
                print(f'  ch {ch:4d}: {nz:2d} non-zero  max={max(row):.6f}')
