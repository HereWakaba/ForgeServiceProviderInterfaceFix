open module forgeserviceproviderinterfacefix {
    requires java.instrument;
    requires java.management;
    requires jdk.unsupported;

    requires cpw.mods.modlauncher;
    requires fmlloader;

    requires org.objectweb.asm;
    requires org.objectweb.asm.tree;

    provides cpw.mods.modlauncher.api.ITransformationService with
            com.ryjs.forgeserviceproviderinterfacefix.early.CoexProbeService;
    provides net.minecraftforge.fml.loading.ImmediateWindowProvider with
            com.ryjs.forgeserviceproviderinterfacefix.early.CoexistenceWindowProvider;
}
