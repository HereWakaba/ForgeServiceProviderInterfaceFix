package com.ryjs.forgeserviceproviderinterfacefix.early;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EarlyHook {

    private static final String BRIDGE_CLASS = "com.ryjs.coexboot.AgentBridge";
    private static final String GATE_CLASS = "com.ryjs.coexboot.CoexGate";
    private static final AtomicBoolean DONE = new AtomicBoolean(false);
    private static final Object LOG_LOCK = new Object();
    private static volatile Path reportFile;

    private EarlyHook() {
    }

    public static void boot() {
        if (!DONE.compareAndSet(false, true)) return;
        try {
            doBoot();
        } catch (Throwable t) {
            report("!! 早期钩子整体失败（不影响游戏继续启动）：" + t);
            for (StackTraceElement e : t.getStackTrace()) report("     at " + e);
        }
    }

    private static void doBoot() throws Throwable {
        report("══════ ForgeServiceProviderinterfaceFix 早期钩子启动 ══════");
        report("java.version = " + System.getProperty("java.version")
                + " | jvm.args = " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        report("本类 loader = " + EarlyHook.class.getClassLoader());
        report("本 jar     = " + resolveSelfLocation());

        MethodHandles.Lookup full = unconditionalLookup();
        report("[1] UNCONDITIONAL Lookup 已就绪（自检：能 findStatic java.base 私有方法）");

        MethodHandle defineClass1 = full.findStatic(ClassLoader.class, "defineClass1",
                MethodType.methodType(Class.class, ClassLoader.class, String.class, byte[].class,
                        int.class, int.class, java.security.ProtectionDomain.class, String.class));
        Class<?> gateClass = null;
        for (String bootClass : new String[]{BRIDGE_CLASS, GATE_CLASS}) {
            byte[] bytes = readClassBytes(bootClass);
            Class<?> defined = (Class<?>) defineClass1.invoke(null, bootClass, bytes, 0, bytes.length, null, null);
            report("[2] 已定义进 boot：" + defined + " / loader=" + defined.getClassLoader() + " (" + bytes.length + "B)");
            if (GATE_CLASS.equals(bootClass)) gateClass = defined;
        }

        if (gateClass != null) {
            gateClass.getField("UNSAFE").set(null, unsafe());
            gateClass.getField("SERVICE_LAYER").set(null, EarlyHook.class.getModule().getLayer());
            report("[2] Unsafe + SERVICE_LAYER 已注入 CoexGate（layer="
                    + EarlyHook.class.getModule().getLayer() + "）");
        }

        Path jar = emitAgentJar();
        report("[3] agent.jar = " + jar);

        int rc = agentOnAttach(jar.toAbsolutePath().toString() + "=coex-early");
        report("[4] Agent_OnAttach rc = " + rc + " (0 = OK)");
        if (rc != 0) {
            report("    附加失败，钩子到此为止；不注册 transformer，保持原行为");
            return;
        }

        Instrumentation inst = awaitInstrumentation(3000);
        if (inst == null) {
            report("    agentmain 没被执行 / 没拿到 Instrumentation，放弃");
            return;
        }
        report("    Instrumentation = " + inst.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(inst)));

        try {
            inst.addTransformer(new CoexTransformer(), true);
            report("[5] addTransformer(coex, true) 成功");
        } catch (UnsupportedOperationException e) {
            report("[5] 带 canRetransform 被拒：" + e + " → 降级注册普通 transformer");
            try {
                inst.addTransformer(new CoexTransformer());
                report("    降级注册成功（首次加载拦截依然有效）");
            } catch (Throwable t) {
                report("    连普通注册也失败，放弃：" + t);
            }
        } catch (Throwable t) {
            report("[5] 注册失败：" + t);
        }

        try {
            Class<?> handler = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler");
            inst.retransformClasses(handler);
            report("[6] 已提交 retransform：" + handler);
        } catch (Throwable t) {
            report("[6] ModuleLayerHandler 插桩失败（会退回 duplicate module 崩溃）：" + t);
        }
        report("══════ 早期钩子完成 ══════");
    }


    @SuppressWarnings("unchecked")
    private static MethodHandles.Lookup unconditionalLookup() throws Exception {
        Class<?> rfClass = Class.forName("sun.reflect.ReflectionFactory");
        Object rf = rfClass.getMethod("getReflectionFactory").invoke(null);
        Constructor<?> raw = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, Class.class, int.class);
        Constructor<MethodHandles.Lookup> ctor = (Constructor<MethodHandles.Lookup>) rfClass
                .getMethod("newConstructorForSerialization", Class.class, Constructor.class)
                .invoke(rf, MethodHandles.Lookup.class, raw);
        MethodHandles.Lookup l = ctor.newInstance(Object.class, null, -1);
        l.findStatic(ClassLoader.class, "defineClass1", MethodType.methodType(Class.class,
                ClassLoader.class, String.class, byte[].class, int.class, int.class,
                java.security.ProtectionDomain.class, String.class));
        return l;
    }

    private static Object unsafe() throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private static byte[] readClassBytes(String fqcn) throws IOException {
        String res = "/" + fqcn.replace('.', '/') + ".class";
        try (InputStream in = EarlyHook.class.getResourceAsStream(res)) {
            if (in == null) throw new IOException("找不到 " + res + "（我们自己的 jar 里应当有它）");
            return in.readAllBytes();
        }
    }

    private static Path emitAgentJar() throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path jar = dir.resolve("coex-early-agent.jar");
        Files.deleteIfExists(jar);
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Agent-Class", BRIDGE_CLASS);
        mf.getMainAttributes().putValue("Can-Redefine-Classes", "true");
        mf.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        mf.getMainAttributes().putValue("Can-Set-Native-Method-Prefix", "true");
        try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            jo.finish();
        }
        return jar;
    }

    private static int agentOnAttach(String spec) throws Exception {
        Class<?> fnClass = Class.forName("com.sun.jna.Function");
        Class<?> ptrClass = Class.forName("com.sun.jna.Pointer");
        Class<?> ibrClass = Class.forName("com.sun.jna.ptr.IntByReference");
        report("    JNA 可见：" + fnClass + " loader=" + fnClass.getClassLoader());
        Method getFunction = fnClass.getMethod("getFunction", String.class, String.class);
        Method invokeInt = fnClass.getMethod("invokeInt", Object[].class);

        Object vmArr = Array.newInstance(ptrClass, 1);
        Object nRef = ibrClass.getConstructor(int.class).newInstance(1);
        Object nPtr = ibrClass.getMethod("getPointer").invoke(nRef);

        Object getVMs = getFunction.invoke(null, "jvm", "JNI_GetCreatedJavaVMs");
        int rc1 = (int) invokeInt.invoke(getVMs, (Object) new Object[]{vmArr, 1, nPtr});
        report("    JNI_GetCreatedJavaVMs rc=" + rc1 + " nVMs=" + ibrClass.getMethod("getValue").invoke(nRef));
        if (rc1 != 0) return rc1;
        Object vm = Array.get(vmArr, 0);
        if (vm == null) throw new IllegalStateException("JavaVM* 为空");

        Object onAttach = getFunction.invoke(null, "instrument", "Agent_OnAttach");
        return (int) invokeInt.invoke(onAttach, (Object) new Object[]{vm, spec, null});
    }

    private static Instrumentation awaitInstrumentation(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Instrumentation inst = readBridgeInstrumentation();
            if (inst != null) return inst;
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return readBridgeInstrumentation();
    }

    private static Instrumentation readBridgeInstrumentation() {
        try {
            Class<?> bootCopy = Class.forName(BRIDGE_CLASS, true, ClassLoader.getPlatformClassLoader());
            Object v = bootCopy.getField("instrumentation").get(null);
            return v instanceof Instrumentation ? (Instrumentation) v : null;
        } catch (Throwable t) {
            return null;
        }
    }


    private static String resolveSelfLocation() {
        try {
            String raw = EarlyHook.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "<未知: " + t + ">";
        }
    }

    public static void report(String msg) {
        synchronized (LOG_LOCK) {
            System.out.println("[CoexEarly] " + msg);
            try {
                if (reportFile == null) {
                    reportFile = Paths.get(System.getProperty("user.dir", "."), "coex-earlyhook.log").toAbsolutePath();
                }
                Files.write(reportFile, (java.time.LocalDateTime.now() + "  " + msg + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
            }
        }
    }
}
