package com.smartcamera.netty;

import com.smartcamera.netty.model.SdpDescription;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspResponseStatuses;
import io.netty.handler.codec.rtsp.RtspVersions;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.smartcamera.service.FrameDistributor;
import io.netty.buffer.ByteBuf;

@Slf4j
public class RtspServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    // Session management: sessionId -> RtspSession
    private static final Map<String, RtspSession> sessions = new ConcurrentHashMap<>();
    private static final AtomicInteger rtpPortAllocator = new AtomicInteger(50000);

    private static final String SUPPORTED_METHODS = "OPTIONS, ANNOUNCE, RECORD, DESCRIBE, SETUP, PLAY, TEARDOWN";

    private static RtpServer rtpServer;
    private final FrameDistributor frameDistributor;

    public static void setRtpServer(RtpServer server) {
        rtpServer = server;
    }

    public RtspServerHandler(FrameDistributor frameDistributor) {
        this.frameDistributor = frameDistributor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        io.netty.handler.codec.http.HttpMethod method = request.method();
        String uri = request.uri();
        int cseq = getCSeq(request);
        int contentLength = request.content().readableBytes();

        log.info("RTSP {} {} CSeq={} Content-Length={}", method, uri, cseq, contentLength);

        String methodName = method.name().toUpperCase();
        if (io.netty.handler.codec.rtsp.RtspMethods.OPTIONS.equals(method)) {
            handleOptions(ctx, cseq);
        } else if (io.netty.handler.codec.rtsp.RtspMethods.DESCRIBE.equals(method)) {
            handleDescribe(ctx, cseq, uri);
        } else if (io.netty.handler.codec.rtsp.RtspMethods.SETUP.equals(method)) {
            handleSetup(ctx, cseq, uri, request);
        } else if (io.netty.handler.codec.rtsp.RtspMethods.PLAY.equals(method)) {
            handlePlay(ctx, cseq, uri);
        } else if (io.netty.handler.codec.rtsp.RtspMethods.TEARDOWN.equals(method)) {
            handleTeardown(ctx, cseq, uri);
        } else if (methodName.equals("ANNOUNCE")) {
            // FFmpeg sends ANNOUNCE to push SDP to server; parse SPS/PPS
            log.info("ANNOUNCE request received with body length: {}", contentLength);
            handleAnnounce(ctx, request, cseq);
        } else if (methodName.equals("RECORD")) {
            // FFmpeg uses RECORD to start pushing stream data
            handleRecord(ctx, cseq, uri);
        } else {
            log.warn("Unknown RTSP method: {}", methodName);
            sendResponse(ctx, RtspResponseStatuses.METHOD_NOT_ALLOWED, cseq);
        }
    }

    private void handleOptions(ChannelHandlerContext ctx, int cseq) {
        log.debug("OPTIONS received");
        FullHttpResponse response = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        response.headers().set(RtspHeaderNames.PUBLIC, SUPPORTED_METHODS);
        ctx.writeAndFlush(response);
    }

    // Cached SPS/PPS per camera, extracted from ANNOUNCE SDP
    private static final Map<String, byte[][]> spsPpsCache = new ConcurrentHashMap<>();

    public static byte[][] getSpsPps(String cameraId) {
        return spsPpsCache.get(cameraId);
    }

    private void handleAnnounce(ChannelHandlerContext ctx, FullHttpRequest request, int cseq) {
        String uri = request.uri();
        String cameraId = extractCameraId(uri);
        int contentLength = request.content().readableBytes();

        // Parse SDP body to extract SPS/PPS from sprop-parameter-sets
        String sdpBody = request.content().toString(StandardCharsets.UTF_8);
        log.info("ANNOUNCE for camera {} — URI: {}, Content-Length: {}, Body:\n{}", cameraId, uri, contentLength, sdpBody);

        // Extract everything after "sprop-parameter-sets="
        int idx = sdpBody.indexOf("sprop-parameter-sets=");
        if (idx == -1) {
            log.warn("No sprop-parameter-sets found in ANNOUNCE SDP for camera {}", cameraId);
        } else {
            String spropRaw = sdpBody.substring(idx + "sprop-parameter-sets=".length());
            // The sprop values are the first two comma-separated base64 strings
            // Everything after the second comma (like "; profile-level-id=...") is separate
            int firstComma = spropRaw.indexOf(',');
            if (firstComma == -1) {
                log.warn("No comma found in sprop-parameter-sets for camera {}", cameraId);
            } else {
                String spsBase64 = spropRaw.substring(0, firstComma).trim();
                String afterFirstComma = spropRaw.substring(firstComma + 1);
                // Second value ends at next comma, semicolon, or whitespace
                int secondEnd = afterFirstComma.indexOf(',');
                int semiIdx = afterFirstComma.indexOf(';');
                if (secondEnd == -1 || (semiIdx >= 0 && semiIdx < secondEnd)) {
                    secondEnd = semiIdx;
                }
                String ppsBase64 = (secondEnd >= 0 ? afterFirstComma.substring(0, secondEnd) : afterFirstComma).trim();

                log.info("SPS base64: '{}', PPS base64: '{}'", spsBase64, ppsBase64);

                try {
                    byte[] sps = Base64.getDecoder().decode(spsBase64);
                    byte[] pps = Base64.getDecoder().decode(ppsBase64);
                    spsPpsCache.put(cameraId, new byte[][]{sps, pps});
                    log.info("Extracted SPS ({} bytes) and PPS ({} bytes) from ANNOUNCE for camera {}",
                            sps.length, pps.length, cameraId);
                } catch (IllegalArgumentException e) {
                    log.error("Failed to decode SPS/PPS: {}", e.getMessage());
                }
            }
        }

        FullHttpResponse response = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        ctx.writeAndFlush(response);
    }

    private void handleRecord(ChannelHandlerContext ctx, int cseq, String uri) {
        log.info("RECORD received for {} — accepting RTP stream", uri);
        FullHttpResponse response = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        response.headers().set(RtspHeaderNames.RANGE, "npt=now-");
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
                RtspVersions.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.wrappedBuffer(sdpBytes));
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        response.headers().set(RtspHeaderNames.CONTENT_TYPE, "application/sdp");
        response.headers().setInt(RtspHeaderNames.CONTENT_LENGTH, sdpBytes.length);
        ctx.writeAndFlush(response);
    }

    private void handleSetup(ChannelHandlerContext ctx, int cseq, String uri, FullHttpRequest request) {
        log.debug("SETUP received for {}", uri);

        String transportHeader = request.headers().get(RtspHeaderNames.TRANSPORT);
        boolean useTcp = transportHeader != null && transportHeader.contains("TCP");

        // Create session
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        RtspSession session = new RtspSession();
        session.setId(sessionId);
        session.setCameraId(extractCameraId(uri));
        session.setChannel(ctx.channel());
        session.setUseTcp(useTcp);

        String transport;
        if (useTcp) {
            // RTP-over-TCP: Use interleaved channels 0 and 1
            session.setRtpChannel(0);
            session.setRtcpChannel(1);
            transport = "RTP/AVP/TCP;unicast;interleaved=0-1;ssrc=00000001";
            log.info("Using RTP-over-TCP for camera {}", session.getCameraId());
        } else {
            // RTP-over-UDP
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

            int serverRtpPort = rtpPortAllocator.getAndIncrement();
            if (serverRtpPort > 51000) {
                rtpPortAllocator.set(50000);
                serverRtpPort = rtpPortAllocator.getAndIncrement();
            }

            session.setClientRtpPort(clientRtpPort);
            session.setServerRtpPort(serverRtpPort);

            // Start RTP server on allocated port
            if (rtpServer != null) {
                rtpServer.bindPort(serverRtpPort, session.getCameraId());
            }

            transport = String.format(
                    "RTP/AVP;unicast;client_port=%d-%d;server_port=%d-%d;ssrc=00000001",
                    clientRtpPort, clientRtpPort + 1, serverRtpPort, serverRtpPort + 1);
        }

        sessions.put(sessionId, session);

        FullHttpResponse response = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        response.headers().set(RtspHeaderNames.TRANSPORT, transport);
        response.headers().set(RtspHeaderNames.SESSION, sessionId + ";timeout=60");
        ctx.writeAndFlush(response);

        log.info("RTSP session created: {} for camera {} (TCP={})", sessionId, session.getCameraId(), useTcp);
    }

    private void handlePlay(ChannelHandlerContext ctx, int cseq, String uri) {
        log.debug("PLAY received for {}", uri);

        // Session should already be created in SETUP
        // In a real implementation, you'd find the session by channel or URI
        RtspSession session = findSessionByChannel(ctx);

        FullHttpResponse response = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
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
            // Release RTP port
            if (rtpServer != null) {
                rtpServer.unbindPort(session.getServerRtpPort());
            }
            log.info("Session {} torn down", session.getId());
        }

        FullHttpResponse response = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0, RtspResponseStatuses.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(RtspHeaderNames.CSEQ, cseq);
        ctx.writeAndFlush(response);
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, int cseq) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0, status, Unpooled.EMPTY_BUFFER);
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
        // TCP interleaved mode
        private boolean useTcp;
        private int rtpChannel;
        private int rtcpChannel;

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
        public boolean isUseTcp() { return useTcp; }
        public void setUseTcp(boolean useTcp) { this.useTcp = useTcp; }
        public int getRtpChannel() { return rtpChannel; }
        public void setRtpChannel(int rtpChannel) { this.rtpChannel = rtpChannel; }
        public int getRtcpChannel() { return rtcpChannel; }
        public void setRtcpChannel(int rtcpChannel) { this.rtcpChannel = rtcpChannel; }
    }

    // Public accessor for session management
    public static Map<String, RtspSession> getSessions() {
        return sessions;
    }
}
