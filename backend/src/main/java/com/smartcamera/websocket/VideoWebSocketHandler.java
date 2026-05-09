package com.smartcamera.websocket;

import com.smartcamera.service.FrameDistributor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for real-time video streaming.
 * Endpoint: /ws/video/live/{cameraId}
 * Sends raw H.264 NALUs as binary messages to the browser.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoWebSocketHandler extends AbstractWebSocketHandler {

    private final FrameDistributor frameDistributor;

    // sessionId -> cameraId mapping
    private final Map<String, String> sessionCameras = new ConcurrentHashMap<>();
    // sessionId -> subscriptionId mapping
    private final Map<String, String> subscriptions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String cameraId = extractCameraId(session);
        if (cameraId == null) {
            session.close(CloseStatus.BAD_DATA.withReason("cameraId path variable is required"));
            return;
        }

        sessionCameras.put(session.getId(), cameraId);

        // Subscribe to frame distributor
        String subscriptionId = frameDistributor.subscribe(cameraId, nalu -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new BinaryMessage(nalu));
                }
            } catch (IOException e) {
                log.error("Failed to send frame to WebSocket session {}", session.getId(), e);
            }
        });

        subscriptions.put(session.getId(), subscriptionId);
        log.info("WebSocket connected for camera {} (session: {})", cameraId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Handle control messages (e.g., pause/resume)
        String payload = message.getPayload();
        log.debug("WebSocket text message from {}: {}", session.getId(), payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String subscriptionId = subscriptions.remove(session.getId());
        String cameraId = sessionCameras.remove(session.getId());

        if (subscriptionId != null) {
            frameDistributor.unsubscribe(subscriptionId);
        }

        log.info("WebSocket disconnected for camera {} (session: {}, status: {})",
                cameraId, session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error for session {}", session.getId(), exception);
    }

    private String extractCameraId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] parts = path.split("/");
        // Expected: /ws/video/live/{cameraId}
        for (int i = 0; i < parts.length; i++) {
            if ("live".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }
}
