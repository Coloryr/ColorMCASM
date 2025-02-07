package com.coloryr.colormc.asms;

import com.coloryr.colormc.ColorMCASM;
import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class Lwjgl3Asm implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            if ("org.lwjgl.glfw.GLFW".equals(className.replace("/", "."))) {
                System.out.println("[ColorMC ASM] Mixin > org.lwjgl.glfw.GLFW done");

                ClassReader cr = new ClassReader(className);
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

                Lwjgl3Adpter lwjgl3Adpter = new Lwjgl3Adpter(cw);

                cr.accept(lwjgl3Adpter, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            }
            return null;
        } catch (IOException e) {
            System.out.println("[ColorMC ASM] transform error " + className);
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
                System.out.println("[ColorMC ASM] Mixin Lwjgl3 > glfwSetInputMode done");
                return new GrabbedMethodVisitor(mv);
            }
            else if (name.equals("glfwCreateWindow")) {
                System.out.println("[ColorMC ASM] Mixin Lwjgl3 > glfwCreateWindow done");
                return new WindowMethodVisitor(mv);
            }

            return mv;
        }

        @Override
        public void visitEnd() {
            MethodVisitor methodVisitor;
            {
                methodVisitor = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "colormcGrab", "(II)V", null, null);
                methodVisitor.visitCode();
                Label label0 = new Label();
                Label label1 = new Label();
                Label label2 = new Label();
                methodVisitor.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception");
                methodVisitor.visitLabel(label0);
                methodVisitor.visitLineNumber(8, label0);
                methodVisitor.visitLdcInsn("com.coloryr.colormc.ColorMCPackBuilder");
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
                methodVisitor.visitVarInsn(ASTORE, 2);
                Label label3 = new Label();
                methodVisitor.visitLabel(label3);
                methodVisitor.visitLineNumber(9, label3);
                methodVisitor.visitVarInsn(ALOAD, 2);
                methodVisitor.visitLdcInsn("setGrabbed");
                methodVisitor.visitInsn(ICONST_2);
                methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Class");
                methodVisitor.visitInsn(DUP);
                methodVisitor.visitInsn(ICONST_0);
                methodVisitor.visitFieldInsn(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
                methodVisitor.visitInsn(AASTORE);
                methodVisitor.visitInsn(DUP);
                methodVisitor.visitInsn(ICONST_1);
                methodVisitor.visitFieldInsn(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
                methodVisitor.visitInsn(AASTORE);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
                methodVisitor.visitVarInsn(ASTORE, 3);
                Label label4 = new Label();
                methodVisitor.visitLabel(label4);
                methodVisitor.visitLineNumber(10, label4);
                methodVisitor.visitVarInsn(ALOAD, 3);
                methodVisitor.visitInsn(ACONST_NULL);
                methodVisitor.visitInsn(ICONST_2);
                methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                methodVisitor.visitInsn(DUP);
                methodVisitor.visitInsn(ICONST_0);
                methodVisitor.visitVarInsn(ILOAD, 0);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                methodVisitor.visitInsn(AASTORE);
                methodVisitor.visitInsn(DUP);
                methodVisitor.visitInsn(ICONST_1);
                methodVisitor.visitVarInsn(ILOAD, 1);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                methodVisitor.visitInsn(AASTORE);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                methodVisitor.visitInsn(POP);
                methodVisitor.visitLabel(label1);
                methodVisitor.visitLineNumber(13, label1);
                Label label5 = new Label();
                methodVisitor.visitJumpInsn(GOTO, label5);
                methodVisitor.visitLabel(label2);
                methodVisitor.visitLineNumber(11, label2);
                methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Exception"});
                methodVisitor.visitVarInsn(ASTORE, 2);
                Label label6 = new Label();
                methodVisitor.visitLabel(label6);
                methodVisitor.visitLineNumber(12, label6);
                methodVisitor.visitVarInsn(ALOAD, 2);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V", false);
                methodVisitor.visitLabel(label5);
                methodVisitor.visitLineNumber(14, label5);
                methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                methodVisitor.visitInsn(RETURN);
                Label label7 = new Label();
                methodVisitor.visitLabel(label7);
                methodVisitor.visitLocalVariable("colorMCPackBuilderClass", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", label3, label1, 2);
                methodVisitor.visitLocalVariable("method", "Ljava/lang/reflect/Method;", null, label4, label1, 3);
                methodVisitor.visitLocalVariable("e", "Ljava/lang/Exception;", null, label6, label5, 2);
                methodVisitor.visitLocalVariable("b", "I", null, label0, label7, 0);
                methodVisitor.visitLocalVariable("c", "I", null, label0, label7, 1);
                methodVisitor.visitMaxs(6, 4);
                methodVisitor.visitEnd();
            }

            {
                methodVisitor = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "colormcAddWindow", "(J)V", null, null);
                methodVisitor.visitCode();
                Label label0 = new Label();
                Label label1 = new Label();
                Label label2 = new Label();
                methodVisitor.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception");
                methodVisitor.visitLabel(label0);
                methodVisitor.visitLineNumber(30, label0);
                methodVisitor.visitLdcInsn("com.coloryr.colormc.ColorMCASM");
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
                methodVisitor.visitVarInsn(ASTORE, 2);
                Label label3 = new Label();
                methodVisitor.visitLabel(label3);
                methodVisitor.visitLineNumber(31, label3);
                methodVisitor.visitVarInsn(ALOAD, 2);
                methodVisitor.visitLdcInsn("addWindow");
                methodVisitor.visitInsn(ICONST_1);
                methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Class");
                methodVisitor.visitInsn(DUP);
                methodVisitor.visitInsn(ICONST_0);
                methodVisitor.visitFieldInsn(GETSTATIC, "java/lang/Long", "TYPE", "Ljava/lang/Class;");
                methodVisitor.visitInsn(AASTORE);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
                methodVisitor.visitVarInsn(ASTORE, 3);
                Label label4 = new Label();
                methodVisitor.visitLabel(label4);
                methodVisitor.visitLineNumber(32, label4);
                methodVisitor.visitVarInsn(ALOAD, 3);
                methodVisitor.visitInsn(ACONST_NULL);
                methodVisitor.visitInsn(ICONST_1);
                methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                methodVisitor.visitInsn(DUP);
                methodVisitor.visitInsn(ICONST_0);
                methodVisitor.visitVarInsn(LLOAD, 0);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                methodVisitor.visitInsn(AASTORE);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                methodVisitor.visitInsn(POP);
                methodVisitor.visitLabel(label1);
                methodVisitor.visitLineNumber(36, label1);
                Label label5 = new Label();
                methodVisitor.visitJumpInsn(GOTO, label5);
                methodVisitor.visitLabel(label2);
                methodVisitor.visitLineNumber(34, label2);
                methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Exception"});
                methodVisitor.visitVarInsn(ASTORE, 2);
                Label label6 = new Label();
                methodVisitor.visitLabel(label6);
                methodVisitor.visitLineNumber(35, label6);
                methodVisitor.visitVarInsn(ALOAD, 2);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V", false);
                methodVisitor.visitLabel(label5);
                methodVisitor.visitLineNumber(37, label5);
                methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                methodVisitor.visitInsn(RETURN);
                Label label7 = new Label();
                methodVisitor.visitLabel(label7);
                methodVisitor.visitLocalVariable("colorMCPackBuilderClass", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", label3, label1, 2);
                methodVisitor.visitLocalVariable("method", "Ljava/lang/reflect/Method;", null, label4, label1, 3);
                methodVisitor.visitLocalVariable("e", "Ljava/lang/Exception;", null, label6, label5, 2);
                methodVisitor.visitLocalVariable("temp", "J", null, label0, label7, 0);
                methodVisitor.visitMaxs(7, 4);
                methodVisitor.visitEnd();
            }

            super.visitEnd();
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
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/glfw/GLFW", "colormcGrab", "(II)V", false);

            super.visitCode();
        }
    }

    private static class WindowMethodVisitor extends MethodVisitor {
        public WindowMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.LRETURN) {
                mv.visitVarInsn(Opcodes.LSTORE, 1);
                mv.visitVarInsn(Opcodes.LLOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/glfw/GLFW", "colormcAddWindow", "(J)V", false);
                mv.visitVarInsn(Opcodes.LLOAD, 1);
            }
            super.visitInsn(opcode);
        }
    }
}

