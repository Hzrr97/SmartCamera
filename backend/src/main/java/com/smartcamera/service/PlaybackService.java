package com.smartcamera.service;

import com.smartcamera.config.MinioConfig;
import com.smartcamera.entity.VideoSegment;
import com.smartcamera.model.vo.PlaybackResultVO;
import com.smartcamera.repository.VideoSegmentRepository;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackService {

    private final VideoSegmentRepository segmentRepository;
    private final MinioConfig minioConfig;
    private final StorageService storageService;

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
            // Download all segments to temp directory
            Path tempDir = Files.createTempDirectory("smart-camera-merge");
            List<Path> tempFiles = new ArrayList<>();

            for (VideoSegment seg : segments) {
                // In a real implementation, download from MinIO
                // For now, we'll reference the segment path
                log.info("Preparing segment for merge: {}", seg.getFilePath());
            }

            // Build ffmpeg concat command
            StringBuilder concatInput = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0) concatInput.append("|");
                concatInput.append(segments.get(i).getFilePath());
            }

            String outputFileName = cameraId + "_" + System.currentTimeMillis() + ".mp4";
            String outputPath = "playback/" + outputFileName;

            // In production, execute ffmpeg merge:
            // ffmpeg -i "concat:seg1.ts|seg2.ts" -c copy -y output.mp4
            // For now, return the first segment's URL as placeholder
            // TODO: Implement actual ffmpeg merge execution

            PlaybackResultVO result = new PlaybackResultVO();
            result.setCameraId(cameraId);
            result.setStartTime(startTime);
            result.setEndTime(endTime);
            result.setDurationMs((long) java.time.Duration.between(startTime, endTime).toMillis());

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
            result.setPlaybackUrl(storageService.getSegmentUrl(segments.get(0).getFilePath(), 60));

            // Clean up temp files
            for (Path f : tempFiles) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(tempDir);

            return result;

        } catch (Exception e) {
            log.error("Failed to create merged playback: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create merged playback", e);
        }
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
