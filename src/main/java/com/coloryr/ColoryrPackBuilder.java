package com.coloryr;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 与启动器之间的消息构建与解析
 *
 * 消息格式为 `[i32 类型][消息体]`，没有总长度前缀，消息体按字段自定界：
 * 字符串是 `[i32 长度][UTF-8 字节]`，布尔 1 字节，i32 与 f64 均大端。
 *
 * 启动器发往游戏的消息：
 * - 2 服务器 MOTD：ip、端口、MOTD，均为字符串
 * - 5 鼠标移动：dx(f64) + dy(f64)，相对位移，单位像素；抓取鼠标时转视角，否则移动光标
 * - 6 鼠标按键：button(i32) + down(bool)，button 与 GLFW 一致，0 左键 1 右键 2 中键
 * - 7 键盘按键：key(i32) + down(bool)，key 为 GLFW 键码
 * - 8 鼠标滚轮：amount(i32)，正数向上
 * - 10 窗口标题：标题字符串
 * - 12 连接验证：密钥字符串，连接建立后由启动器最先发送
 *
 * 游戏发往启动器的消息：
 * - 1 鼠标状态：uuid 字符串 + 是否抓取鼠标
 * - 9 游戏通道：uuid 字符串
 * - 11 窗口大小：uuid 字符串 + 宽 + 高
 */
public class ColoryrPackBuilder {

    public static final int TypeGameMouseState = 1;
    public static final int TypeGameMotd = 2;
    public static final int TypeLaunchShow = 3;
    public static final int TypeLaunchStart = 4;
    public static final int TypeMouseXY = 5;
    public static final int TypeMouseClick = 6;
    public static final int TypeKeybordClick = 7;
    public static final int TypeMouseScoll = 8;
    public static final int TypeGameChannel = 9;
    public static final int TypeSetTitle = 10;
    public static final int TypeGameWindowSize = 11;
    public static final int TypeGameAuth = 12;

    /** 消息类型头的长度，单位字节 */
    private static final int PACK_HEADER_SIZE = 4;
    /** 单条字段允许的最大长度，单位字节，用于拒绝坏数据 */
    private static final int MAX_FIELD_SIZE = 1 << 20;
    /** 鼠标移动消息的消息体长度，单位字节 */
    private static final int MOUSE_XY_SIZE = 16;
    /** 鼠标按键消息的消息体长度，单位字节 */
    private static final int MOUSE_CLICK_SIZE = 5;
    /** 键盘按键消息的消息体长度，单位字节 */
    private static final int KEYBOARD_CLICK_SIZE = 5;
    /** 滚轮消息的消息体长度，单位字节 */
    private static final int MOUSE_SCROLL_SIZE = 4;

    static {
        System.out.println("[ColoryrASM] ColoryrPackBuilder is being initialized.");
    }

    public static void init() {

    }

    private static final Queue<InfoObj> packetMap = new ConcurrentLinkedDeque<>();

    static class InfoObj {
        public String ip;
        public String port;
        public byte[] info;
    }

    public static boolean receive(DatagramPacket p) {
        if (!ColoryrASM.run) {
            if (ColoryrASM.init) {
                return false;
            }
            ColoryrASM.init();
            if (!ColoryrASM.run) {
                return false;
            }
        }

        InfoObj obj = packetMap.poll();
        if (obj == null) {
            return false;
        }

        try {
            p.setSocketAddress(new InetSocketAddress(obj.ip, 4445));
            p.setData(obj.info);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void addServer(String ip, String port, byte[] info) {
        for (InfoObj item : packetMap) {
            if (item.ip.equalsIgnoreCase(ip) && item.port.equalsIgnoreCase(port)) {
                return;
            }
        }
        InfoObj obj = new InfoObj();
        obj.ip = ip;
        obj.port = port;
        obj.info = info;

        packetMap.add(obj);
    }

    public static void setGrabbed(int mode, int value) {
        if (mode == 208897) {
            if (value == 212995) {
                ColoryrPackBuilder.setGrabbed(true);
            } else if (value == 212993) {
                ColoryrPackBuilder.setGrabbed(false);
            }
        }
    }

    public static void setGrabbed(boolean grab) {
        // 先更新注入用的状态：没连上启动器时也要保证鼠标移动的抓取判断正确
        ColoryrInput.setGrabbed(grab);
        if (!ColoryrASM.run) {
            if (ColoryrASM.init) {
                return;
            }
            ColoryrASM.init();
            if (!ColoryrASM.run) {
                return;
            }
        }
        ColoryrASM.client.sendMessage(ColoryrPackBuilder.buildGrabbed(grab));
    }

    static ByteBuf buildGrabbed(boolean grab) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(TypeGameMouseState);
        buf.writeInt(ColoryrASM.uuidBytes.length);
        buf.writeBytes(ColoryrASM.uuidBytes);
        buf.writeBoolean(grab);
        return buf;
    }

    public static ByteBuf buildGameUUID() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(TypeGameChannel);
        buf.writeInt(ColoryrASM.uuidBytes.length);
        buf.writeBytes(ColoryrASM.uuidBytes);
        return buf;
    }

    static String readString(ByteBuf buf) {
        int len = buf.readInt();
        byte[] temp = new byte[len];
        buf.readBytes(temp);

        return new String(temp, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuf buf) {
        int len = buf.readInt();
        byte[] temp = new byte[len];
        buf.readBytes(temp);

        return temp;
    }

    /**
     * 推算缓冲区首条消息的完整长度
     *
     * - `buf`: 已收到的数据，不消费
     *
     * # 返回值
     *
     * 返回完整消息的字节数；数据不足一条消息时返回 0；长度字段非法时返回 -1。
     * 未知类型无法推算边界，按只有类型头的 4 字节处理。
     */
    public static int packLength(ByteBuf buf) {
        if (buf.readableBytes() < PACK_HEADER_SIZE) {
            return 0;
        }
        int base = buf.readerIndex();
        int type = buf.getInt(base);
        int offset = base + PACK_HEADER_SIZE;

        switch (type) {
            case TypeGameMotd:
                for (int i = 0; i < 3; i++) {
                    int end = stringEnd(buf, offset);
                    if (end <= 0) {
                        return end;
                    }
                    offset = end;
                }
                return offset - base;
            case TypeSetTitle: {
                int end = stringEnd(buf, offset);
                return end <= 0 ? end : end - base;
            }
            case TypeGameAuth: {
                int end = stringEnd(buf, offset);
                return end <= 0 ? end : end - base;
            }
            case TypeMouseXY:
                return fixedPackLength(buf, offset, MOUSE_XY_SIZE);
            case TypeMouseClick:
                return fixedPackLength(buf, offset, MOUSE_CLICK_SIZE);
            case TypeKeybordClick:
                return fixedPackLength(buf, offset, KEYBOARD_CLICK_SIZE);
            case TypeMouseScoll:
                return fixedPackLength(buf, offset, MOUSE_SCROLL_SIZE);
            default:
                return PACK_HEADER_SIZE;
        }
    }

    /** 固定长度消息的整条长度，数据不足时返回 0 */
    private static int fixedPackLength(ByteBuf buf, int offset, int bodySize) {
        if (buf.writerIndex() - offset < bodySize) {
            return 0;
        }
        return PACK_HEADER_SIZE + bodySize;
    }

    /** 一个字符串字段结束的位置，数据不足时返回 0，长度非法时返回 -1 */
    private static int stringEnd(ByteBuf buf, int offset) {
        if (buf.writerIndex() - offset < 4) {
            return 0;
        }
        int len = buf.getInt(offset);
        if (len < 0 || len > MAX_FIELD_SIZE) {
            return -1;
        }
        int end = offset + 4 + len;
        if (end > buf.writerIndex()) {
            return 0;
        }
        return end;
    }

    public static void packRead(ByteBuf buf) {
        int type = buf.readInt();
        switch (type) {
            //server pack
            case TypeGameMotd:
                String ip = readString(buf);
                String port = readString(buf);
                byte[] info = readBytes(buf);
                ColoryrPackBuilder.addServer(ip, port, info);
                break;
            case TypeSetTitle:
                String title = readString(buf);
                try {
                    if (ColoryrASM.isLwjgl3) {
                        Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW");
                        Method setWindowTitleMethod = glfwClass.getDeclaredMethod("glfwSetWindowTitle", long.class, CharSequence.class);
                        for (long item : ColoryrASM.windowList) {
                            setWindowTitleMethod.invoke(null, item, title);
                        }
                    } else {

                        Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
                        Method setTitleMethod = displayClass.getMethod("setTitle", String.class);
                        setTitleMethod.invoke(null, title);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case TypeMouseXY:
                ColoryrInput.mouseMove(buf.readDouble(), buf.readDouble());
                break;
            case TypeMouseClick:
                ColoryrInput.mouseButton(buf.readInt(), buf.readBoolean());
                break;
            case TypeKeybordClick:
                ColoryrInput.key(buf.readInt(), buf.readBoolean());
                break;
            case TypeMouseScoll:
                ColoryrInput.scroll(buf.readInt());
                break;
            default:
                break;
        }
    }
}
