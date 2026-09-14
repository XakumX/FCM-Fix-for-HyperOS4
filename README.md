# FCM-Fix-for-HyperOS4

移植自 [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live),
已在 Redmi K90 Ultra(HyperOS 4.0.0.9.XHPCNXM.D01, Android 17)实测通过:
应用后台策略保持「智能限制」+ 锁屏静置状态下,谷歌推送即时到达。

## 原理

HyperOS 4 对 GMS 的限制链路:

```
powerkeeper: GmsObserver 检测 Google 不可达
  → Binder 通知 GreezeManagerService.updateGmsNetStatus(true)
  → 把 GMS 移出 Aurogon 白名单(removeAurogonAllowList)
  → triggerQuickFreeze 快速冻结 GMS → FCM 长连接断开(err io fin)

目标应用被冻结时收到 GMS 的 c2dm 广播:
  isRestrictReceiver → isAllowBroadcast 通过后
  → LaunchMode 下目标不在 mImmobulusModeWhiteList
  → 不解冻目标 → 广播无响应(no response to broadcast)
```

本模块在 system_server 与 com.miui.powerkeeper 内 hook 上述链路的关键判断,
让 GMS 长连接保活,并保证 FCM 广播能解冻并唤醒目标应用。

## 构建

- JDK 21
- Android SDK:platforms;android-37.0、build-tools;37.0.0
- `gradlew.bat assembleRelease`(Windows)

## 安装

1. LSPosed(需支持 LibXposed API 102)→ 启用本模块
2. 作用域勾选:**系统框架** + **com.miui.powerkeeper**
3. 重启;锁屏静置 10~15 分钟后测试推送

启用后系统待机耗电可能增加(GMS 不再被冻结限制)。

## 致谢

- [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live)(GPL-3.0):原始模块