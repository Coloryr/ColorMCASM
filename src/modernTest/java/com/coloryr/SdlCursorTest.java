package com.coloryr;

import com.coloryr.asms.CursorAsm;
import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.*;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GL11.*;

class SdlCursorTest {
    @Test
    void transformsRealSdlAndGlfwVulkanBindings() throws Exception {
        String[] names = {"org/lwjgl/sdl/SDLVideo", "org/lwjgl/sdl/SDLMouse",
                "org/lwjgl/sdl/SDLVulkan", "org/lwjgl/glfw/GLFWVulkan"};
        String[] methods = {"SDL_GL_SwapWindow", "SDL_ShowCursor", "nSDL_Vulkan_CreateSurface", "nglfwCreateWindowSurface"};
        String[] hooks = {"beforeSwap", "visibility", "surfaceCreated", "surfaceCreated"};
        for (int i = 0; i < names.length; i++) {
            try (InputStream input = getClass().getResourceAsStream("/" + names[i] + ".class")) {
                assertNotNull(input);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                byte[] transformed = new CursorAsm().transform(null, names[i], null, null, output.toByteArray());
                StringWriter errors = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(transformed), false, new PrintWriter(errors));
                assertEquals("", errors.toString());
                ClassNode node = new ClassNode();
                new ClassReader(transformed).accept(node, 0);
                int calls = 0;
                for (MethodNode method : node.methods) {
                    if (!method.name.equals(methods[i])) continue;
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals(hooks[i])) calls++;
                    }
                }
                assertTrue(calls > 0, "Hook installed in " + names[i] + "." + methods[i]);
            }
        }
    }

    @Test
    void usesSdlOpenGlContextAndRestoresNativeVisibility() throws Exception {
        assumeTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL video is unavailable");
        long window = 0, context = 0;
        ColoryrCursorRenderer renderer = null;
        try {
            window = SDLVideo.SDL_CreateWindow("SDL cursor test", 128, 128,
                    SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
            assumeTrue(window != 0, "SDL OpenGL window is unavailable");
            context = SDLVideo.SDL_GL_CreateContext(window);
            assertNotEquals(0, context);
            assertTrue(SDLVideo.SDL_GL_MakeCurrent(window, context));
            GL.createCapabilities();
            glClearColor(0.25f, 0.5f, 0.75f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            renderer = new ColoryrCursorRenderer(SDLVideo.class.getClassLoader(), true);
            renderer.draw(128, 128, 20, 30, 24, 24);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer pixel = stack.malloc(4);
                glReadPixels(23, 128 - 36 - 1, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                assertEquals(255, pixel.get(0) & 255);
                assertEquals(GL_NO_ERROR, glGetError());
            }
            assertTrue(SDLMouse.SDL_ShowCursor());
            ColoryrSdlCursor.visibility(true, true);
            // A hidden window has no mouse focus; this still resolves all SDL APIs.
            assertNull(ColoryrSdlCursor.frame(SDLVideo.class.getClassLoader(), window, 128, 128));
            ColoryrSdlCursor.activate(window);
            assertFalse(SDLMouse.SDL_CursorVisible());
            ColoryrSdlCursor.restore(window);
            assertTrue(SDLMouse.SDL_CursorVisible());
            ColoryrSdlCursor.activate(window);
            assertTrue(SDLMouse.SDL_HideCursor());
            ColoryrSdlCursor.visibility(true, false);
            ColoryrSdlCursor.restore(window);
            assertFalse(SDLMouse.SDL_CursorVisible(), "Restore the game's requested hidden cursor");
        } finally {
            if (renderer != null) renderer.close();
            if (window != 0) ColoryrSdlCursor.destroy(SDLVideo.class, window);
            GL.setCapabilities(null);
            if (context != 0) SDLVideo.SDL_GL_DestroyContext(context);
            if (window != 0) SDLVideo.SDL_DestroyWindow(window);
            SDLInit.SDL_Quit();
        }
    }
}
