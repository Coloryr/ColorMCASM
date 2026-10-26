package com.coloryr;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

class CursorRendererTest {
    @Test
    void uploadsTransparentPngAndRestoresTexturesUnpackAndBlendState() throws Exception {
        assumeTrue(glfwInit(), "GLFW is unavailable on this host");
        Path texture = Files.createTempFile("cursor-rgba", ".png");
        long window = 0;
        ColoryrCursorRenderer renderer = null;
        String oldTexture = System.getProperty("coloryrasm.cursor.texture");
        String oldX = System.getProperty("coloryrasm.cursor.hotspotX");
        String oldY = System.getProperty("coloryrasm.cursor.hotspotY");
        try {
            BufferedImage png = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
            png.setRGB(0, 0, 0x80FF0000);
            png.setRGB(3, 3, 0xFF00FF00);
            ImageIO.write(png, "png", texture.toFile());
            System.setProperty("coloryrasm.cursor.texture", texture.toString());
            System.setProperty("coloryrasm.cursor.hotspotX", "1");
            System.setProperty("coloryrasm.cursor.hotspotY", "2");
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(128, 128, "PNG cursor test", 0, 0);
            assumeTrue(window != 0, "OpenGL 3.3 core is unavailable");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glClearColor(0.25f, 0.5f, 0.75f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            int texture0 = glGenTextures(), texture3 = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture0);
            int sampler = GL33.glGenSamplers();
            GL33.glBindSampler(0, sampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, texture3);
            int pbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 4096, GL15.GL_STREAM_DRAW);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 8);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 27);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 3);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 5);
            glBlendFunc(GL_ONE, GL_ZERO);
            glBlendEquationSeparate(GL_FUNC_REVERSE_SUBTRACT, GL_FUNC_SUBTRACT);
            if (GL.getCapabilities().OpenGL40) {
                GL40.glBlendFuncSeparatei(1, GL_DST_COLOR, GL_SRC_COLOR, GL_ZERO, GL_ONE);
                GL40.glBlendEquationSeparatei(1, GL_FUNC_SUBTRACT, GL_FUNC_REVERSE_SUBTRACT);
            }

            renderer = new ColoryrCursorRenderer(GLFW.class.getClassLoader(), true);
            assertEquals(GL13.GL_TEXTURE3, glGetInteger(GL13.GL_ACTIVE_TEXTURE));
            assertEquals(texture3, glGetInteger(GL_TEXTURE_BINDING_2D));
            assertEquals(pbo, glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING));
            assertEquals(8, glGetInteger(GL_UNPACK_ALIGNMENT));
            assertEquals(27, glGetInteger(GL_UNPACK_ROW_LENGTH));
            assertEquals(3, glGetInteger(GL_UNPACK_SKIP_ROWS));
            assertEquals(5, glGetInteger(GL_UNPACK_SKIP_PIXELS));
            renderer.draw(128, 128, 40, 40, 16, 16);
            assertEquals(GL_NO_ERROR, glGetError());
            assertEquals(GL13.GL_TEXTURE3, glGetInteger(GL13.GL_ACTIVE_TEXTURE));
            assertEquals(texture3, glGetInteger(GL_TEXTURE_BINDING_2D));
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            assertEquals(texture0, glGetInteger(GL_TEXTURE_BINDING_2D));
            assertEquals(sampler, glGetInteger(GL33.GL_SAMPLER_BINDING));
            assertEquals(GL_ONE, glGetInteger(GL_BLEND_SRC_RGB));
            assertEquals(GL_ZERO, glGetInteger(GL_BLEND_DST_RGB));
            assertEquals(GL_FUNC_REVERSE_SUBTRACT, glGetInteger(GL_BLEND_EQUATION_RGB));
            assertEquals(GL_FUNC_SUBTRACT, glGetInteger(GL_BLEND_EQUATION_ALPHA));
            if (GL.getCapabilities().OpenGL40) {
                assertEquals(GL_DST_COLOR, GL30.glGetIntegeri(GL_BLEND_SRC_RGB, 1));
                assertEquals(GL_SRC_COLOR, GL30.glGetIntegeri(GL_BLEND_DST_RGB, 1));
                assertEquals(GL_FUNC_SUBTRACT, GL30.glGetIntegeri(GL_BLEND_EQUATION_RGB, 1));
            }
            assertPixel(37, 128 - 33 - 1, 160, 64, 95);
            assertPixel(49, 128 - 45 - 1, 0, 255, 0);
            assertPixel(45, 128 - 33 - 1, 64, 128, 191);
            assertPixel(80, 80, 64, 128, 191);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15.glDeleteBuffers(pbo);
            GL33.glDeleteSamplers(sampler);
            glDeleteTextures(texture0);
            glDeleteTextures(texture3);
        } finally {
            if (renderer != null) renderer.close();
            if (window != 0) {
                GL.setCapabilities(null);
                glfwDestroyWindow(window);
            }
            glfwTerminate();
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

    @Test
    void rendersOnCoreContextAndRestoresGameState() throws Exception {
        assumeTrue(glfwInit(), "GLFW is unavailable on this host");
        long window = 0;
        ColoryrCursorRenderer renderer = null;
        Path texture = Files.createTempFile("cursor-white", ".png");
        String oldTexture = System.getProperty("coloryrasm.cursor.texture");
        String oldX = System.getProperty("coloryrasm.cursor.hotspotX");
        String oldY = System.getProperty("coloryrasm.cursor.hotspotY");
        BufferedImage png = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        png.setRGB(0, 0, 0xFFFFFFFF);
        ImageIO.write(png, "png", texture.toFile());
        System.setProperty("coloryrasm.cursor.texture", texture.toString());
        System.setProperty("coloryrasm.cursor.hotspotX", "0");
        System.setProperty("coloryrasm.cursor.hotspotY", "0");
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            window = glfwCreateWindow(128, 128, "Cursor render test", 0, 0);
            assumeTrue(window != 0, "OpenGL 3.2 core is unavailable");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            renderer = new ColoryrCursorRenderer(GLFW.class.getClassLoader(), true);
            glClearColor(0.25f, 0.5f, 0.75f, 1);
            glClear(GL_COLOR_BUFFER_BIT);

            int vao = glGenVertexArrays();
            int framebuffer = glGenFramebuffers();
            glBindVertexArray(vao);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glViewport(3, 4, 50, 60);
            glScissor(0, 0, 1, 1);
            glEnable(GL_SCISSOR_TEST);
            glEnable(GL_BLEND);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);
            glEnable(GL_RASTERIZER_DISCARD);
            glColorMask(false, true, false, false);
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            int clipOrigin = 0;
            int clipDepth = 0;
            if (GL.getCapabilities().OpenGL45) {
                GL45.glClipControl(GL45.GL_UPPER_LEFT, GL45.GL_ZERO_TO_ONE);
                clipOrigin = GL45.GL_UPPER_LEFT;
                clipDepth = GL45.GL_ZERO_TO_ONE;
            }

            renderer.draw(128, 128, 20, 30, 24, 24);
            assertEquals(GL_NO_ERROR, glGetError());
            assertEquals(vao, glGetInteger(GL_VERTEX_ARRAY_BINDING));
            assertEquals(framebuffer, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
            assertEquals(0, glGetInteger(GL_CURRENT_PROGRAM));
            assertTrue(glIsEnabled(GL_SCISSOR_TEST));
            assertTrue(glIsEnabled(GL_BLEND));
            assertTrue(glIsEnabled(GL_DEPTH_TEST));
            assertTrue(glIsEnabled(GL_CULL_FACE));
            assertTrue(glIsEnabled(GL_RASTERIZER_DISCARD));
            IntBuffer values = MemoryUtil.memAllocInt(4);
            try {
                glGetIntegerv(GL_VIEWPORT, values);
                assertArrayEquals(new int[]{3, 4, 50, 60}, new int[]{
                        values.get(0), values.get(1), values.get(2), values.get(3)});
                glGetIntegerv(GL_COLOR_WRITEMASK, values);
                assertArrayEquals(new int[]{0, 1, 0, 0}, new int[]{
                        values.get(0), values.get(1), values.get(2), values.get(3)});
                glGetIntegerv(GL_POLYGON_MODE, values);
                assertEquals(GL_LINE, values.get(0));
            } finally {
                MemoryUtil.memFree(values);
            }
            if (clipOrigin != 0) {
                assertEquals(clipOrigin, glGetInteger(GL45.GL_CLIP_ORIGIN));
                assertEquals(clipDepth, glGetInteger(GL45.GL_CLIP_DEPTH_MODE));
            }
            assertPixel(23, 128 - 36 - 1, 255, 255, 255);
            assertPixel(20, 128 - 36 - 1, 255, 255, 255);
            assertPixel(80, 80, 64, 128, 191);

            // Exercise the window bridge and its NORMAL -> HIDDEN -> DISABLED -> NORMAL lifecycle.
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glDisable(GL_RASTERIZER_DISCARD);
            glfwSetCursorPos(window, 20, 30);
            ColoryrCursor.beforeGlfwSwap(GLFW.class, window);
            assertEquals(GLFW_CURSOR_HIDDEN, glfwGetInputMode(window, GLFW_CURSOR));
            assertEquals(GLFW_CURSOR_DISABLED,
                    ColoryrCursor.cursorMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED));
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            ColoryrCursor.beforeGlfwSwap(GLFW.class, window);
            assertEquals(GLFW_CURSOR_DISABLED, glfwGetInputMode(window, GLFW_CURSOR));
            assertEquals(GLFW_CURSOR_HIDDEN,
                    ColoryrCursor.cursorMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL));
            ColoryrCursor.destroyGlfw(GLFW.class, window);

            glBindVertexArray(0);
            glDeleteVertexArrays(vao);
            glDeleteFramebuffers(framebuffer);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            if (renderer != null) {
                renderer.close();
            }
            if (window != 0) {
                GL.setCapabilities(null);
                glfwDestroyWindow(window);
            }
            glfwTerminate();
            restoreProperty("coloryrasm.cursor.texture", oldTexture);
            restoreProperty("coloryrasm.cursor.hotspotX", oldX);
            restoreProperty("coloryrasm.cursor.hotspotY", oldY);
            Files.deleteIfExists(texture);
        }
    }

    private static void assertPixel(int x, int y, int r, int g, int b) {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        try {
            GL11.glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            assertEquals(r, pixel.get(0) & 255, 1);
            assertEquals(g, pixel.get(1) & 255, 1);
            assertEquals(b, pixel.get(2) & 255, 1);
        } finally {
            MemoryUtil.memFree(pixel);
        }
    }
}
