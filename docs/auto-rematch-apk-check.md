# 自动续场安装包核对

只读检查共享目录 `雀魂麻將_4.0.16_MC.apk`，没有修改或重打包游戏。

- Manifest：`com.soulgamechst.majsoul`，`4.0.16_MC`，versionCode `222`。
- APK 大小：1,795,200,399 字节。
- SHA-256：`73b28ea9c35a35ce179c0b43a13faaf72d41387a8a6ff1d9967b24a43ed318c7`。
- Unity `2022.3.62f1`，IL2CPP / ToLua，含 ARM64 与 ARM32。
- 目标资源位于 `assets/Android/2_tsb4vk_ee829c5730999255d9fb.majset`，UnityPy 可读 bundle。
- `UI_GameEnd.lua` 和 `UI_AnotherGameConfirm.lua` TextAsset 是 Base64 编码的非明文数据，未取得其解密正文。

实现所用方法、字段来自设备已有运行时明文 Lua 转储，而非 APK 解密结果：

- `UI_GameEnd.OnShow(callback, data)` / `data.game_end_result.players`。
- `btn_next.onClick` 在活动收益阶段会改绑到 `UI_MJReward_Activity.TryClose`。
- `UI_GameEnd.Btn_Again()` 打开 `UI_AnotherGameConfirm`，传入 `game_config.meta.mode_id`。
- `UI_AnotherGameConfirm.Confirm()` 调用 `DesktopMgr.AnotherGame()`。
- 活动分页字段为 `UI_MJReward_Activity.index`，任务索引为 `UI_GameEnd._index`。

APK 与明文转储具有对应的资源名，但不能据此证明源码完全一致。本次未启动真实对局，仍需实机核对结算阶段与下一场连接。
