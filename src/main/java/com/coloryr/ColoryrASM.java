package com.coloryr;

import com.coloryr.asms.Lwjgl2Asm;
import com.coloryr.asms.Lwjgl3Asm;
import com.coloryr.asms.SocketAsm;

import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ColoryrASM {
    public static NettyClient client;
    public static String uuid;
    public static byte[] uuidBytes;
    public static String key;
    public static boolean run;
    public static boolean init;

    public static boolean isLwjgl3;

    public static List<Long> windowList = new ArrayList<>();

    public static void addWindow(long window) {
        isLwjgl3 = true;
        windowList.add(window);
        System.out.println("add window: " + window);
        if (!ColoryrASM.run) {
            if (ColoryrASM.init) {
                return;
            }
            ColoryrASM.init();
        }
    }

    public static void init() {
        System.out.println("[ColoryrASM] init asm");
        init = true;
        String port = System.getProperty("coloryrasm.mixin.port");
        uuid = System.getProperty("coloryrasm.mixin.uuid");
        key = System.getProperty("coloryrasm.mixin.key");
        if (port == null || uuid == null || key == null || key.isEmpty()) {
            System.out.println("[ColoryrASM] lost port, uuid or key, disabled.");
            return;
        }
        uuidBytes = ColoryrASM.uuid.getBytes(StandardCharsets.UTF_8);
        System.out.println("[ColoryrASM] run in " + port + " " + uuid);
        int port1 = Integer.parseInt(port);
        try {
            client = new NettyClient("127.0.0.1", port1);
            System.out.println("[ColoryrASM] environment initialized.");
            run = true;
        } catch (Exception e) {
            System.out.println("[ColoryrASM] environment init fail.");
            e.printStackTrace();
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[ColoryrASM] start patch class");
        inst.addTransformer(new Lwjgl2Asm());
        inst.addTransformer(new Lwjgl3Asm());
        inst.addTransformer(new SocketAsm());
    }
}