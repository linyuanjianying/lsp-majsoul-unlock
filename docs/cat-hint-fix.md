# 猫头提示与黄色提示修复

基线：origin/main e769e19。修改在 fix/cat-hint-yellow 独立工作目录，未提交、未推送。

## 修改

- 新增 CatHintView：按用户提供的 猫头素材.zip 中 SVG 路径、眼睛位置和 cat-hud.js 的金色、深蓝、浅蓝色系进行 Android Canvas 绘制。操作名、百分比保留；猫头区域位于 View 内以避免 Android 裁剪。原生版未复刻 CSS 发光动画。
- TileBadgeOverlay.computeBadgePlacement 原来在同牌视图数量大于一时计为 ambiguous 并跳过。因此最高推荐为对子、刻子中的牌时，最高推荐徽标会消失。现按从左到右选择未使用的同牌视图；赤五仍与普通五分开。这是手动出牌提示的目标选择，不改变自动出牌控制逻辑。
- DiscardHint/ButtonHint 携带原始推荐数组中的 rank，绘制时保留排名，不因过滤后重新编号而把次选误涂成金色。
- GameHintLayer 的坐标去重同时比较来源尺寸；更新抛异常时不推进已消费快照，下次 tick 可重试。

## 验证

- compileUniversalDebugKotlin、compileUniversalDebugAndroidTestKotlin 通过。只做源码编译，跳过 verifyUniversalNativeLibraries；未构建或交付可安装 APK。
- tools/tests/run-jvm-pure-logic.sh：20 passed, 0 failed，调用实际编译的生产类；包含同牌锚点分配、不复用视图、赤五区分、缺失坐标、映射恢复、操作按钮过滤及原始排名保留。
- instrumentation 新增猫头配色排名用例，并修改旧的“重复牌必须隐藏”断言。测试代码已编译，未在设备上执行。
- git diff --check 通过。

未进行雀魂真实牌局复现或像素截图验收。重复牌缺陷是可确定的静态代码路径，并有实际 JVM 回归结果支撑；不能保证用户反馈的所有偶发场景都由这一原因导致。

## 应用补丁

在 e769e19 基线的干净仓库根目录运行：

```sh
git apply --check 猫头提示修复.patch
git apply 猫头提示修复.patch
```

压缩包同时包含修改后的完整源码快照、修改文件、编译日志和回归日志。素材来自用户提供压缩包，保留原始素材用于溯源。
