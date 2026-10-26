package com.coloryr;

/** Position and size in framebuffer pixels, shared by the GL and Vulkan backends. */
final class ColoryrCursorFrame {
    final double x;
    final double y;
    final float sizeX;
    final float sizeY;

    ColoryrCursorFrame(double x, double y, float sizeX, float sizeY) {
        this.x = x;
        this.y = y;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
    }
}
