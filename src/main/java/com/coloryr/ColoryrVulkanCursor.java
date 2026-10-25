package com.coloryr;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Untyped entry points keep Vulkan optional. The typed backend uses the game's
 * LWJGL class loader even when a mod loader isolates it from the agent.
 */
public final class ColoryrVulkanCursor {
    private static final String BACKEND = "com.coloryr.ColoryrVulkanBackend";
    private static final Map<ClassLoader, Class<?>> BACKENDS = new HashMap<>();

    private ColoryrVulkanCursor() {
    }

    private static synchronized Class<?> backend(ClassLoader loader) throws Exception {
        ClassLoader bindings = Class.forName("org.lwjgl.vulkan.VkDevice", false, loader).getClassLoader();
        Class<?> result = BACKENDS.get(bindings);
        if (result == null) {
            ClassLoader bridgeLoader = new ClassLoader(bindings) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals(ColoryrVulkanCursor.class.getName())) {
                        return ColoryrVulkanCursor.class;
                    }
                    if (!name.equals(BACKEND) && !name.startsWith(BACKEND + "$")) {
                        return super.loadClass(name, resolve);
                    }
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) {
                            try (InputStream in = ColoryrVulkanCursor.class.getResourceAsStream(
                                    "/" + name.replace('.', '/') + ".class")) {
                                if (in == null) throw new ClassNotFoundException(name);
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                byte[] buffer = new byte[8192];
                                for (int count; (count = in.read(buffer)) != -1;) out.write(buffer, 0, count);
                                byte[] bytes = out.toByteArray();
                                type = defineClass(name, bytes, 0, bytes.length);
                            } catch (Exception error) {
                                throw new ClassNotFoundException(name, error);
                            }
                        }
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
            };
            result = Class.forName(BACKEND, true, bridgeLoader);
            BACKENDS.put(bindings, result);
        }
        return result;
    }

    private static Object invoke(ClassLoader loader, String name, Class<?>[] types, Object... args) throws Exception {
        try {
            return backend(loader).getMethod(name, types).invoke(null, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        }
    }

    public static void surfaceCreated(int result, Class<?> api, long window, long output, boolean sdl) {
        if (!ColoryrCursor.ENABLED) return;
        try {
            invoke(api.getClassLoader(), "surfaceCreated",
                    new Class<?>[]{int.class, Class.class, long.class, long.class, boolean.class},
                    result, api, window, output, sdl);
        } catch (Throwable error) {
            report(error);
        }
    }

    public static void queueCreated(Object device, int family, long output) {
        if (!ColoryrCursor.ENABLED) return;
        try {
            invoke(device.getClass().getClassLoader(), "queueCreated",
                    new Class<?>[]{Object.class, int.class, long.class}, device, family, output);
        } catch (Throwable error) {
            report(error);
        }
    }

    public static void surfaceDestroyed(Object instance, long surface) {
        if (!ColoryrCursor.ENABLED) return;
        try {
            invoke(instance.getClass().getClassLoader(), "surfaceDestroyed",
                    new Class<?>[]{Object.class, long.class}, instance, surface);
        } catch (Throwable error) {
            report(error);
        }
    }

    public static void prepareSwapchain(Object device, long info) {
        if (!ColoryrCursor.ENABLED) return;
        try {
            invoke(device.getClass().getClassLoader(), "prepareSwapchain",
                    new Class<?>[]{Object.class, long.class}, device, info);
        } catch (Throwable error) {
            report(error);
        }
    }

    public static void swapchainCreated(int result, Object device, long info, long output) {
        if (!ColoryrCursor.ENABLED) return;
        try {
            invoke(device.getClass().getClassLoader(), "swapchainCreated",
                    new Class<?>[]{int.class, Object.class, long.class, long.class}, result, device, info, output);
        } catch (Throwable error) {
            report(error);
        }
    }

    public static int present(Object queue, long info) {
        Class<?> type;
        try {
            type = backend(queue.getClass().getClassLoader());
        } catch (Exception error) {
            report(error);
            return nativePresent(queue, info);
        }
        try {
            // Backend contains the fallback; never present twice after an overlay submission.
            return (Integer) type.getMethod("present", Object.class, long.class).invoke(null, queue, info);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Vulkan present failed", error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static int nativePresent(Object queue, long info) {
        try {
            ClassLoader loader = queue.getClass().getClassLoader();
            long address = (Long) queue.getClass().getMethod("address").invoke(queue);
            Object caps = queue.getClass().getMethod("getCapabilities").invoke(queue);
            long function = caps.getClass().getField("vkQueuePresentKHR").getLong(caps);
            Method nativeCall = Class.forName("org.lwjgl.system.JNI", true, loader)
                    .getMethod("callPPI", long.class, long.class, long.class);
            return (Integer) nativeCall.invoke(null, address, info, function);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Vulkan present entry point is unavailable", error);
        }
    }

    public static void destroySwapchain(Object device, long swapchain) {
        if (!ColoryrCursor.ENABLED) return;
        try {
            invoke(device.getClass().getClassLoader(), "destroySwapchain",
                    new Class<?>[]{Object.class, long.class}, device, swapchain);
        } catch (Throwable error) {
            report(error);
        }
    }

    public static void destroyDevice(Object device) {
        if (!ColoryrCursor.ENABLED) return;
        try {
            invoke(device.getClass().getClassLoader(), "destroyDevice", new Class<?>[]{Object.class}, device);
        } catch (Throwable error) {
            report(error);
        }
    }

    public static double[] frame(Class<?> api, long window, int width, int height, boolean sdl) throws Exception {
        ColoryrCursorFrame frame = sdl
                ? ColoryrSdlCursor.frame(api.getClassLoader(), window, width, height)
                : ColoryrCursor.glfwFrame(api.getClassLoader(), window, width, height);
        return frame == null ? null : new double[]{frame.x, frame.y, frame.sizeX, frame.sizeY};
    }

    public static void activate(long window, boolean sdl) throws Exception {
        if (sdl) ColoryrSdlCursor.activate(window);
        else ColoryrCursor.activateGlfw(window);
    }

    public static void restore(long window, boolean sdl) {
        if (sdl) ColoryrSdlCursor.restore(window);
        else ColoryrCursor.restoreGlfw(window);
    }

    public static ByteBuffer texture(int[] dimensions, float[] hotspot) throws Exception {
        ColoryrCursorImage image = ColoryrCursorImage.load();
        dimensions[0] = image.width;
        dimensions[1] = image.height;
        hotspot[0] = image.hotspotX;
        hotspot[1] = image.hotspotY;
        return image.pixels();
    }

    public static boolean enabled() {
        return ColoryrCursor.ENABLED;
    }

    public static void report(Throwable error) {
        System.out.println("[ColoryrASM] Vulkan software cursor: " + error);
    }
}
