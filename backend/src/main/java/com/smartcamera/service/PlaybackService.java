package com.smartcamera.service;

import com.smartcamera.config.MinioConfig;
import com.smartcamera.entity.VideoSegment;
import com.smartcamera.model.vo.PlaybackResultVO;
import com.smartcamera.repository.VideoSegmentRepository;
import io.minio.GetObjectArgs;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackService {

    private final VideoSegmentRepository segmentRepository;
    private final MinioConfig minioConfig;
    private final StorageService storageService;
    private final SegmentManager segmentManager;

    public List<PlaybackResultVO.SegmentInfoVO> querySegments(String cameraId, LocalDateTime startTime, LocalDateTime endTime) {
        List<VideoSegment> segments = segmentRepository.findByCameraIdAndStartTimeBetweenOrderByStartTimeAsc(
                cameraId, startTime, endTime);

        List<PlaybackResultVO.SegmentInfoVO> result = new ArrayList<>();
        for (VideoSegment seg : segments) {
            PlaybackResultVO.SegmentInfoVO vo = new PlaybackResultVO.SegmentInfoVO();
            vo.setId(seg.getId());
            vo.setFilePath(seg.getFilePath());
            vo.setStartTime(seg.getStartTime());
            vo.setEndTime(seg.getEndTime());
            vo.setDurationMs(seg.getDurationMs());
            vo.setFileSize(seg.getFileSize());
            vo.setDownloadUrl(storageService.getSegmentUrl(seg.getFilePath(), 60));
            result.add(vo);
        }
        return result;
    }

    public PlaybackResultVO getMergedPlaybackUrl(String cameraId, LocalDateTime startTime, LocalDateTime endTime) {
        List<VideoSegment> segments = segmentRepository.findByCameraIdAndStartTimeBetweenOrderByStartTimeAsc(
                cameraId, startTime, endTime);

        if (segments.isEmpty()) {
            throw new RuntimeException("No video segments found for the specified time range");
        }

        try {
            Path tempDir = Files.createTempDirectory("smart-camera-merge");
            List<Path> tempFiles = new ArrayList<>();

            // Download all segments from MinIO to temp directory
            for (VideoSegment seg : segments) {
                try (var inputStream = storageService.getMinioClient().getObject(
                                GetObjectArgs.builder()
                                        .bucket(seg.getBucketName())
                                        .object(seg.getFilePath())
                                        .build())) {
                    Path tempFile = tempDir.resolve(seg.getFilePath().replace("/", "_"));
                    Files.copy(inputStream, tempFile);
                    tempFiles.add(tempFile);
                    log.info("Downloaded segment: {} -> {} ({} bytes)", seg.getFilePath(), tempFile, Files.size(tempFile));
                }
            }

            // Build FFmpeg concat list file
            Path concatFile = tempDir.resolve("concat.txt");
            try (FileOutputStream fos = new FileOutputStream(concatFile.toFile())) {
                for (Path file : tempFiles) {
                    String line = "file '" + file.toAbsolutePath().toString().replace("\\", "/") + "'\n";
                    fos.write(line.getBytes(StandardCharsets.UTF_8));
                }
            }

            // Execute FFmpeg concat merge
            String outputFileName = cameraId + "_" + System.currentTimeMillis() + ".mp4";
            Path outputFile = tempDir.resolve(outputFileName);

            List<String> command = List.of(
                    "ffmpeg", "-y", "-f", "concat", "-safe", "0",
                    "-i", concatFile.toAbsolutePath().toString(),
                    "-c:v", "copy",
                    "-an",
                    "-movflags", "+faststart",
                    outputFile.toAbsolutePath().toString()
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read FFmpeg output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[FFmpeg-merge] {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg merge failed with exit code " + exitCode);
            }

            // Upload merged MP4 to MinIO
            String playbackUrl = storageService.uploadPlayback(cameraId, outputFile, outputFileName);

            // Clean up temp files
            for (Path f : tempFiles) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(concatFile);
            Files.deleteIfExists(tempDir);

            log.info("Merged {} segments into MP4 for camera {} playback", segments.size(), cameraId);

            // Build result
            PlaybackResultVO result = new PlaybackResultVO();
            result.setCameraId(cameraId);
            result.setStartTime(startTime);
            result.setEndTime(endTime);
            result.setDurationMs((long) java.time.Duration.between(startTime, endTime).toMillis());
            result.setPlaybackUrl(playbackUrl);

            List<PlaybackResultVO.SegmentInfoVO> segmentVOs = new ArrayList<>();
            for (VideoSegment seg : segments) {
                PlaybackResultVO.SegmentInfoVO vo = new PlaybackResultVO.SegmentInfoVO();
                vo.setId(seg.getId());
                vo.setFilePath(seg.getFilePath());
                vo.setStartTime(seg.getStartTime());
                vo.setEndTime(seg.getEndTime());
                vo.setDurationMs(seg.getDurationMs());
                vo.setFileSize(seg.getFileSize());
                vo.setDownloadUrl(storageService.getSegmentUrl(seg.getFilePath(), 60));
                segmentVOs.add(vo);
            }
            result.setSegments(segmentVOs);

            return result;

        } catch (Exception e) {
            log.error("Failed to create merged playback: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create merged playback: " + e.getMessage(), e);
        }
    }

    /**
     * Convert H.264 segment to MP4 and stream directly to HTTP response.
     * Downloads H.264 from MinIO, converts via FFmpeg, streams MP4 to browser.
     */
    public void streamSegmentAsMp4(Long segmentId, HttpServletResponse response) {
        VideoSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new RuntimeException("Segment not found: " + segmentId));

        String objectPath = segment.getFilePath();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("smart-camera-stream");
            Path inputFile = tempDir.resolve("input.h264");
            Path mp4File = tempDir.resolve("output.mp4");

            // Download from MinIO
            long downloadedSize;
            try (var is = storageService.getMinioClient().getObject(
                    GetObjectArgs.builder()
                            .bucket(segment.getBucketName())
                            .object(objectPath)
                            .build())) {
                downloadedSize = Files.copy(is, inputFile);
            }

            if (downloadedSize == 0) {
                log.error("Downloaded segment file is empty: {}", objectPath);
                response.sendError(404, "Segment file is empty or not available yet");
                return;
            }
            log.info("Downloaded segment {} ({} bytes, format: h264)", objectPath, downloadedSize);

            // Diagnose and fix: check if the file starts with SPS/PPS
            boolean hasSpsPps = checkH264SpsPps(inputFile, segment.getCameraId());

            // Convert to MP4 via FFmpeg
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");
            // Increase probe size for better codec detection on raw H.264
            command.add("-analyzeduration");
            command.add("10M");
            command.add("-probesize");
            command.add("10M");
            command.add("-fflags");
            command.add("+genpts");
            command.add("-i");
            command.add(inputFile.toAbsolutePath().toString());
            command.add("-c:v");
            command.add("copy");
            command.add("-an");
            command.add("-movflags");
            command.add("+faststart");
            command.add(mp4File.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[FFmpeg-stream] {}", line);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.error("FFmpeg stream conversion failed with exit code {}. Input: {} bytes", exitCode, downloadedSize);
                response.sendError(500, "Failed to convert video for playback");
                return;
            }

            // Stream MP4 to response
            long fileSize = Files.size(mp4File);
            response.setContentType("video/mp4");
            response.setHeader("Content-Length", String.valueOf(fileSize));
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Content-Disposition", "inline");

            try (var in = Files.newInputStream(mp4File);
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }

            log.info("Streamed segment {} as MP4 ({} bytes)", segmentId, fileSize);

        } catch (Exception e) {
            log.error("Failed to stream segment {}: {}", segmentId, e.getMessage(), e);
            try {
                response.sendError(500, "Playback error: " + e.getMessage());
            } catch (IOException ex) {
                log.error("Failed to send error response", ex);
            }
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                            });
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Check whether an H.264 file starts with SPS/PPS NALUs.
     * If missing, first try getting them from the active stream,
     * then fall back to scanning the file itself.
     * Returns true if SPS/PPS are present at the start.
     */
    private boolean checkH264SpsPps(Path file, String cameraId) {
        try {
            byte[] buf = Files.readAllBytes(file);
            int scanLimit = Math.min(buf.length, 200);

            // Check if file starts with SPS/PPS
            boolean hasSpsAtStart = false, hasPpsAtStart = false;
            for (int i = 0; i < scanLimit - 4; i++) {
                if (buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 0 && buf[i+3] == 1) {
                    if (i + 4 < buf.length) {
                        int type = buf[i+4] & 0x1F;
                        if (type == 7) hasSpsAtStart = true;
                        if (type == 8) hasPpsAtStart = true;
                    }
                }
            }
            if (hasSpsAtStart && hasPpsAtStart) {
                log.info("[H264诊断] 文件开头包含 SPS+PPS，可以正常播放 (文件大小: {} bytes)", buf.length);
                return true;
            }

            // SPS/PPS missing at start, try to get from active stream
            byte[] sps = null, pps = null;
            SegmentManager.SpsPpsCache cache = segmentManager.getSpsPps(cameraId);
            if (cache != null) {
                sps = cache.getSps();
                pps = cache.getPps();
                log.info("[H264诊断] 从活动流获取 SPS/PPS 用于修复旧分段 (SPS: {} bytes, PPS: {} bytes)",
                        sps.length, pps.length);
            }

            // If stream doesn't have them either, scan the file
            if (sps == null || pps == null) {
                log.warn("[H264诊断] 活动流没有 SPS/PPS，正在扫描文件 (文件大小: {} bytes)", buf.length);
                for (int i = 0; i < buf.length - 4; i++) {
                    if (buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 0 && buf[i+3] == 1) {
                        if (i + 4 < buf.length) {
                            int type = buf[i+4] & 0x1F;
                            if (type == 7 && sps == null) {
                                int end = findNaluEnd(buf, i + 4);
                                sps = new byte[end - i];
                                System.arraycopy(buf, i, sps, 0, sps.length);
                            }
                            if (type == 8 && pps == null) {
                                int end = findNaluEnd(buf, i + 4);
                                pps = new byte[end - i];
                                System.arraycopy(buf, i, pps, 0, pps.length);
                            }
                        }
                    }
                    if (sps != null && pps != null) break;
                }
            }

            if (sps != null && pps != null) {
                // Inject SPS/PPS at file start
                byte[] newBuf = new byte[sps.length + pps.length + buf.length];
                System.arraycopy(sps, 0, newBuf, 0, sps.length);
                System.arraycopy(pps, 0, newBuf, sps.length, pps.length);
                System.arraycopy(buf, 0, newBuf, sps.length + pps.length, buf.length);
                Files.write(file, newBuf);
                log.info("[H264诊断] 已注入 SPS/PPS 到文件开头 (SPS: {} bytes, PPS: {} bytes)",
                        sps.length, pps.length);
                return true;
            }

            log.error("[H264诊断] 无法获取 SPS/PPS，文件无法播放 (文件大小: {} bytes)", buf.length);
            return false;

        } catch (IOException e) {
            log.warn("[H264诊断] 无法读取文件: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Find the end position of a NALU (next start code or end of buffer).
     */
    private int findNaluEnd(byte[] buf, int start) {
        for (int i = start + 1; i < buf.length - 3; i++) {
            if (buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 1) return i;
            if (buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 0 && buf[i+3] == 1) return i;
        }
        return buf.length;
    }

    public String getM3u8Playlist(String cameraId, LocalDateTime startTime, LocalDateTime endTime) {
        List<VideoSegment> segments = segmentRepository.findByCameraIdAndStartTimeBetweenOrderByStartTimeAsc(
                cameraId, startTime, endTime);

        StringBuilder m3u8 = new StringBuilder();
        m3u8.append("#EXTM3U\n");
        m3u8.append("#EXT-X-VERSION:3\n");

        for (VideoSegment seg : segments) {
            double durationSec = seg.getDurationMs() / 1000.0;
            String url = storageService.getSegmentUrl(seg.getFilePath(), 60);
            m3u8.append(String.format("#EXTINF:%.1f,\n", durationSec));
            m3u8.append(url).append("\n");
        }

        m3u8.append("#EXT-X-ENDLIST\n");
        return m3u8.toString();
    }

    public VideoSegment getSegment(Long segmentId) {
        return segmentRepository.findById(segmentId)
                .orElseThrow(() -> new RuntimeException("Segment not found: " + segmentId));
    }
}
