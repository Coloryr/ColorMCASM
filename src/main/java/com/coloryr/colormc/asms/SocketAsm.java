package com.coloryr.colormc.asms;

import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class SocketAsm implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            if ("java.net.DatagramSocket".equals(className.replace("/", "."))) {
                System.out.println("ColorMC Mixin > java.net.DatagramSocket done");
                ClassReader cr = new ClassReader(className);
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                SocketAdpter adpter = new SocketAdpter(cw);

                cr.accept(adpter, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            }
            return null;
        } catch (IOException e) {
            System.out.println("transform error " + className);
            e.printStackTrace();
        }
        return null;
    }

    private static class SocketAdpter extends ClassVisitor implements Opcodes {

        public SocketAdpter(ClassVisitor classVisitor) {
            super(ASM9, classVisitor);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
            if ("receive".equals(name) && "(Ljava/net/DatagramPacket;)V".equals(descriptor)) {
                System.out.println("ColorMC Mixin DatagramSocket > receive done");
                return new GrabbedMethodVisitor(mv);
            }

            return mv;
        }

        @Override
        public void visitEnd() {
            MethodVisitor methodVisitor;
            methodVisitor = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "getServer", "(Ljava/net/DatagramPacket;)Z", null, null);
            methodVisitor.visitCode();
            Label label0 = new Label();
            Label label1 = new Label();
            Label label2 = new Label();
            methodVisitor.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception");
            methodVisitor.visitLabel(label0);
            methodVisitor.visitLineNumber(30, label0);

// 获取当前线程的上下文类加载器
            methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);

// 使用上下文类加载器来加载类
            methodVisitor.visitLdcInsn("com.coloryr.colormc.ColorMCPackBuilder");
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
            methodVisitor.visitVarInsn(ASTORE, 1);

            Label label3 = new Label();
            methodVisitor.visitLabel(label3);
            methodVisitor.visitLineNumber(31, label3);
            methodVisitor.visitVarInsn(ALOAD, 1);
            methodVisitor.visitLdcInsn("receive");
            methodVisitor.visitInsn(ICONST_1);
            methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Class");
            methodVisitor.visitInsn(DUP);
            methodVisitor.visitInsn(ICONST_0);
            methodVisitor.visitLdcInsn(Type.getType("Ljava/net/DatagramPacket;"));
            methodVisitor.visitInsn(AASTORE);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
            methodVisitor.visitVarInsn(ASTORE, 2);

            Label label4 = new Label();
            methodVisitor.visitLabel(label4);
            methodVisitor.visitLineNumber(32, label4);
            methodVisitor.visitVarInsn(ALOAD, 2);
            methodVisitor.visitInsn(ACONST_NULL);
            methodVisitor.visitInsn(ICONST_1);
            methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            methodVisitor.visitInsn(DUP);
            methodVisitor.visitInsn(ICONST_0);
            methodVisitor.visitVarInsn(ALOAD, 0);
            methodVisitor.visitInsn(AASTORE);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
            methodVisitor.visitLabel(label1);
            methodVisitor.visitInsn(IRETURN);

            methodVisitor.visitLabel(label2);
            methodVisitor.visitLineNumber(33, label2);
            methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Exception"});
            methodVisitor.visitVarInsn(ASTORE, 1);

            Label label5 = new Label();
            methodVisitor.visitLabel(label5);
            methodVisitor.visitLineNumber(34, label5);
            methodVisitor.visitVarInsn(ALOAD, 1);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V", false);

            Label label6 = new Label();
            methodVisitor.visitLabel(label6);
            methodVisitor.visitLineNumber(37, label6);
            methodVisitor.visitInsn(ICONST_0);
            methodVisitor.visitInsn(IRETURN);

            Label label7 = new Label();
            methodVisitor.visitLabel(label7);
            methodVisitor.visitLocalVariable("colorMCPackBuilderClass", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", label3, label2, 1);
            methodVisitor.visitLocalVariable("method", "Ljava/lang/reflect/Method;", null, label4, label2, 2);
            methodVisitor.visitLocalVariable("e", "Ljava/lang/Exception;", null, label5, label6, 1);
            methodVisitor.visitLocalVariable("p", "Ljava/net/DatagramPacket;", null, label0, label7, 0);
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
            // 调用原方法的visitCode
            super.visitCode();

            // 插入if(getServer(p)) { return; }
            mv.visitVarInsn(Opcodes.ALOAD, 1); // 加载参数p
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/DatagramSocket", "getServer", "(Ljava/net/DatagramPacket;)Z", false);
            Label afterIf = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, afterIf); // 如果getServer返回false，跳过return
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(afterIf);
        }
    }
}
