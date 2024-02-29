package com.coloryr.colormc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ColorMCPackBuilder {

    static {
        System.out.println("ColorMCPackBuilder is being initialized.");
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
        System.out.println("set grab: " + grab);
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
        buf.writeInt(1);
        buf.writeInt(ColorMCASM.uuidBytes.length);
        buf.writeBytes(ColorMCASM.uuidBytes);
        buf.writeBoolean(grab);
        return buf;
    }
}
