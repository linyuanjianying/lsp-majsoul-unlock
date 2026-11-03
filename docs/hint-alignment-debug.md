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
