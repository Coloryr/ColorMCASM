package com.coloryr;

import java.nio.IntBuffer;

/** Object cursor handles keep LWJGL 2 types out of the agent's own class loader. */
public interface ColoryrLwjgl2Api {
    boolean isCreated();
    boolean isGrabbed();
    boolean isInsideWindow();
    int getWidth();
    int getHeight();
    int getX();
    int getY();
    int minCursorSize();
    Object getNativeCursor();
    void setNativeCursor(Object cursor);
    Object createCursor(int width, int height, int x, int y, int count, IntBuffer pixels, IntBuffer delays);
    void destroyCursor(Object cursor);
}
