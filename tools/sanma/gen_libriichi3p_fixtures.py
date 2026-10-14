"""Generate ground-truth libriichi3p observations for the 3-player layout.

`mjai.Bot` computes the observation in Rust and passes it to a Python engine via
`react_batch`. A stub engine records every `(775, 34)` buffer and mask, so
driving a scripted game yields reference data produced by the exact binary the
sanma model was trained against.

For each captured observation the fixture also records `after_line`: the index
in `lines` whose processing triggered it. A consumer can therefore replay
`lines[..=after_line]` into its own state machine and compare.

Usage (under qemu):
    qemu-x86_64-static /tmp/pyx/python/bin/python3.12 -u l3p_fixtures.py out.json
"""
import importlib.util
import json
import sys

SO = "/tmp/l3p/libriichi3p-3.12-x86_64-unknown-linux-gnu.so"
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/l3p_fixtures.json"
ACTION_SPACE = 44


def emit(*a):
    print(*a, flush=True)


spec = importlib.util.spec_from_file_location("libriichi3p", SO)
mod = importlib.util.module_from_spec(spec)
sys.modules["libriichi3p"] = mod
spec.loader.exec_module(mod)

assert mod.consts.ACTION_SPACE == ACTION_SPACE
CHANNELS, TILES = mod.consts.obs_shape(4)
emit(f"ACTION_SPACE={ACTION_SPACE} obs_shape(4)=({CHANNELS}, {TILES})")

# The line index currently being fed; recorded alongside each observation.
CURRENT_LINE = [0]


class CaptureEngine:
    name = "capture"
    version = 4
    is_oracle = False
    enable_quick_eval = False
    enable_rule_based_agari_guard = False
    enable_amp = False
    device = None

    def __init__(self):
        self.obs = []
        self.masks = []
        self.after = []

    def __getattr__(self, item):
        return False

    def react_batch(self, obs, masks, invisible_obs):
        for o, m in zip(obs, masks):
            flat = o.reshape(-1) if hasattr(o, "reshape") else o
            self.obs.append([float(v) for v in flat])
            self.masks.append([bool(x) for x in m])
            self.after.append(CURRENT_LINE[0])
        n = len(obs)
        actions = [next((i for i, ok in enumerate(m) if ok), 0) for m in masks]
        q = [[0.0] * ACTION_SPACE for _ in range(n)]
        ms = [[bool(x) for x in m] for m in masks]
        return (actions, q, ms, [True] * n)


eng = CaptureEngine()
bot = mod.mjai.Bot(eng, 0)


def feed(idx, line):
    CURRENT_LINE[0] = idx
    try:
        bot.react(json.dumps(line))
        return None
    except Exception as e:
        return f"{type(e).__name__}: {e}"


tehais = [
    ["1m", "9m", "1p", "2p", "3p", "4p", "5p", "6p", "7p", "1s", "2s", "3s", "E"],
    ["1m", "1m", "9m", "9m", "1p", "1p", "2p", "2p", "3p", "3p", "4p", "4p", "5p"],
    ["1s", "1s", "9s", "9s", "1p", "1p", "2p", "2p", "3p", "3p", "4p", "4p", "E"],
    ["2s", "2s", "3s", "3s", "4s", "4s", "5s", "5s", "6s", "6s", "7s", "7s", "8s"],
]

lines = [
    {"type": "start_game", "names": ["A", "B", "C", "D"]},
    {
        "type": "start_kyoku", "bakaze": "E", "dora_marker": "1s", "kyoku": 1,
        "honba": 0, "kyotaku": 0, "oya": 0,
        "scores": [25000, 25000, 25000, 25000], "tehais": tehais,
    },
]

draw_pool = ["5s", "6s", "7s", "8s", "9s", "E", "S", "W", "N", "P", "F", "C",
             "1m", "9m", "1p", "2p", "3p", "4p"]
k = 0
for _turn in range(6):
    for actor in (0, 1, 2):
        if k >= len(draw_pool):
            break
        tile = draw_pool[k]
        k += 1
        lines.append({"type": "tsumo", "actor": actor, "pai": tile})
        lines.append({"type": "dahai", "actor": actor, "pai": tile, "tsumogiri": True})

errors = []
for idx, line in enumerate(lines):
    err = feed(idx, line)
    if err:
        errors.append(f'{idx} {line["type"]}: {err[:110]}')

emit(f"fed {len(lines)} lines, {len(errors)} errors")
for e in errors[:5]:
    emit("  ", e)

emit(f"captured {len(eng.obs)} observations")
for i, o in enumerate(eng.obs):
    emit(f"  obs[{i}] after_line={eng.after[i]} nonzero={sum(1 for v in o if v != 0.0)}")

payload = {
    "channels": CHANNELS,
    "tiles": TILES,
    "action_space": ACTION_SPACE,
    "lines": lines,
    "cases": [
        {"index": i, "after_line": eng.after[i], "obs": o, "mask": eng.masks[i]}
        for i, o in enumerate(eng.obs)
    ],
}
with open(OUT, "w") as f:
    json.dump(payload, f)
emit(f"wrote {OUT}")
