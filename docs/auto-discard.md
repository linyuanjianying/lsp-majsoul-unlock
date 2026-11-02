# 自动切牌实现与验证

首版仅支持普通四麻、三麻的普通切牌。功能默认关闭，服务重启、切换自检或重连后均需要重新手动开启。游戏升级导致接口或状态不符时停止执行。

## 从推荐到出牌

游戏进程将修改前的消息复制到管理应用，采集头包含来源代次、连接编号和该连接的消息序列。采集队列丢包或连接变化会使代次失效。Akagi 在管理应用后台线程按序分析，输出当前连接、步骤、手牌、回合及首选动作；普通切牌还保留摸切标记。

管理应用的 `AutoDiscardController` 每个决策窗口最多安排一次指令。指令通过只允许游戏 UID 调用的 ContentProvider 返回游戏进程，每 250 ms 轮询一次，内容是 JSON 数据。JNI 保存单个待执行指令，LuaLooper 主线程每 100 ms 检查一次，不执行来自指令的代码。

Lua 适配器先检查采集代次和序列仍然有效，再检查游戏处于正常对局、没有重连或暂停、已结束摸牌动画并开放切牌窗口。实际场风编号从 1 开始，局数也从 1 开始，座位由 mjai 的 0 基转换为游戏的 1 基。核对完整手牌后，选择相同的普通牌或赤牌，并严格按手切或摸切匹配手牌对象。禁切牌、特殊选牌状态及可以和牌的窗口不会自动提交。

提交沿用 `ViewPlayer_Me:_setChoosePai` 和 `_DoDiscardTile`，保留游戏原来的操作状态、手牌显示和请求回调。网络观察器保留原请求和回调，同时报告提交、服务器确认或手动输入。管理应用还使用采集到的原始上行消息校验牌与摸切标记。缺少任一确认、返回错误或超过 6 秒即暂停，不重发。

## 控制与日志

助手页开关和悬浮窗按钮共用运行状态。收纳时，启用中的浮标显示“停”，点击先暂停，再次点击才能展开。状态不自动保存为启用。

助手日志记录 `assistant.autoplay` 的 `ENABLED`、`QUEUED`、`GAME_SUBMITTED`、`UPLINK_CONFIRMED`、`SERVER_ACCEPTED`、`GAME_REJECTED` 和 `PAUSED`。不会保存完整手牌、原始消息、游戏 UUID、账号或采集鉴权令牌。现有故障 ZIP 可导出这些事件。

## 验证方法

`tools/tests/test_auto_discard.lua` 使用假的游戏控制器检查赤五、摸切、重复指令、序列失效、手牌与回合不符、动画未完成、禁切、和牌窗口、特殊选择、手动输入和特殊玩法。GitHub Actions 与表情测试一起运行。

Rust AI 测试覆盖真实 protobuf 上行解码、输入后停止推荐、摸切信息与赤牌保留，以及断线、牌局恢复和三四麻分析。原有皮肤持久化与公告处理测试保持运行。

Android 设备测试使用真实 `JSONObject`，不发送游戏请求，覆盖默认关闭、自检拒绝、代次失效、手动输入、模型异常、非切牌首选、超时、不重试、双重确认和不匹配的上行。构建与运行命令：

```powershell
.\gradlew.bat -p .\hook-probe assembleArm64Debug assembleArm64DebugAndroidTest
adb install -r hook-probe/build/outputs/apk/arm64/debug/MajsoulHookProbe-arm64-debug.apk
adb install -r hook-probe/build/outputs/apk/androidTest/arm64/debug/MajsoulHookProbe-arm64-debug-androidTest.apk
adb shell am instrument -w com.yefeng.majmax.hookprobe.test/com.yefeng.majmax.hookprobe.manager.AutoDiscardInstrumentation
```

最后在机器人练习中验证首选切牌与实际发送一致、服务器接受、下一回合继续、手动暂停、收纳浮标暂停和手动输入暂停。不要将只通过模拟测试视为手机上的出牌链路已确认。

本次四麻机器人对局中，6 次指令均取得客户端上行和服务器接受确认，另一次过期指令被拒绝；手动操作和收纳浮标暂停也已生效。三麻的协议分析已通过自动测试，但三麻实际出牌、其他游戏版本与 LSPatch 下的控制链路尚未进行手机验证。

## 手牌提示徽章

切牌窗口期间，管理应用会在推荐切出的那张手牌上方画一张纯色徽章卡片。徽章的位置不依赖任何屏幕尺寸或牌距的猜测常量，而是用游戏自己报告的手牌坐标对齐：

- 游戏进程的 Lua 适配器在每次对局状态变化时，用主透视相机的 `WorldToScreenPoint` 计算每张暗牌中心的归一化坐标（0..1），并附上手牌 tile 名和相机、屏幕、归一化范围等诊断字段，通过 `hand_coords` / `hand_geometry` 回执返回管理应用。
- 管理应用把归一化坐标乘以悬浮窗自己的实测尺寸得到像素锚点，再按 tile 名把提示匹配到对应手牌。赤五（`0m`/`5mr`）与普通五（`5m`）保持独立，不会串位。
- 没有可信坐标时不画徽章，只保留文字推荐面板；任何被过滤或解码失败的坐标都会记录 `HOOK_COORDS`（含被拒坐标样本），不再静默消失。

坐标回执在 250 ms 轮询中与指令一起返回，`hand_coords` 的 payload 控制在 2 KB 以内，去重签名随手牌内容变化而重发——同一回合内摸牌后即使数量与两端坐标不变，只要中间某张牌变了也会补发新坐标，避免徽章停留在旧位置。

诊断字段 `GAME_GEOMETRY` 同时记录游戏的屏幕尺寸、悬浮窗实测尺寸、暗牌数量、相机是否正交、被相机后方遮挡的牌数，以及归一化坐标的 `minNx/maxNx/minNy/maxNy` 范围。真机上徽章完全消失时，先看这条记录：若 `minNx/maxNx` 全部落在 0..1 之外，说明相机选错或投影异常；若根本没有 `HOOK_COORDS`，说明坐标回执没有到达管理应用。不要凭静态假设或猜测坐标常数宣称已修复，必须以上述真机日志为准。

