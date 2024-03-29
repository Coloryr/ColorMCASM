package com.coloryr.colormc;

import com.coloryr.colormc.asms.Lwjgl2Asm;
import com.coloryr.colormc.asms.Lwjgl3Asm;
import com.coloryr.colormc.asms.SocketAsm;

import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;

public class ColorMCASM {
    public static NettyClient client;
    public static String uuid;
    public static byte[] uuidBytes;
    public static boolean run;
    public static boolean init;

    public static void init() {
        System.out.println("ColorMC ASM init");
        init = true;
        String port = System.getProperty("colormc.mixin.port");
        uuid = System.getProperty("colormc.mixin.uuid");
        if (port == null || uuid == null) {
            System.out.println("ColorMC ASM lost port or uuid or jar, disabled.");
            return;
        }
        uuidBytes = ColorMCASM.uuid.getBytes(StandardCharsets.UTF_8);
        System.out.println("ColorMC ASM run in " + port + " " + uuid);
        int port1 = Integer.parseInt(port);
        try {
            client = new NettyClient("127.0.0.1", port1);
            System.out.println("ColorMC ASM environment initialized.");
            run = true;
        } catch (Exception e) {
            System.out.println("ColorMC ASM environment init fail.");
            e.printStackTrace();
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("ColorMC ASM start");
        inst.addTransformer(new Lwjgl2Asm());
        inst.addTransformer(new Lwjgl3Asm());
        inst.addTransformer(new SocketAsm());
    }
}