package com.ryjs.forgeserviceproviderinterfacefix.early;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;

public final class CoexTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS =
            "net.minecraftforge.fml.loading.moddiscovery.ModsFolderLocator";
    private static final String LAYER_HANDLER = "cpw.mods.modlauncher.ModuleLayerHandler";
    private static final String GATE_OWNER = "com/ryjs/coexboot/CoexGate";

    private static final String PRED_DESC = "(Ljava/util/List;Ljava/nio/file/Path;)Z";

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;
        String dotted = className.replace('/', '.');

        try {
            if (TARGET_CLASS.equals(dotted)) {
                EarlyHook.report("★ 拦到 " + dotted + " | 场景=" + scene(classBeingRedefined)
                        + " | " + classfileBuffer.length + "B");
                return patchLocator(classfileBuffer);
            }
            if (LAYER_HANDLER.equals(dotted)) {
                EarlyHook.report("★ 拦到 " + dotted + " | 场景=" + scene(classBeingRedefined)
                        + " | " + classfileBuffer.length + "B");
                return patchLayerHandler(classfileBuffer);
            }
        } catch (Throwable t) {
            EarlyHook.report("处理 " + dotted + " 出错，放弃补丁：" + t);
            for (StackTraceElement e : t.getStackTrace()) EarlyHook.report("     at " + e);
        }
        return null;
    }

    private static String scene(Class<?> beingRedefined) {
        return beingRedefined == null ? "首次加载" : "retransform";
    }

    private byte[] patchLocator(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);

        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if (PRED_DESC.equals(mn.desc) && mn.name.startsWith("lambda$scanCandidates$")) {
                target = mn;
                break;
            }
        }
        if (target == null) {
            EarlyHook.report("  未找到排除谓词 " + PRED_DESC + "，跳过补丁（避免硬猜签名）");
            return null;
        }

        target.instructions.clear();
        target.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        target.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        target.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GATE_OWNER, "accept", PRED_DESC, false));
        target.instructions.add(new InsnNode(Opcodes.IRETURN));
        target.tryCatchBlocks = new ArrayList<>();
        target.localVariables = null;
        target.attrs = new ArrayList<>();
        ClassWriter cw = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        byte[] out = cw.toByteArray();
        EarlyHook.report("  ✓ 已重写 " + target.name + target.desc + " → " + GATE_OWNER + ".accept"
                + "  (" + bytes.length + "B → " + out.length + "B)");
        return out;
    }

    private byte[] patchLayerHandler(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);

        final String fieldName = "completedLayers";
        String fieldDesc = null;
        for (var fn : cn.fields) {
            if (fieldName.equals(fn.name)) fieldDesc = fn.desc;
        }
        if (fieldDesc == null) {
            EarlyHook.report("  未找到字段 " + fieldName + "，不插桩");
            return null;
        }

        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if (!"buildLayer".equals(mn.name)) continue;
            if (mn.desc.startsWith("(Lcpw/mods/modlauncher/api/IModuleLayerManager$Layer;Ljava/util/function/BiFunction;")) {
                target = mn;
                break;
            }
        }
        if (target == null || target.instructions.size() == 0) {
            EarlyHook.report("  未找到 buildLayer(Layer, BiFunction) 或方法体为空，不插桩");
            return null;
        }

        InsnList prefix = new InsnList();
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, fieldName, fieldDesc));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GATE_OWNER, "purge",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        target.instructions.insert(target.instructions.getFirst(), prefix);

        ClassWriter cw = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        byte[] out = cw.toByteArray();
        EarlyHook.report("  ✓ 已在 " + target.name + target.desc + " 入口插入 " + GATE_OWNER + ".purge"
                + "  (" + bytes.length + "B → " + out.length + "B)");
        return out;
    }
}
