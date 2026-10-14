# Mortal observation parity

Mortal's weights and its observation layout are a **matched pair**. The model
expects exactly `obs_shape(4) = (1012, 34)` floats, and each channel means
precisely what `libriichi` decided it means. Feed a correctly *loaded* model a
*differently ordered* buffer and you still get finite, plausible-looking logits
— it just plays badly, with no error anywhere.

This directory therefore carries an executable specification of that layout.

## Files

| File | Purpose |
|---|---|
| `tools/mortal_obs_ref.py` | Python port of `libriichi/src/state/obs_repr.rs` (`encode_obs`, `encode_ev`, `encode_sp_table`, `encode_tile_set`). Exits non-zero if its channel cursor doesn't land on 1012. |
| `tools/budget_mortal.py` | Replays upstream's `self.idx += ...` sequence to re-derive the channel budget from the real source. |
| `tools/gen_parity_fixtures.py` | Writes `weights/mortal_obs_parity.json` from deterministic `State` fixtures. |
| `native_bot/tests/mortal_obs_parity.rs` | Asserts the fixtures have the libriichi shape and that the Rust encoder consumes exactly 1012 channels. |

## Reproducing

```sh
# 1. sanity-check the layout is self-consistent (must print match: True)
python3 tools/mortal_obs_ref.py --budget

# 2. regenerate the fixtures after changing the reference
python3 tools/gen_parity_fixtures.py

# 3. run the Rust side
cd external/Akagi/native_bot
RUSTFLAGS="-C target-feature=+fp16" cargo test --test mortal_obs_parity -- --nocapture
```

`RUSTFLAGS="-C target-feature=+fp16"` is needed on aarch64 because the `gemm-f16`
dependency in candle emits `fullfp16` NEON instructions that the default target
does not enable. Without it the build fails inside `gemm-common`, unrelated to
this crate.

## The v4 channel map

Derived from upstream and verified to sum to exactly 1012:

```
[   0..   3] tehai thresholds (>=1..>=4)
[   4..   6] aka fives in hand
[   7..  10] score plane 1     (clamp 0..100k / 100k), per player
[  11..  14] score plane 2     (clamp 0..30k / 30k), per player
[  15..  18] rank one-hot
[  19..  22] kyoku one-hot
[  23..  23] honba   (rescale /10)
[  24..  24] kyotaku (rescale /10)
[  25..  26] bakaze + jikaze one-hot
[  27..  27] round progress ((bakaze-E)*4 + kyoku) / 7
[  28..  34] dora indicators (tile_set = 7: 4 count rows + 3 aka rows)
[  35..  58] self kawa, first 6  x 4
[  59.. 130] self kawa, last 18  x 4
[ 131.. 131] self kawa, alpha-weighted
[ 132.. 716] other players' kawa: 3 x (6*8 + 18*8 + 3)
[ 717.. 717] tiles_left / 69
[ 718.. 721] doras_owned (rescale /12), per player
[ 722.. 722] doras_unseen (rescale /23)
[ 723.. 750] kawa overview, 4 players x 7
[ 751.. 830] fuuro overview, 4 players x 4 sets x 5
[ 831.. 834] ankan overview, per player
[ 835.. 835] tiles_seen / 4
[ 836.. 844] others' last tedashi (tile / aka / dora) x 3
[ 845.. 853] others' riichi sutehai (tile / aka / dora) x 3
[ 854.. 856] others' riichi declared
[ 857.. 859] others' riichi accepted
[ 860.. 860] self waits
[ 861.. 861] self at_furiten
[ 862.. 868] self shanten one-hot (cap 6 -> 7)
[ 869.. 869] self riichi accepted
[ 870.. 870] at_kan_select
[ 871.. 873] call-context last kawa (tile / aka / dora)
[ 874.. 878] can_discard block (candidates, keep/next shanten, tenpai, riichi)
[ 879.. 879] can_riichi
[ 880.. 882] can_chi low / mid / high
[ 883.. 883] can_pon
[ 884.. 884] can_daiminkan
[ 885.. 885] can_ankan
[ 886.. 886] can_kakan
[ 887.. 887] can_agari
[ 888.. 888] can_ryukyoku
[ 889.. 890] encode_ev (max EV, two planes)
[ 891.. 958] required tiles per discard (2 x 34, shanten-down split)
[ 959.. 960] max required-tiles discard
[ 961..1011] single-player table (3 x MAX_NUM_TURNS = 3 x 17)
```

### Version notes

`obs_shape` differs per version — `{1: 938, 2: 942, 3: 934, 4: 1012}`. The
version is not the same as the entry convolution's `in_channels`: v3 is **934**
and v2 is **942**, while v1 is 938. An earlier revision of `mortal.rs` mapped
`948 / 938 / 450` onto v1..v3, which matches no upstream release.

3-player sanma is a **separate fork**: `Mateces/mortal-sanma` defines
`obs_shape(5) = (780, 34)` and `ACTION_SPACE = 44`, while the widely deployed
`libriichi3p` interface (Akagi / MahjongCopilot) uses `obs_shape = 775` with the
same 44 actions. The crate currently uses 775, matching the shipped sanma
ecosystem; treat 775 vs 780 as unresolved if a sanma model ever misbehaves.

The `IntegerEncoder` behaves differently per version:

* v1: `one_hot`-less fill of `cap` rows.
* v2 / v3: `one_hot` (`cap + 1`), `rescale` (1), and optional RBF (`intervals - 1`).
* v4: `one_hot` (`cap + 1`) and `rescale` (1) only — **no RBF expansion**.

### Network topology notes

From `mortal/model.py` (authoritative for the weight layout):

* The activation is **`nn.Mish`** for v2..=v4, and `nn.ReLU` for v1. SiLU is
  never used.
* `pre_actv = True` for v2..=v4, so `ResBlock.res_unit` is
  `[BN, actv, Conv, BN, actv, Conv]` and there is **no trailing activation**
  after the residual add. v1 uses `[Conv, BN, actv, Conv, BN]` plus a trailing
  activation.
* `ChannelAttention` pools **both** `mean(-1)` and `amax(-1)` through the same
  MLP and sums them before the sigmoid; using the mean alone is a bug.
* BatchNorm `eps` is `1e-5` by default and `1e-3` for v3 and v4.
* The trunk tail is `Conv1d(conv_channels, 32, k=3, padding=1)` →
  activation → `Flatten` → `Linear(32*34, 1024)`.
* The v4 head is a single `Linear(1024, 1 + ACTION_SPACE)`; the advantage mean
  is taken over the **legal** actions only (`masked_fill(~mask, 0).sum() /
  mask.sum()`), not over the whole action space.

## Status

The Rust encoder (`src/mortal_obs.rs`) is **bit-for-bit identical to the
libriichi reference** for version 4: `tests/mortal_obs_diff.rs` compares all
34408 floats against a buffer produced by `tools/mortal_obs_ref.py` and reports
zero differences. `MORTAL_OBS_DIFF_STRICT=1` makes a divergence fail the test.

### Two encoders, deliberately

| Encoder | Layout | Used for |
|---|---|---|
| `src/obs.rs` (`encode`) | Akagi's own `channels(num_players)` planes | the bundled Akagi model |
| `src/mortal_obs.rs` | Mortal's `[1012, 34]` | any Mortal `.pth` / `.onnx` |

They are not interchangeable. Mortal's weights only understand the second one.

`src/mortal.rs::encode_mortal_obs_4p` uses the Mortal layout when
`in_channels == 1012` and falls back to the legacy Akagi planes otherwise, so a
model never receives a buffer whose layout disagrees with its weights.

### Fixed so far

* The 4P version map (`{1: 938, 2: 942, 3: 934, 4: 1012}`) — was
  `948 / 938 / 450`.
* The activation: `nn.Mish` for v2..=v4, `nn.ReLU` for v1 — was SiLU.
* Pre-activation ResBlocks (`[BN, actv, Conv, BN, actv, Conv]`, no trailing
  activation) for v2..=v4, with the BN+activation carried by the folded conv.
* `ChannelAttention` now pools mean **and** max, matching upstream.
* BatchNorm `eps`: 1e-3 for v3/v4, 1e-5 otherwise.
* `encode_tile_set` advances 7 channels with real count + aka rows.
* The 1012-channel layout itself, encoded directly from `GameState`
  (`src/mortal_obs.rs` + `src/state_features.rs`), replacing the lossy
  `EncInput` path that could only reach ~23 channels.

### Still open

* 3P sanma (`775`) still goes through the legacy path — the parity harness only
  covers v4.
* The v4 DQN advantage mean must be taken over **legal** actions only;
  `rank_by_mortal_logits` currently softmaxes the legal logits directly, which
  is not the same function.
* Action indexing (`mortal_action_index_4p`, the aka fallback) is still
  unverified against real play.
* The single-player-table planes are filled from `MortalSeatView.sp_candidates`,
  which nothing populates yet, so those 123 channels stay zero in live play.
  They are exercised only by the synthetic diff fixture.
