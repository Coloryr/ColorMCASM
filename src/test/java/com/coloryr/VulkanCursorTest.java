package com.coloryr;

import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

class VulkanCursorTest {
    @Test
    void drawsIntoRealSwapchainAndPreservesBackgroundAcrossPresentAndReacquire() throws Exception {
        assumeTrue(glfwInit(), "GLFW is unavailable");
        assumeTrue(GLFWVulkan.glfwVulkanSupported(), "Vulkan is unavailable");
        Path texture = Files.createTempFile("cursor-vk", ".png");
        String oldTexture = System.getProperty("coloryrasm.cursor.texture");
        String oldX = System.getProperty("coloryrasm.cursor.hotspotX");
        String oldY = System.getProperty("coloryrasm.cursor.hotspotY");
        BufferedImage png = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        png.setRGB(0, 0, 0xFF000000);
        png.setRGB(1, 0, 0xFF000000);
        png.setRGB(0, 1, 0xFFFFFFFF);
        png.setRGB(1, 1, 0xFFFFFFFF);
        ImageIO.write(png, "png", texture.toFile());
        System.setProperty("coloryrasm.cursor.texture", texture.toString());
        System.setProperty("coloryrasm.cursor.hotspotX", "1");
        System.setProperty("coloryrasm.cursor.hotspotY", "1");
        try (Context context = new Context()) {
            context.create();
            boolean[] seen = new boolean[context.images.length];
            boolean[] cursorDrawn = new boolean[context.images.length];
            int checkedDrawn = 0, checkedHidden = 0;
            for (int frame = 0; frame < 20; frame++) {
                int image = context.acquire();
                if (seen[image]) {
                    ByteBuffer pixels = context.read(image);
                    try {
                        context.checkPixels(pixels, cursorDrawn[image]);
                        if (cursorDrawn[image]) {
                            checkedDrawn++;
                        } else {
                            checkedHidden++;
                        }
                    } finally {
                        vkUnmapMemory(context.device, context.readMemory);
                    }
                }
                boolean draw = frame < 5 || frame >= 10;
                int requested = draw ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED;
                int mode = ColoryrCursor.cursorMode(context.window, GLFW_CURSOR, requested);
                glfwSetInputMode(context.window, GLFW_CURSOR, mode);
                context.clearAndPresent(image, !seen[image], draw);
                assertEquals(draw ? GLFW_CURSOR_HIDDEN : GLFW_CURSOR_DISABLED,
                        glfwGetInputMode(context.window, GLFW_CURSOR));
                seen[image] = true;
                cursorDrawn[image] = draw;
            }
            assertTrue(checkedDrawn > 0, "Read back at least one presented cursor image");
            assertTrue(checkedHidden > 0, "Read back gameplay with the cursor hidden");
            ColoryrCursor.cursorMode(context.window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            ColoryrVulkanCursor.destroySwapchain(context.device, context.swapchain);
            assertEquals(GLFW_CURSOR_NORMAL, glfwGetInputMode(context.window, GLFW_CURSOR));
        } finally {
            glfwTerminate();
            restoreProperty("coloryrasm.cursor.texture", oldTexture);
            restoreProperty("coloryrasm.cursor.hotspotX", oldX);
            restoreProperty("coloryrasm.cursor.hotspotY", oldY);
            Files.deleteIfExists(texture);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static final class Context implements AutoCloseable {
        long window, surface, swapchain, pool, acquireFence, commandFence;
        long readBuffer, readMemory;
        VkInstance instance;
        VkPhysicalDevice physical;
        VkDevice device;
        VkQueue queue;
        VkCommandBuffer command;
        long[] images, finished;
        int family, format, width, height;

        void create() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                window = glfwCreateWindow(128, 128, "Vulkan cursor test", 0, 0);
                assertNotEquals(0, window);
                PointerBuffer pointer = stack.mallocPointer(1);
                VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default()
                        .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default()
                                .pApplicationName(stack.UTF8("cursor-test")).apiVersion(VK_API_VERSION_1_0))
                        .ppEnabledExtensionNames(GLFWVulkan.glfwGetRequiredInstanceExtensions());
                check(vkCreateInstance(instanceInfo, null, pointer));
                instance = new VkInstance(pointer.get(0), instanceInfo);
                LongBuffer output = stack.mallocLong(1);
                check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, output));
                surface = output.get(0);
                ColoryrVulkanCursor.surfaceCreated(VK_SUCCESS, GLFW.class, window, MemoryUtil.memAddress(output));
                IntBuffer count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null));
                PointerBuffer physicals = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, physicals));
                for (int p = 0; p < physicals.remaining() && physical == null; p++) {
                    VkPhysicalDevice candidate = new VkPhysicalDevice(physicals.get(p), instance);
                    vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                    VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
                    vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                    for (int q = 0; q < families.remaining(); q++) {
                        IntBuffer supported = stack.ints(0);
                        check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, q, surface, supported));
                        if (supported.get(0) != 0 && (families.get(q).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                            physical = candidate;
                            family = q;
                            break;
                        }
                    }
                }
                assumeTrue(physical != null, "No graphics/presentation queue");
                VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
                        .pQueueCreateInfos(VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default()
                                .queueFamilyIndex(family).pQueuePriorities(stack.floats(1)))
                        .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
                check(vkCreateDevice(physical, deviceInfo, null, pointer));
                device = new VkDevice(pointer.get(0), physical, deviceInfo);
                vkGetDeviceQueue(device, family, 0, pointer);
                ColoryrVulkanCursor.queueCreated(device, family, pointer.address());
                queue = new VkQueue(pointer.get(0), device);
                VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
                check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, caps));
                int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                assumeTrue((caps.supportedUsageFlags() & usage) == usage, "Swapchain readback is unavailable");
                assumeTrue((caps.supportedTransforms() & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0);
                width = caps.currentExtent().width() == -1 ? 128 : caps.currentExtent().width();
                height = caps.currentExtent().height() == -1 ? 128 : caps.currentExtent().height();
                check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, null));
                VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
                check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, formats));
                VkSurfaceFormatKHR chosen = null;
                for (VkSurfaceFormatKHR candidate : formats) {
                    if (candidate.format() == VK_FORMAT_R8G8B8A8_UNORM || candidate.format() == VK_FORMAT_B8G8R8A8_UNORM) {
                        chosen = candidate;
                        break;
                    }
                }
                assumeTrue(chosen != null, "No UNORM swapchain format for exact pixel assertions");
                format = chosen.format();
                int imageCount = caps.minImageCount() + 1;
                if (caps.maxImageCount() > 0) imageCount = Math.min(imageCount, caps.maxImageCount());
                VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                        .surface(surface).minImageCount(imageCount).imageFormat(format).imageColorSpace(chosen.colorSpace())
                        .imageArrayLayers(1).imageUsage(usage).imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .preTransform(VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                        .compositeAlpha(Integer.lowestOneBit(caps.supportedCompositeAlpha()))
                        .presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true);
                info.imageExtent().set(width, height);
                ColoryrVulkanCursor.prepareSwapchain(device, info.address());
                assertTrue((info.imageUsage() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0);
                int result = vkCreateSwapchainKHR(device, info, null, output);
                ColoryrVulkanCursor.swapchainCreated(result, device, info.address(), MemoryUtil.memAddress(output));
                check(result);
                assertEquals(usage, info.imageUsage());
                swapchain = output.get(0);
                check(vkGetSwapchainImagesKHR(device, swapchain, count, null));
                LongBuffer handles = stack.mallocLong(count.get(0));
                check(vkGetSwapchainImagesKHR(device, swapchain, count, handles));
                images = new long[count.get(0)];
                finished = new long[images.length];
                for (int i = 0; i < images.length; i++) {
                    images[i] = handles.get(i);
                    check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, output));
                    finished[i] = output.get(0);
                }
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, output));
                acquireFence = output.get(0);
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, output));
                commandFence = output.get(0);
                check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                        .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, output));
                pool = output.get(0);
                check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(pool).commandBufferCount(1).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY), pointer));
                command = new VkCommandBuffer(pointer.get(0), device);
                check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default()
                        .size(width * height * 4L).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT), null, output));
                readBuffer = output.get(0);
                VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device, readBuffer, req);
                VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
                vkGetPhysicalDeviceMemoryProperties(physical, memory);
                int type = -1;
                for (int i = 0; i < memory.memoryTypeCount(); i++) {
                    int required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                    if ((req.memoryTypeBits() & (1 << i)) != 0 && (memory.memoryTypes(i).propertyFlags() & required) == required) {
                        type = i;
                        break;
                    }
                }
                assertTrue(type >= 0);
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(req.size()).memoryTypeIndex(type), null, output));
                readMemory = output.get(0);
                check(vkBindBufferMemory(device, readBuffer, readMemory, 0));
            }
        }

        int acquire() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer index = stack.mallocInt(1);
                check(vkAcquireNextImageKHR(device, swapchain, 5_000_000_000L, VK_NULL_HANDLE, acquireFence, index));
                check(vkWaitForFences(device, acquireFence, true, 5_000_000_000L));
                check(vkResetFences(device, acquireFence));
                return index.get(0);
            }
        }

        void begin(MemoryStack stack) {
            check(vkResetCommandBuffer(command, 0));
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)));
        }

        void submit(MemoryStack stack, long signal) {
            check(vkEndCommandBuffer(command));
            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command.address()));
            if (signal != 0) submit.pSignalSemaphores(stack.longs(signal));
            check(vkQueueSubmit(queue, submit, commandFence));
            check(vkWaitForFences(device, commandFence, true, 5_000_000_000L));
            check(vkResetFences(device, commandFence));
        }

        void barrier(MemoryStack stack, long image, int from, int to, int srcAccess, int dstAccess, int srcStage, int dstStage) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .image(image).oldLayout(from).newLayout(to).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1).levelCount(1);
            vkCmdPipelineBarrier(command, srcStage, dstStage, 0, null, null, barrier);
        }

        ByteBuffer read(int index) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                begin(stack);
                barrier(stack, images[index], VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(width, height, 1);
                vkCmdCopyImageToBuffer(command, images[index], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readBuffer, region);
                VkBufferMemoryBarrier.Buffer host = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(readBuffer).size(VK_WHOLE_SIZE);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, host, null);
                barrier(stack, images[index], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_ACCESS_TRANSFER_READ_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                submit(stack, 0);
                PointerBuffer mapped = stack.mallocPointer(1);
                check(vkMapMemory(device, readMemory, 0, width * height * 4L, 0, mapped));
                return MemoryUtil.memByteBuffer(mapped.get(0), width * height * 4);
            }
        }

        void clearAndPresent(int index, boolean first, boolean draw) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                begin(stack);
                barrier(stack, images[index], first ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkClearColorValue clear = VkClearColorValue.calloc(stack);
                clear.float32(0, 0.25f).float32(1, 0.5f).float32(2, 0.75f).float32(3, 1);
                vkCmdClearColorImage(command, images[index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear,
                        VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1).levelCount(1));
                barrier(stack, images[index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                submit(stack, finished[index]);
                VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack).sType$Default()
                        .pWaitSemaphores(stack.longs(finished[index])).swapchainCount(1)
                        .pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(index));
                long waits = MemoryUtil.memAddress(info.pWaitSemaphores());
                if (draw) {
                    // Follow the live pointer with the hidden window; never warp the user's mouse.
                    DoubleBuffer mouseX = stack.mallocDouble(1), mouseY = stack.mallocDouble(1);
                    IntBuffer windowX = stack.mallocInt(1), windowY = stack.mallocInt(1);
                    glfwGetCursorPos(window, mouseX, mouseY);
                    glfwGetWindowPos(window, windowX, windowY);
                    glfwSetWindowPos(window, windowX.get(0) + (int) mouseX.get(0) - 20,
                            windowY.get(0) + (int) mouseY.get(0) - 30);
                }
                check(ColoryrVulkanCursor.present(queue, info.address()));
                assertEquals(waits, MemoryUtil.memAddress(info.pWaitSemaphores()), "Do not mutate the game's present info");
            }
        }

        void checkPixels(ByteBuffer pixels, boolean drawn) {
            int red = format == VK_FORMAT_B8G8R8A8_UNORM ? 2 : 0;
            int blue = 2 - red;
            int background = 0, white = 0, black = 0;
            // The pointer can move between frames. Inspect the whole image instead of
            // retaining a stale screen coordinate from before the first present.
            for (int offset = 0; offset < pixels.limit(); offset += 4) {
                int r = pixels.get(offset + red) & 255;
                int g = pixels.get(offset + 1) & 255;
                int b = pixels.get(offset + blue) & 255;
                if (Math.abs(r - 64) <= 1 && Math.abs(g - 128) <= 1 && Math.abs(b - 191) <= 1) {
                    background++;
                } else if (r == 255 && g == 255 && b == 255) {
                    white++;
                } else if (r == 0 && g == 0 && b == 0) {
                    black++;
                } else {
                    fail("Unexpected swapchain pixel at " + (offset / 4 % width) + ","
                            + (offset / 4 / width) + ": " + r + "," + g + "," + b);
                }
            }
            if (drawn) {
                assertTrue(white > 10, "The cursor contains its opaque white fill");
                assertTrue(black > 10, "The cursor contains its black outline");
                assertTrue(background > width * height * 0.9, "The existing game image is preserved");
            } else {
                assertEquals(width * height, background, "Grabbed mouse leaves the entire game image unchanged");
            }
        }

        @Override
        public void close() {
            if (device != null) {
                vkDeviceWaitIdle(device);
                ColoryrVulkanCursor.destroyDevice(device);
                if (finished != null) for (long semaphore : finished) if (semaphore != 0) vkDestroySemaphore(device, semaphore, null);
                if (readBuffer != 0) vkDestroyBuffer(device, readBuffer, null);
                if (readMemory != 0) vkFreeMemory(device, readMemory, null);
                if (acquireFence != 0) vkDestroyFence(device, acquireFence, null);
                if (commandFence != 0) vkDestroyFence(device, commandFence, null);
                if (pool != 0) vkDestroyCommandPool(device, pool, null);
                if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
                vkDestroyDevice(device, null);
            }
            if (instance != null) {
                if (surface != 0) {
                    ColoryrVulkanCursor.surfaceDestroyed(instance, surface);
                    vkDestroySurfaceKHR(instance, surface, null);
                }
                vkDestroyInstance(instance, null);
            }
            if (window != 0) {
                ColoryrCursor.destroyGlfw(GLFW.class, window);
                glfwDestroyWindow(window);
            }
        }
    }

    private static void check(int result) {
        assertEquals(VK_SUCCESS, result, "Vulkan result");
    }
}
