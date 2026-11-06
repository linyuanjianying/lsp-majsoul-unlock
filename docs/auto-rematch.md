# 自动续场（实验性）

基于 `18d8036`，只支持普通四麻、三麻段位场（category=2，mode=1/2/11/12）。

## 使用

在管理应用“助手”页打开“整场结束后自动匹配（实验性）”，同时开启无人值守。默认关闭，设置独立保存。模块更新后完全重启游戏，在结算页出现前开启助手，以便观察最终结算 OnShow。

按游戏显示的下一步事件推进结算、收益；下一局可用时调用 Btn_Again，再确认它打开的同模式确认窗。活动收益使用按钮当前绑定事件，保留 TryClose 行为。每页按已有操作延迟设置等待。手动取消结束本场自动续场；关闭无人值守或续场开关撤销等待动作。

## 边界

- 按钮调用成功只表示客户端回调已执行，不代表服务器已接受匹配。新场动作继续由原有完整同步校验控制。
- 同一页不重复点击，确认窗等待上限 15 秒；异常不重试，不主动更换场次。
- 不处理未知弹窗。例如宝箱弹出的 UI_GetReward 暂停自动操作，需手动确认；特殊升段交互也可能需要手动完成。其余可自动完成的收益动画等待原生回调。
- 游戏 Lua 版本依据已有运行时转储；共享 APK 的目标脚本加密，见 auto-rematch-apk-check.md。
- 未进行真实整场游戏验证。本版不宣称支持所有收益/活动窗口，也未产出经真机验证的 APK。

## 构建状态

本机执行 `compileUniversalDebugKotlin` 被 `verifyUniversalNativeLibraries` 拦截：缺少本仓库原生 `.so`，未完成 APK 构建或完整 Kotlin 编译。按仓库 README 构建原生库后走现有 CI。

## 验证

```sh
lua5.1 tools/tests/test_auto_rematch.lua
lua5.1 tools/tests/test_auto_discard.lua
lua5.1 tools/tests/test_emoji_compat.lua
```

17 条续场回归、63 条自动操作回归、10 条表情回归。续场测试可按上述命令单独运行；仓库现有构建工作流保持不变。

真实验证时检查：无额外收益一场、活动多页收益、手动取消确认窗、开关关闭、断线恢复；日志组件 `assistant.rematch` 记录 next_invoked / again_invoked / confirmation_invoked 与暂停、失败原因。不要把 confirmation_invoked 当作排队成功证据。
