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
                System.out.println("ColorMC Mixin Lwjgl2 > org.lwjgl.input.Mouse done");
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
                System.out.println("ColorMC Mixin > setGrabbed done");
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
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/coloryr/colormc/ColorMCPackBuilder", "setGrabbed", "(Z)V", false);
            super.visitCode();
        }
    }
}