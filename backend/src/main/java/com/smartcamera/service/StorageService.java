package com.smartcamera.service;

import com.smartcamera.config.MinioConfig;
import com.smartcamera.entity.VideoSegment;
import com.smartcamera.repository.VideoSegmentRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final VideoSegmentRepository segmentRepository;

    public MinioClient getMinioClient() {
        return minioClient;
    }

    /**
     * Upload a segment file to MinIO and record metadata.
     * Supports .h264 format.
     */
    @Async
    public void uploadSegment(String cameraId, Path localFilePath,
                               LocalDateTime startTime, LocalDateTime endTime,
                               String resolution) {
        try {
            String dateStr = startTime.toLocalDate().toString().replace("-", "");
            String timeStr = startTime.toLocalTime().toString().replace(":", "");
            String fileName = localFilePath.getFileName().toString();
            String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "h264";

            String objectName = cameraId + "/" + dateStr + "/" + timeStr + "." + extension;

            long fileSize = Files.size(localFilePath);

            try (FileInputStream fis = new FileInputStream(localFilePath.toFile())) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(minioConfig.getBucket())
                                .object(objectName)
                                .stream(fis, fileSize, -1)
                                .contentType("application/octet-stream")
                                .build());
            }

            // Record metadata
            VideoSegment segment = new VideoSegment();
            segment.setCameraId(cameraId);
            segment.setFilePath(objectName);
            segment.setBucketName(minioConfig.getBucket());
            segment.setStartTime(startTime);
            segment.setEndTime(endTime);
            segment.setDurationMs((int) Duration.between(startTime, endTime).toMillis());
            segment.setFileSize(fileSize);
            segment.setResolution(resolution);
            segment.setCodec("h264");
            segment.setExpiredAt(endTime.plusDays(minioConfig.getBucket().equals("streams") ? 30 : 0));
            segmentRepository.save(segment);

            log.info("Segment uploaded to MinIO: {} ({} bytes, format: {})", objectName, fileSize, extension);

            // Clean up local temp file
            Files.deleteIfExists(localFilePath);

        } catch (Exception e) {
            log.error("Failed to upload segment to MinIO: {}", e.getMessage(), e);
        }
    }

    /**
     * Upload a merged playback MP4 file to MinIO and return its presigned URL.
     */
    public String uploadPlayback(String cameraId, Path localFilePath, String outputFileName) {
        try {
            String objectName = "playback/" + cameraId + "/" + outputFileName;
            long fileSize = Files.size(localFilePath);

            try (FileInputStream fis = new FileInputStream(localFilePath.toFile())) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(minioConfig.getBucket())
                                .object(objectName)
                                .stream(fis, fileSize, -1)
                                .contentType("video/mp4")
                                .build());
            }

            String url = getSegmentUrl(objectName, 120);
            log.info("Playback MP4 uploaded to MinIO: {} ({} bytes)", objectName, fileSize);

            // Clean up temp file
            Files.deleteIfExists(localFilePath);

            return url;
        } catch (Exception e) {
            log.error("Failed to upload playback MP4: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upload playback file", e);
        }
    }

    /**
     * Get a presigned URL for downloading a segment.
     */
    public String getSegmentUrl(String objectName, int expiryMinutes) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(minioConfig.getBucket())
                            .object(objectName)
                            .method(Method.GET)
                            .expiry(expiryMinutes, TimeUnit.MINUTES)
                            .build());
        } catch (Exception e) {
            log.error("Failed to generate presigned URL for {}: {}", objectName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Delete a segment from MinIO and database.
     */
    public void deleteSegment(Long segmentId) {
        VideoSegment segment = segmentRepository.findById(segmentId).orElse(null);
        if (segment == null) return;

        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(segment.getBucketName())
                            .object(segment.getFilePath())
                            .build());
            segmentRepository.deleteById(segmentId);
            log.info("Segment deleted: id={}", segmentId);
        } catch (Exception e) {
            log.error("Failed to delete segment {}: {}", segmentId, e.getMessage(), e);
        }
    }

    /**
     * Batch delete expired segments.
     */
    public void deleteExpiredSegments() {
        List<VideoSegment> expired = segmentRepository.findByExpiredAtBefore(LocalDateTime.now());
        log.info("Found {} expired segments", expired.size());

        for (VideoSegment segment : expired) {
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(segment.getBucketName())
                                .object(segment.getFilePath())
                                .build());
                segmentRepository.delete(segment);
            } catch (Exception e) {
                log.error("Failed to delete expired segment {}: {}", segment.getId(), e.getMessage(), e);
            }
        }
        log.info("Deleted {} expired segments", expired.size());
    }

    /**
     * Get total storage size (approximate, via segment metadata).
     */
    public long getTotalStorageBytes() {
        // This is an approximation based on DB records
        return segmentRepository.findAll().stream()
                .mapToLong(VideoSegment::getFileSize)
                .sum();
    }

    /**
     * Get total segment count.
     */
    public long getTotalSegmentCount() {
        return segmentRepository.count();
    }
}
