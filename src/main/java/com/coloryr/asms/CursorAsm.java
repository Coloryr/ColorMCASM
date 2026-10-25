package com.coloryr.asms;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Uses LWJGL entry points, independent of Minecraft names and mod loaders. */
public final class CursorAsm implements ClassFileTransformer, Opcodes {
    private static final String CURSOR = "com/coloryr/ColoryrCursor";
    private static final String VK = "com/coloryr/ColoryrVulkanCursor";

    @Override
    public byte[] transform(ClassLoader loader, String owner, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!"org/lwjgl/glfw/GLFW".equals(owner) && !"org/lwjgl/opengl/Display".equals(owner)
                && !"org/lwjgl/glfw/GLFWVulkan".equals(owner)
                && !"org/lwjgl/vulkan/VK10".equals(owner) && !"org/lwjgl/vulkan/KHRSwapchain".equals(owner)
                && !"org/lwjgl/vulkan/KHRSurface".equals(owner)) {
            return null;
        }
        try {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            new ClassReader(bytes).accept(new ClassVisitor(ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    MethodVisitor method = super.visitMethod(access, name, desc, signature, exceptions);
                    if ((access & (ACC_NATIVE | ACC_ABSTRACT)) != 0) {
                        return method;
                    }
                    if (owner.equals("org/lwjgl/vulkan/KHRSwapchain")
                            && name.equals("nvkQueuePresentKHR")
                            && desc.equals("(Lorg/lwjgl/vulkan/VkQueue;J)I")) {
                        method.visitCode();
                        method.visitVarInsn(ALOAD, 0);
                        method.visitVarInsn(LLOAD, 1);
                        method.visitMethodInsn(INVOKESTATIC, VK, "present", "(Ljava/lang/Object;J)I", false);
                        method.visitInsn(IRETURN);
                        method.visitMaxs(3, 3);
                        method.visitEnd();
                        return null;
                    }
                    return new MethodVisitor(ASM9, method) {
                        private void call(String target, String hook, String descriptor) {
                            mv.visitMethodInsn(INVOKESTATIC, target, hook, descriptor, false);
                        }

                        private void window(String target, String hook, boolean hasWindow) {
                            mv.visitLdcInsn(Type.getObjectType(owner));
                            if (hasWindow) {
                                mv.visitVarInsn(LLOAD, 0);
                            }
                            call(target, hook, hasWindow ? "(Ljava/lang/Class;J)V" : "(Ljava/lang/Class;)V");
                        }

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (owner.equals("org/lwjgl/glfw/GLFW") && desc.equals("(J)V")) {
                                if (name.equals("glfwSwapBuffers")) window(CURSOR, "beforeGlfwSwap", true);
                                if (name.equals("glfwDestroyWindow")) window(CURSOR, "destroyGlfw", true);
                            } else if (owner.equals("org/lwjgl/opengl/Display") && desc.equals("()V")) {
                                if (name.equals("swapBuffers")) window(CURSOR, "beforeDisplaySwap", false);
                                if (name.equals("destroy")) window(CURSOR, "destroyDisplay", false);
                            } else if (owner.equals("org/lwjgl/vulkan/KHRSwapchain")) {
                                if (name.equals("nvkCreateSwapchainKHR")
                                        && desc.equals("(Lorg/lwjgl/vulkan/VkDevice;JJJ)I")) {
                                    mv.visitVarInsn(ALOAD, 0);
                                    mv.visitVarInsn(LLOAD, 1);
                                    call(VK, "prepareSwapchain", "(Ljava/lang/Object;J)V");
                                }
                                if (name.equals("nvkDestroySwapchainKHR")
                                        && desc.equals("(Lorg/lwjgl/vulkan/VkDevice;JJ)V")) {
                                    mv.visitVarInsn(ALOAD, 0);
                                    mv.visitVarInsn(LLOAD, 1);
                                    call(VK, "destroySwapchain", "(Ljava/lang/Object;J)V");
                                }
                            } else if (owner.equals("org/lwjgl/vulkan/VK10") && name.equals("nvkDestroyDevice")
                                    && desc.equals("(Lorg/lwjgl/vulkan/VkDevice;J)V")) {
                                mv.visitVarInsn(ALOAD, 0);
                                call(VK, "destroyDevice", "(Ljava/lang/Object;)V");
                            } else if (owner.equals("org/lwjgl/vulkan/KHRSurface") && name.equals("nvkDestroySurfaceKHR")
                                    && desc.equals("(Lorg/lwjgl/vulkan/VkInstance;JJ)V")) {
                                mv.visitVarInsn(ALOAD, 0);
                                mv.visitVarInsn(LLOAD, 1);
                                call(VK, "surfaceDestroyed", "(Ljava/lang/Object;J)V");
                            }
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == IRETURN && desc.equals("(JJJJ)I")
                                    && owner.equals("org/lwjgl/glfw/GLFWVulkan") && name.equals("nglfwCreateWindowSurface")) {
                                surface();
                            }
                            if (opcode == IRETURN && owner.equals("org/lwjgl/vulkan/KHRSwapchain")
                                    && name.equals("nvkCreateSwapchainKHR")
                                    && desc.equals("(Lorg/lwjgl/vulkan/VkDevice;JJJ)I")) {
                                mv.visitInsn(DUP);
                                mv.visitVarInsn(ALOAD, 0);
                                mv.visitVarInsn(LLOAD, 1);
                                mv.visitVarInsn(LLOAD, 5);
                                call(VK, "swapchainCreated", "(ILjava/lang/Object;JJ)V");
                            }
                            if (opcode == RETURN && owner.equals("org/lwjgl/vulkan/VK10")
                                    && name.equals("nvkGetDeviceQueue") && desc.equals("(Lorg/lwjgl/vulkan/VkDevice;IIJ)V")) {
                                mv.visitVarInsn(ALOAD, 0);
                                mv.visitVarInsn(ILOAD, 1);
                                mv.visitVarInsn(LLOAD, 3);
                                call(VK, "queueCreated", "(Ljava/lang/Object;IJ)V");
                            }
                            super.visitInsn(opcode);
                        }

                        private void surface() {
                            mv.visitInsn(DUP);
                            mv.visitLdcInsn(Type.getObjectType(owner));
                            mv.visitVarInsn(LLOAD, 2);
                            mv.visitVarInsn(LLOAD, 6);
                            call(VK, "surfaceCreated", "(ILjava/lang/Class;JJ)V");
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        } catch (Exception error) {
            System.out.println("[ColoryrASM] cursor transform failed: " + owner + ": " + error);
            return null;
        }
    }
}
