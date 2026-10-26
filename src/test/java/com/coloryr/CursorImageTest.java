package com.coloryr;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class CursorImageTest {
    @Test
    void bundledCursorIsACenteredRing() throws Exception {
        String oldTexture = System.getProperty("coloryrasm.cursor.texture");
        String oldX = System.getProperty("coloryrasm.cursor.hotspotX");
        String oldY = System.getProperty("coloryrasm.cursor.hotspotY");
        System.clearProperty("coloryrasm.cursor.texture");
        System.clearProperty("coloryrasm.cursor.hotspotX");
        System.clearProperty("coloryrasm.cursor.hotspotY");
        try {
            ColoryrCursorImage image = ColoryrCursorImage.load();
            assertEquals(32, image.width);
            assertEquals(32, image.height);
            assertEquals(16f, image.hotspotX);
            assertEquals(16f, image.hotspotY);

            ByteBuffer pixels = image.pixels();
            int center = ((image.height / 2) * image.width + image.width / 2) * 4;
            assertEquals(0, pixels.get(center + 3) & 255, "center must stay transparent");
            int ring = ((image.height / 2) * image.width + 2) * 4;
            assertTrue((pixels.get(ring + 3) & 255) > 0, "ring must be visible");
        } finally {
            restore("coloryrasm.cursor.texture", oldTexture);
            restore("coloryrasm.cursor.hotspotX", oldX);
            restore("coloryrasm.cursor.hotspotY", oldY);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
