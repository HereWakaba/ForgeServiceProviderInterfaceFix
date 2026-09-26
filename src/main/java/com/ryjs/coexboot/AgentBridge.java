package com.ryjs.coexboot;

import java.lang.instrument.Instrumentation;

public final class AgentBridge {

    public static volatile Instrumentation instrumentation;
    public static volatile String agentArgs;
    public static volatile long stampedAt;

    private AgentBridge() {
    }

    public static void agentmain(String args, Instrumentation inst) {
        agentArgs = args;
        instrumentation = inst;
        stampedAt = System.currentTimeMillis();
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }
}
