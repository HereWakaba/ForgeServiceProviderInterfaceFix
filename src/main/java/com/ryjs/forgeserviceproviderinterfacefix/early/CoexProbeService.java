package com.ryjs.forgeserviceproviderinterfacefix.early;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;

import java.util.List;
import java.util.Set;


public final class CoexProbeService implements ITransformationService {

    public static final String NAME = "coexprobe";

    public CoexProbeService() {
        EarlyHook.report("[probe] service 构造（T4 ServiceLoader 实例化）");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
        EarlyHook.report("[probe] onLoad，同期服务=" + otherServices);
    }

    @Override
    public void initialize(IEnvironment environment) {
        EarlyHook.report("[probe] initialize");
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        EarlyHook.report("[probe] beginScanning");
        return List.of();
    }

    @Override
    public List<Resource> completeScan(IModuleLayerManager layerManager) {
        EarlyHook.report("[probe] completeScan  " + bootGateStats());
        return List.of();
    }

    @Override
    public List<ITransformer> transformers() {
        EarlyHook.report("[probe] transformers() 被调用（T9，在 purge 之前）  " + bootGateStats());
        return List.of();
    }

    private static String bootGateStats() {
        try {
            Class<?> bootCopy = Class.forName("com.ryjs.coexboot.CoexGate",
                    false, ClassLoader.getPlatformClassLoader());
            return String.valueOf(bootCopy.getMethod("stats").invoke(null));
        } catch (Throwable t) {
            return "<读不到 boot 副本: " + t + ">";
        }
    }
}
