package coloryr.colormc;

import coloryr.colormc.asms.Lwjgl2Asm;
import coloryr.colormc.asms.Lwjgl3Asm;

import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;

public class ColorMCASM {
    public static NettyClient client;
    public static String uuid;
    public static byte[] uuidBytes;
    public static boolean run;

    public static void init() {
        String port = System.getProperty("colormc.mixin.port");
        uuid = System.getProperty("colormc.mixin.uuid");
        if (port == null || uuid == null) {
            System.out.println("ColorMC ASM lost port or uuid or jar, disabled.");
            return;
        } else {
            run = true;
        }
        uuidBytes = ColorMCASM.uuid.getBytes(StandardCharsets.UTF_8);
        System.out.println("ColorMC ASM run in " + port + " " + uuid);
        int port1 = Integer.parseInt(port);
        client = new NettyClient("127.0.0.1", port1);

        System.out.println("ColorMC ASM environment initialized.");
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        ColorMCASM.init();
        if (!run) {
            return;
        }

        inst.addTransformer(new Lwjgl2Asm());
        inst.addTransformer(new Lwjgl3Asm());
        //final ByteBuddy byteBuddy = new ByteBuddy().with(TypeValidation.of(false));

//        new AgentBuilder.Default(byteBuddy)
//                .type(ElementMatchers.named("org.lwjgl.input.Mouse"))
//                .transform(new Lwjgl2Asm())
//                .installOn(inst);

//        new AgentBuilder.Default(byteBuddy)
//                .type(ElementMatchers.named("org.lwjgl.glfw.GLFW"))
//                .transform(new Lwjgl3Asm())
//                .installOn(inst);


    }
}