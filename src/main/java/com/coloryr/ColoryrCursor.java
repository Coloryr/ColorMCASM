package com.coloryr;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Software cursor lifecycle. All window/GL calls run on the game's render thread.
 * No Minecraft classes, native libraries or mod loader are bundled with the agent.
 */
public final class ColoryrCursor {
    private static final int GLFW_CURSOR = 0x00033001;
    private static final int GLFW_CURSOR_NORMAL = 0x00034001;
    private static final int GLFW_CURSOR_HIDDEN = 0x00034002;
    private static final int GLFW_CURSOR_DISABLED = 0x00034003;
    static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("coloryrasm.cursor.enabled", "true"));
    static final float SIZE = cursorSize();
    private static final Map<Long, GlfwCursor> WINDOWS = new ConcurrentHashMap<>();
    private static DisplayCursor displayCursor;

    private ColoryrCursor() {
    }

    /** Called after the existing grab notification, before GLFW applies the mode. */
    public static int cursorMode(long window, int mode, int value) {
        if (!ENABLED || mode != GLFW_CURSOR || window == 0) {
            return value;
        }
        GlfwCursor cursor = window(window);
        if (!cursor.settingMode) {
            cursor.requestedMode = value;
            if ((cursor.renderer != null || cursor.vulkanReady) && !cursor.failed && value == GLFW_CURSOR_NORMAL) {
                return GLFW_CURSOR_HIDDEN;
            }
        }
        return value;
    }

    public static void beforeGlfwSwap(Class<?> glfw, long handle) {
        if (!ENABLED || handle == 0) {
            return;
        }
        GlfwCursor cursor = window(handle);
        if (cursor.failed) {
            return;
        }
        try {
            cursor.init(glfw);
            if ((Long) cursor.currentContext.invoke(null) != handle) {
                return;
            }
            int mode = (Integer) cursor.getInputMode.invoke(null, handle, GLFW_CURSOR);
            if (cursor.requestedMode == -1) {
                cursor.requestedMode = mode;
            }
            if (cursor.requestedMode != GLFW_CURSOR_NORMAL || mode == GLFW_CURSOR_DISABLED) {
                return;
            }
            cursor.getWindowSize.invoke(null, handle, cursor.width, cursor.height);
            int width = cursor.width.get(0);
            int height = cursor.height.get(0);
            cursor.getFramebufferSize.invoke(null, handle, cursor.fbWidth, cursor.fbHeight);
            int fbWidth = cursor.fbWidth.get(0);
            int fbHeight = cursor.fbHeight.get(0);
            if (width <= 0 || height <= 0 || fbWidth <= 0 || fbHeight <= 0) {
                return;
            }
            cursor.getCursorPos.invoke(null, handle, cursor.x, cursor.y);
            double x = cursor.x.get(0);
            double y = cursor.y.get(0);
            if (cursor.renderer == null) {
                cursor.renderer = new ColoryrCursorRenderer(glfw.getClassLoader(), true);
            }
            // HIDDEN keeps absolute menu input; DISABLED would change it to camera input.
            if (mode != GLFW_CURSOR_HIDDEN) {
                cursor.setMode(handle, GLFW_CURSOR_HIDDEN);
            }
            if (x >= 0 && y >= 0 && x < width && y < height) {
                cursor.renderer.draw(fbWidth, fbHeight, x * fbWidth / width, y * fbHeight / height,
                        SIZE * fbWidth / width, SIZE * fbHeight / height);
            }
        } catch (Throwable e) {
            cursor.failed = true;
            try {
                cursor.setMode(handle, cursor.requestedMode);
            } catch (Throwable ignored) {
            }
            report(e);
        }
    }

    public static void beforeDisplaySwap(Class<?> display) {
        if (!ENABLED) {
            return;
        }
        if (displayCursor == null) {
            displayCursor = new DisplayCursor();
        }
        DisplayCursor cursor = displayCursor;
        if (cursor.failed) {
            return;
        }
        try {
            cursor.init(display);
            if (!cursor.api.isCreated()) {
                return;
            }
            if (cursor.api.isGrabbed()) {
                cursor.restore();
                return;
            }
            int width = cursor.api.getWidth();
            int height = cursor.api.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            if (cursor.renderer == null) {
                cursor.renderer = new ColoryrCursorRenderer(display.getClassLoader(), false);
            }
            cursor.hide();
            if (cursor.api.isInsideWindow()) {
                int x = cursor.api.getX();
                int y = height - 1 - cursor.api.getY();
                if (x >= 0 && y >= 0 && x < width && y < height) {
                    cursor.renderer.draw(width, height, x, y, SIZE, SIZE);
                }
            }
        } catch (Throwable e) {
            cursor.failed = true;
            try {
                cursor.restore();
            } catch (Throwable ignored) {
            }
            report(e);
        }
    }

    public static void destroyGlfw(Class<?> glfw, long handle) {
        GlfwCursor cursor = WINDOWS.remove(handle);
        if (cursor == null) {
            return;
        }
        try {
            if (cursor.renderer != null && (Long) cursor.currentContext.invoke(null) == handle) {
                cursor.renderer.close();
            }
        } catch (Throwable e) {
            report(e);
        }
    }

    static ColoryrCursorFrame glfwFrame(ClassLoader loader, long handle, int fbWidth, int fbHeight)
            throws Exception {
        GlfwCursor cursor = window(handle);
        if (cursor.failed) {
            return null;
        }
        cursor.init(Class.forName("org.lwjgl.glfw.GLFW", false, loader));
        int mode = (Integer) cursor.getInputMode.invoke(null, handle, GLFW_CURSOR);
        if (cursor.requestedMode == -1) {
            cursor.requestedMode = mode;
        }
        if (cursor.requestedMode != GLFW_CURSOR_NORMAL || mode == GLFW_CURSOR_DISABLED) {
            return null;
        }
        cursor.getWindowSize.invoke(null, handle, cursor.width, cursor.height);
        int width = cursor.width.get(0), height = cursor.height.get(0);
        cursor.getCursorPos.invoke(null, handle, cursor.x, cursor.y);
        double x = cursor.x.get(0), y = cursor.y.get(0);
        if (width <= 0 || height <= 0 || x < 0 || y < 0 || x >= width || y >= height) {
            return null;
        }
        return new ColoryrCursorFrame(x * fbWidth / width, y * fbHeight / height,
                SIZE * fbWidth / width, SIZE * fbHeight / height);
    }

    static void activateGlfw(long handle) throws Exception {
        GlfwCursor cursor = window(handle);
        cursor.setMode(handle, GLFW_CURSOR_HIDDEN);
        cursor.vulkanReady = true;
    }

    static void restoreGlfw(long handle) {
        GlfwCursor cursor = WINDOWS.get(handle);
        if (cursor != null && cursor.vulkanReady) {
            cursor.vulkanReady = false;
            try {
                cursor.setMode(handle, cursor.requestedMode);
            } catch (Exception e) {
                report(e);
            }
        }
    }

    public static void destroyDisplay(Class<?> display) {
        DisplayCursor cursor = displayCursor;
        displayCursor = null;
        if (cursor == null) {
            return;
        }
        try {
            cursor.restore();
            if (cursor.blank != null) {
                cursor.api.destroyCursor(cursor.blank);
            }
            if (cursor.renderer != null && cursor.api.isCreated()) {
                cursor.renderer.close();
            }
        } catch (Throwable e) {
            report(e);
        }
    }

    private static GlfwCursor window(long handle) {
        GlfwCursor cursor = WINDOWS.get(handle);
        if (cursor == null) {
            GlfwCursor fresh = new GlfwCursor();
            GlfwCursor previous = WINDOWS.putIfAbsent(handle, fresh);
            cursor = previous == null ? fresh : previous;
        }
        return cursor;
    }

    private static float cursorSize() {
        try {
            float size = Float.parseFloat(System.getProperty("coloryrasm.cursor.size", "24"));
            if (!Float.isNaN(size) && !Float.isInfinite(size) && size > 0) {
                return Math.max(8, Math.min(128, size));
            }
        } catch (RuntimeException ignored) {
        }
        return 24;
    }

    private static IntBuffer ints() {
        return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    private static DoubleBuffer doubles() {
        return ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
    }

    private static void report(Throwable error) {
        System.out.println("[ColoryrASM] software cursor disabled for this context: " + error);
    }

    private static final class GlfwCursor {
        int requestedMode = -1;
        boolean settingMode;
        boolean failed;
        boolean vulkanReady;
        ColoryrCursorRenderer renderer;
        Method currentContext;
        Method getInputMode;
        Method setInputMode;
        Method getWindowSize;
        Method getFramebufferSize;
        Method getCursorPos;
        final IntBuffer width = ints();
        final IntBuffer height = ints();
        final IntBuffer fbWidth = ints();
        final IntBuffer fbHeight = ints();
        final DoubleBuffer x = doubles();
        final DoubleBuffer y = doubles();

        void init(Class<?> glfw) throws Exception {
            if (currentContext != null) {
                return;
            }
            getInputMode = glfw.getMethod("glfwGetInputMode", long.class, int.class);
            setInputMode = glfw.getMethod("glfwSetInputMode", long.class, int.class, int.class);
            getWindowSize = glfw.getMethod("glfwGetWindowSize", long.class, IntBuffer.class, IntBuffer.class);
            getFramebufferSize = glfw.getMethod("glfwGetFramebufferSize", long.class, IntBuffer.class, IntBuffer.class);
            getCursorPos = glfw.getMethod("glfwGetCursorPos", long.class, DoubleBuffer.class, DoubleBuffer.class);
            currentContext = glfw.getMethod("glfwGetCurrentContext");
        }

        void setMode(long handle, int mode) throws Exception {
            if (setInputMode == null || mode == -1) {
                return;
            }
            settingMode = true;
            try {
                setInputMode.invoke(null, handle, GLFW_CURSOR, mode);
            } finally {
                settingMode = false;
            }
        }
    }

    private static final class DisplayCursor {
        boolean failed;
        ColoryrCursorRenderer renderer;
        Object blank;
        Object original;
        ColoryrLwjgl2Api api;

        void init(Class<?> display) throws Exception {
            if (api != null) {
                return;
            }
            ClassLoader loader = display.getClassLoader();
            Class<?> mouse = Class.forName("org.lwjgl.input.Mouse", false, loader);
            Class<?> cursor = Class.forName("org.lwjgl.input.Cursor", false, loader);
            ColoryrCursorBindings bindings = new ColoryrCursorBindings();
            bindings.bind("getWidth", display, "getWidth");
            bindings.bind("getHeight", display, "getHeight");
            bindings.bind("getX", mouse, "getX");
            bindings.bind("getY", mouse, "getY");
            bindings.bind("isGrabbed", mouse, "isGrabbed");
            bindings.bind("isInsideWindow", mouse, "isInsideWindow");
            bindings.bind("isCreated", mouse, "isCreated");
            bindings.bind("getNativeCursor", mouse, "getNativeCursor");
            bindings.bind("setNativeCursor", mouse, "setNativeCursor", cursor);
            bindings.bind("minCursorSize", cursor, "getMinCursorSize");
            bindings.constructor("createCursor", cursor, int.class, int.class, int.class, int.class,
                    int.class, IntBuffer.class, IntBuffer.class);
            bindings.bind("destroyCursor", cursor, "destroy");
            api = bindings.create(ColoryrLwjgl2Api.class, loader);
        }

        void hide() {
            if (blank == null) {
                int side = api.minCursorSize();
                IntBuffer pixels = ByteBuffer.allocateDirect(side * side * 4)
                        .order(ByteOrder.nativeOrder()).asIntBuffer();
                blank = api.createCursor(side, side, 0, 0, 1, pixels, null);
            }
            Object current = api.getNativeCursor();
            if (current != blank) {
                original = current;
                api.setNativeCursor(blank);
            }
        }

        void restore() {
            if (blank != null && api.isCreated() && api.getNativeCursor() == blank) {
                api.setNativeCursor(original);
            }
        }
    }
}
