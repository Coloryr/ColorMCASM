package com.coloryr.colormc;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.nio.charset.StandardCharsets;

public class ColorMCNettyPackReader extends ChannelInboundHandlerAdapter {
    private String readString(ByteBuf buf) {
        int len = buf.readInt();
        byte[] temp = new byte[len];
        buf.readBytes(temp);

        return new String(temp, StandardCharsets.UTF_8);
    }

    private byte[] readBytes(ByteBuf buf) {
        int len = buf.readInt();
        byte[] temp = new byte[len];
        buf.readBytes(temp);

        return temp;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            int type = buf.readInt();
            switch (type) {
                //server pack
                case 2:
                    String ip = readString(buf);
                    String port = readString(buf);
                    byte[] info = readBytes(buf);
                    ColorMCPackBuilder.addServer(ip, port, info);
                    break;
            }
        }
    }
}
