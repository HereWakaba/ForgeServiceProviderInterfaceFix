package com.ryjs.forgeserviceproviderinterfacefix.early;

public final class CoexProbeHelper {

    public static volatile String firstLoad;

    private CoexProbeHelper() {
    }

    public static void touch() {
        if (firstLoad == null) {
            firstLoad = "loaded by " + CoexProbeHelper.class.getClassLoader()
                    + " / module=" + CoexProbeHelper.class.getModule().getName();
            EarlyHook.report("[probe] ✓ purge 之后仍能按需加载我们这个模块的新类：" + firstLoad);
        }
    }
}
