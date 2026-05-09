package com.smartcamera.service;

import com.smartcamera.config.CameraProperties;
import com.smartcamera.netty.codec.MpegTsMuxer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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

    public void startSegment(String cameraId, String resolution) {
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
            ctx.setTempFile(tempDir.resolve(cameraId + "_" + System.currentTimeMillis() + ".ts"));
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
     * Write a NALU to the current segment.
     * Checks rotation conditions after writing.
     */
    public void writeNalu(String cameraId, byte[] nalu) {
        SegmentContext ctx = segmentContexts.get(cameraId);
        if (ctx == null) {
            startSegment(cameraId, "1920x1080");
            ctx = segmentContexts.get(cameraId);
        }
        if (ctx == null) return;

        try {
            // Process NALU through TS muxer
            byte[] tsData = ctx.getMuxer().processNalu(nalu);

            if (tsData != null && tsData.length > 0) {
                ctx.getFileOutputStream().write(tsData);
                ctx.setBytesWritten(ctx.getBytesWritten() + tsData.length);
            }

            // Check rotation conditions
            checkRotation(ctx);

        } catch (IOException e) {
            log.error("Failed to write NALU for camera {}: {}", cameraId, e.getMessage(), e);
        }
    }

    /**
     * Force rotate the current segment.
     */
    public void forceRotate(String cameraId) {
        SegmentContext ctx = segmentContexts.get(cameraId);
        if (ctx != null) {
            rotateSegment(ctx);
        }
    }

    /**
     * Stop segmenting for a camera.
     */
    public void stopSegment(String cameraId) {
        SegmentContext ctx = segmentContexts.remove(cameraId);
        if (ctx != null) {
            rotateSegment(ctx);
        }
    }

    private void checkRotation(SegmentContext ctx) {
        boolean timeExpired = System.currentTimeMillis() - ctx.getStartTime().toInstant(java.time.ZoneId.systemDefault()).toEpochMilli()
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
            // Flush remaining data
            byte[] flushData = ctx.getMuxer().flush();
            if (flushData != null && flushData.length > 0) {
                ctx.getFileOutputStream().write(flushData);
            }

            // Close current file
            ctx.getFileOutputStream().flush();
            ctx.getFileOutputStream().close();

            // Calculate end time
            LocalDateTime endTime = LocalDateTime.now();

            // Upload to MinIO
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
                    .resolve(ctx.getCameraId() + "_" + System.currentTimeMillis() + ".ts"));
            ctx.setFileOutputStream(new FileOutputStream(ctx.getTempFile().toFile()));

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
