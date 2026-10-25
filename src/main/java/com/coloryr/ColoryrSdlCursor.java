package com.coloryr;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/** SDL 3 is loaded reflectively so Java 8 / GLFW games do not need it. */
public final class ColoryrSdlCursor {
    private static final Map<Long, ColoryrCursorRenderer> RENDERERS = new HashMap<>();
    private static final Map<Long, Boolean> FAILED = new HashMap<>();
    private static Method focus, position, relative, windowSize, pixelSize, hide, show, visible, current;
    private static boolean requestedVisible;
    private static long activeWindow;
    private static boolean settingVisibility;
    private static final IntBuffer WIDTH = ints(), HEIGHT = ints(), FB_WIDTH = ints(), FB_HEIGHT = ints();
    private static final FloatBuffer X = floats(), Y = floats();

    private ColoryrSdlCursor() {
    }

    private static void init(ClassLoader loader) throws Exception {
        if (focus != null) {
            return;
        }
        Class<?> mouse = Class.forName("org.lwjgl.sdl.SDLMouse", false, loader);
        Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo", false, loader);
        position = mouse.getMethod("SDL_GetMouseState", FloatBuffer.class, FloatBuffer.class);
        relative = mouse.getMethod("SDL_GetWindowRelativeMouseMode", long.class);
        windowSize = video.getMethod("SDL_GetWindowSize", long.class, IntBuffer.class, IntBuffer.class);
        pixelSize = video.getMethod("SDL_GetWindowSizeInPixels", long.class, IntBuffer.class, IntBuffer.class);
        current = video.getMethod("SDL_GL_GetCurrentWindow");
        hide = mouse.getMethod("SDL_HideCursor");
        show = mouse.getMethod("SDL_ShowCursor");
        visible = mouse.getMethod("SDL_CursorVisible");
        requestedVisible = (Boolean) visible.invoke(null);
        focus = mouse.getMethod("SDL_GetMouseFocus");
    }

    public static void visibility(boolean result, boolean shown) {
        if (!ColoryrCursor.ENABLED || !result || settingVisibility) {
            return;
        }
        requestedVisible = shown;
        if (shown && activeWindow != 0) {
            try {
                setVisible(false);
            } catch (Exception e) {
                fail(e);
            }
        }
    }

    static ColoryrCursorFrame frame(ClassLoader loader, long window, int width, int height) throws Exception {
        init(loader);
        if (!requestedVisible || (Boolean) relative.invoke(null, window)
                || (Long) focus.invoke(null) != window) {
            return null;
        }
        if (!(Boolean) windowSize.invoke(null, window, WIDTH, HEIGHT)) {
            return null;
        }
        int w = WIDTH.get(0), h = HEIGHT.get(0);
        position.invoke(null, X, Y);
        float x = X.get(0), y = Y.get(0);
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x >= w || y >= h) {
            return null;
        }
        return new ColoryrCursorFrame(x * width / w, y * height / h,
                ColoryrCursor.SIZE * width / w, ColoryrCursor.SIZE * height / h);
    }

    static void activate(long window) throws Exception {
        setVisible(false);
        activeWindow = window;
    }

    static void restore(long window) {
        if (activeWindow == window && window != 0) {
            activeWindow = 0;
            try {
                setVisible(requestedVisible);
            } catch (Exception e) {
                fail(e);
            }
        }
    }

    public static void beforeSwap(Class<?> sdl, long window) {
        if (!ColoryrCursor.ENABLED || FAILED.containsKey(window)) {
            return;
        }
        try {
            init(sdl.getClassLoader());
            if ((Long) current.invoke(null) != window
                    || !(Boolean) pixelSize.invoke(null, window, FB_WIDTH, FB_HEIGHT)) {
                return;
            }
            int w = FB_WIDTH.get(0), h = FB_HEIGHT.get(0);
            if (w <= 0 || h <= 0) {
                return;
            }
            ColoryrCursorFrame frame = frame(sdl.getClassLoader(), window, w, h);
            if (frame == null) {
                restore(window);
                return;
            }
            ColoryrCursorRenderer renderer = RENDERERS.get(window);
            if (renderer == null) {
                renderer = new ColoryrCursorRenderer(sdl.getClassLoader(), true);
                RENDERERS.put(window, renderer);
            }
            renderer.draw(w, h, frame.x, frame.y, frame.sizeX, frame.sizeY);
            activate(window);
        } catch (Throwable e) {
            FAILED.put(window, true);
            restore(window);
            fail(e);
        }
    }

    public static void destroy(Class<?> sdl, long window) {
        ColoryrCursorRenderer renderer = RENDERERS.remove(window);
        FAILED.remove(window);
        restore(window);
        if (renderer != null) {
            try {
                if ((Long) current.invoke(null) == window) {
                    renderer.close();
                }
            } catch (Throwable e) {
                fail(e);
            }
        }
    }

    private static void setVisible(boolean value) throws Exception {
        settingVisibility = true;
        try {
            if (!(Boolean) (value ? show : hide).invoke(null)) {
                throw new IllegalStateException("SDL cursor visibility update failed");
            }
        } finally {
            settingVisibility = false;
        }
    }

    private static IntBuffer ints() {
        return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    private static FloatBuffer floats() {
        return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static void fail(Throwable error) {
        System.out.println("[ColoryrASM] SDL software cursor: " + error);
    }
}
