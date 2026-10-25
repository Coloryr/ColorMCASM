package com.coloryr;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Textured overlay, using the game's LWJGL 2 or 3 at runtime. */
final class ColoryrCursorRenderer {
    private final Map<String, Method> methods = new HashMap<>();
    private final IntBuffer query = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
    private final boolean modern;
    private final boolean coreProfile;
    private final boolean clipControl;
    private final boolean indexedBlend;
    private final boolean samplerObjects;
    private final boolean unpackBuffer;
    private final ColoryrCursorImage image;
    private final int framebufferTarget;
    private final int[] capabilities;
    private final boolean[] enabled;
    private final int[] viewport = new int[4];
    private final int[] colorMask = new int[4];
    private final int[] polygonMode = new int[2];
    private int program;
    private int vao;
    private int origin;
    private int scale;
    private int texture;
    private int sampler;

    ColoryrCursorRenderer(ClassLoader loader, boolean lwjgl3) throws Exception {
        Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11", false, loader);
        Class<?> gl20 = Class.forName("org.lwjgl.opengl.GL20", false, loader);
        Class<?> context = Class.forName(lwjgl3 ? "org.lwjgl.opengl.GL" : "org.lwjgl.opengl.GLContext",
                false, loader);
        Object caps = context.getMethod("getCapabilities").invoke(null);
        if (!capability(caps, "OpenGL20")) {
            throw new IllegalStateException("OpenGL 2.0 is required for the software cursor");
        }
        modern = capability(caps, "OpenGL30");
        boolean gl32 = capability(caps, "OpenGL32");
        clipControl = capability(caps, "OpenGL45") || capability(caps, "GL_ARB_clip_control");
        indexedBlend = capability(caps, "OpenGL40") || capability(caps, "GL_ARB_draw_buffers_blend");
        samplerObjects = capability(caps, "OpenGL33") || capability(caps, "GL_ARB_sampler_objects");
        unpackBuffer = capability(caps, "OpenGL21") || capability(caps, "GL_ARB_pixel_buffer_object");
        image = ColoryrCursorImage.load();

        bind("integer", gl11, "glGetInteger", int.class);
        bind("integers", gl11, lwjgl3 ? "glGetIntegerv" : "glGetInteger", int.class, IntBuffer.class);
        bind("enabled", gl11, "glIsEnabled", int.class);
        bind("enable", gl11, "glEnable", int.class);
        bind("disable", gl11, "glDisable", int.class);
        bind("viewport", gl11, "glViewport", int.class, int.class, int.class, int.class);
        bind("colorMask", gl11, "glColorMask", boolean.class, boolean.class, boolean.class, boolean.class);
        bind("polygonMode", gl11, "glPolygonMode", int.class, int.class);
        bind("createShader", gl20, "glCreateShader", int.class);
        bind("shaderSource", gl20, "glShaderSource", int.class, CharSequence.class);
        bind("compileShader", gl20, "glCompileShader", int.class);
        bind("shaderStatus", gl20, "glGetShaderi", int.class, int.class);
        bind("shaderLog", gl20, "glGetShaderInfoLog", int.class, int.class);
        bind("deleteShader", gl20, "glDeleteShader", int.class);
        bind("createProgram", gl20, "glCreateProgram");
        bind("attachShader", gl20, "glAttachShader", int.class, int.class);
        bind("linkProgram", gl20, "glLinkProgram", int.class);
        bind("programStatus", gl20, "glGetProgrami", int.class, int.class);
        bind("programLog", gl20, "glGetProgramInfoLog", int.class, int.class);
        bind("deleteProgram", gl20, "glDeleteProgram", int.class);
        bind("useProgram", gl20, "glUseProgram", int.class);
        bind("uniformLocation", gl20, "glGetUniformLocation", int.class, CharSequence.class);
        bind("uniform2f", gl20, "glUniform2f", int.class, float.class, float.class);
        bind("uniform1i", gl20, "glUniform1i", int.class, int.class);
        bind("genTexture", gl11, "glGenTextures");
        bind("bindTexture", gl11, "glBindTexture", int.class, int.class);
        bind("deleteTexture", gl11, "glDeleteTextures", int.class);
        bind("texParameter", gl11, "glTexParameteri", int.class, int.class, int.class);
        bind("texImage", gl11, "glTexImage2D", int.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int.class, ByteBuffer.class);
        bind("pixelStore", gl11, "glPixelStorei", int.class, int.class);
        bind("activeTexture", Class.forName("org.lwjgl.opengl.GL13", false, loader),
                "glActiveTexture", int.class);
        if (indexedBlend) {
            Class<?> blend = Class.forName("org.lwjgl.opengl."
                    + (capability(caps, "OpenGL40") ? "GL40" : "ARBDrawBuffersBlend"), false, loader);
            String suffix = capability(caps, "OpenGL40") ? "" : "ARB";
            bind("blendFunc", blend, "glBlendFuncSeparatei" + suffix, int.class,
                    int.class, int.class, int.class, int.class);
            bind("blendEquation", blend, "glBlendEquationSeparatei" + suffix,
                    int.class, int.class, int.class);
        } else {
            bind("blendFunc", Class.forName("org.lwjgl.opengl.GL14", false, loader),
                    "glBlendFuncSeparate", int.class, int.class, int.class, int.class);
            bind("blendEquation", gl20, "glBlendEquationSeparate", int.class, int.class);
        }
        if (samplerObjects) {
            bind("bindSampler", Class.forName("org.lwjgl.opengl."
                    + (capability(caps, "OpenGL33") ? "GL33" : "ARBSamplerObjects"), false, loader),
                    "glBindSampler", int.class, int.class);
        }
        if (unpackBuffer) {
            bind("bindBuffer", Class.forName("org.lwjgl.opengl.GL15", false, loader),
                    "glBindBuffer", int.class, int.class);
        }

        coreProfile = gl32 && (integer(0x9126) & 1) != 0;
        if (modern) {
            Class<?> gl30 = Class.forName("org.lwjgl.opengl.GL30", false, loader);
            bind("genVao", gl30, "glGenVertexArrays");
            bind("bindVao", gl30, "glBindVertexArray", int.class);
            bind("deleteVao", gl30, "glDeleteVertexArrays", int.class);
            bind("fragLocation", gl30, "glBindFragDataLocation", int.class, int.class, CharSequence.class);
            bind("framebuffer", gl30, "glBindFramebuffer", int.class, int.class);
            bind("enabledIndexed", gl30, "glIsEnabledi", int.class, int.class);
            bind("enableIndexed", gl30, "glEnablei", int.class, int.class);
            bind("disableIndexed", gl30, "glDisablei", int.class, int.class);
            bind("colorMaskIndexed", gl30, "glColorMaski",
                    int.class, boolean.class, boolean.class, boolean.class, boolean.class);
            bind("draw", gl11, "glDrawArrays", int.class, int.class, int.class);
            framebufferTarget = 0x8CA9; // GL_DRAW_FRAMEBUFFER, leaving the read binding untouched.
        } else {
            bind("pushCurrent", gl11, "glPushAttrib", int.class);
            bind("popCurrent", gl11, "glPopAttrib");
            bind("begin", gl11, "glBegin", int.class);
            bind("end", gl11, "glEnd");
            bind("texCoord", gl11, "glTexCoord2f", float.class, float.class);
            bind("vertex", gl11, "glVertex2f", float.class, float.class);
            if (capability(caps, "GL_EXT_framebuffer_object")) {
                Class<?> ext = Class.forName("org.lwjgl.opengl.EXTFramebufferObject", false, loader);
                bind("framebuffer", ext, "glBindFramebufferEXT", int.class, int.class);
                framebufferTarget = 0x8D40;
            } else {
                framebufferTarget = 0;
            }
        }
        if (clipControl) {
            String type = capability(caps, "OpenGL45") ? "GL45" : "ARBClipControl";
            bind("clipControl", Class.forName("org.lwjgl.opengl." + type, false, loader),
                    "glClipControl", int.class, int.class);
        }

        List<Integer> states = new ArrayList<>();
        int[] common = {0x0B71, 0x0BE2, 0x0B44, 0x0C11, 0x0B90, 0x0BF2, 0x809E, 0x80A0};
        for (int state : common) {
            states.add(state);
        }
        if (!coreProfile) {
            states.add(0x0BC0); // Alpha testing is absent from core contexts.
            states.add(0x0B42); // Polygon stipple is also compatibility-only.
        }
        if (modern) {
            states.add(0x8C89); // GL_RASTERIZER_DISCARD
        }
        for (int i = 0, count = integer(0x0D32); i < count; i++) {
            states.add(0x3000 + i); // User clip planes / clip distances.
        }
        capabilities = new int[states.size()];
        enabled = new boolean[states.size()];
        for (int i = 0; i < states.size(); i++) {
            capabilities[i] = states.get(i);
        }

        int vertex = 0;
        int fragment = 0;
        try {
            String version = modern ? (gl32 ? "#version 150\n" : "#version 130\n") : "#version 120\n";
            vertex = shader(0x8B31, vertexSource(version));
            fragment = shader(0x8B30, fragmentSource(version));
            program = (Integer) call("createProgram");
            call("attachShader", program, vertex);
            call("attachShader", program, fragment);
            if (modern) {
                call("fragLocation", program, 0, "cursorColor");
            }
            call("linkProgram", program);
            if ((Integer) call("programStatus", program, 0x8B82) == 0) {
                throw new IllegalStateException("Cursor shader link: " + call("programLog", program, 4096));
            }
            origin = (Integer) call("uniformLocation", program, "origin");
            scale = (Integer) call("uniformLocation", program, "scale");
            sampler = (Integer) call("uniformLocation", program, "cursorTexture");
            if (modern) {
                vao = (Integer) call("genVao");
            }
            upload();
        } catch (Exception e) {
            close();
            throw e;
        } finally {
            if (vertex != 0) {
                call("deleteShader", vertex);
            }
            if (fragment != 0) {
                call("deleteShader", fragment);
            }
        }
    }

    void draw(int width, int height, double x, double y, float sizeX, float sizeY) throws Exception {
        int oldProgram = integer(0x8B8D);
        int oldVao = modern ? integer(0x85B5) : 0;
        int oldFramebuffer = framebufferTarget == 0 ? 0 : integer(0x8CA6);
        int oldClipOrigin = clipControl ? integer(0x935C) : 0;
        int oldClipDepth = clipControl ? integer(0x935D) : 0;
        int oldActiveTexture = integer(0x84E0);
        call("activeTexture", 0x84C0);
        int oldTexture = integer(0x8069);
        int oldSampler = samplerObjects ? integer(0x8919) : 0;
        int[] blend = {integer(0x80C9), integer(0x80C8), integer(0x80CB), integer(0x80CA),
                integer(0x8009), integer(0x883D)};
        integers(0x0BA2, viewport);
        integers(0x0C23, colorMask);
        integers(0x0B40, polygonMode);
        for (int i = 0; i < capabilities.length; i++) {
            enabled[i] = modern && capabilities[i] == 0x0BE2
                    ? (Boolean) call("enabledIndexed", capabilities[i], 0)
                    : (Boolean) call("enabled", capabilities[i]);
        }
        boolean pushed = false;
        try {
            if (framebufferTarget != 0) {
                call("framebuffer", framebufferTarget, 0);
            }
            if (clipControl) {
                call("clipControl", 0x8CA1, 0x935E); // Lower-left, negative-one-to-one.
            }
            call("viewport", 0, 0, width, height);
            colorMask(true, true, true, true);
            call("polygonMode", 0x0408, 0x1B02);
            for (int state : capabilities) {
                enable(state, false);
            }
            enable(0x0BE2, true);
            blendFunc(0x0302, 0x0303, 1, 0x0303);
            blendEquation(0x8006, 0x8006);
            call("bindTexture", 0x0DE1, texture);
            if (samplerObjects) {
                call("bindSampler", 0, 0);
            }
            call("useProgram", program);
            call("uniform1i", sampler, 0);
            float pixelX = sizeX / image.height;
            float pixelY = sizeY / image.height;
            float left = (float) (2 * (x - image.hotspotX * pixelX) / width - 1);
            float top = (float) (1 - 2 * (y - image.hotspotY * pixelY) / height);
            float stepX = 2 * image.width * pixelX / width;
            float stepY = -2 * image.height * pixelY / height;
            if (modern) {
                call("bindVao", vao);
                call("uniform2f", origin, left, top);
                call("uniform2f", scale, stepX, stepY);
                call("draw", 0x0005, 0, 4);
            } else {
                // Only the GL 2 compatibility path uses immediate mode.
                // The shader consumes clip-space vertices, so no matrix stack is touched.
                call("pushCurrent", 1);
                pushed = true;
                call("begin", 0x0005);
                try {
                    vertex(0, 0, left, top);
                    vertex(1, 0, left + stepX, top);
                    vertex(0, 1, left, top + stepY);
                    vertex(1, 1, left + stepX, top + stepY);
                } finally {
                    call("end");
                }
            }
        } finally {
            if (pushed) {
                call("popCurrent");
            }
            call("useProgram", oldProgram);
            if (modern) {
                call("bindVao", oldVao);
            }
            call("bindTexture", 0x0DE1, oldTexture);
            if (samplerObjects) {
                call("bindSampler", 0, oldSampler);
            }
            call("activeTexture", oldActiveTexture);
            blendFunc(blend[0], blend[1], blend[2], blend[3]);
            blendEquation(blend[4], blend[5]);
            for (int i = 0; i < capabilities.length; i++) {
                enable(capabilities[i], enabled[i]);
            }
            if (coreProfile) {
                call("polygonMode", 0x0408, polygonMode[0]);
            } else {
                call("polygonMode", 0x0404, polygonMode[0]);
                call("polygonMode", 0x0405, polygonMode[1]);
            }
            colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
            call("viewport", viewport[0], viewport[1], viewport[2], viewport[3]);
            if (clipControl) {
                call("clipControl", oldClipOrigin, oldClipDepth);
            }
            if (framebufferTarget != 0) {
                call("framebuffer", framebufferTarget, oldFramebuffer);
            }
        }
    }

    void close() throws Exception {
        if (texture != 0) {
            call("deleteTexture", texture);
            texture = 0;
        }
        if (vao != 0) {
            call("deleteVao", vao);
            vao = 0;
        }
        if (program != 0) {
            call("deleteProgram", program);
            program = 0;
        }
    }

    private void vertex(float u, float v, float x, float y) throws Exception {
        call("texCoord", u, v);
        call("vertex", x, y);
    }

    private void upload() throws Exception {
        int active = integer(0x84E0);
        call("activeTexture", 0x84C0);
        int binding = integer(0x8069);
        int buffer = unpackBuffer ? integer(0x88EF) : 0;
        int[] names = {0x0CF5, 0x0CF2, 0x0CF3, 0x0CF4};
        int[] values = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            values[i] = integer(names[i]);
        }
        try {
            if (unpackBuffer) {
                call("bindBuffer", 0x88EC, 0);
            }
            for (int i = 0; i < names.length; i++) {
                call("pixelStore", names[i], i == 0 ? 1 : 0);
            }
            texture = (Integer) call("genTexture");
            call("bindTexture", 0x0DE1, texture);
            call("texParameter", 0x0DE1, 0x2801, 0x2600);
            call("texParameter", 0x0DE1, 0x2800, 0x2600);
            call("texParameter", 0x0DE1, 0x2802, 0x812F);
            call("texParameter", 0x0DE1, 0x2803, 0x812F);
            call("texImage", 0x0DE1, 0, 0x8058, image.width, image.height, 0,
                    0x1908, 0x1401, image.pixels());
        } finally {
            for (int i = 0; i < names.length; i++) {
                call("pixelStore", names[i], values[i]);
            }
            if (unpackBuffer) {
                call("bindBuffer", 0x88EC, buffer);
            }
            call("bindTexture", 0x0DE1, binding);
            call("activeTexture", active);
        }
    }

    private void blendFunc(int source, int dest, int sourceAlpha, int destAlpha) throws Exception {
        if (indexedBlend) {
            call("blendFunc", 0, source, dest, sourceAlpha, destAlpha);
        } else {
            call("blendFunc", source, dest, sourceAlpha, destAlpha);
        }
    }

    private void blendEquation(int rgb, int alpha) throws Exception {
        if (indexedBlend) {
            call("blendEquation", 0, rgb, alpha);
        } else {
            call("blendEquation", rgb, alpha);
        }
    }

    private void enable(int state, boolean value) throws Exception {
        if (modern && state == 0x0BE2) {
            call(value ? "enableIndexed" : "disableIndexed", state, 0);
        } else {
            call(value ? "enable" : "disable", state);
        }
    }

    private void colorMask(boolean red, boolean green, boolean blue, boolean alpha) throws Exception {
        if (modern) {
            call("colorMaskIndexed", 0, red, green, blue, alpha);
        } else {
            call("colorMask", red, green, blue, alpha);
        }
    }

    private int shader(int type, String source) throws Exception {
        int shader = (Integer) call("createShader", type);
        try {
            call("shaderSource", shader, source);
            call("compileShader", shader);
            if ((Integer) call("shaderStatus", shader, 0x8B81) == 0) {
                throw new IllegalStateException("Cursor shader compile: " + call("shaderLog", shader, 4096));
            }
            return shader;
        } catch (Exception e) {
            call("deleteShader", shader);
            throw e;
        }
    }

    private int integer(int name) throws Exception {
        return (Integer) call("integer", name);
    }

    private void integers(int name, int[] values) throws Exception {
        query.clear();
        call("integers", name, query);
        for (int i = 0; i < values.length; i++) {
            values[i] = query.get(i);
        }
    }

    private void bind(String key, Class<?> owner, String name, Class<?>... params) throws Exception {
        methods.put(key, owner.getMethod(name, params));
    }

    private Object call(String key, Object... args) throws Exception {
        return methods.get(key).invoke(null, args);
    }

    private static boolean capability(Object caps, String name) {
        try {
            return caps.getClass().getField(name).getBoolean(caps);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private String vertexSource(String version) {
        if (!modern) {
            return version + "varying vec2 point;\n"
                    + "void main() { point = gl_MultiTexCoord0.xy; gl_Position = gl_Vertex; }\n";
        }
        return version + "uniform vec2 origin;\nuniform vec2 scale;\nout vec2 point;\n"
                + "void main() {\n"
                + "  point = vec2((gl_VertexID == 1 || gl_VertexID == 3) ? 1.0 : 0.0,\n"
                + "               gl_VertexID >= 2 ? 1.0 : 0.0);\n"
                + "  gl_Position = vec4(origin + point * scale, 0.0, 1.0);\n"
                + "}\n";
    }

    private String fragmentSource(String version) {
        StringBuilder source = new StringBuilder(version);
        source.append(modern ? "in vec2 point;\nout vec4 cursorColor;\n" : "varying vec2 point;\n");
        return source.append("uniform sampler2D cursorTexture;\nvoid main() {\n  ")
                .append(modern ? "cursorColor" : "gl_FragColor")
                .append(" = ").append(modern ? "texture" : "texture2D")
                .append("(cursorTexture, point);\n}\n").toString();
    }
}
