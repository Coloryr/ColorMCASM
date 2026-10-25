package com.coloryr;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/** Loaded against the game's Vulkan bindings, never on the OpenGL-only path. */
public final class ColoryrVulkanBackend {
    private static final Map<Long, Surface> SURFACES = new ConcurrentHashMap<>();
    private static final Map<Long, QueueInfo> QUEUES = new ConcurrentHashMap<>();
    private static final Map<Long, Overlay> SWAPCHAINS = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> ORIGINAL_USAGE = new ConcurrentHashMap<>();

    private ColoryrVulkanBackend() {
    }

    public static void surfaceCreated(int result, Class<?> api, long window, long output, boolean sdl) {
        if ((sdl ? result != 0 : result == VK_SUCCESS) && output != 0) {
            long surface = MemoryUtil.memGetLong(output);
            if (surface != 0) SURFACES.put(surface, new Surface(api, window, sdl));
        }
    }

    public static void queueCreated(Object handle, int family, long output) {
        if (output != 0) {
            VkDevice device = (VkDevice) handle;
            long queue = MemoryUtil.memGetAddress(output);
            if (queue != 0) QUEUES.put(queue, new QueueInfo(device.address(), family));
        }
    }

    public static void surfaceDestroyed(Object instance, long surface) {
        SURFACES.remove(surface);
    }

    public static void prepareSwapchain(Object handle, long address) {
        VkDevice device = (VkDevice) handle;
        VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.create(address);
        if (!eligible(info)) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.getPhysicalDevice(), info.surface(), caps),
                    "surface capabilities");
            VkFormatProperties format = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), info.imageFormat(), format);
            int features = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT;
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0
                    && (format.optimalTilingFeatures() & features) == features) {
                ORIGINAL_USAGE.put(address, info.imageUsage());
                info.imageUsage(info.imageUsage() | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
            }
        } catch (Throwable error) {
            ColoryrVulkanCursor.report(error);
        }
    }

    public static void swapchainCreated(int result, Object handle, long address, long output) {
        VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.create(address);
        Integer usage = ORIGINAL_USAGE.remove(address);
        try {
            if (result == VK_SUCCESS && output != 0 && eligible(info)
                    && (info.imageUsage() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0) {
                long swapchain = MemoryUtil.memGetLong(output);
                if (swapchain != 0) SWAPCHAINS.put(swapchain, new Overlay((VkDevice) handle, swapchain,
                        SURFACES.get(info.surface()), info));
            }
        } finally {
            if (usage != null) info.imageUsage(usage);
        }
    }

    private static boolean eligible(VkSwapchainCreateInfoKHR info) {
        return ColoryrVulkanCursor.enabled() && SURFACES.containsKey(info.surface())
                && info.flags() == 0 && info.pNext() == 0 && info.imageArrayLayers() == 1
                && info.preTransform() == VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                && info.imageExtent().width() > 0 && info.imageExtent().height() > 0
                && info.presentMode() >= VK_PRESENT_MODE_IMMEDIATE_KHR
                && info.presentMode() <= VK_PRESENT_MODE_FIFO_RELAXED_KHR;
    }

    public static int present(Object handle, long address) {
        VkQueue queue = (VkQueue) handle;
        VkPresentInfoKHR original = VkPresentInfoKHR.create(address);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long effective = address;
            Overlay overlay = null;
            try {
                // A group present would need one overlay submission covering all swapchains.
                if (original.swapchainCount() == 1 && ColoryrVulkanCursor.enabled()) {
                    overlay = SWAPCHAINS.get(original.pSwapchains().get(0));
                    if (overlay != null && !overlay.failed) {
                        QueueInfo queueInfo = QUEUES.get(queue.address());
                        if (queueInfo == null || queueInfo.device != overlay.device.address()
                                || queue.getDevice().address() != overlay.device.address()) {
                            throw new IllegalStateException("Untracked Vulkan presentation queue");
                        }
                        double[] frame = overlay.surface.frame(overlay.width, overlay.height);
                        if (frame != null) {
                            if (!overlay.ready) overlay.init(queue, queueInfo.family);
                            if (overlay.family != queueInfo.family) {
                                throw new IllegalStateException("Vulkan presentation queue family changed");
                            }
                            int index = original.pImageIndices().get(0);
                            Image image = overlay.images[index];
                            overlay.record(image, frame);
                            VkPresentInfoKHR replacement = VkPresentInfoKHR.malloc(stack).set(original);
                            replacement.pWaitSemaphores(stack.longs(image.complete));
                            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack).sType$Default()
                                    .pCommandBuffers(stack.pointers(image.command.address()))
                                    .pSignalSemaphores(stack.longs(image.complete));
                            LongBuffer waits = original.pWaitSemaphores();
                            if (waits != null && waits.hasRemaining()) {
                                IntBuffer stages = stack.mallocInt(waits.remaining());
                                while (stages.hasRemaining()) stages.put(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                                stages.flip();
                                submit.pWaitSemaphores(waits).pWaitDstStageMask(stages);
                            }
                            check(vkResetFences(overlay.device, image.fence), "reset overlay fence");
                            check(vkQueueSubmit(queue, submit, image.fence), "submit overlay");
                            // From here on the original semaphores have been consumed. Always
                            // present using the overlay semaphore, including visibility failures.
                            effective = replacement.address();
                            overlay.surface.activate();
                        } else {
                            overlay.surface.restore();
                        }
                    }
                } else {
                    for (int i = 0; i < original.swapchainCount(); i++) {
                        Overlay state = SWAPCHAINS.get(original.pSwapchains().get(i));
                        if (state != null) state.surface.restore();
                    }
                }
            } catch (Throwable error) {
                if (overlay != null) {
                    overlay.failed = true;
                    overlay.surface.restore();
                }
                ColoryrVulkanCursor.report(error);
            }
            return org.lwjgl.system.JNI.callPPI(queue.address(), effective,
                    queue.getCapabilities().vkQueuePresentKHR);
        }
    }

    public static void destroySwapchain(Object handle, long swapchain) {
        Overlay overlay = SWAPCHAINS.remove(swapchain);
        if (overlay != null) {
            vkDeviceWaitIdle(overlay.device);
            overlay.close();
            overlay.surface.restore();
        }
    }

    public static void destroyDevice(Object handle) {
        VkDevice device = (VkDevice) handle;
        for (Overlay overlay : new ArrayList<>(SWAPCHAINS.values())) {
            if (overlay.device.address() == device.address()) destroySwapchain(device, overlay.swapchain);
        }
        QUEUES.entrySet().removeIf(entry -> entry.getValue().device == device.address());
    }

    private static final class Surface {
        final Class<?> api;
        final long window;
        final boolean sdl;

        Surface(Class<?> api, long window, boolean sdl) {
            this.api = api;
            this.window = window;
            this.sdl = sdl;
        }

        double[] frame(int width, int height) throws Exception {
            return ColoryrVulkanCursor.frame(api, window, width, height, sdl);
        }

        void activate() throws Exception {
            ColoryrVulkanCursor.activate(window, sdl);
        }

        void restore() {
            ColoryrVulkanCursor.restore(window, sdl);
        }
    }

    private static final class QueueInfo {
        final long device;
        final int family;

        QueueInfo(long device, int family) {
            this.device = device;
            this.family = family;
        }
    }

    private static final class Image {
        long view, framebuffer, complete, fence;
        VkCommandBuffer command;
    }

    private static final class Overlay {
        final VkDevice device;
        final long swapchain;
        final Surface surface;
        final int width, height, format;
        final int[] sharingFamilies;
        final int[] textureSize = new int[2];
        final float[] hotspot = new float[2];
        int family;
        boolean ready, failed;
        Image[] images;
        long pool, texture, textureMemory, textureView, sampler;
        long renderPass, descriptorLayout, descriptorPool, descriptorSet, pipelineLayout, pipeline;

        Overlay(VkDevice device, long swapchain, Surface surface, VkSwapchainCreateInfoKHR info) {
            this.device = device;
            this.swapchain = swapchain;
            this.surface = surface;
            width = info.imageExtent().width();
            height = info.imageExtent().height();
            format = info.imageFormat();
            IntBuffer families = info.imageSharingMode() == VK_SHARING_MODE_CONCURRENT
                    ? info.pQueueFamilyIndices() : null;
            sharingFamilies = families == null ? null : new int[families.remaining()];
            if (families != null) {
                for (int i = 0; i < sharingFamilies.length; i++) sharingFamilies[i] = families.get(i);
            }
        }

        void init(VkQueue queue, int family) throws Exception {
            this.family = family;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer count = stack.ints(0);
                vkGetPhysicalDeviceQueueFamilyProperties(device.getPhysicalDevice(), count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(device.getPhysicalDevice(), count, families);
                if (family >= families.remaining() || (families.get(family).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) {
                    throw new IllegalStateException("Vulkan present queue does not support graphics");
                }
                if (sharingFamilies != null) {
                    boolean found = false;
                    for (int allowed : sharingFamilies) found |= allowed == family;
                    if (!found) throw new IllegalStateException("Vulkan present queue cannot access this swapchain");
                }
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                        .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, out),
                        "create overlay command pool");
                pool = out.get(0);
                uploadTexture(queue, stack);
                createPipeline(stack);
                check(vkGetSwapchainImagesKHR(device, swapchain, count, null), "count swapchain images");
                LongBuffer handles = stack.mallocLong(count.get(0));
                check(vkGetSwapchainImagesKHR(device, swapchain, count, handles), "get swapchain images");
                images = new Image[count.get(0)];
                PointerBuffer commands = stack.mallocPointer(images.length);
                check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(images.length), commands),
                        "allocate overlay commands");
                for (int i = 0; i < images.length; i++) {
                    Image image = new Image();
                    images[i] = image;
                    image.command = new VkCommandBuffer(commands.get(i), device);
                    image.view = imageView(handles.get(i), format, stack);
                    VkFramebufferCreateInfo fb = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                            .renderPass(renderPass).pAttachments(stack.longs(image.view)).width(width).height(height).layers(1);
                    check(vkCreateFramebuffer(device, fb, null, out), "create overlay framebuffer");
                    image.framebuffer = out.get(0);
                    check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, out),
                            "create overlay semaphore");
                    image.complete = out.get(0);
                    check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default()
                            .flags(VK_FENCE_CREATE_SIGNALED_BIT), null, out), "create overlay fence");
                    image.fence = out.get(0);
                }
                ready = true;
            } catch (Exception | LinkageError error) {
                vkDeviceWaitIdle(device);
                close();
                throw error;
            }
        }

        private void uploadTexture(VkQueue queue, MemoryStack stack) throws Exception {
            ByteBuffer pixels = ColoryrVulkanCursor.texture(textureSize, hotspot);
            long staging = 0, stagingMemory = 0;
            long uploadFence = 0;
            VkCommandBuffer command = null;
            try {
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default()
                        .size(pixels.remaining()).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, out), "create texture staging buffer");
                staging = out.get(0);
                VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device, staging, req);
                stagingMemory = allocate(req, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);
                check(vkBindBufferMemory(device, staging, stagingMemory, 0), "bind staging buffer");
                PointerBuffer mapped = stack.mallocPointer(1);
                check(vkMapMemory(device, stagingMemory, 0, req.size(), 0, mapped), "map staging buffer");
                MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), mapped.get(0), pixels.remaining());
                vkUnmapMemory(device, stagingMemory);
                VkImageCreateInfo image = VkImageCreateInfo.calloc(stack).sType$Default()
                        .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                        .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                image.extent().set(textureSize[0], textureSize[1], 1);
                check(vkCreateImage(device, image, null, out), "create cursor texture");
                texture = out.get(0);
                vkGetImageMemoryRequirements(device, texture, req);
                textureMemory = allocate(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
                check(vkBindImageMemory(device, texture, textureMemory, 0), "bind cursor texture");
                PointerBuffer commands = stack.mallocPointer(1);
                check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), commands),
                        "allocate texture upload command");
                command = new VkCommandBuffer(commands.get(0), device);
                check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin texture upload");
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(texture).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, null, barrier);
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                copy.imageExtent().set(textureSize[0], textureSize[1], 1);
                vkCmdCopyBufferToImage(command, staging, texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        0, null, null, barrier);
                check(vkEndCommandBuffer(command), "end texture upload");
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, out),
                        "create upload fence");
                uploadFence = out.get(0);
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(1, stack).sType$Default()
                        .pCommandBuffers(stack.pointers(command.address())), uploadFence), "upload cursor texture");
                check(vkWaitForFences(device, uploadFence, true, -1L), "wait for texture upload");
                textureView = imageView(texture, VK_FORMAT_R8G8B8A8_UNORM, stack);
                check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, out), "create cursor sampler");
                sampler = out.get(0);
            } finally {
                if (uploadFence != 0) vkDestroyFence(device, uploadFence, null);
                if (command != null) vkFreeCommandBuffers(device, pool, command);
                if (staging != 0) vkDestroyBuffer(device, staging, null);
                if (stagingMemory != 0) vkFreeMemory(device, stagingMemory, null);
            }
        }

        private long allocate(VkMemoryRequirements req, int properties, MemoryStack stack) {
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memory);
            for (int i = 0; i < memory.memoryTypeCount(); i++) {
                if ((req.memoryTypeBits() & (1 << i)) != 0
                        && (memory.memoryTypes(i).propertyFlags() & properties) == properties) {
                    LongBuffer out = stack.mallocLong(1);
                    check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                            .allocationSize(req.size()).memoryTypeIndex(i), null, out), "allocate cursor memory");
                    return out.get(0);
                }
            }
            throw new IllegalStateException("No compatible Vulkan cursor memory type");
        }

        private long imageView(long image, int format, MemoryStack stack) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateImageView(device, info, null, out), "create cursor image view");
            return out.get(0);
        }

        private void createPipeline(MemoryStack stack) throws Exception {
            LongBuffer out = stack.mallocLong(1);
            VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack)
                    .format(format).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            VkAttachmentReference.Buffer reference = VkAttachmentReference.calloc(1, stack)
                    .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(reference);
            VkSubpassDependency.Buffer deps = VkSubpassDependency.calloc(2, stack);
            deps.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            deps.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT);
            check(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(attachment).pSubpasses(subpass).pDependencies(deps), null, out), "create cursor render pass");
            renderPass = out.get(0);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(binding), null, out), "create cursor descriptor layout");
            descriptorLayout = out.get(0);
            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT).size(16);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push), null, out), "create cursor pipeline layout");
            pipelineLayout = out.get(0);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(VkDescriptorPoolSize.calloc(1, stack)
                            .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)), null, out), "create cursor descriptor pool");
            descriptorPool = out.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), out), "allocate cursor descriptor");
            descriptorSet = out.get(0);
            VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler)
                    .imageView(textureView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            vkUpdateDescriptorSets(device, VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .dstSet(descriptorSet).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(image), null);
            long vertex = 0, fragment = 0;
            try {
                vertex = shader("/coloryrasm/cursor.vert.spv", stack);
                fragment = shader("/coloryrasm/cursor.frag.spv", stack);
                VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));
                stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
                VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                        .blendEnable(true).srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK_BLEND_OP_ADD).colorWriteMask(15);
                VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                        .pStages(stages)
                        .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                        .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP))
                        .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                                .viewportCount(1).scissorCount(1))
                        .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                                .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1))
                        .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                        .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                        .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                        .layout(pipelineLayout).renderPass(renderPass).subpass(0);
                check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, out), "create cursor pipeline");
                pipeline = out.get(0);
            } finally {
                if (vertex != 0) vkDestroyShaderModule(device, vertex, null);
                if (fragment != 0) vkDestroyShaderModule(device, fragment, null);
            }
        }

        private long shader(String path, MemoryStack stack) throws Exception {
            try (InputStream in = ColoryrVulkanCursor.class.getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("Missing cursor shader: " + path);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] bytes = new byte[4096];
                for (int count; (count = in.read(bytes)) != -1;) out.write(bytes, 0, count);
                ByteBuffer code = stack.malloc(out.size());
                code.put(out.toByteArray()).flip();
                LongBuffer output = stack.mallocLong(1);
                check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                        .pCode(code), null, output), "create cursor shader");
                return output.get(0);
            }
        }

        void record(Image image, double[] frame) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(vkWaitForFences(device, image.fence, true, -1L), "wait for overlay command");
                check(vkResetCommandBuffer(image.command, 0), "reset overlay command");
                check(vkBeginCommandBuffer(image.command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin overlay command");
                VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                        .renderPass(renderPass).framebuffer(image.framebuffer);
                begin.renderArea().extent().set(width, height);
                vkCmdBeginRenderPass(image.command, begin, VK_SUBPASS_CONTENTS_INLINE);
                vkCmdBindPipeline(image.command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                vkCmdBindDescriptorSets(image.command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                        0, stack.longs(descriptorSet), null);
                vkCmdSetViewport(image.command, 0, VkViewport.calloc(1, stack)
                        .width(width).height(height).minDepth(0).maxDepth(1));
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.extent().set(width, height);
                vkCmdSetScissor(image.command, 0, scissor);
                float pixelX = (float) frame[2] / textureSize[1], pixelY = (float) frame[3] / textureSize[1];
                vkCmdPushConstants(image.command, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, stack.floats(
                        (float) (2 * (frame[0] - hotspot[0] * pixelX) / width - 1),
                        (float) (2 * (frame[1] - hotspot[1] * pixelY) / height - 1),
                        2 * textureSize[0] * pixelX / width, 2 * textureSize[1] * pixelY / height));
                vkCmdDraw(image.command, 4, 1, 0, 0);
                vkCmdEndRenderPass(image.command);
                check(vkEndCommandBuffer(image.command), "end overlay command");
            }
        }

        void close() {
            if (images != null) {
                for (Image image : images) {
                    if (image == null) continue;
                    if (image.framebuffer != 0) vkDestroyFramebuffer(device, image.framebuffer, null);
                    if (image.view != 0) vkDestroyImageView(device, image.view, null);
                    if (image.complete != 0) vkDestroySemaphore(device, image.complete, null);
                    if (image.fence != 0) vkDestroyFence(device, image.fence, null);
                }
                images = null;
            }
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
            if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
            if (renderPass != 0) vkDestroyRenderPass(device, renderPass, null);
            if (sampler != 0) vkDestroySampler(device, sampler, null);
            if (textureView != 0) vkDestroyImageView(device, textureView, null);
            if (texture != 0) vkDestroyImage(device, texture, null);
            if (textureMemory != 0) vkFreeMemory(device, textureMemory, null);
            if (pool != 0) vkDestroyCommandPool(device, pool, null);
            pipeline = pipelineLayout = descriptorPool = descriptorLayout = renderPass = 0;
            sampler = textureView = texture = textureMemory = pool = 0;
            ready = false;
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: " + result);
    }
}
