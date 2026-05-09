package com.smartcamera.netty;

import com.smartcamera.netty.codec.H264Parser;
import com.smartcamera.netty.model.RtpPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Handles incoming RTP packets on UDP ports.
 * Parses RTP payloads into H.264 NALUs and distributes them to subscribers.
 */
@Slf4j
public class RtpPacketHandler {

    private final H264Parser h264Parser = new H264Parser();

    // cameraId -> list of NALU consumers (subscribers)
    private final Map<String, List<Consumer<byte[]>>> subscribers = new ConcurrentHashMap<>();

    /**
     * Process raw RTP packet data received on UDP socket.
     */
    public void processRtpData(byte[] data, String cameraId) {
        RtpPacket packet = RtpPacket.parse(data);
        if (packet == null) {
            log.warn("Failed to parse RTP packet for camera {}", cameraId);
            return;
        }

        List<byte[]> nalus = h264Parser.parse(packet);
        for (byte[] nalu : nalus) {
            distributeNalu(cameraId, nalu);
        }
    }

    private void distributeNalu(String cameraId, byte[] nalu) {
        List<Consumer<byte[]>> subs = subscribers.get(cameraId);
        if (subs != null) {
            for (Consumer<byte[]> sub : subs) {
                try {
                    sub.accept(nalu);
                } catch (Exception e) {
                    log.error("Error distributing NALU to subscriber for camera {}", cameraId, e);
                }
            }
        }
    }

    /**
     * Subscribe to NALU stream for a camera.
     * Returns a subscription ID that can be used to unsubscribe.
     */
    public String subscribe(String cameraId, Consumer<byte[]> consumer) {
        subscribers.computeIfAbsent(cameraId, k -> new CopyOnWriteArrayList<>());
        subscribers.get(cameraId).add(consumer);
        log.debug("Subscriber added for camera {}", cameraId);
        return cameraId + "_" + consumer.hashCode();
    }

    /**
     * Unsubscribe from NALU stream.
     */
    public void unsubscribe(String cameraId, Consumer<byte[]> consumer) {
        List<Consumer<byte[]>> subs = subscribers.get(cameraId);
        if (subs != null) {
            subs.remove(consumer);
            log.debug("Subscriber removed for camera {}", cameraId);
        }
    }

    public void clearSubscribers(String cameraId) {
        subscribers.remove(cameraId);
    }
}
