package com.coloryr;

import java.lang.reflect.Method;
import java.net.DatagramPacket;

public class Test {
    public static void coloryrGrab(int b, int c) {
        try {
            Class<?> classpack = Class.forName("com.coloryr.ColoryrPackBuilder");
            Method method = classpack.getMethod("setGrabbed", int.class, int.class);
            method.invoke(null, b, c);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void coloryrGrab(boolean temp) {
        try {
            Class<?> classpack = Class.forName("com.coloryr.ColoryrPackBuilder");
            Method method = classpack.getMethod("setGrabbed", boolean.class);
            method.invoke(null, temp);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void coloryrAddWindow(long temp) {
        try {
            Class<?> classpack = Class.forName("com.coloryr.ColoryrASM");
            Method method = classpack.getMethod("addWindow", long.class);
            method.invoke(null, temp);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean getServer(DatagramPacket p) {
        try {
            Class<?> classpack = Class.forName("com.coloryr.ColoryrPackBuilder");
            Method method = classpack.getMethod("receive", DatagramPacket.class);
            return (boolean) method.invoke(null, p);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }
}
