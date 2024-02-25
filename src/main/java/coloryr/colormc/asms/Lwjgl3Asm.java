package coloryr.colormc.asms;

import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class Lwjgl3Asm implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            if ("org.lwjgl.glfw.GLFW".equals(className.replace("/", "."))) {
                System.out.println("ColorMC Mixin > org.lwjgl.glfw.GLFW done");
                ClassReader cr = new ClassReader(className);
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                Lwjgl3Adpter lwjgl3Adpter = new Lwjgl3Adpter(cw);

                cr.accept(lwjgl3Adpter, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            }
            return null;
        } catch (IOException e) {
            System.out.println("transform error " + className);
            e.printStackTrace();
        }
        return null;
    }

    private static class Lwjgl3Adpter extends ClassVisitor implements Opcodes {

        public Lwjgl3Adpter(ClassVisitor classVisitor) {
            super(ASM9, classVisitor);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);

            if (name.equals("glfwSetInputMode")) {
                System.out.println("ColorMC Mixin > glfwSetInputMode done");
                return new GrabbedMethodVisitor(mv);
            }

            return mv;
        }
    }

    private static class GrabbedMethodVisitor extends MethodVisitor {
        public GrabbedMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "coloryr/colormc/ColorMCPackBuilder", "setGrabbed", "(II)V", false);
            super.visitCode();
        }
    }
}

//public class Lwjgl3Asm implements AgentBuilder.Transformer {
//    public static Object intercept(long window, int mode, int value, @SuperCall Callable<?> callable) throws Exception {
//        if (mode == 208897) {
//            if (value == 212995) {
//                ColorMCPackBuilder.buildGrabbed(true);
//            } else if (value == 212993) {
//                ColorMCPackBuilder.buildGrabbed(false);
//            }
//        }
//        return callable.call();
//    }
//
//    @Override
//    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
//                                            ClassLoader classLoader, JavaModule javaModule, ProtectionDomain protectionDomain) {
//        if (Objects.requireNonNull(typeDescription.getPackage()).getActualName().equals("lwjgl.lwjgl.glfw")) {
//            return builder
//                    .method(ElementMatchers.named("glfwSetInputMode")
//                            .and(ElementMatchers.isPublic()))
//                    .intercept(MethodDelegation.to(Lwjgl3Asm.class));
//
//        }
//        return builder;
//    }
//}

