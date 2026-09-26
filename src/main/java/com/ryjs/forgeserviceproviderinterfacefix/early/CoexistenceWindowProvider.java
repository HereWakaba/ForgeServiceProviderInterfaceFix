package com.ryjs.forgeserviceproviderinterfacefix.early;

import net.minecraftforge.fml.loading.ImmediateWindowProvider;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class CoexistenceWindowProvider implements ImmediateWindowProvider {

    private static final String HOOK_NAME = "forgeserviceproviderinterfacefix_earlyhook";

    static {
        System.out.println(HOOK_NAME+" loaded");
        EarlyHook.boot();
    }

    public CoexistenceWindowProvider() {
    }

    @Override
    public String name() {
        return HOOK_NAME;
    }

    @Override
    public Runnable initialize(String[] args) {
        return () -> {
        };
    }

    @Override
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {
    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        return 0L;
    }

    @Override
    public boolean positionWindow(Optional<Object> monitor, IntConsumer widthSetter, IntConsumer heightSetter,
                                  IntConsumer xSetter, IntConsumer ySetter) {
        return false;
    }

    @Override
    public <T> Supplier<T> loadingOverlay(Supplier<?> mc, Supplier<?> ri, Consumer<Optional<Throwable>> ex, boolean fade) {
        return () -> null;
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {
    }

    @Override
    public void periodicTick() {
    }

    @Override
    public String getGLVersion() {
        return "";
    }
}
