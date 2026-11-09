# 自动打牌「拒绝风暴」的真实根因：坐标遥测被误判为命令拒绝

分析对象：`majmax-diagnostics-20261109-152943.zip`（assistant 13039 条、hook 48 条，
app 0.14.20 / versionCode 48，游戏 4.0.16_MC）。

## 根因

`AutoDiscardController.poll()` 用 `when (state)` 分派回执，但只给 `hand_coords` 写了
`continue`。适配器每帧发出的三行坐标遥测里：

- `hand_coords` —— 被正确跳过；
- `hand_geometry` —— 掉进 `when` 之后的逻辑；
- `button_coords` —— 同上。

这两行由 `AiOverlayService.pullAuto()` 独立解析（`parseHandGeometry` /
`parseButtonCoords`），本身不带 id，**不是对任何命令的判定**。掉进后面的分支后：

- `pending == null`（没有待发命令）时进入 `else ->`，写成 `GAME_REJECTED` 并
  `suspend(...)`，于是每帧都记一条"被拒"并打断同步；
- `pending != null` 时进入无 id 分支，写成 `ACK_UNMATCHED`。

因此日志里 5604 条 `GAME_REJECTED` 中 **5311 条 hand_geometry + 289 条 button_coords
根本不是"手牌几何被遮挡导致操作被拒"**，而是坐标遥测被误分类；3029 条 `ACK_UNMATCHED`
同样是遥测。真正的命令拒绝只有 3 条 `special_selection` 和 1 条 `illegal_tile`。

由此可以解释日志中此前被误读的现象：

- 相邻拒绝间隔 0/1/2 ms 与 255/256/257 ms 大量成对出现——是每帧两行遥测，不是重算；
- `reason` 一律 54 字符（`concealed:13,tiles:1m,7z,cams:UICamera|o`）——是传输层截断，
  不是数据形状，`concealed` 这个真正有用的计数从来没落盘；
- 暂停/重开与拒绝量的对应关系混乱——拒绝量跟随的是遥测帧数，不是冷却窗口。

## 修复

1. `poll()` 的 `when` 里 `hand_coords`、`hand_geometry`、`button_coords` 三类一律
   `continue`：不写日志、不 `suspend`、不碰 `pending`、不动开关状态；**即便带了 id 也按
   遥测忽略**。
2. 无 id 的回执不再有任何路径可以变成拒绝：`ackId.isEmpty()` 直接跳过。原来"无 id 且
   `pending != null` → `ACK_UNMATCHED`"的分支整段删除，`ACK_UNMATCHED` 及其
   `*_REPEAT` 不再产生。
3. 真实拒绝（`stale` / `round_mismatch` / `illegal_tile` …）保持原语义：写一条
   `GAME_REJECTED`（`stage=command`、带 ack id）并 `suspend`。
4. 重复拒绝的汇总改为「自上次 flush 起 2 秒」的周期窗口：首条立即写，其后同签名重复只
   累加计数，窗口到期（由下一条回执触发）时输出一条 `GAME_REJECTED_REPEAT repeats=N`
   （`N` = 该窗口内首次之外的出现次数）。不再依赖 `pause` 才输出，也不会因为每条都刷新
   "最近时间"而永不超时。签名是 `(stage, code, detail)` 的列表，不含 ack id——同一条已
   失效的判据换一个命令 id 重述时仍然折叠。
5. `reason` 上限 64 → 512，`cams` 256 → 320，`ack_id` 96；渲染改为固定词表白名单，
   按适配器真实字段（`concealed/handpai/invalid/behind/minNx…/cam/ortho/cams/tiles`、
   `buttons[key,nx,ny]`）取值，非白名单键与长字符串只留 8 位指纹，原始 payload 不落日志。

## 受影响文件

- `hook-probe/src/main/java/com/yefeng/majmax/hookprobe/manager/AutoDiscardController.kt`
- `hook-probe/src/main/java/com/yefeng/majmax/hookprobe/manager/AutoAckDetails.kt`（新增）
- `hook-probe/src/main/java/com/yefeng/majmax/hookprobe/manager/AiOverlayService.kt`（auto 接线）
- `hook-probe/src/main/java/com/yefeng/majmax/hookprobe/manager/DiagnosticsStore.kt`
- `hook-probe/src/androidTest/.../AutoAckDiagnosticsChecks.kt`（新增）
- `hook-probe/src/androidTest/.../AutoDiscardInstrumentation.kt`
- `tools/tests/AutoAckChainProbe.kt` + `tools/tests/jvm-shims/*` + `tools/tests/run-auto-ack-chain.sh`（新增）

Lua 适配器、UI 四个文件、`tools/tests/test_auto_discard.lua` 未改动。

## 回归

`tools/tests/run-auto-ack-chain.sh` → **PASS 58 checks**，其中直接针对本次根因的：

- 已启用、有命令在途时喂 20 轮遥测：零 `GAME_REJECTED`、零 `WAITING`、开关不变；
- 命令待发（未确认）时喂遥测：`pending` 命令与 id 原样保留，之后 `submitted` /
  `accepted` / UPLINK 仍按同一 id 串起来；
- 遥测即使带 id 也不被分类；
- 暂停后同样的遥测零记录；
- 真实拒绝：只写一条、`stage=command`、带 id、`suspend`；两个不同状态各写一条；
- 周期汇总：计数精确（首条之外的出现次数）、窗口无需 `pause` 也会到期、map 上限
  `FOLD_CAPACITY = 64`。

完整 Gradle 构建由主 agent 执行；本机 chroot 内 aapt2 无法启动，与改动无关。
