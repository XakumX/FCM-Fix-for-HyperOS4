package com.xakumx.os4.fcmfix;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.PowerExemptionManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * HyperOS 4 (Android 17) 谷歌推送修复模块 —— 基于 Redmi K90 Ultra (OS 4.0.x) 的
 * miui-services.jar / PowerKeeper.apk 反编译结果精确适配,无 GUI。
 *
 * <p>修复链路:
 * <ul>
 *   <li><b>GMS 长连接</b>:关闭 {@code mGmsLimitEnabled} + 强制 {@code updateGmsNetStatus}
 *       为不限制 → 阻止"移出 Aurogon 白名单 + QuickFreeze"限制链;</li>
 *   <li><b>QuickFreeze 豁免</b>:三时机(constructor / updateCloudAllowList /
 *       getNoRestrictApps)把 GMS 补进 {@code mAllowList} 与 {@code mNoRestrictAppSet},
 *       并调用 {@code TransfermLocalAllowList} 同步 native;</li>
 *   <li><b>待机断网豁免</b>:拦截 {@code AppStandbyController.updateRuleForUidLocked}
 *       与 {@code DeviceIdlePolicyHelper.r} 对 GMS 的下发,避免熄屏待机时 GMS 被
 *       标记 inactive 而断网(表现为 err:3 连接被拒、断开数小时);</li>
 *   <li><b>广播投递</b>:{@code isAllowBroadcast}/{@code deferBroadcastForMiui} 放行
 *       c2dm 与 4 个重连广播;{@code checkImmobulusModeRestrict} 让 FCM 广播在
 *       LaunchMode 下也解冻目标应用;</li>
 *   <li><b>黑白名单</b>:{@code ListAppsManager} static 黑名单移除 GMS、白名单放行,
 *       {@code AwareResourceControl} 断网黑名单移除 GMS,{@code ProcessPolicy} 白名单补 GMS;</li>
 *   <li><b>唤醒目标</b>:{@code checkApplicationAutoStart} / {@code isAllowStartService}
 *       放行 GMS 广播与 FCM 服务启动,AMS 广播为目标应用加临时电量豁免并补
 *       {@code FLAG_INCLUDE_STOPPED_PACKAGES}。</li>
 * </ul>
 *
 * <p>所有方法查找带"候选名 + 参数弹性匹配 + 逐块 try/catch"回退(见 {@link Utils}),
 * 每个 hook 成败分别输出 {@code [OK]}/{@code [FAIL]} 日志便于 OTA 后排查。
 */
@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {
    private static final String TAG = "HyperGreeze";
    private static final List<String> CN_DEFER_BROADCAST = Arrays.asList(
            "com.google.android.intent.action.GCM_RECONNECT",
            "com.google.android.gcm.DISCONNECTED",
            "com.google.android.gcm.CONNECTED",
            "com.google.android.gms.gcm.HEARTBEAT_ALARM");
    private static final String ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE";
    private static final String ACTION_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT";
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private static final String GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent";

    /** 热重载用:本次安装的 hook id 集合。 */
    private final Set<String> hookedIds = new HashSet<>();

    private record PackageClassLoader(String packageName, ClassLoader classLoader) {
    }

    private PackageClassLoader param;

    // ---------------- 工具 ----------------

    /**
     * 安装 hook 并登记唯一 id(热重载时同 id 会被原子替换,避免重复挂载)。
     * id 由 方法名 / 类简名+参数个数 派生,天然唯一。
     */
    private XposedInterface.HookBuilder hb(Executable exec) {
        var builder = hook(exec);
        if (getApiVersion() >= 102) {
            String id = exec instanceof Method m
                    ? m.getName()
                    : "ctor-" + exec.getDeclaringClass().getSimpleName() + "-" + exec.getParameterCount();
            builder.setId(id);
            hookedIds.add(id);
        }
        return builder;
    }

    private void hookOk(String what) {
        log(Log.INFO, TAG, "[OK] hooked " + what);
    }

    private void hookFail(String what, Throwable t) {
        log(Log.ERROR, TAG, "[FAIL] " + what + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }

    private static boolean hasStringArg(List<Object> args, String value) {
        for (Object a : args) {
            if (value.equals(a)) return true;
        }
        return false;
    }

    private static boolean hasAnyDeferAction(List<Object> args) {
        for (Object a : args) {
            if (a instanceof String s && CN_DEFER_BROADCAST.contains(s)) return true;
        }
        return false;
    }

    /** 判断 action 是否为 FCM 推送/重连相关(c2dm、MESSAGING_EVENT 或 4 个重连广播)。 */
    private static boolean isFcmAction(String action) {
        return ACTION_REMOTE_INTENT.equals(action)
                || ACTION_MESSAGING_EVENT.equals(action)
                || CN_DEFER_BROADCAST.contains(action);
    }

    /**
     * 判断对象是否为 GMS 发出的 c2dm 广播记录(BroadcastRecord)。
     * 传入预解析字段以避开热路径上的反射查找(字段为 null 时回退按名字反射)。
     */
    private static boolean isGmsC2dmRecord(Object obj, Field intentField, Field callerField) {
        if (obj == null) return false;
        Object intent;
        Object caller;
        if (intentField != null) {
            try {
                intent = intentField.get(obj);
            } catch (Throwable t) {
                return false;
            }
            caller = fieldValue(callerField, obj, "callerPackage", "callerPkg", "callingPackage");
        } else {
            intent = Utils.getFieldOrNull(obj, "intent");
            caller = Utils.getFieldOrNull(obj, "callerPackage", "callerPkg", "callingPackage");
        }
        if (!(intent instanceof Intent it) || !ACTION_REMOTE_INTENT.equals(it.getAction())) return false;
        return GMS_PACKAGE_NAME.equals(caller) || GMS_PERSISTENT_PROCESS_NAME.equals(caller);
    }

    /** 优先用预解析字段取值,失败再按候选名反射取值。 */
    private static Object fieldValue(Field field, Object obj, String... fallbackNames) {
        if (field != null) {
            try {
                return field.get(obj);
            } catch (Throwable ignored) {
            }
        }
        return Utils.getFieldOrNull(obj, fallbackNames);
    }

    private static boolean isFcmIntent(Object arg) {
        if (!(arg instanceof Intent intent)) return false;
        String action = intent.getAction();
        return ACTION_REMOTE_INTENT.equals(action) || ACTION_MESSAGING_EVENT.equals(action);
    }

    private static boolean hasFcmIntentArg(List<Object> args) {
        for (Object a : args) {
            if (isFcmIntent(a)) return true;
        }
        return false;
    }

    private static boolean hasGmsC2dmRecordArg(List<Object> args, Field intentField, Field callerField) {
        for (Object a : args) {
            if (isGmsC2dmRecord(a, intentField, callerField)) return true;
        }
        return false;
    }

    /** 把所有 Boolean 参数强制置 false,返回是否有 Boolean 参数。 */
    private static boolean forceBooleanArgsFalse(Object[] args) {
        boolean has = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Boolean) {
                args[i] = Boolean.FALSE;
                has = true;
            }
        }
        return has;
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        this.param = new PackageClassLoader("system", classLoader);
        log(Log.INFO, TAG, "[LOAD] HyperFCM A17 v2 loading in system_server, api=" + getApiVersion());
        try {
            hookSystemServer(classLoader);
        } catch (Throwable tr) {
            hookFail("SystemServer", tr);
        }
    }

    private void hookSystemServer(ClassLoader classLoader) {
        try {
            hookGreezeManagerService(classLoader);
        } catch (Throwable t) {
            hookFail("GreezeManagerService", t);
        }
        try {
            hookAurogonImmobulusMode(classLoader);
        } catch (Throwable t) {
            hookFail("AurogonImmobulusMode", t);
        }
        try {
            hookDomesticPolicyManager(classLoader);
        } catch (Throwable t) {
            hookFail("DomesticPolicyManager", t);
        }
        try {
            hookListAppsManager(classLoader);
        } catch (Throwable t) {
            hookFail("ListAppsManager", t);
        }
        try {
            hookAutoStartChecks(classLoader);
        } catch (Throwable t) {
            hookFail("AutoStartChecks", t);
        }
        try {
            hookProcessPolicy(classLoader);
        } catch (Throwable t) {
            hookFail("ProcessPolicy", t);
        }
        try {
            hookAwareResourceControl(classLoader);
        } catch (Throwable t) {
            hookFail("AwareResourceControl", t);
        }
        try {
            hookActivityManagerService(classLoader);
        } catch (Throwable t) {
            hookFail("ActivityManagerService", t);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        var packageName = param.getPackageName();
        var classLoader = param.getClassLoader();
        this.param = new PackageClassLoader(packageName, classLoader);
        try {
            hookPackage(packageName, classLoader);
        } catch (Throwable tr) {
            hookFail("package " + packageName, tr);
        }
    }

    private void hookPackage(String packageName, ClassLoader classLoader) {
        if ("com.miui.powerkeeper".equals(packageName)) {
            try {
                hookStandbyNetworkExemption(classLoader);
            } catch (Throwable e) {
                hookFail("StandbyNetworkExemption", e);
            }
            try {
                hookGlobalFeatureConfigureHelper(classLoader);
            } catch (Throwable e) {
                hookFail("GlobalFeatureConfigureHelper", e);
            }
        }
    }

    // ---------------- 热重载 ----------------

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        param.setSavedInstanceState(this.param);
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        hookedIds.clear();
        var isSystemServer = param.isSystemServer();
        if (param.getSavedInstanceState() instanceof PackageClassLoader(
                String packageName, ClassLoader classLoader
        )) {
            try {
                if (isSystemServer) {
                    hookSystemServer(classLoader);
                } else {
                    hookPackage(packageName, classLoader);
                }
            } catch (Throwable tr) {
                hookFail("Hot reload", tr);
            }
        }
        // 旧句柄中未被本次安装登记的 hook 才取消(同 id 的已被原子替换)
        param.getOldHookHandles().forEach(h -> {
            if (!hookedIds.contains(h.getId())) {
                h.unhook();
            }
        });
    }

    // ---------------- system_server:GreezeManagerService ----------------

    private void hookGreezeManagerService(ClassLoader classLoader) {
        var GreezeManagerServiceClass = Utils.findClass(classLoader,
                "com.miui.server.greeze.GreezeManagerService");
        if (GreezeManagerServiceClass == null) {
            hookFail("GreezeManagerService class", new ClassNotFoundException("class not found"));
            return;
        }

        // 0) 构造器完成后关闭 GMS 限制总开关(字段 mGmsLimitEnabled 仍在,HyperOS 4 确认)
        for (Constructor<?> ctor : GreezeManagerServiceClass.getDeclaredConstructors()) {
            hb(ctor)
                    .intercept(chain -> {
                        try {
                            return chain.proceed();
                        } finally {
                            try {
                                Field f = Utils.findField(GreezeManagerServiceClass,
                                        "mGmsLimitEnabled", "mGmsLimitEnable", "mGmsLimit");
                                if (f != null) {
                                    f.setBoolean(chain.getThisObject(), false);
                                    hookOk("GreezeManagerService.mGmsLimitEnabled=false");
                                }
                            } catch (Throwable e) {
                                hookFail("GreezeManagerService.mGmsLimitEnabled", e);
                            }
                        }
                    });
            deoptimize(ctor);
        }

        // 1) isAllowBroadcast:5 参签名确认存在(弹性回退)
        final Method isAllowBroadcast = Utils.findMethodLoose(GreezeManagerServiceClass, 5, "isAllowBroadcast");
        if (isAllowBroadcast != null) {
            hb(isAllowBroadcast)
                    .intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        // 热路径:签名确认 5 参 (callerUid, callerPkgName, calleeUid, calleePkgName, action)
                        if (args.size() >= 5 && chain.getArg(4) instanceof String action) {
                            if (ACTION_REMOTE_INTENT.equals(action)) {
                                if (GMS_PACKAGE_NAME.equals(chain.getArg(1))) {
                                    return true;
                                }
                                Object callee = chain.getArg(3);
                                if (GMS_PACKAGE_NAME.equals(callee) || GMS_PERSISTENT_PROCESS_NAME.equals(callee)) {
                                    return true;
                                }
                            } else if (CN_DEFER_BROADCAST.contains(action)) {
                                Object callee = chain.getArg(3);
                                if (GMS_PACKAGE_NAME.equals(callee) || GMS_PERSISTENT_PROCESS_NAME.equals(callee)) {
                                    return true;
                                }
                            }
                            return chain.proceed();
                        }
                        // 弹性回退:签名变化时按参数内容判断
                        boolean callerGms = hasStringArg(args, GMS_PACKAGE_NAME);
                        boolean isRemote = hasStringArg(args, ACTION_REMOTE_INTENT);
                        if (callerGms && isRemote) {
                            return true;
                        }
                        if (hasAnyDeferAction(args) || isRemote) {
                            boolean calleeGms = hasStringArg(args, GMS_PACKAGE_NAME)
                                    || hasStringArg(args, GMS_PERSISTENT_PROCESS_NAME);
                            if (calleeGms) {
                                return true;
                            }
                        }
                        return chain.proceed();
                    });
            deoptimize(isAllowBroadcast);
            hookOk("GreezeManagerService." + isAllowBroadcast.getName());
        } else {
            hookFail("GreezeManagerService.isAllowBroadcast", new NoSuchMethodException("isAllowBroadcast"));
        }

        // 2) deferBroadcastForMiui(String):重连广播不延迟(签名确认存在)
        final Method deferBroadcastForMiui = Utils.findMethodLoose(GreezeManagerServiceClass, 1, "deferBroadcastForMiui");
        if (deferBroadcastForMiui != null) {
            hb(deferBroadcastForMiui)
                    .intercept(chain -> {
                        if (hasAnyDeferAction(chain.getArgs())) {
                            return false;
                        }
                        return chain.proceed();
                    });
            deoptimize(deferBroadcastForMiui);
            hookOk("GreezeManagerService." + deferBroadcastForMiui.getName());
        } else {
            hookFail("GreezeManagerService.deferBroadcastForMiui", new NoSuchMethodException("deferBroadcastForMiui"));
        }

        // 3) updateGmsNetStatus(boolean):HyperOS 4 的 GMS 限制入口,强制不限制
        final Method updateGmsNetStatus = Utils.findMethodLoose(GreezeManagerServiceClass, 1, "updateGmsNetStatus");
        if (updateGmsNetStatus != null) {
            hb(updateGmsNetStatus)
                    .intercept(chain -> {
                        var args = chain.getArgs().toArray();
                        forceBooleanArgsFalse(args);
                        return chain.proceed(args);
                    });
            deoptimize(updateGmsNetStatus);
            hookOk("GreezeManagerService." + updateGmsNetStatus.getName());
        } else {
            hookFail("GreezeManagerService.updateGmsNetStatus", new NoSuchMethodException("updateGmsNetStatus"));
        }

        // 4) checkImmobulusModeRestrict(String,String):LaunchMode 下目标包不在
        //    mImmobulusModeWhiteList 时广播不解冻(默认仅含 com.xiaomi.metoknlp),
        //    导致冻结中的目标应用对 FCM 广播无响应 → FCM 广播一律允许解冻
        final Method checkImmobulusModeRestrict = Utils.findMethodLoose(GreezeManagerServiceClass, 2, "checkImmobulusModeRestrict");
        if (checkImmobulusModeRestrict != null) {
            hb(checkImmobulusModeRestrict)
                    .intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        // 热路径:签名确认 (String targetPkgName, String action)
                        if (args.size() == 2) {
                            if (chain.getArg(1) instanceof String action && isFcmAction(action)) {
                                return false;
                            }
                            return chain.proceed();
                        }
                        // 弹性回退:任意参数中含 FCM action
                        for (Object a : args) {
                            if (a instanceof String s && isFcmAction(s)) {
                                return false;
                            }
                        }
                        return chain.proceed();
                    });
            deoptimize(checkImmobulusModeRestrict);
            hookOk("GreezeManagerService." + checkImmobulusModeRestrict.getName());
        } else {
            hookFail("GreezeManagerService.checkImmobulusModeRestrict",
                    new NoSuchMethodException("checkImmobulusModeRestrict"));
        }
    }

    // ---------------- system_server:AurogonImmobulusMode ----------------

    private void hookAurogonImmobulusMode(ClassLoader classLoader) {
        var AurogonImmobulusModeClass = Utils.findClass(classLoader,
                "com.miui.server.greeze.AurogonImmobulusMode");
        if (AurogonImmobulusModeClass == null) {
            hookFail("AurogonImmobulusMode class", new ClassNotFoundException("class not found"));
            return;
        }

        // 把 GMS 加回 Aurogon 白名单(mAllowList)并同步 native 层;
        // 同时加入 mNoRestrictAppSet(QuickFreeze 执行时的唯一豁免集合)
        final Method transferLocalAllowList = Utils.findMethodLoose(AurogonImmobulusModeClass, 1, "TransfermLocalAllowList");
        // 预解析字段,回调内不再反射查找
        final Field mAllowListField = Utils.findField(AurogonImmobulusModeClass, "mAllowList");
        final Field mNoRestrictAppSetField = Utils.findField(AurogonImmobulusModeClass, "mNoRestrictAppSet");

        XposedInterface.Hooker ensureGmsAllowed = chain -> {
            try {
                Object result = chain.proceed();
                addGmsToAurogonExemption(chain.getThisObject(), mAllowListField, mNoRestrictAppSetField, transferLocalAllowList);
                return result;
            } catch (Throwable t) {
                throw t;
            }
        };

        // 构造器完成后与云端白名单更新后都补加 GMS
        for (Constructor<?> ctor : AurogonImmobulusModeClass.getDeclaredConstructors()) {
            hb(ctor)
                    .intercept(ensureGmsAllowed);
            deoptimize(ctor);
        }
        Method updateCloudMethod = Utils.findMethodByName(AurogonImmobulusModeClass, "updateCloudAllowList");
        if (updateCloudMethod != null) {
            hb(updateCloudMethod)
                    .intercept(ensureGmsAllowed);
            deoptimize(updateCloudMethod);
            hookOk("AurogonImmobulusMode.updateCloudAllowList");
        }
        // Settings(MILLET_NO_RESTRICT_APP)变化时会清空重建 mNoRestrictAppSet,补加钩子
        Method getNoRestrictMethod = Utils.findMethodByName(AurogonImmobulusModeClass, "getNoRestrictApps");
        if (getNoRestrictMethod != null) {
            hb(getNoRestrictMethod)
                    .intercept(chain -> {
                        try {
                            Object result = chain.proceed();
                            addGmsToAurogonExemption(chain.getThisObject(), mAllowListField, mNoRestrictAppSetField, transferLocalAllowList);
                            return result;
                        } catch (Throwable t) {
                            throw t;
                        }
                    });
            deoptimize(getNoRestrictMethod);
            hookOk("AurogonImmobulusMode.getNoRestrictApps");
        }
        // removeAurogonAllowList 的唯一调用源(lambda$updateGmsNetStatus$3)已被
        // updateGmsNetStatus 强制 false 封堵,不再需要拦截(已移除)。
    }

    /** 把 GMS 补加进 Aurogon 白名单(mAllowList)与 QuickFreeze 豁免集合(mNoRestrictAppSet)。 */
    private void addGmsToAurogonExemption(Object target, Field mAllowListField, Field mNoRestrictAppSetField,
                                          Method transferLocalAllowList) {
        try {
            if (mAllowListField != null && mAllowListField.get(target) instanceof List<?> allowList
                    && (allowList.isEmpty() || allowList.get(0) instanceof String)) {
                boolean added = false;
                if (!allowList.contains(GMS_PACKAGE_NAME)) {
                    ((List<String>) allowList).add(GMS_PACKAGE_NAME);
                    added = true;
                    hookOk("AurogonImmobulusMode.mAllowList + GMS");
                }
                // 无论是否新添加都尝试同步 native(启动早期可能因 PM 未初始化失败,
                // 后续 updateCloudAllowList/getNoRestrictApps 时机重试即可自愈)
                if (transferLocalAllowList != null) {
                    getInvoker(transferLocalAllowList).invoke(target, allowList);
                    if (added) {
                        hookOk("AurogonImmobulusMode.TransfermLocalAllowList synced");
                    }
                }
            }
        } catch (Throwable e) {
            hookFail("AurogonImmobulusMode.mAllowList", e);
        }
        try {
            if (mNoRestrictAppSetField != null && mNoRestrictAppSetField.get(target) instanceof Set<?> noRestrict
                    && (noRestrict.isEmpty() || noRestrict.iterator().next() instanceof String)) {
                if (!noRestrict.contains(GMS_PACKAGE_NAME)) {
                    ((Set<String>) noRestrict).add(GMS_PACKAGE_NAME);
                    hookOk("AurogonImmobulusMode.mNoRestrictAppSet + GMS");
                }
            }
        } catch (Throwable e) {
            hookFail("AurogonImmobulusMode.mNoRestrictAppSet", e);
        }
    }

    // ---------------- system_server:DomesticPolicyManager ----------------

    private void hookDomesticPolicyManager(ClassLoader classLoader) {
        var DomesticPolicyManagerClass = Utils.findClass(classLoader,
                "com.miui.server.greeze.DomesticPolicyManager");
        if (DomesticPolicyManagerClass == null) {
            hookFail("DomesticPolicyManager class", new ClassNotFoundException("class not found"));
            return;
        }
        final Method deferBroadcast = Utils.findMethodLoose(DomesticPolicyManagerClass, 1, "deferBroadcast");
        if (deferBroadcast != null) {
            hb(deferBroadcast)
                    .intercept(chain -> false);
            deoptimize(deferBroadcast);
            hookOk("DomesticPolicyManager." + deferBroadcast.getName());
        } else {
            hookFail("DomesticPolicyManager.deferBroadcast", new NoSuchMethodException("deferBroadcast"));
        }
    }

    // ---------------- system_server:ListAppsManager ----------------

    private void hookListAppsManager(ClassLoader classLoader) {
        var ListAppsManagerClass = Utils.findClass(classLoader,
                "com.miui.server.greeze.power.ListAppsManager");
        if (ListAppsManagerClass == null) {
            hookFail("ListAppsManager class", new ClassNotFoundException("class not found"));
            return;
        }
        // HyperOS 4:字段全部 static(SYSTEM_BLACK_LIST / USE_DATA_WHITE_LIST)
        final Field systemBlackListField = Utils.findField(ListAppsManagerClass, "SYSTEM_BLACK_LIST", "mSystemBlackList");
        final Field useDataWhiteListField = Utils.findField(ListAppsManagerClass,
                "USE_DATA_WHITE_LIST", "mUseDataWhiteList");
        for (Constructor<?> ctor : ListAppsManagerClass.getDeclaredConstructors()) {
            hb(ctor)
                    .intercept(chain -> {
                        try {
                            return chain.proceed();
                        } finally {
                            try {
                                if (systemBlackListField != null
                                        && systemBlackListField.get(null) instanceof List<?> blackList
                                        && blackList.stream().allMatch(x -> x instanceof String)) {
                                    ((List<String>) blackList).remove(GMS_PACKAGE_NAME);
                                    hookOk("ListAppsManager.SYSTEM_BLACK_LIST - GMS");
                                }
                            } catch (Throwable e) {
                                hookFail("ListAppsManager.SYSTEM_BLACK_LIST", e);
                            }
                            try {
                                if (useDataWhiteListField != null
                                        && useDataWhiteListField.get(null) instanceof Set<?> whiteSet
                                        && whiteSet.stream().allMatch(x -> x instanceof String)) {
                                    ((Set<String>) whiteSet).add(GMS_PACKAGE_NAME);
                                    hookOk("ListAppsManager.USE_DATA_WHITE_LIST + GMS");
                                }
                            } catch (Throwable e) {
                                hookFail("ListAppsManager.USE_DATA_WHITE_LIST", e);
                            }
                        }
                    });
            deoptimize(ctor);
        }
        hookOk("ListAppsManager constructors x" + ListAppsManagerClass.getDeclaredConstructors().length);
        // isInWhiteList(String):对 GMS 直接放行(签名确认存在)
        final Method isInWhiteList = Utils.findMethodLoose(ListAppsManagerClass, 1, "isInWhiteList");
        if (isInWhiteList != null) {
            hb(isInWhiteList)
                    .intercept(chain -> {
                        if (GMS_PACKAGE_NAME.equals(chain.getArg(0))) {
                            return true;
                        }
                        return chain.proceed();
                    });
            deoptimize(isInWhiteList);
            hookOk("ListAppsManager." + isInWhiteList.getName());
        }
    }

    // ---------------- system_server:AwareResourceControl ----------------

    private void hookAwareResourceControl(ClassLoader classLoader) {
        var AwareResourceControlClass = Utils.findClass(classLoader,
                "com.miui.server.greeze.power.AwareResourceControl");
        if (AwareResourceControlClass == null) {
            hookFail("AwareResourceControl class", new ClassNotFoundException("class not found"));
            return;
        }
        // HyperOS 4:mNoNetworkBlackUids 仍在且初始包含 GMS
        final Field noNetworkField = Utils.findField(AwareResourceControlClass,
                "mNoNetworkBlackUids", "mNoNetworkBlackList");
        for (Constructor<?> ctor : AwareResourceControlClass.getDeclaredConstructors()) {
            hb(ctor)
                    .intercept(chain -> {
                        try {
                            return chain.proceed();
                        } finally {
                            try {
                                if (noNetworkField != null
                                        && noNetworkField.get(chain.getThisObject()) instanceof List<?> blackList
                                        && blackList.stream().allMatch(x -> x instanceof String)) {
                                    ((List<String>) blackList).remove(GMS_PACKAGE_NAME);
                                    hookOk("AwareResourceControl.mNoNetworkBlackUids - GMS");
                                }
                            } catch (Throwable e) {
                                hookFail("AwareResourceControl.mNoNetworkBlackUids", e);
                            }
                        }
                    });
            deoptimize(ctor);
        }
        hookOk("AwareResourceControl constructors x" + AwareResourceControlClass.getDeclaredConstructors().length);
    }

    // ---------------- system_server:自启动检查(多代回退链) ----------------

    private void hookAutoStartChecks(ClassLoader classLoader) {
        // 预解析 BroadcastRecord 字段,避免热路径每次反射查找
        var BroadcastRecordClass = Utils.findClass(classLoader, "com.android.server.am.BroadcastRecord");
        final Field brIntentField = BroadcastRecordClass == null ? null : Utils.findField(BroadcastRecordClass, "intent");
        final Field brCallerField = BroadcastRecordClass == null ? null
                : Utils.findField(BroadcastRecordClass, "callerPackage", "callerPkg", "callingPackage");

        String[] queueCandidates = {
                "com.android.server.am.BroadcastQueueModernStubImpl", // HyperOS 1.x~4.x(已确认存在)
                "com.android.server.am.BroadcastQueueImpl",           // MIUI 13
                "com.android.server.am.BroadcastQueueInjector",       // MIUI 12
                "com.android.server.am.BroadcastQueue",               // AOSP 17 新队列
                "com.android.server.am.BroadcastController",
        };
        Class<?> checkedClass = null;
        for (String name : queueCandidates) {
            Class<?> c = Utils.findClass(classLoader, name);
            if (c == null) continue;
            Method m = Utils.findMethodByName(c, "checkApplicationAutoStart");
            if (m == null) continue;
            checkedClass = c;
            hb(m)
                    .intercept(chain -> {
                        if (hasGmsC2dmRecordArg(chain.getArgs(), brIntentField, brCallerField)) {
                            return true;
                        }
                        return chain.proceed();
                    });
            deoptimize(m);
            hookOk(c.getName() + ".checkApplicationAutoStart");
            // 注意:不再 hook checkReceiverIfRestricted —— 直接返回 false 会跳过
            // isRestrictReceiver 内部的 thawUidAsync 解冻逻辑,导致冻结中的目标应用
            // 对广播无响应。改由 isAllowBroadcast + checkImmobulusModeRestrict 两个
            // hook 让系统内部完整走"允许广播 → 解冻目标 → 投递"路径。
            break;
        }
        if (checkedClass == null) {
            hookFail("checkApplicationAutoStart", new ClassNotFoundException("no candidate queue class found"));
        }

        var AutoStartClass = Utils.findClass(classLoader, "com.android.server.am.AutoStartManagerServiceStubImpl");
        if (AutoStartClass != null) {
            final Method m = Utils.findMethodLoose(AutoStartClass, 3, "isAllowStartService");
            if (m != null) {
                hb(m)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            // 热路径:签名确认 (Context, Intent, int[, int])
                            if (args.size() >= 2 && isFcmIntent(chain.getArg(1))) {
                                return true;
                            }
                            if (hasFcmIntentArg(args)) {
                                return true;
                            }
                            return chain.proceed();
                        });
                deoptimize(m);
                hookOk("AutoStartManagerServiceStubImpl." + m.getName());
            }
        }

        // SmartPowerService.shouldInterceptBroadcast 与
        // SmartPowerPolicyManager.shouldInterceptService 在 HyperOS 4 已不存在
        // (反编译确认),相关 hook 已移除。
    }

    private void hookProcessPolicy(ClassLoader classLoader) {
        var ProcessPolicyClass = Utils.findClass(classLoader, "com.android.server.am.ProcessPolicy");
        if (ProcessPolicyClass == null) {
            hookFail("ProcessPolicy class", new ClassNotFoundException("removed in Android 17 upstream"));
            return;
        }
        final Method getWhiteListMethod = Utils.findMethodLoose(ProcessPolicyClass, 1, "getWhiteList");
        if (getWhiteListMethod != null) {
            hb(getWhiteListMethod)
                    .intercept(chain -> {
                        var result = chain.proceed();
                        List<Object> args = chain.getArgs();
                        int flags = 0;
                        // 热路径:签名确认 (int flags)
                        if (!args.isEmpty() && chain.getArg(0) instanceof Integer i) {
                            flags = i;
                        } else {
                            for (Object a : args) {
                                if (a instanceof Integer i2) {
                                    flags = i2;
                                    break;
                                }
                            }
                        }
                        if ((flags & 1) != 0 && result instanceof List<?>) {
                            var whiteList = (List<String>) result;
                            if (!whiteList.contains(GMS_PACKAGE_NAME)) whiteList.add(GMS_PACKAGE_NAME);
                            if (!whiteList.contains(GMS_PERSISTENT_PROCESS_NAME)) whiteList.add(GMS_PERSISTENT_PROCESS_NAME);
                        }
                        return result;
                    });
            deoptimize(getWhiteListMethod);
            hookOk("ProcessPolicy." + getWhiteListMethod.getName());
        }
    }

    // ---------------- system_server:AMS 广播 ----------------

    private static PowerExemptionManager powerExemptionManager = null;

    @RequiresApi(Build.VERSION_CODES.S)
    private static PowerExemptionManager getPowerExemptionManager(Context context) {
        if (powerExemptionManager == null) {
            powerExemptionManager = new PowerExemptionManager(context);
        }
        return powerExemptionManager;
    }

    private void hookActivityManagerService(ClassLoader classLoader) throws NoSuchFieldException {
        var ActivityManagerServiceClass = Utils.findClass(classLoader,
                "com.android.server.am.ActivityManagerService");
        if (ActivityManagerServiceClass == null) {
            hookFail("ActivityManagerService class", new ClassNotFoundException("class not found"));
            return;
        }
        var mContextField = ActivityManagerServiceClass.getDeclaredField("mContext");
        mContextField.setAccessible(true);
        var IApplicationThreadClass = Utils.findClass(classLoader, "android.app.IApplicationThread");
        var ProcessRecordClass = Utils.findClass(classLoader, "com.android.server.am.ProcessRecord");
        if (ProcessRecordClass == null) {
            hookFail("ProcessRecord class", new ClassNotFoundException("class not found"));
            return;
        }
        var infoField = ProcessRecordClass.getDeclaredField("info");
        infoField.setAccessible(true);

        Method broadcastMethod = Utils.findMethodWithMaxParams(ActivityManagerServiceClass,
                "broadcastIntentWithFeature", "broadcastIntent");
        if (broadcastMethod == null) {
            hookFail("AMS broadcastIntentWithFeature/broadcastIntent",
                    new NoSuchMethodException("broadcastIntentWithFeature"));
            return;
        }
        int intentArgIndex = Utils.findIntentArgIndex(broadcastMethod);
        if (intentArgIndex < 0) {
            hookFail("AMS broadcast intent arg", new NoSuchMethodException("no Intent param"));
            return;
        }

        Method getRecordMethod = null;
        if (IApplicationThreadClass != null) {
            getRecordMethod = Utils.findMethodWithFirstParam(ActivityManagerServiceClass,
                    IApplicationThreadClass, "getRecordForAppLOSP", "getRecordForAppLocked");
        }
        if (getRecordMethod == null) {
            getRecordMethod = Utils.findMethodByName(ActivityManagerServiceClass,
                    "getRecordForAppLOSP", "getRecordForAppLocked");
        }
        final Method getRecord = getRecordMethod;

        hb(broadcastMethod)
                .intercept(chain -> {
                    if (chain.getArg(intentArgIndex) instanceof Intent intent) {
                        if (ACTION_REMOTE_INTENT.equals(intent.getAction()) && getRecord != null) {
                            boolean fromGms = false;
                            try {
                                Object app = getInvoker(getRecord).invoke(chain.getThisObject(), chain.getArg(0));
                                if (app != null && infoField.get(app) instanceof ApplicationInfo info
                                        && GMS_PACKAGE_NAME.equals(info.packageName)) {
                                    fromGms = true;
                                }
                            } catch (Throwable ignored) {
                            }
                            if (fromGms) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                        && intent.getPackage() instanceof String packageName
                                        && mContextField.get(chain.getThisObject()) instanceof Context mContext) {
                                    try {
                                        getPowerExemptionManager(mContext).addToTemporaryAllowList(
                                                packageName,
                                                102 /* PowerExemptionManager.REASON_PUSH_MESSAGING_OVER_QUOTA */,
                                                "GOOGLE_C2DM", 2000);
                                    } catch (Throwable e) {
                                        hookFail("PowerExemptionManager", e);
                                    }
                                }
                                if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
                                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                }
                            }
                        }
                    }
                    return chain.proceed();
                });
        deoptimize(broadcastMethod);
        hookOk("ActivityManagerService." + broadcastMethod.getName()
                + " (" + broadcastMethod.getParameterCount() + " params, intent@" + intentArgIndex + ")");
    }

    // ---------------- powerkeeper:待机断网豁免 ----------------

    /** GMS 的 uid(懒解析并缓存,供待机断网豁免判断使用)。 */
    private static volatile int sGmsUid = -1;

    private static int resolveGmsUid(Object holder) {
        if (sGmsUid > 0) return sGmsUid;
        Object ctx = Utils.getFieldOrNull(holder, "mContext", "f232c");
        if (ctx instanceof Context c) {
            try {
                sGmsUid = c.getPackageManager().getApplicationInfo(GMS_PACKAGE_NAME, 0).uid;
            } catch (Throwable ignored) {
            }
        }
        return sGmsUid;
    }

    /**
     * 待机断网豁免 —— 深夜深度待机时 FCM 长时间断连(err:3 连接被拒、断开数小时)的根因链:
     * <pre>
     * 熄屏待机 → AppStandbyController.updateRuleForUidLocked(uid, rule=1)
     *   → setUidState(uid, allow=false)
     *     → DeviceIdlePolicyHelper.r(uid, disallow=true)
     *       → DeviceIdleController.setAppInactive(pkg, true) → 应用被断网
     * </pre>
     * 让 GMS 在规则层与下发层都豁免,保证待机期间仍能维持推送连接。
     */
    private void hookStandbyNetworkExemption(ClassLoader classLoader) {
        // 1) 规则层:rule == 1 表示待机断网(setUidState(uid, rule != 1))
        var StandbyClass = Utils.findClass(classLoader,
                "com.miui.powerkeeper.controller.AppStandbyController");
        if (StandbyClass != null) {
            final Method updateRule = Utils.findMethodLoose(StandbyClass, 2, "updateRuleForUidLocked");
            if (updateRule != null) {
                hb(updateRule)
                        .intercept(chain -> {
                            int uid = chain.getArg(0) instanceof Integer i ? i : -1;
                            if (uid > 0 && uid == resolveGmsUid(chain.getThisObject())
                                    && chain.getArg(1) instanceof Integer rule && rule == 1) {
                                hookOk("StandbyRule: GMS 待机断网规则被豁免 (uid=" + uid + ")");
                                return chain.proceed(new Object[]{uid, 0});
                            }
                            return chain.proceed();
                        });
                deoptimize(updateRule);
                hookOk("AppStandbyController." + updateRule.getName());
            } else {
                hookFail("AppStandbyController.updateRuleForUidLocked",
                        new NoSuchMethodException("updateRuleForUidLocked"));
            }
        }

        // 2) 下发层:DeviceIdlePolicyHelper.r(uid, disallow)
        var HelperClass = Utils.findClass(classLoader, "com.miui.powerkeeper.DeviceIdlePolicyHelper");
        if (HelperClass != null) {
            final Method disallowMethod = Utils.findMethod(HelperClass, 2, "r");
            if (disallowMethod != null) {
                hb(disallowMethod)
                        .intercept(chain -> {
                            int uid = chain.getArg(0) instanceof Integer i ? i : -1;
                            if (uid > 0 && chain.getArg(1) instanceof Boolean disallow && disallow
                                    && uid == resolveGmsUid(chain.getThisObject())) {
                                hookOk("StandbyNet: GMS 断网下发被豁免 (uid=" + uid + ")");
                                return chain.proceed(new Object[]{uid, Boolean.FALSE});
                            }
                            return chain.proceed();
                        });
                deoptimize(disallowMethod);
                hookOk("DeviceIdlePolicyHelper." + disallowMethod.getName());
            }
        }
    }

    // ---------------- powerkeeper:GlobalFeatureConfigureHelper ----------------

    private void hookGlobalFeatureConfigureHelper(ClassLoader classLoader) {
        var GlobalFeatureConfigureHelperClass = Utils.findClass(classLoader,
                "com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper");
        if (GlobalFeatureConfigureHelperClass == null) {
            hookFail("GlobalFeatureConfigureHelper class", new ClassNotFoundException("class not found"));
            return;
        }
        Method getDozeWhiteListAppsMethod = Utils.findMethodByName(GlobalFeatureConfigureHelperClass,
                "getDozeWhiteListApps");
        if (getDozeWhiteListAppsMethod != null) {
            hb(getDozeWhiteListAppsMethod)
                    .intercept(chain -> {
                        var result = chain.proceed();
                        if (result instanceof List<?>) {
                            var whiteList = (List<String>) result;
                            if (!whiteList.contains(GMS_PACKAGE_NAME)) {
                                whiteList.add(GMS_PACKAGE_NAME);
                            }
                        }
                        return result;
                    });
            deoptimize(getDozeWhiteListAppsMethod);
            hookOk("GlobalFeatureConfigureHelper." + getDozeWhiteListAppsMethod.getName());
        } else {
            hookFail("GlobalFeatureConfigureHelper.getDozeWhiteListApps",
                    new NoSuchMethodException("getDozeWhiteListApps"));
        }
    }
}
