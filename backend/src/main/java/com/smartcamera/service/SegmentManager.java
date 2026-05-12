package com.smartcamera.service;

import com.smartcamera.config.CameraProperties;
import com.smartcamera.netty.codec.MpegTsMuxer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages video segment lifecycle: creates TS files from NALUs,
 * rotates segments based on time/size, and triggers upload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentManager {

    private final CameraProperties properties;
    private final StorageService storageService;

    // cameraId -> segment context
    private final Map<String, SegmentContext> segmentContexts = new ConcurrentHashMap<>();

    public synchronized void startSegment(String cameraId, String resolution, byte[] sps, byte[] pps) {
        if (segmentContexts.containsKey(cameraId)) {
            log.debug("Segment already active for camera {}", cameraId);
            return;
        }

        try {
            Path tempDir = Path.of(properties.getStream().getTempDir());
            Files.createDirectories(tempDir);

            SegmentContext ctx = new SegmentContext();
            ctx.setCameraId(cameraId);
            ctx.setResolution(resolution);
            ctx.setStartTime(LocalDateTime.now());
            ctx.setMuxer(new MpegTsMuxer());

            // 初始化 muxer：预置 SPS/PPS，确保每个分段文件开头都有参数集
            if (sps != null && pps != null) {
                try {
                    ctx.getMuxer().init(sps, pps);
                    ctx.setSpsNalu(sps);
                    ctx.setPpsNalu(pps);
                    log.info("StartSegment 预置 SPS/PPS for camera {} (SPS: {} bytes, PPS: {} bytes)",
                            cameraId, sps.length, pps.length);
                } catch (IOException e) {
                    log.error("Failed to init muxer with SPS/PPS for camera {}: {}", cameraId, e.getMessage());
                }
            }

            ctx.setTempFile(tempDir.resolve(cameraId + "_" + System.currentTimeMillis() + ".h264"));
            ctx.setFileOutputStream(new FileOutputStream(ctx.getTempFile().toFile()));
            ctx.setMaxSizeBytes(properties.getStream().getSegmentMaxSizeMb() * 1024L * 1024L);
            ctx.setDurationMs(properties.getStream().getSegmentDurationMinutes() * 60L * 1000L);

            segmentContexts.put(cameraId, ctx);
            log.info("Started new segment for camera {}", cameraId);
        } catch (IOException e) {
            log.error("Failed to start segment for camera {}: {}", cameraId, e.getMessage(), e);
        }
    }

    /**
     * 为已存在的分段动态注入 SPS/PPS（用于收到 ANNOUNCE 后补全）。
     */
    public synchronized void injectSpsPps(String cameraId, byte[] sps, byte[] pps) {
        SegmentContext ctx = segmentContexts.get(cameraId);
        if (ctx == null) {
            log.warn("Cannot inject SPS/PPS: no active segment for camera {}", cameraId);
            return;
        }
        if (sps == null || pps == null) return;

        ctx.setSpsNalu(sps);
        ctx.setPpsNalu(pps);

        // 注入到 muxer 和当前文件
        try {
            ctx.getMuxer().init(sps, pps);
            log.info("Injected SPS/PPS into active segment for camera {} (SPS: {} bytes, PPS: {} bytes)",
                    cameraId, sps.length, pps.length);
        } catch (IOException e) {
            log.error("Failed to inject SPS/PPS for camera {}: {}", cameraId, e.getMessage());
        }
    }

    /**
     * Write a NALU to the current segment.
     * Synchronized per-camera to prevent concurrent write/rotate races.
     */
    public synchronized void writeNalu(String cameraId, byte[] nalu) {
        SegmentContext ctx = segmentContexts.get(cameraId);
        if (ctx == null) {
            startSegment(cameraId, "1920x1080", null, null);
            ctx = segmentContexts.get(cameraId);
        }
        if (ctx == null || ctx.getFileOutputStream() == null) return;

        try {
            int type = com.smartcamera.netty.codec.H264Parser.getNaluType(nalu);

            // 诊断日志：记录前 20 个 NALU 的类型
            if (ctx.getNaluCount() < 20) {
                ctx.setNaluCount(ctx.getNaluCount() + 1);
                log.debug("[NALU跟踪] Camera {} 第{}个NALU: type={}, size={} bytes", cameraId, ctx.getNaluCount(), type, nalu.length);
            }

            // Cache SPS/PPS on context BEFORE processing (muxer strips start code)
            if (type == 7) {
                // Extract payload for caching
                byte[] payload = stripStartCode(nalu);
                ctx.setSpsNalu(payload);
                log.info("[NALU跟踪] 缓存 SPS for camera {} ({} bytes)", cameraId, payload.length);
            } else if (type == 8) {
                byte[] payload = stripStartCode(nalu);
                ctx.setPpsNalu(payload);
                log.info("[NALU跟踪] 缓存 PPS for camera {} ({} bytes)", cameraId, payload.length);
            }

            // Process NALU through TS muxer (writes SPS/PPS + video data to file)
            byte[] tsData = ctx.getMuxer().processNalu(nalu);

            if (tsData != null && tsData.length > 0) {
                ctx.getFileOutputStream().write(tsData);
                ctx.setBytesWritten(ctx.getBytesWritten() + tsData.length);
            }

            // Check rotation conditions
            checkRotation(ctx);

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Stream Closed")) {
                return; // Ignore during rotation
            }
            log.error("Failed to write NALU for camera {}: {}", cameraId, e.getMessage(), e);
        }
    }

    /**
     * Write an AAC audio frame to the current segment.
     * Frames already have ADTS headers from RtpChannelHandler.
     */
    public synchronized void writeAudio(String cameraId, byte[] audioFrame) {
        // 音频写入已移除，保留空方法避免编译错误
    }

    private byte[] stripStartCode(byte[] nalu) {
        int offset = 0;
        if (nalu.length >= 4 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 0 && nalu[3] == 1) {
            offset = 4;
        } else if (nalu.length >= 3 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 1) {
            offset = 3;
        }
        byte[] result = new byte[nalu.length - offset];
        System.arraycopy(nalu, offset, result, 0, result.length);
        return result;
    }

    /**
     * Get cached SPS/PPS for a camera (for playback injection).
     */
    public synchronized SpsPpsCache getSpsPps(String cameraId) {
        SegmentContext ctx = segmentContexts.get(cameraId);
        if (ctx == null) return null;
        byte[] sps = ctx.getSpsNalu();
        byte[] pps = ctx.getPpsNalu();
        return (sps != null && pps != null) ? new SpsPpsCache(sps, pps) : null;
    }

    /**
     * Container for SPS/PPS data used during playback initialization.
     */
    public static class SpsPpsCache {
        private final byte[] sps;
        private final byte[] pps;
        public SpsPpsCache(byte[] sps, byte[] pps) { this.sps = sps; this.pps = pps; }
        public byte[] getSps() { return sps; }
        public byte[] getPps() { return pps; }
    }

    /**
     * Stop segmenting for a camera.
     */
    public synchronized void stopSegment(String cameraId) {
        SegmentContext ctx = segmentContexts.remove(cameraId);
        if (ctx != null) {
            rotateSegment(ctx);
        }
    }

    private void checkRotation(SegmentContext ctx) {
        boolean timeExpired = System.currentTimeMillis() - ctx.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                > ctx.getDurationMs();
        boolean sizeExpired = ctx.getBytesWritten() >= ctx.getMaxSizeBytes();

        if (timeExpired || sizeExpired) {
            String reason = timeExpired ? "time" : "size";
            log.debug("Rotating segment for camera {} (reason: {})", ctx.getCameraId(), reason);
            rotateSegment(ctx);
        }
    }

    private void rotateSegment(SegmentContext ctx) {
        try {
            // Cache SPS/PPS before closing
            byte[] sps = ctx.getSpsNalu();
            byte[] pps = ctx.getPpsNalu();

            // Flush remaining video data
            byte[] flushData = ctx.getMuxer().flush();
            if (flushData != null && flushData.length > 0) {
                ctx.getFileOutputStream().write(flushData);
            }

            // Close video file
            ctx.getFileOutputStream().flush();
            ctx.getFileOutputStream().close();

            // Calculate end time
            LocalDateTime endTime = LocalDateTime.now();

            // Upload to MinIO (async, temp file cleanup handled inside uploadSegment)
            storageService.uploadSegment(
                    ctx.getCameraId(),
                    ctx.getTempFile(),
                    ctx.getStartTime(),
                    endTime,
                    ctx.getResolution()
            );

            // Start new segment
            ctx.setMuxer(new MpegTsMuxer());
            ctx.setStartTime(LocalDateTime.now());
            ctx.setBytesWritten(0);
            ctx.setTempFile(Path.of(properties.getStream().getTempDir())
                    .resolve(ctx.getCameraId() + "_" + System.currentTimeMillis() + ".h264"));
            ctx.setFileOutputStream(new FileOutputStream(ctx.getTempFile().toFile()));

            // Write SPS/PPS directly to file start so every segment is independently playable
            if (sps != null && pps != null) {
                byte[] startCode = {0x00, 0x00, 0x00, 0x01};
                ctx.getFileOutputStream().write(startCode);
                ctx.getFileOutputStream().write(sps);
                ctx.getFileOutputStream().write(startCode);
                ctx.getFileOutputStream().write(pps);
                ctx.getFileOutputStream().flush();
                log.info("Injected SpsPps into new segment for camera {} (SPS: {} bytes, PPS: {} bytes)",
                        ctx.getCameraId(), sps.length, pps.length);
            } else {
                log.warn("No cached SpsPps available for camera {}, segments may be unplayable", ctx.getCameraId());
            }

            log.info("Segment rotated for camera {}", ctx.getCameraId());

        } catch (IOException e) {
            log.error("Failed to rotate segment for camera {}: {}", ctx.getCameraId(), e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down segment manager, flushing all segments...");
        segmentContexts.keySet().forEach(this::stopSegment);
    }

    private static class SegmentContext {
        private String cameraId;
        private String resolution;
        private LocalDateTime startTime;
        private MpegTsMuxer muxer;
        private Path tempFile;
        private FileOutputStream fileOutputStream;
        private long bytesWritten = 0;
        private long maxSizeBytes;
        private long durationMs;
        private byte[] spsNalu;
        private byte[] ppsNalu;
        private int naluCount = 0;

        public int getNaluCount() { return naluCount; }
        public void setNaluCount(int naluCount) { this.naluCount = naluCount; }

        public byte[] getSpsNalu() { return spsNalu; }
        public byte[] getPpsNalu() { return ppsNalu; }
        public void setSpsNalu(byte[] spsNalu) { this.spsNalu = spsNalu; }
        public void setPpsNalu(byte[] ppsNalu) { this.ppsNalu = ppsNalu; }

        public String getCameraId() { return cameraId; }
        public void setCameraId(String cameraId) { this.cameraId = cameraId; }
        public String getResolution() { return resolution; }
        public void setResolution(String resolution) { this.resolution = resolution; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public MpegTsMuxer getMuxer() { return muxer; }
        public void setMuxer(MpegTsMuxer muxer) { this.muxer = muxer; }
        public Path getTempFile() { return tempFile; }
        public void setTempFile(Path tempFile) { this.tempFile = tempFile; }
        public FileOutputStream getFileOutputStream() { return fileOutputStream; }
        public void setFileOutputStream(FileOutputStream fileOutputStream) { this.fileOutputStream = fileOutputStream; }
        public long getBytesWritten() { return bytesWritten; }
        public void setBytesWritten(long bytesWritten) { this.bytesWritten = bytesWritten; }
        public long getMaxSizeBytes() { return maxSizeBytes; }
        public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    }
}
