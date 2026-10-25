package com.coloryr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 输入事件队列与注入
 *
 * 启动器通过 netty 链接下发鼠标键盘事件，这里只负责入队；
 * 游戏主线程在每帧的轮询处取出并注入，避免跨线程修改游戏状态。
 * 鼠标移动按抓取状态分两种处理：抓取时转视角，未抓取时移动系统光标。
 * LWJGL3 的注入在 {@link ColoryrGlfw}，本类只处理 LWJGL2。
 */
public final class ColoryrInput {
    /** 键盘按键事件 */
    static final int EVENT_KEY = 1;
    /** 鼠标按键事件 */
    static final int EVENT_MOUSE_BUTTON = 2;
    /** 鼠标移动事件 */
    static final int EVENT_MOUSE_MOVE = 3;
    /** 鼠标滚轮事件 */
    static final int EVENT_SCROLL = 4;

    /** LWJGL2 单条键盘事件占用的字节数 */
    private static final int LWJGL2_KEY_EVENT_SIZE = 18;
    /** LWJGL2 单条鼠标事件占用的字节数 */
    private static final int LWJGL2_MOUSE_EVENT_SIZE = 22;

    /** 一条待注入的输入事件 */
    static final class InputEvent {
        /** 事件类型，取值见本类 `EVENT_*` */
        int type;
        /** 键码或鼠标按键号，含义由类型决定 */
        int key;
        /** 是否按下 */
        boolean down;
        /** 鼠标相对位移的 X 分量，单位像素 */
        double x;
        /** 鼠标相对位移的 Y 分量，单位像素 */
        double y;
        /** 滚轮格数，正数向上 */
        int amount;
    }

    /** 键盘事件队列，由 netty 线程写入、游戏主线程读取 */
    private static final Queue<InputEvent> KEY_EVENTS = new ConcurrentLinkedQueue<>();
    /** 鼠标事件队列，由 netty 线程写入、游戏主线程读取 */
    private static final Queue<InputEvent> MOUSE_EVENTS = new ConcurrentLinkedQueue<>();

    /** 游戏是否已抓取鼠标，只有抓取时鼠标移动才会改变视角 */
    private static volatile boolean grabbed;

    /** LWJGL2 合成事件的按住状态，原生轮询每帧都会覆盖，需要重新写回 */
    private static final Set<Integer> HELD_KEYS = ConcurrentHashMap.newKeySet();
    /** LWJGL2 合成鼠标按键的按住状态 */
    private static final Set<Integer> HELD_BUTTONS = ConcurrentHashMap.newKeySet();

    private static boolean keyboardReady;
    private static boolean keyboardFailed;
    private static Field keyboardReadBuffer;
    private static Field keyboardKeyDownBuffer;

    private static boolean mouseReady;
    private static boolean mouseFailed;
    private static Field mouseReadBuffer;
    private static Field mouseButtons;
    private static Field mouseDx;
    private static Field mouseDy;
    private static Method mouseGetX;
    private static Method mouseGetY;
    private static Method mouseSetCursorPosition;
    private static Method displayGetWidth;
    private static Method displayGetHeight;
    private static Method displayIsActive;

    /** 鼠标移动的不足一像素的余量，避免小位移被截断丢掉 */
    private static double mouseMoveX;
    private static double mouseMoveY;

    private static boolean keyboardErrorPrinted;
    private static boolean mouseErrorPrinted;

    private ColoryrInput() {
    }

    /** 记录一次键盘按键事件 */
    static void key(int key, boolean down) {
        InputEvent event = new InputEvent();
        event.type = EVENT_KEY;
        event.key = key;
        event.down = down;
        KEY_EVENTS.add(event);
    }

    /** 记录一次鼠标按键事件 */
    static void mouseButton(int button, boolean down) {
        InputEvent event = new InputEvent();
        event.type = EVENT_MOUSE_BUTTON;
        event.key = button;
        event.down = down;
        MOUSE_EVENTS.add(event);
    }

    /** 记录一次鼠标相对移动，单位像素 */
    static void mouseMove(double x, double y) {
        if (x == 0 && y == 0) {
            return;
        }
        InputEvent event = new InputEvent();
        event.type = EVENT_MOUSE_MOVE;
        event.x = x;
        event.y = y;
        MOUSE_EVENTS.add(event);
    }

    /** 记录一次滚轮事件，正数向上 */
    static void scroll(int amount) {
        if (amount == 0) {
            return;
        }
        InputEvent event = new InputEvent();
        event.type = EVENT_SCROLL;
        event.amount = amount;
        MOUSE_EVENTS.add(event);
    }

    /** 取出一条键盘事件，没有时返回 null */
    static InputEvent pollKey() {
        return KEY_EVENTS.poll();
    }

    /** 取出一条鼠标事件，没有时返回 null */
    static InputEvent pollMouse() {
        return MOUSE_EVENTS.poll();
    }

    /** 鼠标是否处于抓取状态 */
    static boolean isGrabbed() {
        return grabbed;
    }

    /** 更新鼠标抓取状态，由游戏的 setGrabbed 调用 */
    static void setGrabbed(boolean value) {
        if (grabbed != value) {
            mouseMoveX = 0;
            mouseMoveY = 0;
        }
        grabbed = value;
    }

    /**
     * 把队列里的键盘事件写入 LWJGL2 的事件缓冲
     *
     * 由 `org.lwjgl.input.Keyboard.poll` 末尾调用，此时原生轮询已经结束，
     * 追加的事件会在同一帧的 `next()` 中被游戏读走。
     *
     * - `keyboardClass`: 游戏实际加载的 `org.lwjgl.input.Keyboard`
     */
    public static void dispatchKeyboard(Class<?> keyboardClass) {
        try {
            if (!keyboardReady) {
                initKeyboard(keyboardClass);
            }
            if (!keyboardReady) {
                KEY_EVENTS.clear();
                return;
            }

            ByteBuffer readBuffer = (ByteBuffer) keyboardReadBuffer.get(null);
            ByteBuffer keyDownBuffer = (ByteBuffer) keyboardKeyDownBuffer.get(null);
            if (readBuffer == null || keyDownBuffer == null) {
                KEY_EVENTS.clear();
                return;
            }

            // 原生轮询会覆盖按住状态，先把合成按键写回去
            for (Integer key : HELD_KEYS) {
                if (key >= 0 && key < keyDownBuffer.capacity()) {
                    keyDownBuffer.put(key, (byte) 1);
                }
            }

            InputEvent event;
            while ((event = KEY_EVENTS.poll()) != null) {
                if (event.down) {
                    HELD_KEYS.add(event.key);
                } else {
                    HELD_KEYS.remove(event.key);
                }
                if (event.key >= 0 && event.key < keyDownBuffer.capacity()) {
                    keyDownBuffer.put(event.key, (byte) (event.down ? 1 : 0));
                }
                appendKeyEvent(readBuffer, event);
            }
        } catch (Throwable e) {
            printLwjgl2Error(true, e);
        }
    }

    /**
     * 把队列里的鼠标事件写入 LWJGL2 的事件缓冲
     *
     * 由 `org.lwjgl.input.Mouse.poll` 末尾调用。抓取状态下的移动直接累加到
     * `dx` / `dy`，游戏通过 `getDX` / `getDY` 取走；未抓取时移动系统光标。
     *
     * - `mouseClass`: 游戏实际加载的 `org.lwjgl.input.Mouse`
     */
    public static void dispatchMouse(Class<?> mouseClass) {
        try {
            if (!mouseReady) {
                initMouse(mouseClass);
            }
            if (!mouseReady) {
                MOUSE_EVENTS.clear();
                return;
            }

            ByteBuffer readBuffer = (ByteBuffer) mouseReadBuffer.get(null);
            ByteBuffer buttons = (ByteBuffer) mouseButtons.get(null);
            if (readBuffer == null || buttons == null) {
                MOUSE_EVENTS.clear();
                return;
            }

            int dx = mouseDx.getInt(null);
            int dy = mouseDy.getInt(null);
            boolean moved = false;
            int cursorStepX = 0;
            int cursorStepY = 0;
            boolean cursorMoved = false;

            // 原生轮询会覆盖按住状态，先把合成按键写回去
            for (Integer button : HELD_BUTTONS) {
                if (button >= 0 && button < buttons.capacity()) {
                    buttons.put(button, (byte) 1);
                }
            }

            InputEvent event;
            while ((event = MOUSE_EVENTS.poll()) != null) {
                switch (event.type) {
                    case EVENT_MOUSE_MOVE:
                        mouseMoveX += event.x;
                        mouseMoveY += event.y;
                        int stepX = (int) mouseMoveX;
                        int stepY = (int) mouseMoveY;
                        if (stepX == 0 && stepY == 0) {
                            break;
                        }
                        mouseMoveX -= stepX;
                        mouseMoveY -= stepY;
                        if (grabbed) {
                            // 抓取时按视角转动处理
                            dx += stepX;
                            dy += stepY;
                            moved = true;
                        } else {
                            // 未抓取时按光标模式处理
                            cursorStepX += stepX;
                            cursorStepY += stepY;
                            cursorMoved = true;
                        }
                        break;
                    case EVENT_MOUSE_BUTTON:
                        if (cursorMoved) {
                            moveCursor(cursorStepX, cursorStepY);
                            cursorStepX = 0;
                            cursorStepY = 0;
                            cursorMoved = false;
                        }
                        if (event.down) {
                            HELD_BUTTONS.add(event.key);
                        } else {
                            HELD_BUTTONS.remove(event.key);
                        }
                        if (event.key >= 0 && event.key < buttons.capacity()) {
                            buttons.put(event.key, (byte) (event.down ? 1 : 0));
                        }
                        appendMouseButtonEvent(readBuffer, event.key, event.down);
                        break;
                    case EVENT_SCROLL:
                        if (cursorMoved) {
                            moveCursor(cursorStepX, cursorStepY);
                            cursorStepX = 0;
                            cursorStepY = 0;
                            cursorMoved = false;
                        }
                        appendMouseScrollEvent(readBuffer, event.amount);
                        break;
                    default:
                        break;
                }
            }

            if (cursorMoved) {
                moveCursor(cursorStepX, cursorStepY);
            }
            if (moved) {
                mouseDx.setInt(null, dx);
                mouseDy.setInt(null, dy);
            }
        } catch (Throwable e) {
            printLwjgl2Error(false, e);
        }
    }

    /**
     * 光标模式下把系统光标移动一段相对位移
     *
     * LWJGL2 的鼠标坐标原点在左下角，而协议里的 dy 正方向是向下，
     * 所以写入 `setCursorPosition` 时 Y 要取反。
     *
     * - `dx`: 水平位移，单位像素
     * - `dy`: 垂直位移，单位像素，正数向下
     */
    private static void moveCursor(int dx, int dy) {
        if (mouseSetCursorPosition == null || mouseGetX == null || mouseGetY == null) {
            return;
        }
        try {
            // 窗口不在前台时不挪系统光标，避免影响启动器或其它窗口
            if (displayIsActive != null && !((Boolean) displayIsActive.invoke(null))) {
                return;
            }

            int x = (Integer) mouseGetX.invoke(null) + dx;
            int y = (Integer) mouseGetY.invoke(null) - dy;
            if (displayGetWidth != null) {
                int width = (Integer) displayGetWidth.invoke(null);
                if (width > 0) {
                    x = Math.min(x, width - 1);
                }
            }
            if (displayGetHeight != null) {
                int height = (Integer) displayGetHeight.invoke(null);
                if (height > 0) {
                    y = Math.min(y, height - 1);
                }
            }
            x = Math.max(0, x);
            y = Math.max(0, y);
            mouseSetCursorPosition.invoke(null, x, y);
        } catch (Throwable e) {
            printLwjgl2Error(false, e);
        }
    }

    private static void initKeyboard(Class<?> keyboardClass) {
        if (keyboardFailed) {
            return;
        }
        try {
            Field readBuffer = keyboardClass.getDeclaredField("readBuffer");
            Field keyDownBuffer = keyboardClass.getDeclaredField("keyDownBuffer");
            readBuffer.setAccessible(true);
            keyDownBuffer.setAccessible(true);
            keyboardReadBuffer = readBuffer;
            keyboardKeyDownBuffer = keyDownBuffer;
            keyboardReady = true;
        } catch (Throwable e) {
            keyboardFailed = true;
            printLwjgl2Error(true, e);
        }
    }

    private static void initMouse(Class<?> mouseClass) {
        if (mouseFailed) {
            return;
        }
        try {
            Field readBuffer = mouseClass.getDeclaredField("readBuffer");
            Field buttons = mouseClass.getDeclaredField("buttons");
            Field dx = mouseClass.getDeclaredField("dx");
            Field dy = mouseClass.getDeclaredField("dy");
            readBuffer.setAccessible(true);
            buttons.setAccessible(true);
            dx.setAccessible(true);
            dy.setAccessible(true);
            mouseReadBuffer = readBuffer;
            mouseButtons = buttons;
            mouseDx = dx;
            mouseDy = dy;
            try {
                mouseGetX = mouseClass.getMethod("getX");
                mouseGetY = mouseClass.getMethod("getY");
                mouseGetX.setAccessible(true);
                mouseGetY.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                mouseSetCursorPosition = mouseClass.getMethod("setCursorPosition", int.class, int.class);
                mouseSetCursorPosition.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                // LWJGL2 的鼠标坐标原点在左下角，移动光标时要换算，也需要窗口尺寸做边界
                Class<?> display = Class.forName("org.lwjgl.opengl.Display", false,
                        mouseClass.getClassLoader());
                displayGetWidth = display.getMethod("getWidth");
                displayGetHeight = display.getMethod("getHeight");
                displayIsActive = display.getMethod("isActive");
            } catch (Throwable ignored) {
            }
            mouseReady = true;
        } catch (Throwable e) {
            mouseFailed = true;
            printLwjgl2Error(false, e);
        }
    }

    /**
     * 把一条键盘事件追加到 LWJGL2 的读缓冲
     *
     * 布局与 `Keyboard.readNext` 一致：int 键码、byte 状态、int 字符、
     * long 纳秒、byte 是否重复。
     */
    private static void appendKeyEvent(ByteBuffer buffer, InputEvent event) {
        int position = buffer.position();
        int limit = buffer.limit();
        if (buffer.capacity() - limit < LWJGL2_KEY_EVENT_SIZE) {
            return;
        }
        buffer.limit(buffer.capacity());
        buffer.position(limit);
        buffer.putInt(event.key & 0xFF);
        buffer.put((byte) (event.down ? 1 : 0));
        buffer.putInt(0);
        buffer.putLong(System.nanoTime());
        buffer.put((byte) 0);
        buffer.limit(buffer.position());
        buffer.position(position);
    }

    /** 追加一条鼠标按键事件，布局与 `Mouse.next` 一致 */
    private static void appendMouseButtonEvent(ByteBuffer buffer, int button, boolean down) {
        int position = buffer.position();
        int limit = buffer.limit();
        if (buffer.capacity() - limit < LWJGL2_MOUSE_EVENT_SIZE) {
            return;
        }
        buffer.limit(buffer.capacity());
        buffer.position(limit);
        buffer.put((byte) button);
        buffer.put((byte) (down ? 1 : 0));
        putMousePosition(buffer);
        buffer.putInt(0);
        buffer.putLong(System.nanoTime());
        buffer.limit(buffer.position());
        buffer.position(position);
    }

    /** 追加一条滚轮事件，布局与 `Mouse.next` 一致 */
    private static void appendMouseScrollEvent(ByteBuffer buffer, int amount) {
        int position = buffer.position();
        int limit = buffer.limit();
        if (buffer.capacity() - limit < LWJGL2_MOUSE_EVENT_SIZE) {
            return;
        }
        buffer.limit(buffer.capacity());
        buffer.position(limit);
        buffer.put((byte) -1);
        buffer.put((byte) 0);
        putMousePosition(buffer);
        buffer.putInt(amount);
        buffer.putLong(System.nanoTime());
        buffer.limit(buffer.position());
        buffer.position(position);
    }

    /**
     * 写入鼠标事件的位置字段
     *
     * 抓取时 `Mouse.next` 读的是相对位移，未抓取时读的是绝对坐标，
     * 按键与滚轮本身不移动，位移写 0、坐标写当前值。
     */
    private static void putMousePosition(ByteBuffer buffer) {
        if (grabbed) {
            buffer.putInt(0);
            buffer.putInt(0);
            return;
        }
        int x = 0;
        int y = 0;
        try {
            if (mouseGetX != null) {
                x = (Integer) mouseGetX.invoke(null);
            }
            if (mouseGetY != null) {
                y = (Integer) mouseGetY.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        buffer.putInt(x);
        buffer.putInt(y);
    }

    private static void printLwjgl2Error(boolean keyboard, Throwable e) {
        boolean printed = keyboard ? keyboardErrorPrinted : mouseErrorPrinted;
        if (printed) {
            return;
        }
        if (keyboard) {
            keyboardErrorPrinted = true;
        } else {
            mouseErrorPrinted = true;
        }
        System.out.println("[ColoryrASM] lwjgl2 " + (keyboard ? "keyboard" : "mouse")
                + " input inject failed: " + e);
    }
}
