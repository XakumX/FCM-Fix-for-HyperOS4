# HyperOS 4 (Android 17) GMS 推送限制机制分析

> 数据来源:Redmi K90 Ultra(HyperOS 4.0 开发版,Android 17)实机提取的
> `miui-services.jar` / `PowerKeeper.apk` / `PowerInsight.apk` 反编译结果(jadx 1.5.6)。
> 本文档记录 HyperOS 4 相对旧版 HyperOS 的 GMS 管控变化与适配结论。

## 一、核心结论:HyperOS 4 的 GMS 限制机制已全面换代

旧版(HyperOS 1~3)通过 `GreezeManagerService.triggerGMSLimitAction` 直接限制 GMS;
HyperOS 4 引入了全新的 **Aurogon / Immobulus 省电体系**:
`powerkeeper 检测 Google 网络不可达 → Binder 通知 greezer → 把 GMS 移出 Aurogon 白名单 → 快速冷冻(QuickFreeze)`,
FCM 长连接因此无法重连。这就是旧模块失效的根因。

完整链路:

```
powerkeeper: GmsObserver.onGoogleReachabilityChanged(false)
  └─ updateFrameworkGmsNetStatus(limit=true)
       └─ Binder: miui.greeze.IGreezeManager.updateGmsNetStatus(true)
            └─ GreezeManagerService.lambda$updateGmsNetStatus$3()
                 ├─ if (!PolicyManager.isCnModel() || !mGmsLimitEnabled || mScreenOn) return;
                 ├─ mImmobulusMode.removeAurogonAllowList("com.google.android.gms")
                 └─ 对每个运行中的 GMS uid: mImmobulusMode.triggerQuickFreeze(gmsuid, 0)
```

## 二、各 hook 点在新系统的状态(逐一反编译确认)

### system_server(miui-services.jar)

| 旧 hook 点 | HyperOS 4 状态 | 处理 |
|---|---|---|
| `GreezeManagerService.isAllowBroadcast(int,String,int,String,String)` | ✅ 5 参签名未变 | 保留 hook;增强:GMS callee + c2dm 也放行 |
| `GreezeManagerService.deferBroadcastForMiui(String)` | ✅ 未变 | 保留 |
| `GreezeManagerService.triggerGMSLimitAction(boolean/无参)` | ❌ 方法已删除 | 移除 hook |
| `GreezeManagerService.mGmsLimitEnabled` | ✅ public boolean 字段仍在,构造器置 true | **新增**:构造器后置 false |
| `GreezeManagerService.updateGmsNetStatus(boolean)` | 🆕 新增方法(GMS 限制入口) | **新增**:参数强制 false |
| `AurogonImmobulusMode`(新类 `com.miui.server.greeze.AurogonImmobulusMode`) | 🆕 | **新增**:构造/更新云白名单后把 GMS 加回 `mAllowList` 并调 `TransfermLocalAllowList`;hook `removeAurogonAllowList(String)` 拒绝移除 GMS |
| `DomesticPolicyManager.deferBroadcast(String)` | ✅ 未变,`CN_DEFER_BROADCAST` 四个 action 原样 | 保留(直接返回 false) |
| `ListAppsManager.mSystemBlackList` / `mUseDataWhiteList` | ❌ 实例字段已删除;**全部改为 static**:`SYSTEM_BLACK_LIST` / `USE_DATA_WHITE_LIST`;构造器仅 `!isCnModel()` 时移除 GMS | **重写**:操作 static 字段 + hook `isInWhiteList(String)` 对 GMS 返回 true |
| `AwareResourceControl.mNoNetworkBlackUids` | ✅ 字段仍在,初始值含 `com.google.android.gms` | 保留(构造后移除 GMS) |
| `BroadcastQueueModernStubImpl.checkApplicationAutoStart(BroadcastQueue,BroadcastRecord,ResolveInfo)` | ✅ 3 参签名未变 | 保留 |
| `ProcessPolicy`(类) | ✅ 仍在 | 保留 |
| `AMS.broadcastIntentWithFeature` / `PowerExemptionManager` | ✅ 未变 | 保留(弹性匹配) |

### powerkeeper(PowerKeeper.apk)

| 旧 hook 点 | HyperOS 4 状态 | 处理 |
|---|---|---|
| `NetdExecutor.initGmsChain(String,int,String)` | ❌ 已删除 | 移除 hook |
| `NetdExecutor.setGmsDnsBlockerState(int,boolean)` | 🆕 执行 `dnsproxyd setuiddnsrule <uid> deny/allow`(GMS DNS 拦截) | **新增**:boolean 强制 false(allow) |
| `NetdExecutor.enableFirewallStandbyChain()/disable...` | 🆕 `dnsproxyd enablemiuistandby`(全局 standby 防火墙链,`AppStandbyController.setMiuiStandby` 调用) | 不 hook(全局链,影响面大;GMS 定向放行由 setGmsDnsBlockerState 完成) |
| `GmsObserver.updateGmsAlarm/updateGmsNetWork/updateGoogleReletivesWakelock(boolean)` | ❌ 已删除 | 移除 hook |
| `GmsObserver.updateFrameworkGmsNetStatus(boolean)` | 🆕 通知 greezer 限制 GMS | **新增**:参数强制 false |
| `GlobalFeatureConfigureHelper.getDozeWhiteListApps` | ✅ 未变 | 保留 |

### PowerInsight.apk

仅含统计采集(GMS 广告 ID、启用状态监测),**不参与推送拦截**,无需 hook。

## 三-b、广播投递链路(冻结目标应用对 FCM 无响应的根因)

`GreezeManagerService.isRestrictReceiver(Intent, callerUid, callerPkgName, calleeUid, calleePkgName)`
是广播接收者限制的入口:

1. `getFreezeState(calleeUid) != FULLY_FROZEN` → 不限制(正常投递);
2. `isAllowByGoogleFreeze(...)` → 走 Google/AOSP freeze 路径;
3. `isAllowBroadcast(...)` 通过后:
   - `!isRunningLaunchMode() || !checkImmobulusModeRestrict(calleePkgName, action)` → `thawUidAsync(calleeUid, 1000, "broadcast")` 解冻目标;
   - 否则 → `addLaunchModeQiutList(calleeUid)` **不解冻**;
4. `checkImmobulusModeRestrict` 查 `mImmobulusModeWhiteList`(默认仅含 `com.xiaomi.metoknlp`),
   其它包一律返回 true → 熄屏 LaunchMode 期间,冻结中的目标应用对 GMS 广播无响应
   → 表现为 FCM 诊断中的 `no response to broadcast from xxx`(与应用后台策略「智能限制」冻结行为一致)。

**修复(v1.7.0 → v1.7.1 修正)**:hook `checkImmobulusModeRestrict(String,String)`,当 action 为
c2dm / MESSAGING_EVENT / 4 个重连广播时返回 false,强制走 `thawUidAsync` 解冻目标应用。

**重要教训(v1.7.1)**:不能 hook 外层 `BroadcastQueueModernStubImpl.checkReceiverIfRestricted`
直接返回 false —— 那样会跳过 `isRestrictReceiver` 内部的 `thawUidAsync`,冻结进程仍无响应。
正确做法是只 hook 内层判断(isAllowBroadcast + checkImmobulusModeRestrict),
让系统内部完整走"允许广播 → 解冻目标 → 投递"路径。

## 三、移植版(v1.7.0)的实现要点

1. **双入口封堵**:system_server 侧 `updateGmsNetStatus` 强制 false(限制请求直接无效)+ `mGmsLimitEnabled=false`(开关关闭);powerkeeper 侧 `updateFrameworkGmsNetStatus` 强制 false(不发起限制请求)。
2. **Aurogon 白名单多保险**:构造器后/`updateCloudAllowList` 后/`getNoRestrictApps` 后三处补加;`removeAurogonAllowList` 拒绝移除;每次补加都调用 `TransfermLocalAllowList` 同步 native。
   - **QuickFreeze 豁免**:反编译发现 `triggerQuickFreeze` 的执行函数不检查 `mAllowList`,但检查 `mNoRestrictAppSet`(Settings 键 `MILLET_NO_RESTRICT_APP`,会被 ContentObserver 触发 `getNoRestrictApps()` 清空重建)—— 模块在三处时机把 GMS 补加进 `mNoRestrictAppSet`,确保 GMS 在熄屏时也不会被 QuickFreeze 冻结。
3. **广播放行**:`isAllowBroadcast` 5 参 hook 覆盖 GMS 作为 caller(发 c2dm)与 callee(收重连广播)两个方向;`deferBroadcastForMiui` + `DomesticPolicyManager.deferBroadcast` 保持对 4 个重连广播的豁免。
4. **黑白名单**:`ListAppsManager` static 字段 `SYSTEM_BLACK_LIST` 移除 GMS、`USE_DATA_WHITE_LIST` 加入 GMS、`isInWhiteList` 直接放行;`AwareResourceControl` 断网黑名单移除 GMS。
5. **网络放行**:`setGmsDnsBlockerState` 强制 allow(GMS DNS 不被 dnsproxyd 拦截)。
6. **广播解冻(v1.7.0)**:hook `checkImmobulusModeRestrict`,FCM 广播在 LaunchMode 下也强制 `thawUidAsync` 解冻目标应用(解决"后台智能限制/休眠时 no response to broadcast")。
7. 所有新增点仍保留"按名字 + 参数弹性匹配"回退,单个失败不影响其它,全部输出 `[OK]`/`[FAIL]` 日志。
