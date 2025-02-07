package com.coloryr.colormc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ColorMCPackBuilder {

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

    static {
        System.out.println("[ColorMC ASM] ColorMCPackBuilder is being initialized.");
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
        if (!ColorMCASM.run) {
            if (ColorMCASM.init) {
                return false;
            }
            ColorMCASM.init();
            if (!ColorMCASM.run) {
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
                ColorMCPackBuilder.setGrabbed(true);
            } else if (value == 212993) {
                ColorMCPackBuilder.setGrabbed(false);
            }
        }
    }

    public static void setGrabbed(boolean grab) {
        //System.out.println("[ColorMC] set grab: " + grab);
        if (!ColorMCASM.run) {
            if (ColorMCASM.init) {
                return;
            }
            ColorMCASM.init();
            if (!ColorMCASM.run) {
                return;
            }
        }
        ColorMCASM.client.sendMessage(ColorMCPackBuilder.buildGrabbed(grab));
    }

    private static ByteBuf buildGrabbed(boolean grab) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(TypeGameMouseState);
        buf.writeInt(ColorMCASM.uuidBytes.length);
        buf.writeBytes(ColorMCASM.uuidBytes);
        buf.writeBoolean(grab);
        return buf;
    }

    public static ByteBuf buildGameUUID() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(TypeGameChannel);
        buf.writeInt(ColorMCASM.uuidBytes.length);
        buf.writeBytes(ColorMCASM.uuidBytes);
        return buf;
    }

    private static String readString(ByteBuf buf) {
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

    public static void packRead(ByteBuf buf) {
        int type = buf.readInt();
        switch (type) {
            //server pack
            case TypeGameMotd:
                String ip = readString(buf);
                String port = readString(buf);
                byte[] info = readBytes(buf);
                ColorMCPackBuilder.addServer(ip, port, info);
                break;
            case TypeSetTitle:
                String title = readString(buf);
                try {
                    if (ColorMCASM.isLwjgl3) {
                        Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW");
                        Method setWindowTitleMethod = glfwClass.getDeclaredMethod("glfwSetWindowTitle", long.class, CharSequence.class);
                        for (long item : ColorMCASM.windowList) {
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
        }
    }
}
