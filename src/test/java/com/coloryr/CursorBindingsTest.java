package com.coloryr;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class CursorBindingsTest {
    @Test
    void invokesPrimitivesConstructorsAndInstanceMethodsAcrossIsolatedGameLoader() throws Exception {
        ClassLoader gameLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(Calls.class.getName())) {
                    throw new ClassNotFoundException("Game loader cannot see the agent API");
                }
                if (!name.equals(Target.class.getName())) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        byte[] bytes = resource("/" + name.replace('.', '/') + ".class");
                        type = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        Class<?> target = Class.forName(Target.class.getName(), true, gameLoader);
        ColoryrCursorBindings bindings = new ColoryrCursorBindings();
        bindings.bind("wide", target, "wide", long.class, double.class, long.class);
        bindings.bind("enabled", target, "enabled", boolean.class);
        bindings.constructor("create", target, int.class);
        bindings.bind("value", target, "value");
        Calls calls = bindings.create(Calls.class, gameLoader);
        assertNotSame(gameLoader, calls.getClass().getClassLoader());
        assertEquals(12, calls.wide(5, 3.0, 4));
        assertTrue(calls.enabled(true));
        assertFalse(calls.enabled(false));
        Object object = calls.create(37);
        assertSame(gameLoader, object.getClass().getClassLoader());
        assertEquals(37, calls.value(object));
        assertThrows(UnsupportedOperationException.class, () -> calls.wide(1, 2));

        byte[] generated = bindings.generate(Calls.class);
        assertEquals(52, ((generated[6] & 255) << 8) | (generated[7] & 255));
        StringWriter errors = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(generated), gameLoader, false, new PrintWriter(errors));
        assertEquals("", errors.toString());
        assertDirectCalls(generated, null);
    }

    @Test
    void rendererAndLwjgl2FramePathsContainNoReflectiveDispatchOrVarargs() {
        assertDirectCalls(resource("/com/coloryr/ColoryrCursorRenderer.class"), "draw");
        assertDirectCalls(resource("/com/coloryr/ColoryrCursor.class"), "beforeDisplaySwap");
        assertDirectCalls(resource("/com/coloryr/ColoryrCursor.class"), "destroyDisplay");
        assertDirectCalls(resource("/com/coloryr/ColoryrCursor$DisplayCursor.class"), "hide");
        assertDirectCalls(resource("/com/coloryr/ColoryrCursor$DisplayCursor.class"), "restore");
    }

    private static void assertDirectCalls(byte[] bytes, String selected) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (selected != null && !name.equals(selected)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        assertFalse(owner.startsWith("java/lang/reflect/"), "Reflective dispatch: " + name);
                        assertFalse(owner.equals("java/util/Map") && name.equals("get"), "String dispatch lookup");
                        assertFalse(owner.startsWith("java/lang/") && name.equals("valueOf"), "Argument boxing");
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        assertFalse(opcode == Opcodes.ANEWARRAY && type.equals("java/lang/Object"),
                                "Object varargs allocation");
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static byte[] resource(String path) {
        try (InputStream input = CursorBindingsTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int count; (count = input.read(chunk)) != -1;) output.write(chunk, 0, count);
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public interface Calls {
        long wide(long first, double second, long third);
        long wide(long first, long second);
        boolean enabled(boolean value);
        Object create(int value);
        int value(Object instance);
    }

    public static final class Target {
        private final int value;

        public Target(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static long wide(long first, double second, long third) {
            return first + (long) second + third;
        }

        public static boolean enabled(boolean value) {
            return value;
        }
    }
}
