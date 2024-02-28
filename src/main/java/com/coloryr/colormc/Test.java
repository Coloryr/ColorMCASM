package com.coloryr.colormc;

import java.lang.reflect.Method;
import java.net.DatagramPacket;

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

    public static boolean getServer(DatagramPacket p) {
        try {
            Class<?> colorMCPackBuilderClass = Class.forName("com.coloryr.colormc.ColorMCPackBuilder");
            Method method = colorMCPackBuilderClass.getMethod("receive", DatagramPacket.class);
            return (boolean) method.invoke(null, p);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }
}
