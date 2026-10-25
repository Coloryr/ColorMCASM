package com.coloryr;

import java.lang.reflect.Method;

/**
 * LWJGL3 输入注入
 *
 * 游戏的按键 / 鼠标按键 / 滚轮 / 光标回调都注册在 GLFW 上，
 * 这里在 `glfwSet*Callback` 被调用时把回调对象留下，帧轮询时再代为调用，
 * 效果与真实设备事件一致，也不依赖具体 Minecraft 版本。
 *
 * 鼠标移动分两种模式，由游戏是否抓取鼠标决定：
 * - 抓取时把移动量累加到视角，坐标偏移靠 {@link ColoryrGlfwCursor} 遮蔽；
 * - 未抓取时直接移动系统光标，游戏从光标回调收到新的绝对坐标。
 *
 * 运行时没有函数式回调接口的极老版本会放弃抓取模式的鼠标移动注入，
 * 未抓取时的光标移动仍然可用。
 */
public final class ColoryrGlfw {
    /** 光标回调包装类的类名，用来识别已经包装过的回调 */
    static final String CURSOR_WRAPPER = "com.coloryr.ColoryrGlfwCursor";

    /** 已捕获的回调 */
    private static final class CallbackRef {
        final long window;
        final Object target;
        final Method invoke;

        CallbackRef(long window, Object target, Method invoke) {
            this.window = window;
            this.target = target;
            this.invoke = invoke;
        }
    }

    private static volatile CallbackRef keyRef;
    private static volatile CallbackRef buttonRef;
    private static volatile CallbackRef scrollRef;
    private static volatile CallbackRef cursorRef;

    /** 鼠标偏移量，游戏看到的坐标等于 GLFW 坐标加上该偏移 */
    private static double offsetX;
    private static double offsetY;
    /** 交给游戏光标的绝对坐标 */
    private static double virtualX;
    private static double virtualY;
    /** 是否收到过真实光标事件，没有时无法确定游戏当前的光标坐标 */
    private static boolean hasReal;
    /** 上一帧的抓取状态，切换时清掉偏移 */
    private static boolean lastGrabbed;

    /** 光标回调所属的窗口，光标模式下用来移动系统光标 */
    private static volatile long cursorWindow;

    /** GLFW 光标位置的读写方法，只在光标模式下使用 */
    private static Method glfwGetCursorPos;
    private static Method glfwSetCursorPos;
    private static Method glfwGetWindowSize;
    private static Method glfwGetWindowAttrib;
    private static boolean glfwApiFailed;

    /** GLFW_FOCUSED 的常量值 */
    private static final int GLFW_FOCUSED = 0x00020001;
    private static final double[] CURSOR_X = new double[1];
    private static final double[] CURSOR_Y = new double[1];
    private static final int[] WINDOW_WIDTH = new int[1];
    private static final int[] WINDOW_HEIGHT = new int[1];

    private static boolean errorPrinted;

    private ColoryrGlfw() {
    }

    /** 留下游戏注册的键盘回调 */
    public static void captureKeyCallback(long window, Object callback) {
        keyRef = wrap(window, callback, long.class, int.class, int.class, int.class, int.class);
    }

    /** 留下游戏注册的鼠标按键回调 */
    public static void captureMouseButtonCallback(long window, Object callback) {
        buttonRef = wrap(window, callback, long.class, int.class, int.class, int.class);
    }

    /** 留下游戏注册的滚轮回调 */
    public static void captureScrollCallback(long window, Object callback) {
        scrollRef = wrap(window, callback, long.class, double.class, double.class);
    }

    /**
     * 接管游戏注册的光标回调
     *
     * - `window`: 回调所属的 GLFW 窗口
     * - `callback`: 游戏注册的光标回调
     *
     * # 返回值
     *
     * 有函数式回调接口时返回包装后的回调，没有时原样返回；
     * 原样返回时抓取模式的鼠标移动注入不可用，未抓取时的光标移动不受影响
     */
    public static Object wrapCursorCallback(long window, Object callback) {
        try {
            cursorWindow = window;
            if (callback == null) {
                cursorRef = null;
                hasReal = false;
                offsetX = 0;
                offsetY = 0;
                return null;
            }
            if (CURSOR_WRAPPER.equals(callback.getClass().getName())) {
                return callback;
            }
            initGlfwApi(callback.getClass().getClassLoader());

            CallbackRef ref = wrap(window, callback, long.class, double.class, double.class);
            if (ref == null) {
                return callback;
            }
            cursorRef = ref;
            hasReal = false;
            offsetX = 0;
            offsetY = 0;

            // 更老的 LWJGL 3 可能没有函数式回调接口，没有时只能原样返回
            Class<?> callbackType = Class.forName("org.lwjgl.glfw.GLFWCursorPosCallbackI", false,
                    callback.getClass().getClassLoader());
            Object wrapper = Class.forName(CURSOR_WRAPPER).getMethod("create").invoke(null);
            // 接口必须由同一个类加载器解析，否则 CHECKCAST 会失败
            return callbackType.isInstance(wrapper) ? wrapper : callback;
        } catch (Throwable e) {
            printError("cursor callback", e);
            return callback;
        }
    }

    /** 游戏主线程每帧调用一次，把队列里的事件交给游戏 */
    public static void dispatch() {
        try {
            boolean grabbed = ColoryrInput.isGrabbed();
            if (grabbed != lastGrabbed) {
                lastGrabbed = grabbed;
                offsetX = 0;
                offsetY = 0;
            }

            ColoryrInput.InputEvent event;
            while ((event = ColoryrInput.pollKey()) != null) {
                CallbackRef ref = keyRef;
                if (ref != null) {
                    call(ref, ref.window, event.key, 0, event.down ? 1 : 0, 0);
                }
            }

            // 光标模式下的移动先攒着，一次帧内只移动一次系统光标
            double cursorX = 0.0D;
            double cursorY = 0.0D;
            boolean cursorMoved = false;

            while ((event = ColoryrInput.pollMouse()) != null) {
                switch (event.type) {
                    case ColoryrInput.EVENT_MOUSE_BUTTON:
                        if (cursorMoved) {
                            moveSystemCursor(cursorWindow, cursorX, cursorY);
                            cursorX = 0.0D;
                            cursorY = 0.0D;
                            cursorMoved = false;
                        }
                        CallbackRef button = buttonRef;
                        if (button != null) {
                            call(button, button.window, event.key, event.down ? 1 : 0, 0);
                        }
                        break;
                    case ColoryrInput.EVENT_SCROLL:
                        if (cursorMoved) {
                            moveSystemCursor(cursorWindow, cursorX, cursorY);
                            cursorX = 0.0D;
                            cursorY = 0.0D;
                            cursorMoved = false;
                        }
                        CallbackRef scroll = scrollRef;
                        if (scroll != null) {
                            call(scroll, scroll.window, 0.0D, (double) event.amount);
                        }
                        break;
                    case ColoryrInput.EVENT_MOUSE_MOVE:
                        if (grabbed) {
                            // 抓取时按视角转动处理，坐标偏移由包装回调统一补偿
                            if (!hasReal || cursorRef == null) {
                                break;
                            }
                            offsetX += event.x;
                            offsetY += event.y;
                            virtualX += event.x;
                            virtualY += event.y;
                            call(cursorRef, cursorRef.window, virtualX, virtualY);
                        } else {
                            // 未抓取时按光标模式处理，移动的是系统光标
                            cursorX += event.x;
                            cursorY += event.y;
                            cursorMoved = true;
                        }
                        break;
                    default:
                        break;
                }
            }

            if (cursorMoved) {
                moveSystemCursor(cursorWindow, cursorX, cursorY);
            }
        } catch (Throwable e) {
            printError("dispatch", e);
        }
    }

    /**
     * 真实光标事件，由 {@link ColoryrGlfwCursor} 转发
     *
     * 把 GLFW 坐标加上注入累积的偏移再交给游戏，这样真实移动和
     * 注入移动在游戏那边是连续的，不会互相抵消。
     *
     * - `window`: 事件所属的 GLFW 窗口
     * - `x`: GLFW 给出的绝对坐标
     * - `y`: GLFW 给出的绝对坐标
     */
    static void onRealCursor(long window, double x, double y) {
        CallbackRef ref = cursorRef;
        if (ref == null) {
            return;
        }
        virtualX = x + offsetX;
        virtualY = y + offsetY;
        hasReal = true;
        call(ref, window, virtualX, virtualY);
    }

    /** 解析 GLFW 的光标位置方法，光标模式下需要直接移动系统光标 */
    private static void initGlfwApi(ClassLoader loader) {
        if (glfwGetCursorPos != null || glfwApiFailed || loader == null) {
            return;
        }
        try {
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", false, loader);
            glfwGetCursorPos = glfw.getMethod("glfwGetCursorPos", long.class, double[].class, double[].class);
            glfwSetCursorPos = glfw.getMethod("glfwSetCursorPos", long.class, double.class, double.class);
            glfwGetWindowSize = glfw.getMethod("glfwGetWindowSize", long.class, int[].class, int[].class);
            glfwGetWindowAttrib = glfw.getMethod("glfwGetWindowAttrib", long.class, int.class);
        } catch (Throwable e) {
            glfwApiFailed = true;
            printError("glfw cursor api", e);
        }
    }

    /**
     * 光标模式下把系统光标移动一段相对位移
     *
     * 光标移动到新位置后会由 GLFW 产生一条真实的光标事件，包装回调会把它转给游戏；
     * 这里再直接给游戏补一次，保证同一批事件里的按键落在新位置上。
     *
     * - `window`: GLFW 窗口
     * - `dx`: 水平位移，单位像素
     * - `dy`: 垂直位移，单位像素，正数向下
     */
    private static void moveSystemCursor(long window, double dx, double dy) {
        if (window == 0L || glfwGetCursorPos == null || glfwSetCursorPos == null) {
            return;
        }
        try {
            // 窗口不在前台时不挪系统光标，避免影响启动器或其它窗口
            if (glfwGetWindowAttrib != null
                    && ((Integer) glfwGetWindowAttrib.invoke(null, window, GLFW_FOCUSED)) == 0) {
                return;
            }

            glfwGetCursorPos.invoke(null, window, CURSOR_X, CURSOR_Y);
            double x = CURSOR_X[0] + dx;
            double y = CURSOR_Y[0] + dy;
            if (glfwGetWindowSize != null) {
                glfwGetWindowSize.invoke(null, window, WINDOW_WIDTH, WINDOW_HEIGHT);
                if (WINDOW_WIDTH[0] > 0) {
                    x = Math.max(0.0D, Math.min(WINDOW_WIDTH[0] - 1.0D, x));
                }
                if (WINDOW_HEIGHT[0] > 0) {
                    y = Math.max(0.0D, Math.min(WINDOW_HEIGHT[0] - 1.0D, y));
                }
            }

            glfwSetCursorPos.invoke(null, window, x, y);
            CallbackRef ref = cursorRef;
            if (ref != null) {
                virtualX = x;
                virtualY = y;
                hasReal = true;
                call(ref, window, x, y);
            }
        } catch (Throwable e) {
            printError("move cursor", e);
        }
    }

    private static CallbackRef wrap(long window, Object callback, Class<?>... params) {
        if (callback == null) {
            return null;
        }
        Method invoke = resolveInvoke(callback, params);
        if (invoke == null) {
            printError("callback " + callback.getClass().getName(), null);
            return null;
        }
        return new CallbackRef(window, callback, invoke);
    }

    private static Method resolveInvoke(Object callback, Class<?>... params) {
        Class<?> type = callback.getClass();
        for (Class<?> iface : type.getInterfaces()) {
            try {
                return accessible(iface.getMethod("invoke", params));
            } catch (Throwable ignored) {
            }
        }
        for (Class<?> parent = type; parent != null && parent != Object.class; parent = parent.getSuperclass()) {
            try {
                return accessible(parent.getMethod("invoke", params));
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method accessible(Method method) {
        try {
            method.setAccessible(true);
        } catch (Throwable ignored) {
        }
        return method;
    }

    private static void call(CallbackRef ref, Object... args) {
        try {
            ref.invoke.invoke(ref.target, args);
        } catch (Throwable e) {
            printError("invoke " + ref.invoke.getDeclaringClass().getName(), e);
        }
    }

    private static void printError(String part, Throwable e) {
        if (errorPrinted) {
            return;
        }
        errorPrinted = true;
        System.out.println("[ColoryrASM] glfw " + part + " inject failed: " + e);
    }
}
