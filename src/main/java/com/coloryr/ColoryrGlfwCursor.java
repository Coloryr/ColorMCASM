package com.coloryr;

import org.lwjgl.glfw.GLFWCursorPosCallbackI;

/**
 * 包装游戏注册的光标回调
 *
 * 游戏抓取鼠标后按「本次坐标 - 上次坐标」计算视角，而 GLFW 的虚拟光标位置
 * 无法从 Java 侧修改。若把注入的坐标直接交给游戏，下一次真实移动会用 GLFW
 * 的虚拟坐标去减，正好把注入量抵消掉。
 *
 * 所以由本类接管 GLFW 回调，把真实坐标加上注入累积的偏移再转给游戏，
 * 两种来源的移动在游戏那边就是连续的。运行时找不到该回调接口时不会加载本类。
 */
public final class ColoryrGlfwCursor implements GLFWCursorPosCallbackI {
    private static ColoryrGlfwCursor instance;

    private ColoryrGlfwCursor() {
    }

    /** 创建（复用）包装回调，由 {@link ColoryrGlfw} 反射调用，接口不存在时就不会加载本类 */
    public static Object create() {
        if (instance == null) {
            instance = new ColoryrGlfwCursor();
        }
        return instance;
    }

    @Override
    public void invoke(long window, double xpos, double ypos) {
        ColoryrGlfw.onRealCursor(window, xpos, ypos);
    }
}
