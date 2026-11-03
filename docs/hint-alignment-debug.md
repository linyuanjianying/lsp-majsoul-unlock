# 手牌提示徽章对齐排障记录

> 分支 `feat/hand-hint-alignment`。本文记录"提示又看不到了"这一轮的失败根因、实际证据、三相机逐个结果、修复与验证，以及仍需真机确认的事项。

## 第一轮（已合并进本分支历史）：窗口 attach 时序

`render()` 在 overlay 根视图**尚未 attach 到 WindowManager、尚未 measure** 时就调用
`measureMapping(frame)` 去读 `frame.width/height`。此时 frame 是刚 `new` 出来的
`FrameLayout`，宽高为 0 → 返回 `null` → 记录 `BADGE_HIDDEN reason=no_origin` → 立即 `detach()`。

当时判断"`frame.post` 布局之后再算坐标"可修，但 #282 构建的日志证明**并没有修好**：

- `BADGE_HIDDEN` 单条记录与 `HINT_ORDER` 出现在**同一毫秒序列**（`timeUtcMs 1793682520923`
  紧跟 `HINT_ORDER 1793682520915`），也就是该次 render 自己就把那次尝试判死了；
- 且 `frame=2256x1080`，**不是** 0x0。

## 第二轮（本轮，#282 / commit 5ce05bc 构建）：真正根因

### 根因 1（决定性）：Lua UI 资产缓存从未刷新，Kotlin 收到的是旧"裸数组"载荷

- 设备上 `…/com.soulgamechst.majsoul/files/majsoulmax-hook/ui/MajsoulMaxAutoDiscard.lua`
  修改时间 `Nov 2 21:27`，大小 `33315` 字节；仓库当前资产为 `35587` 字节。
- 该已安装脚本第 635 行仍是 `ack('', 'hand_coords', A.json.encode(coords))` —— **裸数组**，
  没有 `{screenW, screenH, coords}` 包装；`diag.cams` 也只有 4 个字段（无 inView/total/meanNy）。
- 同目录 `.asset-version` 内容为 `0.12.0-hint5`，而 `ProbeModule.ASSET_VERSION` 也是
  `0.12.0-hint5`：`if (!ASSET_VERSION.equals(installed))` 恒为 false，所以
  `VERSIONED_ASSETS` **从未被覆盖**。手写版本号忘记升级 = 新 Lua 永远不生效。
- 后果正好对上日志：`HOOK_COORDS kept=14`（坐标到了、解析成功），但
  `parseHandCoords` 走裸数组分支 → `sourceW=sourceH=0` → `buildMapping` 返回 null →
  `BADGE_HIDDEN`。而且 `cams` 永远是空字符串，所以日志里只有 `cam=camera`、没有 `cams=`。

### 根因 2：这次 `BADGE_HIDDEN` 的字段全被诊断白名单吞掉

`GeometryTrace.structuredKeys` 里有 `sourceW/sourceH/frameOrigin/viewport`，但
`DiagnosticsStore.allowedFieldNames` 没有这三个键，于是它们被塞进 `reason` 并被 64 字符截断，
最终只剩 `reasonno_origin frame2256x1080`。也就是说这条 bug 本来就把自身证据抹掉了。

### 根因 3：`frame.post` 方案在更新路径上会自我取消

旧的实现每次 `render()` 都 `renderGeneration++` 再 `frame.post { if (gen != renderGeneration) return }`。
一局里 update 频率远高于一次布局通过，排队的回调很容易被下一次 render 的 generation 顶掉，
于是"布局后再测量"永远不会发生。

### 生产侧潜在风险：native ack 静默丢弃

`probe.cpp:luaAutoAck` 对 `length > 2048` 直接 `return 0`，不写日志。14 张手牌的包装载荷约
1.1 KB，已接近上限；一旦超限，Kotlin 侧会表现为"完全没有坐标"，与相机无关。

### 相机不是根因

`HOOK_COORDS kept=14 rejected=0` 与 `minNx 0.2091 / maxNx 0.7406 / minNy 0.9019 / maxNy 0.9228`
说明投影是正常的（全部落在屏幕内、贴近手牌下缘）。本轮不再把 `no_origin` 归因于相机。

## 本轮修复

1. `ProbeModule.java`：`ASSET_VERSION` 改为由资产**实际字节长度**推导
   （`0.12.0-hint6-<name>:<len>…`）。任何一次 Lua 编辑都会改变时间戳，从而强制覆盖已缓存脚本；
   `CONFIGURED` 诊断同时带 `assetVersion=…`，让日志能直接看出装的是哪一版 Lua。
2. `TileBadgeOverlay.kt`：
   - 新增纯函数 `buildMapping(...)`，把失败拆成 `source_missing` / `frame_unmeasured` /
     `metrics_invalid`，`BADGE_HIDDEN` 一律带 `sourceW/sourceH/frame/frameOrigin/viewport` 真实数值；
   - `render()` 不再 `post` + generation 自取消。改为：已测得尺寸则**同步**放置；未测得则用
     `OnPreDrawListener` + `OnGlobalLayoutListener` 一次性等待，`cancelAwait()` 精确移除两个
     listener（`detach()`/`close()` 前先移除，不泄漏）；
   - `updateHookedCoords` 记录 `HOOK_SOURCE`（`wrapped` / `legacy_bare_array` + 实际 dims），
     旧缓存脚本能被一眼识别，不会再被误判成 Kotlin 量测故障。
3. `probe.cpp`：ack 上限 `2048 → 16 KiB`，超限时写入
   `{"state":"ack_oversize"}` 并由 `AutoDiscardController` 记为 `ACK_OVERSIZE` 并暂停等待重新同步，
   不再静默丢失。
4. `MajsoulMaxAutoDiscard.lua`：`hand_coords` 的去重签名由"拼接全部牌名/全部 nx"改为 32 位哈希，
   给单条 ack 留出余量（同时对相机名敏感，换相机即重发）。
5. `DiagnosticsStore.allowedFieldNames`：补 `sourceW/sourceH/frameOrigin/viewport/assetVersion`。

## 三相机逐个结果

`diag.cams` 由 `reportHandCoords` 每次几何指纹变化时输出，格式
`name|ortho|inView/total|minNx,maxNx,minNy,maxNy|meanNy`，多台用 `+` 连接。

**注意白名单**：`DiagnosticsStore` 按 `isLetterOrDigit || " _-./()：:@,+|"` 过滤字符串，
所以 `inView/total` 里的 `/` 会**被删掉**（`14/14` → `1414`）。读日志时请按此解释。

#282 日志里 `cams` 缺失的原因是旧 Lua（根因 1），不是 Kotlin 没带该字段——修复后重启一次牌局
即可看到三台相机各自的投影结果。

## 验证

- `lua5.1 tools/tests/test_auto_discard.lua` → **61 automatic operation checks passed**。
- Kotlin：`compileUniversalDebugKotlin` + `compileUniversalDebugAndroidTestKotlin`（本机 JDK 21）。
- 新增 instrumented 断言：`source_missing` / `frame_unmeasured` / `metrics_invalid` 三态，
  以及"初始 0x0 → 测得 2376x1080 后放置"的回归用例（`nx=0.7406 → x=1640`）。
- 模拟器 instrumented 测试与 APK 打包以 CI 为准。

## 待真机确认

1. 重启牌局后 `CONFIGURED` 里 `assetVersion` 应变为 `0.12.0-hint6-…`；`HOOK_SOURCE` 应为
   `wrapped;sourceW=2376;sourceH=1080`。
2. 徽章应出现 `BADGE_PLACED`（`frame=2256x1080`、`frameOrigin=120,0`、`viewport=2376x1080`）。
3. 若仍是 `BADGE_HIDDEN`，看 `reason` 取自 `source_missing` / `frame_unmeasured` / `no_anchor`，
   据此区分数据链路与窗口时序。
4. `GAME_GEOMETRY` 应带上 `cams=…`，可据此最终确认获胜相机。

## 第三轮（本轮，commit 61488dc 之后）：竖直偏移 —— 徽章压住牌面

前两轮修好的是"徽章有没有出现"；本轮只修"徽章出现在哪个高度"。相机、x 轴投影、
牌到提示的配对逻辑一律未动。

### 实测证据（`assistant-1.jsonl`，2026-11-03 约 14:31，游戏 `lastUpdate 2026-11-03 14:28:47`）

```
GAME_GEOMETRY ortho=1;cam=camera          # 三相机中只有它对 14/14 张牌投影成功
screenW=2376;screenH=1080
overlay root frame=2256x1080;origin=(120,0);viewport=2376x1080
BADGE_PLACED targets=2z@1420,961:12 1m@377,961:0 9m@472,961:1 …
```

对照同一帧的截图（2400x1080 手机，手牌在画面下缘）：

| 量 | 值 | 来源 |
| --- | --- | --- |
| 手牌牌顶边 | y ≈ 922 | 截图 |
| 徽章**顶边** | y = 961 | 日志 `BADGE_PLACED` |
| 徽章底边 | y ≈ 961 + 70~80 | 截图（徽章高度约 70~80 px） |
| 投影锚点 | `ny=0.9228` → 0.9228×1080 ≈ **997** | 日志 |

结论：锚点 997 与牌顶 922 相差 **75 px**；旧实现把
`BADGE_LIFT_FRACTION = 0.0333 × frameHeight`，即 0.0333×1080 ≈ **36 px**，
直接当成徽章**顶边**（997−36=961），既没有减掉徽章自身高度，也远小于真实的
"锚点→牌顶"距离，于是徽章整块压在牌面上（参考图要求徽章**底边贴着牌顶**）。

### 旧公式的问题不止是常数

1. **忽略了徽章高度**：即使常数凑对，只要换字号/换权重文案让徽章变高，仍会压牌。
2. **竖直和水平用了不同基准**：x 用"牌宽中心"，y 用"帧高分比"，两者没有共同语义。
3. **硬编码 1080p 像素量级**：不同分辨率/密度下比例会漂。

### 新方案（仅竖直方向）

`computeBadgePlacement` 现在只返回**投影锚点本身**（`x` 为牌中心，`y` 为原始投影 y），
不再在纯函数里减 lift；竖直位置在徽章**实测尺寸**之后按下面的式子得出：

```
tileTop  = anchorY - tileTopOffset            # 锚点→牌顶边
top      = tileTop - badgeHeight - gap        # 徽章底边贴在牌顶上方
bottom   = tileTop - gap
```

- `tileTopOffset = round(frameHeight × 0.0694)`（`TILE_TOP_OFFSET_OF_FRAME_HEIGHT`）。
  0.0694 = 75/1080，是**同一帧的日志锚点与截图牌顶**唯一对得上的量；按帧高归一化，
  因此随分辨率/密度线性缩放（1440 高 → 100 px）。
- `badgeHeight` 不再猜：`createBadgeCard()` 之后**先同步 `measure(AT_MOST frameWidth,
  AT_MOST frameHeight)`**，再 `addView` 并用 `measuredHeight` 计算 `top`，并将
  `LayoutParams.height` 固定为该高度。这样避免了"刚 addView 时 width=height=0"以及
  `post` 第二轮被新 render 的 generation 顶掉（前两轮的坑）。
- `gap = 1dp`；`top` 会被夹在 `[0, frameHeight - height]`；若牌顶以上空间不足，
  徽章高度收缩到可用空间；仍低于 20 px（1080p 尺度）则**跳过该徽章并记
  `BADGE_SKIPPED`**，而不是画到牌面上。
- 只改竖直；`x` 仍为 `centerX - badgeWidth/2`（夹在 2 px 内缩），投影、相机、配对不变。

### 为什么不用"牌高比例"

`estimateCardHeightPx()` 仍保留，但它只用于**记录**（`BADGE_VERTICAL cardH=…`）与
合理性判断。原因是实测样本里同一行牌之间的 `ny` 差很小（0.008~0.021，均值约 0.015，
即约 16 px），由此估出的"卡高"只有 48 px；若用它乘 0.70，锚点→牌顶只有约 34 px，
徽章仍会低 40 px。也就是说，在 `ortho1/camera` 这台相机下**坐标样本本身不足以反推牌高**，
帧高归一化才是唯一有实测佐证的量。

### 新增诊断

`BADGE_PLACED` 现在报告真实可见的徽章数；新增一条 `BADGE_VERTICAL`：
`anchor=997;cardH=48;off=75;badgeH=72;gap=1;top=852;skipped=0;frameH=1080`。
核对方式：`top + badgeH == anchor - off - gap`，且该值应等于截图里对应牌的牌顶。
`DiagnosticsStore` / `GeometryTrace` 已把 `anchor/cardH/off/badgeH/gap/top/skipped/frameH`
加入结构化白名单，不会再被折进 `reason` 并被 64 字符截断。

### 验证与验证边界

- 新增 instrumented 用例（纯函数，无需真机）：
  - `computeBadgeTop`：`anchor=997, off=75, badgeH=70, gap=0` → `top=852, bottom=922`；
    徽章更高时 top 更小、底边不变；1440 高时 `off=100`；底部/顶部/退化帧的夹取与跳过。
  - `estimateCardHeightPx`：同排用步距估、抽牌行用 dy 估、异常值夹取、单张无法定标。
  - 整链路：设备坐标 → 放置 → 竖直公式，断言每枚徽章底边 == 牌顶。
  - 既有用例改为断言 `computeBadgePlacement` 返回**未抬升**的原始锚点
    （`0.28→(545,972)`、`0.7406→(1640,988)`）。
- Lua 资产未改动，`lua5.1 tools/tests/test_auto_discard.lua` 61 项不受影响。

### 局限（务必知晓）

- 0.0694 是在**这一台设备 + `ortho1/camera` 这台相机 + 该游戏版本**上，用日志锚点与
  截图牌顶对齐得到的。换相机、换游戏 UI 缩放、换纵横比后该比值可能不同；届时
  应看 `BADGE_VERTICAL` 的 `anchor/off/top` 与截图重新定标，而不是调徽章字号。
- 目前只有"手牌在下缘、横向布局"这一种情况被验证；竖屏/其他牌桌布局未验证。
- 本轮**没有真机肉眼确认**，只保证编译与纯函数断言。
