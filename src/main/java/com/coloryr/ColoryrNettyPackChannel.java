package com.coloryr;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * 与启动器的 netty 连接
 *
 * 协议没有总长度前缀，TCP 可能把多条消息粘在一次读取里、也可能只读到半条，
 * 所以这里自己累积数据，按消息类型推算边界后再交给 {@link ColoryrPackBuilder}。
 * 连接建立后先等启动器发来的连接验证，密钥不一致直接断开；
 * 验证通过后才注册游戏通道并上报初始鼠标状态。
 */
public class ColoryrNettyPackChannel extends ChannelInboundHandlerAdapter {
    /** 累积数据的上限，单位字节；超过仍拼不出消息说明数据异常 */
    private static final int MAX_BUFFER_SIZE = 1 << 20;

    /** 跨读取累积的字节 */
    private final ByteBuf buffer = Unpooled.buffer();

    /** 是否已通过启动器发来的连接验证 */
    private boolean authenticated;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ReferenceCountUtil.release(msg);
            return;
        }

        ByteBuf in = (ByteBuf) msg;
        try {
            buffer.writeBytes(in);
        } finally {
            in.release();
        }

        while (true) {
            int length;
            try {
                length = ColoryrPackBuilder.packLength(buffer);
            } catch (Throwable e) {
                buffer.clear();
                return;
            }
            if (length == 0) {
                break;
            }
            if (length < 0) {
                System.out.println("[ColoryrASM] invalid pack length, drop received data");
                buffer.clear();
                return;
            }

            ByteBuf frame = buffer.readRetainedSlice(length);
            try {
                int type = frame.getInt(frame.readerIndex());
                if (!authenticated) {
                    if (type != ColoryrPackBuilder.TypeGameAuth) {
                        ctx.close();
                        return;
                    }
                    frame.skipBytes(4);
                    String key = ColoryrPackBuilder.readString(frame);
                    if (!key.equals(ColoryrASM.key)) {
                        System.out.println("[ColoryrASM] auth failed, closing.");
                        ctx.close();
                        return;
                    }
                    authenticated = true;
                    // 验证通过后再注册游戏通道并上报初始鼠标状态
                    ctx.writeAndFlush(ColoryrPackBuilder.buildGameUUID());
                    ctx.writeAndFlush(ColoryrPackBuilder.buildGrabbed(ColoryrInput.isGrabbed()));
                    continue;
                }
                if (type == ColoryrPackBuilder.TypeGameAuth) {
                    continue;
                }
                ColoryrPackBuilder.packRead(frame);
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                frame.release();
            }
        }

        if (buffer.readableBytes() > MAX_BUFFER_SIZE) {
            buffer.clear();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        buffer.release();
        super.channelInactive(ctx);
    }
}
