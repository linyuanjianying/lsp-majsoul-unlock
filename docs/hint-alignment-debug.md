# 手牌提示徽章对齐排障记录

> 分支 `feat/hand-hint-alignment`。本文记录"提示又看不到了"这一轮的失败根因、实际证据、三相机逐个结果、修复与验证，以及仍需真机确认的事项。

## 结论（TL;DR）

这一轮徽章消失的**直接根因不是相机投影错误**，而是 Kotlin 覆盖层的窗口时序 bug：

`render()` 在 overlay 根视图**尚未 attach 到 WindowManager、尚未 measure** 时，就调用
`measureMapping(frame)` 去读 `frame.width/height`。此时 frame 是刚 `new` 出来的
`FrameLayout`，宽高为 0，`measureMapping` 返回 `null` → 记录
`BADGE_HIDDEN reason=no_origin` → `badgeTargets` 为空 → 立即 `detach()`。

结果：代码里那段"`frame.post` 布局之后再算坐标"的逻辑**永远执行不到**——因为 `addView`
发生在 `positionHints`（内部 `measureMapping`）**之后**，而 `measureMapping` 一失败就 `detach`
了，post 回调从未被挂上（即便挂上也因 `root` 已被置 null 而直接 return）。

相机投影本身是正确的：日志显示坐标 `nx ∈ 0.209..0.7406`、`ny ∈ 0.897..0.923`，全部落在屏幕内，
且 `HOOK_COORDS kept=14 rejected=0`。问题纯粹是"几何算得对，但窗口异步测量时序错"。

## 实际证据（日志）

- 安装版本 `lastUpdate 2026-11-03 11:28:40`；assistant 日志尾部 `11:30:24`。
- `BADGE_HIDDEN reason=no_origin views=14 hints=3 frame=0x0` —— `frame=0x0` 是决定性证据：
  overlay 根视图在测量时宽高还是 0。
- 邻近 `GAME_GEOMETRY`：Unity `2376x1080`，overlay `2376x1080`，`ortho=1`，坐标
  `nx 0.209..0.7406`、`ny 0.897..0.923`。
- `HOOK_COORDS kept=14 rejected=0` —— 相机至少投影出了屏幕内坐标，几何是对的。

`frame=0x0` + `reason=no_origin` 二者同时出现，即锁定为窗口异步问题，而非相机问题。

## 三相机逐个结果（新增有界候选诊断）

此前 `pickCamera` 每次都真实地用 `positions` 调 `projectThrough` 枚举并评分所有 enabled 相机，
但日志只记录获胜相机名和 `ortho`，其他相机不落日志，导致无法逐个核对。

现改为：`hand_geometry` 里**总是**输出有界候选诊断 `cams`，一轮一次（由几何指纹去重，非每 tick）：

```
cams = name|ortho|inView/total|minNx,maxNx,minNy,maxNy|meanNy  （每台相机一段，'+' 连接）
```

最多列出 6 台相机。三个字段对应你要的三件事：

- `inView/total`：该相机投影命中屏幕内的牌数；
- `minNx,maxNx,minNy,maxNy`：投影包围盒（范围）；
- `meanNy`：投影平均 y（手牌应贴底，`meanNy` 接近 1）。

选择逻辑不变：**不盲改 `Camera.main`，不强制某相机**；仍按"命中数 → meanNy 更靠底 → 水平更紧凑"
评分，且要求 `meanNy >= 0.5`（排除上半个镜像/特效相机）。正确手牌位置由实际全手覆盖 + 底部指标
（`inView` 与 `meanNy`）选定。若候选含三个相机，现在能各自看到命中数、投影范围和平均 y。

真机日志出现 `cams=...` 后，即可逐一确认三台相机里哪台把 14 张手牌投到屏幕下缘。

## 修复

### 1. 覆盖层窗口时序（核心，`TileBadgeOverlay.kt`）

`render()` 重构为：

1. 空 hint → `renderGeneration++` + `detach()`（使旧 post 失效）。
2. **先** `addView`/`updateViewLayout` 把 root 挂到 WindowManager。
3. `renderGeneration++`，捕获 `gen` 与 `frame`。
4. `frame.post { ... }` 在布局完成后执行：
   - 守卫：`root === frame && gen == renderGeneration`，过期直接 return；
   - 重新 `measureMapping(frame)`（此时 frame 已测量，宽高非 0）；
   - mapping 无效 → 记录 `BADGE_HIDDEN reason=no_origin` 并**保留**文字面板（不再 detach），
     return，等下一轮稳定几何/手牌再试；
   - mapping 有效 → `computeBadgePlacement` 算锚点 → 创建 badge view → 测宽 → 摆放；
   - 输出 `BADGE_PLACED` 记录最终像素目标。

关键点：**几何测量与坐标计算严格推迟到 root attach + 布局之后**；绝不再在零尺寸 frame 上
提前计算并 detach；`detach()` 也 `renderGeneration++` 使所有在途 post 失效。

### 2. 数学与异步 render 拆分（可测纯函数，`TileBadgeOverlay.kt`）

- 新增 `internal fun computeBadgePlacement(hints, views, mapping, frameHeight): BadgePlacement`：
  纯配对 + 坐标映射，不碰窗口。`mapping` 由外部测量后传入。
- `DiscardHint` 从 `private` 提升为 `internal`（纯函数需要暴露其参数类型）。

### 3. 相机候选诊断（`MajsoulMaxAutoDiscard.lua`）

见上文"三相机逐个结果"。`diag.cams` 从"仅 `cam == nil` 时输出"改为"总是输出"，格式扩展为
`name|ortho|inView/total|bbox|meanNy`。

## 验证

- Lua 语法：`luac -p` 通过。
- Lua 测试：`lua tools/tests/test_auto_discard.lua` → **61 automatic operation checks passed**，
  含 4 个相机选择测试（正交手牌相机优先、排除上半个镜像投影、无覆盖时保留文字提示并输出候选诊断、
  同相机稳定性）。
- Kotlin 主源码：`compileArm64DebugKotlin`（本机 arm64，跳过资源链接）确认 `TileBadgeOverlay.kt`、
  `HandCoordinateMapper.kt` 无编译错误（剩余 `R` unresolved 为跳过资源生成所致，非本改动引入）。
- 新增 Instrumented 纯测试（`AutoDiscardInstrumentation.kt`）：
  - "computeBadgePlacement places anchors from a post-layout mapping"——覆盖"初始 frame=0 但
    post-layout mapper 可用"场景（`nx=0.28 → x=545`）；
  - "rejects an unusable mapping instead of guessing"——mapping 无效/空 views 时返回空；
  - "reports ambiguous duplicate tiles"——重复牌歧义上报。

## 待真机确认

1. 真机安装新构建后，`frame=0x0` 的 `BADGE_HIDDEN` 应消失，改为 `BADGE_PLACED` 且
   `frame=2376x1080`。
2. 新 `cams` 字段应出现三台相机的 `inView/total`/包围盒/`meanNy`，据此最终确认获胜相机确实是
   把 14 张手牌投到底部下缘的那台。
3. 若仍无徽章，下一级信号是 `BADGE_HIDDEN reason=no_coords`（无坐标）或 `reason=no_anchor`
   （坐标有但配对不到），而非 `no_origin`——据此可区分"相机几何"与"窗口异步"两类问题。
