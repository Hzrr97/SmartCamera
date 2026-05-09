package com.smartcamera.netty;

import com.smartcamera.config.CameraProperties;
import com.smartcamera.config.NettyConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RtspServer {

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final CameraProperties properties;

    private Channel channel;

    @PostConstruct
    public void start() {
        int port = properties.getRtsp().getPort();
        log.info("Starting RTSP server on port {}", port);

        Thread serverThread = new Thread(() -> {
            try {
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(io.netty.channel.nio.NioServerSocketChannel.class)
                        .childHandler(new RtspServerInitializer());

                ChannelFuture future = bootstrap.bind(port).sync();
                channel = future.channel();
                log.info("RTSP server started successfully on port {}", port);

                channel.closeFuture().sync();
            } catch (InterruptedException e) {
                log.error("RTSP server interrupted", e);
                Thread.currentThread().interrupt();
            }
        }, "rtsp-server-thread");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping RTSP server...");
        if (channel != null) {
            channel.close();
        }
    }
}
