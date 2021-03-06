package com.beaver.rpc.core.transport.impl.netty.server;

import com.beaver.rpc.common.domain.RpcService;
import com.beaver.rpc.core.transport.Server;
import com.beaver.rpc.core.transport.impl.netty.codec.RpcMessageDecoder;
import com.beaver.rpc.core.transport.impl.netty.codec.RpcMessageEncoder;
import com.beaver.rpc.common.constant.RpcConstants;
import com.beaver.rpc.common.util.RuntimeUtil;
import com.beaver.rpc.common.util.ThreadPoolFactoryUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;

import lombok.extern.slf4j.Slf4j;


import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NettyServer implements Server {
    private volatile boolean isStarted;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private DefaultEventExecutorGroup serviceHandlerGroup;
    private ServerBootstrap bootstrap;
    private ServerChannel serverChannel;

    public ConcurrentMap<String, Channel> getClientChannels() {
        return clientChannels;
    }

    private ConcurrentMap<String, Channel> clientChannels;

    @Override
    public boolean startServer() {
        if (isStarted) {
            return true;
        }

        clientChannels = new ConcurrentHashMap<>();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        serviceHandlerGroup = new DefaultEventExecutorGroup(
                RuntimeUtil.cpus() * 2,
                ThreadPoolFactoryUtils.createThreadFactory("service-handler-group", false)
        );
        NettyServer nettyServer = this;
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    // TCP??????????????? Nagle ????????????????????????????????????????????????????????????????????????????????????TCP_NODELAY ??????????????????????????????????????? Nagle ?????????
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // ???????????? TCP ??????????????????
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    //????????????????????????????????????????????????????????????????????????????????????,????????????????????????????????????????????????????????????????????????????????????????????????
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    // ???????????????????????????????????????????????????????????????
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 30 ?????????????????????????????????????????????????????????
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS));
                            p.addLast(new RpcMessageEncoder());
                            p.addLast(new RpcMessageDecoder());
                            p.addLast(serviceHandlerGroup, new NettyServerHandler(nettyServer));
                        }
                    });

            // ???????????????????????????????????????
            ChannelFuture channelFuture = bootstrap.bind(host, RpcConstants.NETTY_SERVICE_PORT);
            channelFuture.syncUninterruptibly();
            serverChannel = (ServerChannel) channelFuture.channel();
            isStarted = true;
        } catch (UnknownHostException e) {
            log.error("unknown host when start server:", e);
        }
        return true;
    }

    @Override
    public boolean stopServer() {
        if (isStarted) {
            log.info("shutdown bossGroup and workerGroup");
            if (bootstrap != null) {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
                serviceHandlerGroup.shutdownGracefully();
            }

            if (serverChannel != null) {
                ChannelFuture future = serverChannel.close();
                future.addListener((ChannelFutureListener) future1 -> {
                    if (!future1.isSuccess()) {
                        log.warn("Netty ServerChannel[{}] close failed", future1.cause());
                    }
                });
            }

            for (Channel channel : clientChannels.values()) {
                channel.close();
            }
            isStarted = false;
        }

        return true;
    }

    @Override
    public boolean addService(RpcService rpcServiceConfig) {
        return false;
    }

    @Override
    public boolean removeService(RpcService rpcServiceConfig) {
        return false;
    }

    @Override
    public boolean processRequest() {
        return false;
    }
}
