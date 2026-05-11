package com.smartcamera.netty;

import com.smartcamera.service.FrameDistributor;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UDP server for receiving RTP packets from FFmpeg.
 * Dynamically binds to ports allocated during RTSP SETUP.
 */
@Slf4j
@Component
public class RtpServer {

    private final EventLoopGroup group = new NioEventLoopGroup(2);
    private final FrameDistributor frameDistributor;

    // port -> channel mapping
    private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();

    public RtpServer(FrameDistributor frameDistributor) {
        this.frameDistributor = frameDistributor;
    }

    /**
     * Bind to a specific port to receive RTP packets for a camera.
     */
    public void bindPort(int port, String cameraId) {
        if (channels.containsKey(port)) {
            log.debug("Port {} already bound for camera {}", port, cameraId);
            return;
        }

        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new RtpChannelHandler(cameraId, frameDistributor));
                        }
                    });

            Channel channel = bootstrap.bind(port).sync().channel();
            channels.put(port, channel);
            log.info("RTP server bound to port {} for camera {}", port, cameraId);
        } catch (Exception e) {
            log.error("Failed to bind RTP port {} for camera {}", port, cameraId, e);
        }
    }

    /**
     * Unbind a port when stream ends.
     */
    public void unbindPort(int port) {
        Channel channel = channels.remove(port);
        if (channel != null) {
            channel.close();
            log.info("RTP server unbound from port {}", port);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down RTP server...");
        for (Channel channel : channels.values()) {
            channel.close();
        }
        channels.clear();
        group.shutdownGracefully();
    }
}
