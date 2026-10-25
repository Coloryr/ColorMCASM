package com.coloryr.asms;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class Lwjgl2Asm implements ClassFileTransformer {
    private static final String INPUT = "com/coloryr/ColoryrInput";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        String name = className.replace("/", ".");
        if (!"org.lwjgl.input.Mouse".equals(name) && !"org.lwjgl.input.Keyboard".equals(name)) {
            return null;
        }
        try {
            System.out.println("[ColoryrASM] Mixin Lwjgl2 > " + name + " done");
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            Lwjgl2Adpter lwjgl2Adpter = new Lwjgl2Adpter(cw, className);

            cr.accept(lwjgl2Adpter, ClassReader.EXPAND_FRAMES);
            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[ColoryrASM] transform error " + className);
            e.printStackTrace();
        }
        return null;
    }

    private static class Lwjgl2Adpter extends ClassVisitor implements Opcodes {
        /** 被转换类的内部名，如 org/lwjgl/input/Mouse */
        private final String className;

        public Lwjgl2Adpter(ClassVisitor classVisitor, String className) {
            super(ASM9, classVisitor);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
            if (name.equals("setGrabbed") && descriptor.equals("(Z)V")) {
                System.out.println("[ColoryrASM] Mixin > setGrabbed done");
                return new GrabbedMethodVisitor(mv);
            }
            if (name.equals("poll") && descriptor.equals("()V")) {
                System.out.println("[ColoryrASM] Mixin > poll done");
                if (className.endsWith("/Mouse")) {
                    return new PollMethodVisitor(mv, className, "dispatchMouse");
                }
                if (className.endsWith("/Keyboard")) {
                    return new PollMethodVisitor(mv, className, "dispatchKeyboard");
                }
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
            super.visitCode();
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/coloryr/ColoryrPackBuilder", "setGrabbed", "(Z)V", false);
        }
    }

    /** 原生轮询结束、事件进入读缓冲之后，把合成事件追加进去 */
    private static class PollMethodVisitor extends MethodVisitor {
        private final String className;
        private final String method;

        public PollMethodVisitor(MethodVisitor mv, String className, String method) {
            super(Opcodes.ASM9, mv);
            this.className = className;
            this.method = method;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                mv.visitLdcInsn(Type.getObjectType(className));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, INPUT, method, "(Ljava/lang/Class;)V", false);
            }
            super.visitInsn(opcode);
        }
    }
}
