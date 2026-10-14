# 3-player (sanma) ground truth from the real `libriichi3p`

Mortal's 3-player weights were trained against Akagi's `libriichi3p`, a
3-player-only fork of `libriichi`. That binary is the only authority on the
775-channel layout: the Mateces/mortal-sanma source declared `obs_shape(5)=775`
at one revision but its own encoder could never produce it (`unreachable!()` on
version 5), so it cannot be used to derive the layout.

This directory records how to drive the real binary so the Rust encoder can be
verified bit-exactly, the same way `tests/mortal_obs_diff.rs` does for 4 players.

## Where the binary comes from

`libriichi3p` is not in Akagi (`shinkuan/Akagi@v3` is deliberately
libriichi-free) and not on PyPI. It *was* committed to `latorc/MahjongCopilot`
in commit `fff6b48` ("feat: support Akagi 3p model") and removed in `d76cb40`
("0.5.1"), which replaced it with a placeholder file. The blobs are still in
that repository's history:

```sh
git clone --filter=blob:none --no-checkout https://github.com/latorc/MahjongCopilot.git mc
cd mc
git show fff6b48:libriichi3p/libriichi3p-3.12-x86_64-unknown-linux-gnu.so > /tmp/l3p.so
```

Available builds: `{3.10,3.11,3.12}` x `{x86_64-unknown-linux-gnu,
x86_64-pc-windows-msvc, x86_64-apple-darwin, aarch64-apple-darwin}`. There is no
aarch64-Linux build, so on an ARM host the x86_64 one has to be run under
emulation.

## Running it on an aarch64 host

```sh
# 1. x86_64 emulation + runtime libs
dpkg --add-architecture amd64
apt-get update
apt-get install -y qemu-user-static libc6:amd64 libgcc-s1:amd64 zlib1g:amd64

# 2. an x86_64 CPython 3.12 (the module is a cp312 extension)
curl -sSfL -o /tmp/pyx.tar.gz \
  https://github.com/astral-sh/python-build-standalone/releases/download/20240415/cpython-3.12.3+20240415-x86_64-unknown-linux-gnu-install_only.tar.gz
mkdir -p /tmp/pyx && tar xzf /tmp/pyx.tar.gz -C /tmp/pyx

# 3. numpy for the target platform (the Rust side uses the `numpy` crate to
#    build the observation arrays; numpy 2.x is not compatible with
#    numpy-rs 0.20, so pin 1.x)
uv pip install --target /tmp/npwheel --python-version 3.12 \
  --python-platform x86_64-manylinux_2_28 --only-binary :all: "numpy==1.26.4"
cp -r /tmp/npwheel/* /tmp/pyx/python/lib/python3.12/site-packages/

# 4. run from a directory that does not contain a `numpy` folder
cd /root/work
qemu-x86_64-static /tmp/pyx/python/bin/python3.12 -u tools/sanma/gen_libriichi3p_fixtures.py out.json
```

`PYTHONPATH` must not point at a source tree containing `numpy/`, or numpy
refuses to import.

## How the observations are captured

`mjai.Bot(engine, player_id)` keeps the state in Rust, computes the observation
internally, and hands it to a Python engine through
`react_batch(obs, masks, invisible_obs)`. `gen_libriichi3p_fixtures.py` supplies a
stub engine that records every buffer, so driving a scripted game yields
reference `(775, 34)` arrays — produced by the exact binary the weights expect.

Note the mjai parser in this build still validates **4-seat** arrays, so
`start_game.names`, `start_kyoku.scores` and `start_kyoku.tehais` need a filler
4th entry.

## What the binary reports

```
MAX_VERSION       = 4          # the 3p build's own version numbering
ACTION_SPACE      = 44
GRP_SIZE          = 7
obs_shape(4)      = (775, 34)  # obs_shape(4) IS the sanma layout
oracle_obs_shape(4) = (217, 34)
```

The sanma checkpoints confirm this: `three.onnx` / `mortal.pth` carry
`config.control.version == 4`, and `model3p.py` uses the combined DQN head
exactly when `version == 4` — which is what a `net.weight` of `(45, 1024)`
(= 1 + 44) means.

## Measurements from the ground-truth buffer

Anchored by locating distinctive planes in `weights/libriichi3p_obs.json`:

| section | channel(s) | note |
|---|---|---|
| tehai thresholds | 0..3 | same as 4p |
| akas in hand | 4..6 | same |
| score plane 1 | 7,9,11 | **value = score / 105000** (4p uses /100000) |
| score plane 2 | 8,10,12 | **value = score / 40000** (4p uses /30000) |
| rank | 13..15 | **3 channels** (4p uses 4) |
| kyoku | 16..18 | **3 channels** (4p uses 4) |
| honba | 19 | |
| kyotaku | 20 | |
| bakaze / jikaze | 21, 22 | one-hot on the honour part of the tile axis |
| round progress | 23 | |
| dora indicators | 24..30 | `encode_tile_set` = 7 |
| self kawa | 31..127 | 24 + 72 + 1 alpha |
| other kawas | 128..517 | 2 seats x (6*8 + 18*8 + 3) = 390 |
| tiles_left | 518 | **value = tiles_left / 69** (not /54) |
| doras owned | 519..522 | **4 channels**, not 3 |
| doras unseen | 523 | value = unseen / 23 |
| kawa overview | 524..544 | 3 x 7 |
| fuuro overview | 545..604 | 3 x 4 x 5 |
| ankan overview | 605..607 | 3 |
| tiles_seen | 608 | confirmed: 21 non-zero tiles in case 0 |
| ... | 609.. | |

The head sizes that differ from 4p (rank 3, kyoku 3) plus fixed-size
`doras_owned` (4) account for the 5-channel gap between this layout and the
"4-player layout with 3-player groups" (780) that Mateces/mortal-sanma settled
on.

## Status

The 3-player layout is implemented in `src/mortal_obs.rs` as `Layout::SANMA`
and wired into `encode_mortal_obs_3p` for `in_channels == 775`.
`tests/sanma_obs_parity.rs` replays the fixture's mjai lines into a
`GameState3P`, encodes, and compares against the real binary's output.

**Verified bit-exact: channels 0..523** (tehai through `doras_unseen`, including
scores, rank, kyoku, winds, dora, both kawa blocks and the overviews).

Remaining differences, all at channel 524 and later:

| region | channels | why |
|---|---|---|
| kawa overview | 524..544 | a few tiles differ; the reference's per-seat overview is not exactly the discard list |
| furiten | 628 | `at_furiten` is derived from `forbidden_discards`, which riichienv also uses for riichi restrictions |
| keep/next shanten, tenpai discards | 640..642 | needs ukeire analysis per discard candidate |
| `encode_ev` + required tiles + single-player table | 652..774 | **needs libriichi's sanma SP tables, which riichienv does not provide** |

The last row is the same gap the 4-player path has: `riichienv-core` exposes
`single_player_tables()` for 4 players only, and the sanma fork's variant is
part of libriichi's `algo/sp` that would have to be ported. Until then the sanma
observation is correct for everything except the decision-analysis tail.

Also note: riichienv's 3-player `Dahai` handler does not populate
`discard_from_hand` and never fills `Observation3P::tsumogiri_flags`, so the
per-discard tsumogiri bit is reconstructed from the state's mjai log
(the engine now runs the sanma state with logging enabled). When the log is
unavailable discards are treated as tedashi-free.

## A bug this caught in the 4-player path

`encode_kawa` (the other seats' discards) was implemented with the wrong channel
order in both `src/mortal_obs.rs` and `tools/mortal_obs_ref.py`:

```
wrong:  0/1 chi, 2 tile, 3 aka, 4 dora, 5 riichi, 6 tedashi, 7 tsumogiri
correct:0/1 chi, 2 kan,  3 tile, 4 aka,  5 dora,  6 tedashi, 7 riichi
```

Because the Rust encoder and the Python reference carried the *same* mistake,
the 4-player diff test passed - it was comparing two copies of one misreading.
The real `libriichi3p` binary exposed it immediately. Both are now fixed and
the 4-player diff still reports zero differences, this time against the actual
source text from `Equim-chan/Mortal`.

