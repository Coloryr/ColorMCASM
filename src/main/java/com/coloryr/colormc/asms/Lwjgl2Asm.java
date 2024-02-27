package com.coloryr.colormc.asms;

import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class Lwjgl2Asm implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            if ("org.lwjgl.input.Mouse".equals(className.replace("/", "."))) {
                System.out.println("ColorMC Mixin > org.lwjgl.input.Mouse done");
                ClassReader cr = new ClassReader(className);
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                Lwjgl2Adpter lwjgl2Adpter = new Lwjgl2Adpter(cw);

                cr.accept(lwjgl2Adpter, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            }
            return null;
        } catch (IOException e) {
            System.out.println("transform error " + className);
            e.printStackTrace();
        }
        return null;
    }

    private static class Lwjgl2Adpter extends ClassVisitor implements Opcodes {

        public Lwjgl2Adpter(ClassVisitor classVisitor) {
            super(ASM9, classVisitor);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
            if (name.equals("setGrabbed")) {
                System.out.println("ColorMC Mixin Lwjgl2 > setGrabbed done");
                return new GrabbedMethodVisitor(mv);
            }

            return mv;
        }

        @Override
        public void visitEnd() {
            MethodVisitor methodVisitor = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "colormcGrab", "(Z)V", null, null);
            methodVisitor.visitCode();
            Label label0 = new Label();
            Label label1 = new Label();
            Label label2 = new Label();
            methodVisitor.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception");
            methodVisitor.visitLabel(label0);
            methodVisitor.visitLineNumber(18, label0);
            methodVisitor.visitLdcInsn("com.coloryr.colormc.ColorMCPackBuilder");
            methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
            methodVisitor.visitVarInsn(ASTORE, 1);
            Label label3 = new Label();
            methodVisitor.visitLabel(label3);
            methodVisitor.visitLineNumber(19, label3);
            methodVisitor.visitVarInsn(ALOAD, 1);
            methodVisitor.visitLdcInsn("setGrabbed");
            methodVisitor.visitInsn(ICONST_1);
            methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Class");
            methodVisitor.visitInsn(DUP);
            methodVisitor.visitInsn(ICONST_0);
            methodVisitor.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "TYPE", "Ljava/lang/Class;");
            methodVisitor.visitInsn(AASTORE);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
            methodVisitor.visitVarInsn(ASTORE, 2);
            Label label4 = new Label();
            methodVisitor.visitLabel(label4);
            methodVisitor.visitLineNumber(20, label4);
            methodVisitor.visitVarInsn(ALOAD, 2);
            methodVisitor.visitInsn(ACONST_NULL);
            methodVisitor.visitInsn(ICONST_1);
            methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            methodVisitor.visitInsn(DUP);
            methodVisitor.visitInsn(ICONST_0);
            methodVisitor.visitVarInsn(ILOAD, 0);
            methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            methodVisitor.visitInsn(AASTORE);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            methodVisitor.visitInsn(POP);
            methodVisitor.visitLabel(label1);
            methodVisitor.visitLineNumber(24, label1);
            Label label5 = new Label();
            methodVisitor.visitJumpInsn(GOTO, label5);
            methodVisitor.visitLabel(label2);
            methodVisitor.visitLineNumber(22, label2);
            methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Exception"});
            methodVisitor.visitVarInsn(ASTORE, 1);
            Label label6 = new Label();
            methodVisitor.visitLabel(label6);
            methodVisitor.visitLineNumber(23, label6);
            methodVisitor.visitVarInsn(ALOAD, 1);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V", false);
            methodVisitor.visitLabel(label5);
            methodVisitor.visitLineNumber(25, label5);
            methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            methodVisitor.visitInsn(RETURN);
            Label label7 = new Label();
            methodVisitor.visitLabel(label7);
            methodVisitor.visitLocalVariable("colorMCPackBuilderClass", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", label3, label1, 1);
            methodVisitor.visitLocalVariable("method", "Ljava/lang/reflect/Method;", null, label4, label1, 2);
            methodVisitor.visitLocalVariable("e", "Ljava/lang/Exception;", null, label6, label5, 1);
            methodVisitor.visitLocalVariable("temp", "Z", null, label0, label7, 0);
            methodVisitor.visitMaxs(6, 3);
            methodVisitor.visitEnd();
        }
    }

    private static class GrabbedMethodVisitor extends MethodVisitor {
        public GrabbedMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/input/Mouse", "colormcGrab", "(Z)V", false);
            super.visitCode();
        }
    }
}

//public class Lwjgl2Asm implements AgentBuilder.Transformer {
//    public static Object intercept(boolean grabbed, @SuperCall Callable<?> callable) throws Exception {
//        ColorMCPackBuilder.buildGrabbed(grabbed);
//        return callable.call();
//    }
//
//    @Override
//    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
//                                            ClassLoader classLoader, JavaModule javaModule, ProtectionDomain protectionDomain) {
//        if (Objects.requireNonNull(typeDescription.getPackage()).getActualName().equals("org.lwjgl.input")) {
//            return builder
//                    .method(ElementMatchers.named("setGrabbed")
//                            .and(ElementMatchers.isPublic()))
//                    .intercept(MethodDelegation.to(Lwjgl3Asm.class));
//        }
//        return builder;
//    }
//}