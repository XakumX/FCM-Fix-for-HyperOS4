package com.xakumx.os4.fcmfix;

import android.content.Intent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 反射工具集:针对 HyperOS 4 / Android 17 上类名与方法签名不稳定的情况,
 * 提供候选类名查找与弹性方法匹配能力。
 */
public class Utils {

    /** 链式调用辅助:对 target 执行 action 后原样返回 target。 */
    public static <T> T evaluate(T target, Consumer<T> action) {
        action.accept(target);
        return target;
    }

    /** 依次尝试候选类名,返回第一个能加载的类;全部失败返回 null。 */
    public static Class<?> findClass(ClassLoader cl, String... candidates) {
        for (String name : candidates) {
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 按方法名查找(任意参数个数与类型),返回第一个匹配的方法。 */
    public static Method findMethodByName(Class<?> c, String... names) {
        if (c == null) return null;
        for (String name : names) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** 按方法名 + 参数个数查找。 */
    public static Method findMethod(Class<?> c, int paramCount, String... names) {
        if (c == null) return null;
        for (String name : names) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** 在重载中找参数个数最多的版本(用于 broadcastIntentWithFeature 等持续加参的方法)。 */
    public static Method findMethodWithMaxParams(Class<?> c, String... names) {
        Method best = null;
        if (c == null) return null;
        for (String name : names) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    if (best == null || m.getParameterCount() > best.getParameterCount()) {
                        best = m;
                    }
                }
            }
        }
        if (best != null) best.setAccessible(true);
        return best;
    }

    /** 在重载中找第一个参数类型为 {@code paramType} 的版本(用于 getRecordForAppLOSP 的双重重载)。 */
    public static Method findMethodWithFirstParam(Class<?> c, Class<?> paramType, String... names) {
        if (c == null) return null;
        for (String name : names) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() > 0
                        && m.getParameterTypes()[0] == paramType) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** 返回方法参数列表中第一个 Intent 参数的下标;没有则返回 -1。 */
    public static int findIntentArgIndex(Method m) {
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Intent.class) return i;
        }
        return -1;
    }

    /** 沿类层级向上查找字段值,字段名按候选顺序尝试;失败返回 null。 */
    public static Object getField(Object obj, String... names) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 反射获取字段失败时返回 null 的包装。 */
    public static Object getFieldOrNull(Object obj, String... names) {
        try {
            return getField(obj, names);
        } catch (Throwable t) {
            return null;
        }
    }
}
