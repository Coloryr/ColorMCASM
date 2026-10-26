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
        int[][] points = {{0, 0}, {0, 20}, {5, 15}, {9, 24}, {13, 22}, {9, 13}, {16, 13}};
        BufferedImage image = new BufferedImage(16, 24, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 16; x++) {
                double px = x + 0.5, py = y + 0.5, edge = 100;
                boolean inside = false;
                for (int i = 0; i < points.length; i++) {
                    int[] a = points[i], b = points[(i + 1) % points.length];
                    if ((a[1] > py) != (b[1] > py)
                            && px < (b[0] - a[0]) * (py - a[1]) / (b[1] - a[1]) + a[0]) {
                        inside = !inside;
                    }
                    double vx = b[0] - a[0], vy = b[1] - a[1];
                    double t = Math.max(0, Math.min(1, ((px - a[0]) * vx + (py - a[1]) * vy) / (vx * vx + vy * vy)));
                    edge = Math.min(edge, Math.hypot(px - a[0] - vx * t, py - a[1] - vy * t));
                }
                if (inside) {
                    image.setRGB(x, y, edge >= 1 ? 0xFFFFFFFF : 0xFF000000);
                }
            }
        }
        ImageIO.write(image, "PNG", folder.resolve("cursor.png").toFile());
    }
}
