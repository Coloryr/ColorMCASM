package com.coloryr.asms;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class Lwjgl3Asm implements ClassFileTransformer {
    private static final String GLFW_BRIDGE = "com/coloryr/ColoryrGlfw";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            if (className == null || !"org.lwjgl.glfw.GLFW".equals(className.replace("/", "."))) {
                return null;
            }
            System.out.println("[ColoryrASM] Mixin > org.lwjgl.glfw.GLFW done");
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            Lwjgl3Adpter lwjgl3Adpter = new Lwjgl3Adpter(cw);

            cr.accept(lwjgl3Adpter, ClassReader.EXPAND_FRAMES);
            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("[ColoryrASM] transform error " + className);
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

            if ("glfwSetInputMode".equals(name) && "(JII)V".equals(descriptor)) {
                System.out.println("[ColoryrASM] Mixin Lwjgl3 > glfwSetInputMode done");
                return new GrabbedMethodVisitor(mv);
            } else if ("glfwCreateWindow".equals(name)) {
                System.out.println("[ColoryrASM] Mixin Lwjgl3 > glfwCreateWindow done");
                return new WindowMethodVisitor(mv);
            } else if (isPollEvents(name, descriptor)) {
                System.out.println("[ColoryrASM] Mixin Lwjgl3 > " + name + " done");
                return new PollEventsVisitor(mv);
            } else if (isCallbackSetter(descriptor)) {
                if ("glfwSetKeyCallback".equals(name)) {
                    return new CallbackVisitor(mv, "captureKeyCallback", descriptor, false);
                }
                if ("glfwSetMouseButtonCallback".equals(name)) {
                    return new CallbackVisitor(mv, "captureMouseButtonCallback", descriptor, false);
                }
                if ("glfwSetScrollCallback".equals(name)) {
                    return new CallbackVisitor(mv, "captureScrollCallback", descriptor, false);
                }
                if ("glfwSetCursorPosCallback".equals(name)) {
                    System.out.println("[ColoryrASM] Mixin Lwjgl3 > glfwSetCursorPosCallback done");
                    return new CallbackVisitor(mv, "wrapCursorCallback", descriptor, true);
                }
            }

            return mv;
        }

        /** 是否是处理事件的轮询 / 等待方法，等待事件同样会把回调交给游戏 */
        private static boolean isPollEvents(String name, String descriptor) {
            if ("glfwPollEvents".equals(name) || "glfwWaitEvents".equals(name)) {
                return "()V".equals(descriptor);
            }
            return "glfwWaitEventsTimeout".equals(name) && "(D)V".equals(descriptor);
        }

        /** 是否是 `(long, 回调)` 形式的方法，避免误伤同名重载 */
        private static boolean isCallbackSetter(String descriptor) {
            Type[] args = Type.getArgumentTypes(descriptor);
            return args.length == 2
                    && args[0].getSort() == Type.LONG
                    && args[1].getSort() == Type.OBJECT;
        }

        @Override
        public void visitEnd() {
            MethodVisitor methodVisitor;
            {
                methodVisitor = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "coloryrGrab", "(II)V", null, null);
                methodVisitor.visitCode();
                Label label0 = new Label();
                Label label1 = new Label();
                Label label2 = new Label();
                methodVisitor.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception");
                methodVisitor.visitLabel(label0);
                methodVisitor.visitLineNumber(8, label0);
                methodVisitor.visitLdcInsn("com.coloryr.ColoryrPackBuilder");
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
                methodVisitor.visitLocalVariable("coloryrPackBuilderClass", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", label3, label1, 2);
                methodVisitor.visitLocalVariable("method", "Ljava/lang/reflect/Method;", null, label4, label1, 3);
                methodVisitor.visitLocalVariable("e", "Ljava/lang/Exception;", null, label6, label5, 2);
                methodVisitor.visitLocalVariable("b", "I", null, label0, label7, 0);
                methodVisitor.visitLocalVariable("c", "I", null, label0, label7, 1);
                methodVisitor.visitMaxs(6, 4);
                methodVisitor.visitEnd();
            }

            {
                methodVisitor = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "coloryrAddWindow", "(J)V", null, null);
                methodVisitor.visitCode();
                Label label0 = new Label();
                Label label1 = new Label();
                Label label2 = new Label();
                methodVisitor.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception");
                methodVisitor.visitLabel(label0);
                methodVisitor.visitLineNumber(30, label0);
                methodVisitor.visitLdcInsn("com.coloryr.ColoryrASM");
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
                methodVisitor.visitLocalVariable("coloryrASMClass", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", label3, label1, 2);
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
            super.visitCode();
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/glfw/GLFW", "coloryrGrab", "(II)V", false);
            mv.visitVarInsn(Opcodes.LLOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/coloryr/ColoryrCursor",
                    "cursorMode", "(JII)I", false);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
        }
    }

    private static class WindowMethodVisitor extends MethodVisitor {
        public WindowMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.LRETURN) {
                mv.visitVarInsn(Opcodes.LSTORE, 1);
                mv.visitVarInsn(Opcodes.LLOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/glfw/GLFW", "coloryrAddWindow", "(J)V", false);
                mv.visitVarInsn(Opcodes.LLOAD, 1);
            }
            super.visitInsn(opcode);
        }
    }

    /** 帧轮询钩子：在原生事件处理前注入队列里的合成事件 */
    private static class PollEventsVisitor extends MethodVisitor {
        public PollEventsVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GLFW_BRIDGE, "dispatch", "()V", false);
        }
    }

    /** 回调注册钩子：留下游戏注册的回调，光标回调还会被替换成包装对象 */
    private static class CallbackVisitor extends MethodVisitor {
        private final String method;
        private final boolean replace;
        private final String callbackType;

        public CallbackVisitor(MethodVisitor mv, String method, String descriptor, boolean replace) {
            super(Opcodes.ASM9, mv);
            this.method = method;
            this.replace = replace;
            Type[] args = Type.getArgumentTypes(descriptor);
            this.callbackType = args.length > 1 ? args[1].getInternalName() : "java/lang/Object";
        }

        @Override
        public void visitCode() {
            super.visitCode();
            // 静态方法的第一个参数是 long 窗口句柄，占两个槽位，回调在局部变量 2
            mv.visitVarInsn(Opcodes.LLOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            if (replace) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, GLFW_BRIDGE, method, "(JLjava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, callbackType);
                mv.visitVarInsn(Opcodes.ASTORE, 2);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, GLFW_BRIDGE, method, "(JLjava/lang/Object;)V", false);
            }
        }
    }
}
