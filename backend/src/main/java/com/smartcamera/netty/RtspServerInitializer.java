package com.smartcamera.netty;

import com.smartcamera.service.FrameDistributor;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RtspServerInitializer extends ChannelInitializer<SocketChannel> {

    private final FrameDistributor frameDistributor;

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HttpServerCodec handles both request decoding and response encoding
        // RTSP messages are structurally identical to HTTP messages
        pipeline.addLast("codec", new HttpServerCodec());

        // Aggregate HttpRequest + HttpContent into FullHttpRequest
        // This is required because RtspServerHandler expects FullHttpRequest
        pipeline.addLast("aggregator", new HttpObjectAggregator(65536));

        // Business handler
        pipeline.addLast("handler", new RtspServerHandler(frameDistributor));
    }
}
