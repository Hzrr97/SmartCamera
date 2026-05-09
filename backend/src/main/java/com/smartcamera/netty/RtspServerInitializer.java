package com.smartcamera.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;

public class RtspServerInitializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HTTP codec (RTSP is text-based like HTTP)
        pipeline.addLast("httpCodec", new HttpServerCodec());
        pipeline.addLast("aggregator", new HttpObjectAggregator(64 * 1024));

        // RTSP encoder for responses
        pipeline.addLast("rtspEncoder", new RtspEncoder());

        // RTSP request decoder
        pipeline.addLast("rtspDecoder", new RtspDecoder());

        // Business handlers
        pipeline.addLast("rtspHandler", new RtspServerHandler());
    }
}
