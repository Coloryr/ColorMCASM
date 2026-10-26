package com.coloryr;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;

/** CPU decoding is deferred until a render backend is ready; pixels are uploaded once. */
final class ColoryrCursorImage {
    final int width;
    final int height;
    final float hotspotX;
    final float hotspotY;
    private final byte[] rgba;

    private ColoryrCursorImage(BufferedImage image, float hotspotX, float hotspotY) {
        width = image.getWidth();
        height = image.getHeight();
        // No explicit hotspot: center the texture on the pointer. The old arrow texture
        // relied on the top-left corner; pass hotspotX/Y=0 to get that back.
        float centerX = Float.isNaN(hotspotX) ? width / 2f : hotspotX;
        float centerY = Float.isNaN(hotspotY) ? height / 2f : hotspotY;
        this.hotspotX = Math.max(0, Math.min(width - 1, centerX));
        this.hotspotY = Math.max(0, Math.min(height - 1, centerY));
        rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = image.getRGB(x, y);
                int offset = (y * width + x) * 4;
                rgba[offset] = (byte) (color >> 16);
                rgba[offset + 1] = (byte) (color >> 8);
                rgba[offset + 2] = (byte) color;
                rgba[offset + 3] = (byte) (color >> 24);
            }
        }
    }

    static ColoryrCursorImage load() throws IOException {
        String path = System.getProperty("coloryrasm.cursor.texture", "").trim();
        float x = number("coloryrasm.cursor.hotspotX");
        float y = number("coloryrasm.cursor.hotspotY");
        if (!path.isEmpty()) {
            try (ImageInputStream stream = ImageIO.createImageInputStream(new File(path))) {
                return decode(stream, x, y);
            }
        }
        try (InputStream input = ColoryrCursorImage.class.getResourceAsStream("/coloryrasm/cursor.png")) {
            if (input == null) {
                throw new IOException("Missing /coloryrasm/cursor.png");
            }
            try (ImageInputStream stream = ImageIO.createImageInputStream(input)) {
                return decode(stream, x, y);
            }
        }
    }

    static ColoryrCursorImage decode(ImageInputStream stream, float x, float y) throws IOException {
        if (stream == null) {
            throw new IOException("Cursor image cannot be opened");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
            throw new IOException("Unsupported cursor image");
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(stream, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > 1024 || height > 1024) {
                throw new IOException("Cursor image dimensions must be between 1 and 1024");
            }
            return new ColoryrCursorImage(reader.read(0), x, y);
        } finally {
            reader.dispose();
        }
    }

    ByteBuffer pixels() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(rgba.length);
        buffer.put(rgba).flip();
        return buffer;
    }

    private static float number(String key) {
        String raw = System.getProperty(key);
        if (raw == null) {
            return Float.NaN;
        }
        try {
            float value = Float.parseFloat(raw.trim());
            return Float.isNaN(value) || Float.isInfinite(value) ? Float.NaN : value;
        } catch (RuntimeException ignored) {
            return Float.NaN;
        }
    }
}
