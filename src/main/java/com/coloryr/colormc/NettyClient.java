package com.coloryr.colormc;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class NettyClient {

    private String host;
    private Integer port;
    private Bootstrap bootstrap;
    private Channel channel;

    public NettyClient(String host, Integer port) throws Exception {
        this.host = host;
        this.port = port;
        this.bootstrap = setup();
        this.run();
    }

    private Bootstrap setup() {
        EventLoopGroup group = new NioEventLoopGroup();
        return new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new ColorMCNettyPackReader());
                    }
                });
    }

    public void run() throws InterruptedException {
        channel = bootstrap.connect(host, port).sync().channel();
    }

    public void sendMessage(ByteBuf pack) {
        if (channel != null && channel.isWritable()) {
            channel.writeAndFlush(pack);
        }
    }
}
