package com.smartcamera.netty;

import com.smartcamera.netty.model.SdpDescription;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspMethods;
import io.netty.handler.codec.rtsp.RtspResponseStatuses;
import io.netty.handler.codec.rtsp.RtspVersions;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RtspServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    // Session management: sessionId -> RtspSession
    private static final Map<String, RtspSession> sessions = new ConcurrentHashMap<>();
    private static final AtomicInteger rtpPortAllocator = new AtomicInteger(50000);

    private static final String SUPPORTED_METHODS = "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN";

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        RtspMethods method = request.method();
        String uri = request.uri();
        int cseq = getCSeq(request);

        log.debug("RTSP {} {} CSeq={}", method, uri, cseq);

        switch (method) {
            case OPTIONS -> handleOptions(ctx, cseq);
            case DESCRIBE -> handleDescribe(ctx, cseq, uri);
            case SETUP -> handleSetup(ctx, cseq, uri, request);
            case PLAY -> handlePlay(ctx, cseq, uri);
            case TEARDOWN -> handleTeardown(ctx, cseq, uri);
            default -> sendResponse(ctx, RtspResponseStatuses.METHOD_NOT_ALLOWED, cseq);
        }
    }

    private void handleOptions(ChannelHandlerContext ctx, int cseq) {
        log.debug("OPTIONS received");
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        response.headers().set(RtspHeaderNames.PUBLIC, SUPPORTED_METHODS);
        ctx.writeAndFlush(response);
    }

    private void handleDescribe(ChannelHandlerContext ctx, int cseq, String uri) {
        log.debug("DESCRIBE received for {}", uri);

        // Extract cameraId from URI: rtsp://host:port/live/{cameraId}
        String cameraId = extractCameraId(uri);
        if (cameraId == null) {
            sendResponse(ctx, RtspResponseStatuses.NOT_FOUND, cseq);
            return;
        }

        // Build SDP
        SdpDescription sdp = new SdpDescription();
        sdp.setOrigin("- 0 0 IN IP4 127.0.0.1");
        sdp.setSessionName("Camera: " + cameraId);
        sdp.setConnection("IN IP4 0.0.0.0");
        sdp.setMedia("video 0 RTP/AVP 96");
        sdp.setRtpmap("96 H264/90000");
        sdp.setFmtp("packetization-mode=1;profile-level-id=42C028;");
        sdp.setControl("trackID=1");

        String sdpBody = sdp.build();
        byte[] sdpBytes = sdpBody.getBytes(StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.wrappedBuffer(sdpBytes));
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/sdp");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, sdpBytes.length);
        ctx.writeAndFlush(response);
    }

    private void handleSetup(ChannelHandlerContext ctx, int cseq, String uri, FullHttpRequest request) {
        log.debug("SETUP received for {}", uri);

        // Parse Transport header to get client ports
        String transportHeader = request.headers().get(HttpHeaderNames.TRANSPORT);
        int clientRtpPort = 0;
        if (transportHeader != null) {
            String[] parts = transportHeader.split(";");
            for (String part : parts) {
                if (part.startsWith("client_port=")) {
                    String[] ports = part.substring("client_port=".length()).split("-");
                    clientRtpPort = Integer.parseInt(ports[0]);
                }
            }
        }

        // Allocate server RTP port
        int serverRtpPort = rtpPortAllocator.getAndIncrement();
        if (serverRtpPort > 51000) {
            rtpPortAllocator.set(50000);
            serverRtpPort = rtpPortAllocator.getAndIncrement();
        }

        // Create session
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        RtspSession session = new RtspSession();
        session.setId(sessionId);
        session.setCameraId(extractCameraId(uri));
        session.setClientRtpPort(clientRtpPort);
        session.setServerRtpPort(serverRtpPort);
        session.setChannel(ctx.channel());
        sessions.put(sessionId, session);

        String transport = String.format(
                "RTP/AVP;unicast;client_port=%d-%d;server_port=%d-%d;ssrc=00000001",
                clientRtpPort, clientRtpPort + 1, serverRtpPort, serverRtpPort + 1);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        response.headers().set(HttpHeaderNames.TRANSPORT, transport);
        response.headers().set(RtspHeaderNames.SESSION, sessionId + ";timeout=60");
        ctx.writeAndFlush(response);

        log.info("RTSP session created: {} for camera {}", sessionId, session.getCameraId());
    }

    private void handlePlay(ChannelHandlerContext ctx, int cseq, String uri) {
        log.debug("PLAY received for {}", uri);

        // Session should already be created in SETUP
        // In a real implementation, you'd find the session by channel or URI
        RtspSession session = findSessionByChannel(ctx);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        if (session != null) {
            response.headers().set(RtspHeaderNames.SESSION, session.getId());
            session.setPlaying(true);
            session.setStartTime(System.currentTimeMillis());
            log.info("PLAY started for session {} camera {}", session.getId(), session.getCameraId());
        }
        ctx.writeAndFlush(response);
    }

    private void handleTeardown(ChannelHandlerContext ctx, int cseq, String uri) {
        log.debug("TEARDOWN received for {}", uri);

        RtspSession session = findSessionByChannel(ctx);
        if (session != null) {
            sessions.remove(session.getId());
            log.info("Session {} torn down", session.getId());
        }

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        ctx.writeAndFlush(response);
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, int cseq) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.RTSP_1_0, status, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        ctx.writeAndFlush(response);
    }

    private int getCSeq(FullHttpRequest request) {
        String cseq = request.headers().get(RtspHeaderNames.CSEQ);
        return cseq != null ? Integer.parseInt(cseq) : 0;
    }

    private String extractCameraId(String uri) {
        if (uri == null) return null;
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("live".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private RtspSession findSessionByChannel(ChannelHandlerContext ctx) {
        return sessions.values().stream()
                .filter(s -> s.getChannel() == ctx.channel())
                .findFirst()
                .orElse(null);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        RtspSession session = findSessionByChannel(ctx);
        if (session != null) {
            sessions.remove(session.getId());
            log.info("Channel inactive, removed session {}", session.getId());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("RTSP handler exception", cause);
        ctx.close();
    }

    // Session data class
    public static class RtspSession {
        private String id;
        private String cameraId;
        private int clientRtpPort;
        private int serverRtpPort;
        private io.netty.channel.Channel channel;
        private boolean playing;
        private long startTime;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getCameraId() { return cameraId; }
        public void setCameraId(String cameraId) { this.cameraId = cameraId; }
        public int getClientRtpPort() { return clientRtpPort; }
        public void setClientRtpPort(int clientRtpPort) { this.clientRtpPort = clientRtpPort; }
        public int getServerRtpPort() { return serverRtpPort; }
        public void setServerRtpPort(int serverRtpPort) { this.serverRtpPort = serverRtpPort; }
        public io.netty.channel.Channel getChannel() { return channel; }
        public void setChannel(io.netty.channel.Channel channel) { this.channel = channel; }
        public boolean isPlaying() { return playing; }
        public void setPlaying(boolean playing) { this.playing = playing; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
    }

    // Public accessor for session management
    public static Map<String, RtspSession> getSessions() {
        return sessions;
    }
}
