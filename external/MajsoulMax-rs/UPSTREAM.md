# Pinned MajsoulMax-rs components

Source: https://github.com/Xerxes-2/MajsoulMax-rs

Pinned commit: `7065716d12514b0a6a4bbc55adf29c0b5b2bacaa`.

This directory keeps the upstream Modder source, Liqi descriptor and runtime configuration needed by the Android hook. The Android-specific C ABI and settings adapter live in `hook-probe/rust-modder`. The upstream project is licensed under GPL-3.0; its license is retained here.

## Android catalog additions, 2026-09-24

`liqi_config/max_data.yaml` adds the following entries from the game's 2026-09-23 content update. The character and skin IDs were found in the connected Android client's runtime tables. Names and decoration IDs were cross-checked against [MajsoulData's extracted item tables](https://github.com/Avenshy/MajsoulData/tree/2677b55ad27dd4103f786c9dee33779d38b30f60/data/tables/item_definition) and its `0.16.283-4.0.47` catalog release. Existing entries and the pinned Modder/protocol sources are preserved.

| Entry | Section | ID |
| --- | --- | --- |
| 墨菲 | character | 20000126 |
| 墨菲默认服装 | skin | 40012601 |
| 墨菲契约服装 | skin | 40012602 |
| 和牌-灵感大暴走 | item | 30520015 |
| 立直-给点颜色 | item | 30530015 |
| 立直棒-墨彩喷喷 | item | 30560012 |
| 桌布-混一色！ | item | 30580030 |
| 牌背-喵体力学 | item | 30570019 |
| 守梦夜明-2026（限时物品） | loading_image | 307295 |
| 守梦夜明-2026（普通物品） | loading_image | 307495 |
| 守梦夜明-2026（插画配置） | loading_image | 260901 |

The Android asset revision is bumped so a future APK containing this catalog replaces the game's cached catalog while preserving the user's settings. These are local display overrides, not changes to server-side ownership.

## Local persistence fix, 2026-09-27

`src/modder.rs` saves the settings document immediately after handling `.lq.Lobby.changeCharacterSkin`, before sending the local character notification. The pinned upstream handler only updates `char_skin` in memory, so the selected skin is otherwise lost on process restart unless another settings action happens to save it. The Android settings adapter provides the existing atomic file writer. The regression test in `hook-probe/rust-modder` checks immediate persistence, repeated changes, unrelated settings and restoration from disk when the server returns default skins after restart.

The adapter names temporary settings files using the process ID and a write sequence, rather than thread names that can contain invalid path characters on the Windows test host.

## Additional skin catalog entries, 2026-09-27

The connected client's shop shows four Drowsy Daylight (纯白午后) skins that were missing from the local catalog. Their IDs were present in the Android runtime table inspected on September 24 and are cross-checked against the same pinned MajsoulData tables linked above. This update adds only those four skin IDs and refreshes the Android asset revision while keeping the user's selected skins.

| Character | Character ID | Skin ID |
| --- | --- | --- |
| 相原舞 | 200005 | 400509 |
| 如月彩音 | 200068 | 406805 |
| 二之宫花 | 200017 | 401710 |
| 雏桃 | 200026 | 402607 |

## Opponent display-name modes, 2026-11-12

`src/modder.rs` now rewrites the display names of the *opponent* seats in the
`.lq.FastTest.authGame` response, driven by two new settings fields
(`namingMode`, `customNames`) in the Android settings adapter:

| Mode | Behaviour |
| --- | --- |
| `normal` (default) | Unchanged: server names are kept and `showServer` keeps adding the zone prefix to every seat. |
| `abc` (also parsed as `anonymize`; shown as 预设昵称 / "Preset names") | The three opponents become `领头的羊`, `镇山的虎` and `远见的鹰` in visual left-to-right seat order. |
| `custom` | The three `customNames` entries are used instead; an empty entry keeps that opponent's server name. |

The rewrite is seated, not positional: the local seat is located by looking up
the local `account_id` inside the `seat_list` that `authGame` carries, and only
the remaining seats are renamed. The seat after the local player is the
shimocha on the right, the next one the toimen at the top and the seat before
the local player the kamicha on the left, so the visual order is
`[self + n - 1, .., self + 1] mod n` (first label = left/kamicha, second =
top/toimen, third = right/shimocha); a three-player table uses only the first
two. No `seat_list`-bearing payload other than `authGame` exists in the Liqi
schema (`GameLiveHead` aside), and `syncGame`/`enterGame` snapshots carry no
names, so a mode change takes effect on the next game entry or reconnect.

Only received display data is mutated: outgoing requests still pass through
byte for byte, and when the local account is missing from `seat_list` (for
example in a spectator flow) every name is preserved.

A rewritten seat keeps the region tag: `showServer` is an independent switch and
the sole authority for the `[CN]`/`[JP]`/`[EN]`/`[??]` prefix (the Chinese tag
contains the game's own U+FEFF), so the label is composed as
`zone_tag(account) + label` while it is on and as the bare label while it is
off - for preset labels and custom labels alike, and with no text matching
involved (a custom label that itself starts with `[JP]` is taken verbatim).
`zone_tag` is the single place that mapping exists; the `authGame` rewrite and
the `majmax_naming_prefix` C ABI the display layer calls both use it. Account id
0 (a robot) has no region and is never tagged. Settings written by an older
build keep `normal`, and an unknown `namingMode` string degrades to `normal`
instead of failing the settings load.

Regression coverage lives in `hook-probe/rust-modder`: the settings tests cover
legacy documents, tolerant parsing, validation (at most three labels, 64
characters, no control characters) and empty-label semantics, while the adapter
tests drive `login` + `authGame` end to end for normal, `abc`, `custom` (with a
robot opponent) and the missing-seat-anchor cases plus the untouched request.

## Local display refresh for the current table, 2026-11-12

The rewrite above only reaches the table whose `authGame` response is being
parsed, so a user who changes the mode while a game is running would have to
wait for the next one. The display side is therefore handled locally by
`hook-probe/src/main/assets/liqi_config/ui/MajsoulMaxNaming.lua`, which wraps
`DesktopMgr:get_player_name` and redraws the table once through
`UI_DesktopInfo.Inst:RefreshSeat(true)` when the stored choice changes. Nothing
in the protocol changes because of it: no request is rewritten, no connection is
touched.

Because the rewrite already replaced the server names inside the response, the
original names have to be kept somewhere for "go back to normal" to work while
that table is up. `src/modder.rs` therefore records, for every `authGame`
response, the nicknames it is about to replace (bounded to the three opponents,
replaced wholesale per response) and publishes them through
`crate::publish_naming_originals`. The cached value is the *pre-prefix* name: the
tag added for that game is stripped again (only the exact tag of that account,
and only while `showServer` was on, i.e. only when this module put it there), so
the display layer can re-apply the tag from the live switch instead of being
stuck with the one of an earlier game.

The adapter exposes that cache and the compact naming state to the Lua bridge as
`majmax_naming_original`, `majmax_naming_prefix` and `majmax_naming_state`; all
three live in a small dedicated mutex so the Unity main thread never waits on a
packet being modified. The state carries the switch itself
(`<mode>\n<show_server>\n<label0>\n<label1>\n<label2>`), so toggling `showServer`
is its own redraw token, and the display layer never derives a prefix from a
name or a label. The cache is cleared per `authGame` response and on connection
close, is never written to any log, and is display-only.

Tests: `auth_game_publishes_the_names_it_replaced`,
`every_auth_game_replaces_the_restore_cache`,
`preset_labels_keep_the_region_tag_of_their_own_seat`,
`turning_show_server_off_drops_the_tag_of_a_rewritten_seat`,
`naming_prefix_abi_matches_the_protocol_zone_tag`, and
`a_custom_label_is_taken_verbatim_and_never_matched_against_a_tag` (plus
`settings_patch_publishes_the_lua_state`) in `hook-probe/rust-modder`, and
`tools/tests/test_naming_refresh.lua` for the Lua layer (normal → preset →
custom → normal, the switch on/off/on for both label kinds and for restored
names, the own name, three-player mapping, missing UI, missing bridge).
