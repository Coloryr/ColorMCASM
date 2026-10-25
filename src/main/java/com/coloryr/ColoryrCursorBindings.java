package com.coloryr;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/** Resolves methods once, then emits ordinary typed calls without reflection or varargs. */
final class ColoryrCursorBindings {
    private final Map<String, Executable> targets = new HashMap<>();

    void bind(String key, Class<?> owner, String name, Class<?>... params) throws NoSuchMethodException {
        targets.put(key, owner.getMethod(name, params));
    }

    void constructor(String key, Class<?> owner, Class<?>... params) throws NoSuchMethodException {
        targets.put(key, owner.getConstructor(params));
    }

    <T> T create(Class<T> contract, ClassLoader gameLoader) throws ReflectiveOperationException {
        Class<?> implementation = new DirectLoader(gameLoader, contract).define(generate(contract));
        return contract.cast(implementation.getConstructor().newInstance());
    }

    private static final class DirectLoader extends ClassLoader {
        private final Class<?> contract;

        DirectLoader(ClassLoader parent, Class<?> contract) {
            super(parent);
            this.contract = contract;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // A mod loader may not delegate the agent contract to its defining loader.
            if (name.equals(contract.getName())) {
                return contract;
            }
            return super.loadClass(name, resolve);
        }

        Class<?> define(byte[] bytes) {
            return defineClass(contract.getName() + "$DirectCalls", bytes, 0, bytes.length);
        }
    }

    byte[] generate(Class<?> contract) {
        String name = Type.getInternalName(contract) + "$DirectCalls";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object",
                new String[]{Type.getInternalName(contract)});
        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        for (Method api : contract.getMethods()) {
            if (!Modifier.isAbstract(api.getModifiers())) continue;
            MethodVisitor method = writer.visitMethod(ACC_PUBLIC, api.getName(),
                    Type.getMethodDescriptor(api), null, null);
            method.visitCode();
            Executable target = targets.get(api.getName());
            if (target == null || !compatible(api, target)) {
                method.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
                method.visitInsn(DUP);
                method.visitLdcInsn("Cursor binding unavailable: " + api.getName());
                method.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException",
                        "<init>", "(Ljava/lang/String;)V", false);
                method.visitInsn(ATHROW);
            } else {
                emitCall(method, api, target);
            }
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static boolean compatible(Method api, Executable target) {
        boolean receiver = target instanceof Method && !Modifier.isStatic(target.getModifiers());
        Class<?>[] args = api.getParameterTypes(), params = target.getParameterTypes();
        if (args.length != params.length + (receiver ? 1 : 0)) return false;
        if (receiver && args[0].isPrimitive()) return false;
        for (int i = 0; i < params.length; i++) {
            Class<?> arg = args[i + (receiver ? 1 : 0)];
            if ((arg.isPrimitive() || params[i].isPrimitive()) && arg != params[i]) return false;
        }
        Class<?> result = target instanceof Constructor ? target.getDeclaringClass()
                : ((Method) target).getReturnType();
        return api.getReturnType().isAssignableFrom(result);
    }

    private static void emitCall(MethodVisitor method, Method api, Executable target) {
        String owner = Type.getInternalName(target.getDeclaringClass());
        boolean constructor = target instanceof Constructor;
        boolean receiver = !constructor && !Modifier.isStatic(target.getModifiers());
        if (constructor) {
            method.visitTypeInsn(NEW, owner);
            method.visitInsn(DUP);
        }
        Class<?>[] args = api.getParameterTypes(), params = target.getParameterTypes();
        int slot = 1;
        for (int i = 0; i < args.length; i++) {
            Type type = Type.getType(args[i]);
            method.visitVarInsn(type.getOpcode(ILOAD), slot);
            Class<?> expected = receiver && i == 0 ? target.getDeclaringClass()
                    : params[i - (receiver ? 1 : 0)];
            if (!expected.isPrimitive() && !expected.isAssignableFrom(args[i])) {
                method.visitTypeInsn(CHECKCAST, Type.getInternalName(expected));
            }
            slot += type.getSize();
        }
        if (constructor) {
            method.visitMethodInsn(INVOKESPECIAL, owner, "<init>",
                    Type.getConstructorDescriptor((Constructor<?>) target), false);
        } else {
            method.visitMethodInsn(receiver ? INVOKEVIRTUAL : INVOKESTATIC, owner, target.getName(),
                    Type.getMethodDescriptor((Method) target), false);
        }
        method.visitInsn(Type.getType(api.getReturnType()).getOpcode(IRETURN));
    }
}
