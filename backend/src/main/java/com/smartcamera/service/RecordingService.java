package com.smartcamera.service;

import com.smartcamera.netty.RtspServerHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates recording lifecycle: subscribes SegmentManager to FrameDistributor
 * when recording starts, unsubscribes when recording stops.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingService {

    private final FrameDistributor frameDistributor;
    private final SegmentManager segmentManager;

    private final Map<String, RecordingContext> activeRecordings = new ConcurrentHashMap<>();

    public void startRecording(String cameraId, String resolution) {
        if (activeRecordings.containsKey(cameraId)) {
            log.debug("Recording already active for camera {}", cameraId);
            return;
        }

        // 从 RTSP ANNOUNCE SDP 缓存中获取 SPS/PPS
        byte[][] spsPps = RtspServerHandler.getSpsPps(cameraId);
        byte[] sps = spsPps != null ? spsPps[0] : null;
        byte[] pps = spsPps != null ? spsPps[1] : null;
        if (sps != null && pps != null) {
            log.info("Found SPS/PPS from RTSP cache for camera {}, will inject into segments", cameraId);
        } else {
            log.warn("No SPS/PPS in RTSP cache yet for camera {}, will poll for late ANNOUNCE", cameraId);
        }

        segmentManager.startSegment(cameraId, resolution, sps, pps);

        String videoSubId = frameDistributor.subscribe(cameraId, nalu ->
                segmentManager.writeNalu(cameraId, nalu)
        );

        activeRecordings.put(cameraId, new RecordingContext(videoSubId));
        log.info("Recording started for camera {}, video subscription: {}", cameraId, videoSubId);

        // 如果 ANNOUNCE 还没到，启动延迟轮询补注 SPS/PPS
        if (sps == null || pps == null) {
            new Thread(() -> pollSpsPpsFromRtsp(cameraId), "sps-poll-" + cameraId).start();
        }
    }

    /**
     * Poll RTSP cache for up to 5 seconds waiting for ANNOUNCE SPS/PPS to arrive.
     */
    private void pollSpsPpsFromRtsp(String cameraId) {
        for (int i = 0; i < 50; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }

            byte[][] spsPps = RtspServerHandler.getSpsPps(cameraId);
            if (spsPps != null && spsPps[0] != null && spsPps[1] != null) {
                segmentManager.injectSpsPps(cameraId, spsPps[0], spsPps[1]);
                log.info("Poll found SPS/PPS from RTSP cache for camera {} after {}ms", cameraId, (i + 1) * 100);
                return;
            }
        }
        log.warn("SPS/PPS not found in RTSP cache after 5s polling for camera {}", cameraId);
    }

    public void stopRecording(String cameraId) {
        RecordingContext ctx = activeRecordings.remove(cameraId);
        if (ctx == null) {
            return;
        }

        frameDistributor.unsubscribe(ctx.getVideoSubscriptionId());
        segmentManager.stopSegment(cameraId);
        log.info("Recording stopped for camera {}", cameraId);
    }

    public boolean isRecording(String cameraId) {
        return activeRecordings.containsKey(cameraId);
    }

    /**
     * Inject SPS/PPS into the active recording segment.
     * Called when ANNOUNCE SDP arrives after recording has started.
     */
    public void injectSpsPps(String cameraId, byte[] sps, byte[] pps) {
        if (activeRecordings.containsKey(cameraId)) {
            segmentManager.injectSpsPps(cameraId, sps, pps);
        }
    }

    private static class RecordingContext {
        private final String videoSubscriptionId;

        RecordingContext(String videoSubscriptionId) {
            this.videoSubscriptionId = videoSubscriptionId;
        }

        public String getVideoSubscriptionId() {
            return videoSubscriptionId;
        }
    }
}
