package com.coloryr;

import com.coloryr.asms.CursorAsm;
import com.coloryr.asms.Lwjgl3Asm;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class CursorAsmTest {
    @Test
    void transformsRealGlfwAndKeepsGrabNotificationBeforeModeFilter() throws Exception {
        byte[] original;
        try (InputStream in = GLFW.class.getResourceAsStream("GLFW.class")) {
            assertNotNull(in);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int length;
            while ((length = in.read(chunk)) != -1) {
                bytes.write(chunk, 0, length);
            }
            original = bytes.toByteArray();
        }
        byte[] inputPatched = new Lwjgl3Asm().transform(null, "org/lwjgl/glfw/GLFW", null, null, original);
        byte[] patched = new CursorAsm().transform(null, "org/lwjgl/glfw/GLFW", null, null, inputPatched);
        assertValid(patched);
        ClassNode node = read(patched);
        assertEquals(1, countCalls(node, "glfwSwapBuffers", "beforeGlfwSwap"));
        assertEquals(1, countCalls(node, "glfwDestroyWindow", "destroyGlfw"));
        MethodNode mode = node.methods.stream().filter(m -> m.name.equals("glfwSetInputMode")).findFirst().get();
        int grabIndex = -1;
        int filterIndex = -1;
        int index = 0;
        for (AbstractInsnNode instruction : mode.instructions) {
            if (instruction instanceof MethodInsnNode) {
                String method = ((MethodInsnNode) instruction).name;
                if (method.equals("coloryrGrab")) {
                    grabIndex = index;
                } else if (method.equals("cursorMode")) {
                    filterIndex = index;
                }
            }
            index++;
        }
        assertTrue(grabIndex >= 0 && filterIndex > grabIndex);
    }

    @Test
    void displayUpdateDoesNotDrawTwiceThroughNestedSwap() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/lwjgl/opengl/Display", null, "java/lang/Object", null);
        for (String name : new String[]{"update", "swapBuffers", "destroy"}) {
            org.objectweb.asm.MethodVisitor method =
                    writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
            method.visitCode();
            if (name.equals("update")) {
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/Display", "swapBuffers", "()V", false);
            }
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        byte[] patched = new CursorAsm().transform(null, "org/lwjgl/opengl/Display", null, null, writer.toByteArray());
        assertValid(patched);
        ClassNode node = read(patched);
        assertEquals(0, countCalls(node, "update", "beforeDisplaySwap"));
        assertEquals(1, countCalls(node, "swapBuffers", "beforeDisplaySwap"));
        assertEquals(1, countCalls(node, "destroy", "destroyDisplay"));
        assertNull(new CursorAsm().transform(null, "net/minecraft/client/Minecraft", null, null, writer.toByteArray()));
    }

    @Test
    void inputModesPassThroughUntilRendererIsReady() {
        long window = 12345;
        assertEquals(GLFW.GLFW_CURSOR_NORMAL,
                ColoryrCursor.cursorMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL));
        assertEquals(GLFW.GLFW_CURSOR_DISABLED,
                ColoryrCursor.cursorMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED));
        assertEquals(GLFW.GLFW_CURSOR_HIDDEN,
                ColoryrCursor.cursorMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN));
        assertEquals(1, ColoryrCursor.cursorMode(window, GLFW.GLFW_RAW_MOUSE_MOTION, 1));
        ColoryrCursor.destroyGlfw(GLFW.class, window);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static long countCalls(ClassNode node, String method, String hook) {
        long count = 0;
        for (MethodNode candidate : node.methods) {
            if (candidate.name.equals(method)) {
                for (AbstractInsnNode instruction : candidate.instructions) {
                    if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals(hook)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static void assertValid(byte[] bytes) {
        StringWriter errors = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(errors));
        assertEquals("", errors.toString());
    }
    @Test
    void transformsRealVulkanSwapchainAndQueueEntries() throws Exception {
        byte[] vk10 = resource(VK10.class, "VK10.class");
        byte[] swapchain = resource(KHRSwapchain.class, "KHRSwapchain.class");
        CursorAsm transformer = new CursorAsm();
        byte[] patchedVk10 = transformer.transform(null, "org/lwjgl/vulkan/VK10", null, null, vk10);
        byte[] patchedSwapchain = transformer.transform(null, "org/lwjgl/vulkan/KHRSwapchain", null, null, swapchain);
        assertValid(patchedVk10);
        assertValid(patchedSwapchain);
        assertEquals(1, countCalls(read(patchedVk10), "nvkGetDeviceQueue", "queueCreated"));
        assertEquals(1, countCalls(read(patchedVk10), "nvkDestroyDevice", "destroyDevice"));
        assertEquals(1, countCalls(read(patchedSwapchain), "nvkCreateSwapchainKHR", "prepareSwapchain"));
        assertEquals(1, countCalls(read(patchedSwapchain), "nvkCreateSwapchainKHR", "swapchainCreated"));
        assertEquals(1, countCalls(read(patchedSwapchain), "nvkDestroySwapchainKHR", "destroySwapchain"));
        assertEquals(1, countCalls(read(patchedSwapchain), "nvkQueuePresentKHR", "present"));
        byte[] surface = transformer.transform(null, "org/lwjgl/vulkan/KHRSurface", null, null,
                resource(KHRSurface.class, "KHRSurface.class"));
        assertValid(surface);
        assertEquals(1, countCalls(read(surface), "nvkDestroySurfaceKHR", "surfaceDestroyed"));
        MethodNode present = read(patchedSwapchain).methods.stream()
                .filter(m -> m.name.equals("nvkQueuePresentKHR")).findFirst().get();
        assertEquals("(Lorg/lwjgl/vulkan/VkQueue;J)I", present.desc);
    }

    private static byte[] resource(Class<?> type, String name) throws Exception {
        try (InputStream in = type.getResourceAsStream(name)) {
            assertNotNull(in);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int length; (length = in.read(chunk)) != -1;) bytes.write(chunk, 0, length);
            return bytes.toByteArray();
        }
    }
}
