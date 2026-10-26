package com.coloryr;

import org.lwjgl.util.shaderc.Shaderc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Development tool only; shaderc and its natives are not included in the agent. */
public final class CursorAssetCompiler {
    public static void main(String[] args) throws Exception {
        Path folder = Paths.get("src/main/resources/coloryrasm");
        long compiler = Shaderc.shaderc_compiler_initialize();
        try {
            String[] stages = {"vert", "frag"};
            for (int i = 0; i < stages.length; i++) {
                String name = "cursor." + stages[i];
                String source = new String(Files.readAllBytes(folder.resolve(name)), "UTF-8");
                long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                        i == 0 ? Shaderc.shaderc_glsl_vertex_shader : Shaderc.shaderc_glsl_fragment_shader,
                        name, "main", 0);
                try {
                    if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                        throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(result));
                    }
                    ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    Files.write(folder.resolve(name + ".spv"), data);
                } finally {
                    Shaderc.shaderc_result_release(result);
                }
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
        // Default texture: the gamepad cursor ring. 32x32, outer diameter 31, 5px stroke,
        // radial gray gradient, 55% alpha. The hotspot defaults to the texture center.
        int size = 32;
        double outer = 15.5;
        double inner = outer - 5;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double sumAlpha = 0;
                double sumValue = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        double px = x + (sx + 0.5) / 4.0;
                        double py = y + (sy + 0.5) / 4.0;
                        double radius = Math.hypot(px - size / 2.0, py - size / 2.0);
                        if (radius < inner || radius > outer) {
                            continue;
                        }
                        double t = (radius - inner) / (outer - inner);
                        double value = t < 0.5
                                ? 200 + (138 - 200) * (t / 0.5)
                                : 138 + (78 - 138) * ((t - 0.5) / 0.5);
                        sumAlpha += 140;
                        sumValue += value * 140;
                    }
                }
                if (sumAlpha > 0) {
                    int alpha = (int) Math.round(sumAlpha / 16);
                    int value = (int) Math.round(sumValue / sumAlpha);
                    image.setRGB(x, y, (alpha << 24) | (value << 16) | (value << 8) | value);
                }
            }
        }
        ImageIO.write(image, "PNG", folder.resolve("cursor.png").toFile());
    }
}
