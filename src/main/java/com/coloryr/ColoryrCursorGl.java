package com.coloryr;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Stable, typed contract for the ASM-generated calls into the game's GL bindings. */
public interface ColoryrCursorGl {
    int integer(int name);
    void integers(int name, IntBuffer values);
    boolean enabled(int state);
    void enable(int state);
    void disable(int state);
    void viewport(int x, int y, int width, int height);
    void colorMask(boolean red, boolean green, boolean blue, boolean alpha);
    void polygonMode(int face, int mode);
    int createShader(int type);
    void shaderSource(int shader, CharSequence source);
    void compileShader(int shader);
    int shaderStatus(int shader, int name);
    String shaderLog(int shader, int length);
    void deleteShader(int shader);
    int createProgram();
    void attachShader(int program, int shader);
    void linkProgram(int program);
    int programStatus(int program, int name);
    String programLog(int program, int length);
    void deleteProgram(int program);
    void useProgram(int program);
    int uniformLocation(int program, CharSequence name);
    void uniform2f(int location, float x, float y);
    void uniform1i(int location, int value);
    int genTexture();
    void bindTexture(int target, int texture);
    void deleteTexture(int texture);
    void texParameter(int target, int name, int value);
    void texImage(int target, int level, int internalFormat, int width, int height,
                  int border, int format, int type, ByteBuffer pixels);
    void pixelStore(int name, int value);
    void activeTexture(int unit);
    void blendFunc(int source, int dest, int sourceAlpha, int destAlpha);
    void blendFunc(int buffer, int source, int dest, int sourceAlpha, int destAlpha);
    void blendEquation(int rgb, int alpha);
    void blendEquation(int buffer, int rgb, int alpha);
    void bindSampler(int unit, int sampler);
    void bindBuffer(int target, int buffer);
    int genVao();
    void bindVao(int vao);
    void deleteVao(int vao);
    void fragLocation(int program, int color, CharSequence name);
    void framebuffer(int target, int framebuffer);
    boolean enabledIndexed(int state, int index);
    void enableIndexed(int state, int index);
    void disableIndexed(int state, int index);
    void colorMaskIndexed(int index, boolean red, boolean green, boolean blue, boolean alpha);
    void draw(int mode, int first, int count);
    void pushCurrent(int mask);
    void popCurrent();
    void begin(int mode);
    void end();
    void texCoord(float u, float v);
    void vertex(float x, float y);
    void clipControl(int origin, int depth);
}
