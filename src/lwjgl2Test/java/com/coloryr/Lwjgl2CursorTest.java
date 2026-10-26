package com.coloryr;

import com.coloryr.asms.CursorAsm;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

class Lwjgl2CursorTest {
    @Test
    void initializesDirectMouseBridgeAgainstActualLwjgl2WithoutCreatingWindow() throws Exception {
        assertFalse(Mouse.isCreated());
        try {
            ColoryrCursor.beforeDisplaySwap(Display.class);
            Field stateField = ColoryrCursor.class.getDeclaredField("displayCursor");
            stateField.setAccessible(true);
            Object state = stateField.get(null);
            assertNotNull(state);
            Field failed = state.getClass().getDeclaredField("failed");
            failed.setAccessible(true);
            assertFalse(failed.getBoolean(state), "Real LWJGL 2 methods and cursor constructor must resolve");
            Field apiField = state.getClass().getDeclaredField("api");
            apiField.setAccessible(true);
            ColoryrLwjgl2Api api = (ColoryrLwjgl2Api) apiField.get(state);
            assertNotNull(api);
            assertNotSame(Display.class.getClassLoader(), api.getClass().getClassLoader());
            assertFalse(api.isCreated());
            assertEquals(Display.getWidth(), api.getWidth());
            assertEquals(Display.getHeight(), api.getHeight());
            assertSame(Mouse.getNativeCursor(), api.getNativeCursor());
        } finally {
            ColoryrCursor.destroyDisplay(Display.class);
        }
    }

    @Test
    void transformsActualMinecraft1710LwjglDisplay() throws Exception {
        byte[] bytes;
        try (InputStream stream = Display.class.getResourceAsStream("Display.class")) {
            assertNotNull(stream);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int length;
            while ((length = stream.read(chunk)) != -1) {
                output.write(chunk, 0, length);
            }
            bytes = output.toByteArray();
        }
        byte[] patched = new CursorAsm().transform(null, "org/lwjgl/opengl/Display", null, null, bytes);
        assertNotNull(patched);
        StringWriter errors = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(patched), false, new PrintWriter(errors));
        assertEquals("", errors.toString());
        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, 0);
        long count = node.methods.stream().flatMap(method -> {
            java.util.List<MethodInsnNode> calls = new java.util.ArrayList<>();
            for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode) {
                    calls.add((MethodInsnNode) instruction);
                }
            }
            return calls.stream();
        }).filter(call -> call.name.equals("beforeDisplaySwap")).count();
        assertEquals(1, count);
    }

    @Test
    void rendersGl2FallbackOnOffscreenPbuffer() throws Exception {
        renderOnOffscreenPbuffer(true);
    }

    @Test
    void rendersLwjgl2ModernPathOnOffscreenPbuffer() throws Exception {
        renderOnOffscreenPbuffer(false);
    }

    private static void renderOnOffscreenPbuffer(boolean forceGl2) throws Exception {
        assumeTrue((Pbuffer.getCapabilities() & Pbuffer.PBUFFER_SUPPORTED) != 0,
                "Offscreen LWJGL 2 pbuffers are unavailable");
        Pbuffer buffer = new Pbuffer(128, 128, new PixelFormat(), null);
        Path texture = Files.createTempFile("cursor-lwjgl2", ".png");
        String oldTexture = System.getProperty("coloryrasm.cursor.texture");
        String oldX = System.getProperty("coloryrasm.cursor.hotspotX");
        String oldY = System.getProperty("coloryrasm.cursor.hotspotY");
        BufferedImage png = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        png.setRGB(0, 0, 0xFFFFFFFF);
        ImageIO.write(png, "png", texture.toFile());
        System.setProperty("coloryrasm.cursor.texture", texture.toString());
        System.setProperty("coloryrasm.cursor.hotspotX", "0");
        System.setProperty("coloryrasm.cursor.hotspotY", "0");
        ColoryrCursorRenderer renderer = null;
        ContextCapabilities caps = null;
        String[] names = {"OpenGL30", "OpenGL40", "GL_ARB_draw_buffers_blend"};
        Field[] fields = new Field[names.length];
        boolean[] original = new boolean[names.length];
        try {
            buffer.makeCurrent();
            caps = GLContext.getCapabilities();
            for (int i = 0; i < names.length; i++) {
                fields[i] = ContextCapabilities.class.getField(names[i]);
                fields[i].setAccessible(true);
                original[i] = fields[i].getBoolean(caps);
            }
            if (forceGl2) {
                // Exercise immediate-mode and non-indexed blend calls on newer drivers too.
                for (Field field : fields) field.setBoolean(caps, false);
            } else {
                assumeTrue(caps.OpenGL30, "OpenGL 3 is unavailable on this LWJGL 2 context");
            }
            renderer = new ColoryrCursorRenderer(GLContext.class.getClassLoader(), false);
            glClearColor(0.25f, 0.5f, 0.75f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glViewport(3, 4, 50, 60);
            glEnable(GL_ALPHA_TEST);
            glAlphaFunc(GL_NEVER, 0);
            glEnable(GL_SCISSOR_TEST);
            glScissor(0, 0, 1, 1);
            glEnable(GL_DEPTH_TEST);
            glColorMask(false, true, false, false);
            glTexCoord2f(0.2f, 0.4f);
            renderer.draw(128, 128, 20, 30, 24, 24);
            assertEquals(GL_NO_ERROR, glGetError());
            assertTrue(glIsEnabled(GL_ALPHA_TEST));
            assertTrue(glIsEnabled(GL_SCISSOR_TEST));
            assertTrue(glIsEnabled(GL_DEPTH_TEST));
            assertEquals(0, glGetInteger(GL_CURRENT_PROGRAM));
            IntBuffer values = BufferUtils.createIntBuffer(16);
            glGetInteger(GL_VIEWPORT, values);
            assertEquals(3, values.get(0));
            assertEquals(4, values.get(1));
            assertEquals(50, values.get(2));
            assertEquals(60, values.get(3));
            assertPixel(23, 128 - 36 - 1, 255, 255, 255);
            assertPixel(20, 128 - 36 - 1, 255, 255, 255);
            assertPixel(80, 80, 64, 128, 191);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            if (renderer != null) {
                renderer.close();
            }
            if (caps != null) {
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i] != null) fields[i].setBoolean(caps, original[i]);
                }
            }
            buffer.destroy();
            restoreProperty("coloryrasm.cursor.texture", oldTexture);
            restoreProperty("coloryrasm.cursor.hotspotX", oldX);
            restoreProperty("coloryrasm.cursor.hotspotY", oldY);
            Files.deleteIfExists(texture);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void assertPixel(int x, int y, int r, int g, int b) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        assertEquals(r, pixel.get(0) & 255, 1);
        assertEquals(g, pixel.get(1) & 255, 1);
        assertEquals(b, pixel.get(2) & 255, 1);
    }
}
