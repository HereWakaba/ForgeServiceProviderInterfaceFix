package com.ryjs.coexboot;

import java.io.File;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;


public final class CoexGate {

    public static final String MARKER = "Coexist-As-Mod";

    public static int checked;
    public static int allowed;

    public static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    public static volatile Object UNSAFE;

    public static volatile ModuleLayer SERVICE_LAYER;

    private CoexGate() {
    }

    public static boolean accept(List<Path> excluded, Path p) {
        try {
            String fileName = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!fileName.endsWith(".jar")) return false;
            if (excluded == null || !excluded.contains(p)) return true;

            checked++;
            // 注意：不能用嵌套 record / 内部类当返回值 —— 运行时只把 CoexGate 这一个 .class
            // 注入 bootstrap，CoexGate$Xxx.class 开不出来会直接 NoClassDefFoundError。
            String[] verdict = judge(p.toFile());
            boolean allow = "1".equals(verdict[0]);
            if (allow) {
                allowed++;
                rememberModuleNames(p);
                System.out.println("[CoexEarly] 放行共存 jar（原被 SPI 抢占排除）: " + fileName
                        + "  ← 依据：" + verdict[1] + "  待摘=" + PENDING);
                return true;
            }
            System.out.println("[CoexEarly] 维持排除 " + fileName + "  ← 依据：" + verdict[1]);
            return false;
        } catch (Throwable t) {
            System.out.println("[CoexEarly] accept 异常，保守跳过: " + t);
            return false;
        }
    }

    /**
     * 唯一判据：{@code MANIFEST.MF} 主属性里显式写了 {@code Coexist-As-Mod: true}。
     * 不再拿"根目录有 mods.toml"做兜底 —— 那等于对所有 SPI jar 无差别放行。
     * mods.toml 存在与否仅作为诊断信息一并输出，不参与判定。
     *
     * @return {@code ["1"|"0", 可读的判定依据]}
     */
    private static String[] judge(File file) {
        try (JarFile jar = new JarFile(file)) {
            boolean hasModsToml = jar.getEntry("META-INF/mods.toml") != null
                    || jar.getEntry("META-INF/MODS.TOML") != null;
            String hint = "，mods.toml " + (hasModsToml ? "存在" : "也不存在");

            Manifest mf = jar.getManifest();
            if (mf == null) {
                return new String[]{"0", "无 MANIFEST.MF" + hint};
            }
            Attributes attrs = mf.getMainAttributes();
            String v = attrs == null ? null : attrs.getValue(MARKER);
            if (v == null) {
                return new String[]{"0", "缺属性 " + MARKER + hint};
            }
            if (!"true".equalsIgnoreCase(v.trim())) {
                return new String[]{"0", MARKER + "=" + v + "（不是 true）" + hint};
            }
            return new String[]{"1", "命中 " + MARKER + ": true" + hint};
        } catch (Throwable t) {
            return new String[]{"0", "读 jar 失败：" + t.getClass().getSimpleName() + ": " + t.getMessage()};
        }
    }

    private static void rememberModuleNames(Path jar) {
        try {
            java.lang.instrument.Instrumentation inst = AgentBridge.instrumentation;
            if (inst == null) return;
            String want = jar.getFileName().toString();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                try {
                    String src = c.getProtectionDomain().getCodeSource().getLocation().getPath();
                    if (src == null) continue;
                    src = URLDecoder.decode(src, "UTF-8");
                    int bang = src.indexOf("!/");
                    if (bang > 0) src = src.substring(0, bang);
                    int jarEnd = src.lastIndexOf(".jar");
                    if (jarEnd < 0) continue;
                    String filePart = src.substring(src.lastIndexOf('/') + 1, jarEnd + 4);
                    if (filePart.equalsIgnoreCase(want)) {
                        Module m = c.getModule();
                        if (m != null && m.getName() != null && !m.getName().isBlank()) PENDING.add(m.getName());
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            System.out.println("[CoexEarly] 反查模块名失败: " + t);
        }
    }

    public static void purge(Object completedLayers, Object layer) {
        if (PENDING.isEmpty()) return;
        if (!"GAME".equals(String.valueOf(layer))) return;
        try {
            ModuleLayer service = SERVICE_LAYER;
            if (service == null) {
                System.out.println("[CoexEarly] SERVICE_LAYER 未注入，放弃摘除");
                return;
            }
            java.lang.module.Configuration cfg = service.configuration();

            java.lang.reflect.Field modulesField = cfg.getClass().getDeclaredField("modules");
            Object cur = readField(cfg, modulesField);
            if (!(cur instanceof Set)) return;

            Set<Object> kept = new LinkedHashSet<>();
            int removed = 0;
            for (Object rm : (Set<?>) cur) {
                String name = (String) rm.getClass().getMethod("name").invoke(rm);
                if (name != null && PENDING.contains(name)) {
                    removed++;
                    continue;
                }
                kept.add(rm);
            }
            if (removed == 0) {
                System.out.println("[CoexEarly] SERVICE Configuration 里没命中 " + PENDING);
                return;
            }
            writeField(cfg, modulesField, kept);
            System.out.println("[CoexEarly] ✓ 已从 SERVICE 层摘掉 " + removed + " 个模块：" + PENDING
                    + "（nameToModule 保留，不影响其后续类加载）");
        } catch (Throwable t) {
            System.out.println("[CoexEarly] purge 异常（不干预启动）：" + causeChain(t));
        }
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        while (c != null) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append("  <-  ");
            c = (c == c.getCause()) ? null : c.getCause();
        }
        return sb.toString();
    }

    public static String stats() {
        return "checked=" + checked + " allowed=" + allowed + " pending=" + PENDING;
    }

    private static long offsetOf(java.lang.reflect.Field f) throws Exception {
        Object u = UNSAFE;
        if (u == null) throw new IllegalStateException("UNSAFE 未注入");
        return (Long) u.getClass().getMethod("objectFieldOffset", java.lang.reflect.Field.class).invoke(u, f);
    }

    private static Object readField(Object target, java.lang.reflect.Field f) throws Exception {
        Object u = UNSAFE;
        if (u == null) {
            f.setAccessible(true);
            return f.get(target);
        }
        return u.getClass().getMethod("getObject", Object.class, long.class).invoke(u, target, offsetOf(f));
    }

    private static void writeField(Object target, java.lang.reflect.Field f, Object value) throws Exception {
        Object u = UNSAFE;
        if (u == null) {
            f.setAccessible(true);
            f.set(target, value);
            return;
        }
        u.getClass().getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(u, target, offsetOf(f), value);
    }
}
