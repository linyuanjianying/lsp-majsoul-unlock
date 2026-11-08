# 登录后偶发闪退检查与修复

## 实际证据

设备 com.soulgamechst.majsoul 的 ApplicationExitInfo 存在两条 reason=5 APP CRASH(NATIVE)、status=11。对应 /data/tombstones/tombstone_03、tombstone_04：

- 2026-11-05 02:16:28 +0800，启动28秒，IL2CPP Threadpo 线程。
- 2026-11-06 23:03:30 +0800，启动25秒，同类型线程。
- 两次都是 SIGSEGV / SEGV_MAPERR，读取地址 0x70，x0=0。
- 两次 #00 都是 libil2cpp.so+0x22cb254，BuildId 40c22eb901af6122f7e5dc202c28a4104ad2e58d。
- 两次 #01 都是模块 hookedBestDispose(void*, void const*)+88。

只保存相关崩溃头和堆栈到交付包，没有导出完整进程内存。

## 指令核对与结论边界

本机同BuildId游戏库的0x22cb254为 ldr x8,[x0,#112]，符合null+0x70读取。调用者返回地址0x215b22c指向对0x22cb238入口的调用；0x22cb238..0x22cb244是12字节的短包装（读取字段、条件跳转、ret），相邻0x22cb248为另一方法入口。

两份崩溃明确关联模块Dispose trampoline；短函数内联patch兼容/相邻入口影响是较强假设。尚未还原事故当时Hook框架的trampoline机器码，不能将“具体改写越界”当成已证实事实；也不能仅凭栈排除原始游戏竞争条件。

## 修复

删除 WebSocket.Dispose() 的内联Hook注册、回调与原函数指针；游戏原始Dispose不再经过模块trampoline。保留 WebSocket.Close()、Send和二进制消息回调原有流程。

代价：仅通过Dispose释放而没有调用Close的连接，不再在这一时刻显式清除模块请求/待注入缓存。Close路径仍清理；需在断网重连/反复登录测试中观察缓存与连接隔离，后续更合适的关闭通知回调应单独设计，不能把同一个短包装直接重新Hook。

本轮还检查了Java初始化、GameHintLayer和Lua引导。没有证据把子代理提出的OOM、GC或泛化线程风险认定为本次根因，因此未作推测性大范围改动。

## 验证

- probe.cpp 使用NDK头文件分别编译为arm64、armv7、x86、x86_64 Android目标对象文件；-O2 -Wall -Wextra -Werror 全部通过。
- compileUniversalDebugKotlin、compileUniversalDebugAndroidTestKotlin通过；源码编译跳过native库完整性任务。
- 现有提示纯逻辑回归20项通过。
- git diff --check通过，交付补丁反向apply --check通过。

未链接发布版SO、未生成或安装APK、未启动游戏进行反复登录验证；本机原NDK包装脚本在clang启动时失败，使用本机Clang配NDK sysroot完成上述四架构对象编译。本修复已落实在源码，尚未成为设备上生效的修复。

基线e769e19；本次交付包含上一轮猫头与黄色提示修复。未提交或推送GitHub。
