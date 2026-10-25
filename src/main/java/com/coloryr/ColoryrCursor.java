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
            if (!(Boolean) cursor.isCreated.invoke(null)) {
                return;
            }
            if ((Boolean) cursor.isGrabbed.invoke(null)) {
                cursor.restore();
                return;
            }
            int width = (Integer) cursor.getWidth.invoke(null);
            int height = (Integer) cursor.getHeight.invoke(null);
            if (width <= 0 || height <= 0) {
                return;
            }
            if (cursor.renderer == null) {
                cursor.renderer = new ColoryrCursorRenderer(display.getClassLoader(), false);
            }
            cursor.hide();
            if ((Boolean) cursor.isInsideWindow.invoke(null)) {
                int x = (Integer) cursor.getX.invoke(null);
                int y = height - 1 - (Integer) cursor.getY.invoke(null);
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
                cursor.blank.getClass().getMethod("destroy").invoke(cursor.blank);
            }
            if (cursor.renderer != null && (Boolean) cursor.isCreated.invoke(null)) {
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
        Method getWidth;
        Method getHeight;
        Method getX;
        Method getY;
        Method isCreated;
        Method isGrabbed;
        Method isInsideWindow;
        Method getNativeCursor;
        Method setNativeCursor;
        Class<?> cursorClass;

        void init(Class<?> display) throws Exception {
            if (isCreated != null) {
                return;
            }
            ClassLoader loader = display.getClassLoader();
            Class<?> mouse = Class.forName("org.lwjgl.input.Mouse", false, loader);
            cursorClass = Class.forName("org.lwjgl.input.Cursor", false, loader);
            getWidth = display.getMethod("getWidth");
            getHeight = display.getMethod("getHeight");
            getX = mouse.getMethod("getX");
            getY = mouse.getMethod("getY");
            isGrabbed = mouse.getMethod("isGrabbed");
            isInsideWindow = mouse.getMethod("isInsideWindow");
            getNativeCursor = mouse.getMethod("getNativeCursor");
            setNativeCursor = mouse.getMethod("setNativeCursor", cursorClass);
            isCreated = mouse.getMethod("isCreated");
        }

        void hide() throws Exception {
            if (blank == null) {
                int side = (Integer) cursorClass.getMethod("getMinCursorSize").invoke(null);
                IntBuffer pixels = ByteBuffer.allocateDirect(side * side * 4)
                        .order(ByteOrder.nativeOrder()).asIntBuffer();
                blank = cursorClass.getConstructor(int.class, int.class, int.class, int.class,
                        int.class, IntBuffer.class, IntBuffer.class)
                        .newInstance(side, side, 0, 0, 1, pixels, null);
            }
            Object current = getNativeCursor.invoke(null);
            if (current != blank) {
                original = current;
                setNativeCursor.invoke(null, blank);
            }
        }

        void restore() throws Exception {
            if (blank != null && (Boolean) isCreated.invoke(null) && getNativeCursor.invoke(null) == blank) {
                setNativeCursor.invoke(null, original);
            }
        }
    }
}
