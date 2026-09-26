package com.ryjs.forgeserviceproviderinterfacefix;

import com.ryjs.forgeserviceproviderinterfacefix.early.CoexProbeHelper;
import com.ryjs.forgeserviceproviderinterfacefix.early.EarlyHook;
import net.minecraftforge.fml.common.Mod;

@Mod(Forgeserviceproviderinterfacefix.MODID)
public class Forgeserviceproviderinterfacefix {
    public static final String MODID = "forgeserviceproviderinterfacefix";
    public Forgeserviceproviderinterfacefix() {
        CoexProbeHelper.touch();
        EarlyHook.report("[mod] ✓ 已作为 mod 被正常加载：modid=" + MODID);
    }
}
