package com.coloryr.colormc;

import java.lang.reflect.Method;

public class Test {
    public static void colormcGrab(int b, int c) {
        try {
            Class<?> colorMCPackBuilderClass = Class.forName("com.coloryr.colormc.ColorMCPackBuilder");
            Method method = colorMCPackBuilderClass.getMethod("setGrabbed", int.class, int.class);
            method.invoke(null, b, c);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void colormcGrab(boolean temp) {
        try {
            Class<?> colorMCPackBuilderClass = Class.forName("com.coloryr.colormc.ColorMCPackBuilder");
            Method method = colorMCPackBuilderClass.getMethod("setGrabbed", boolean.class);
            method.invoke(null, temp);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
