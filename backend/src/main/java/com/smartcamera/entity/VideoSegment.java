package com.smartcamera.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "video_segment", indexes = {
        @Index(name = "idx_camera_time", columnList = "camera_id, start_time, end_time"),
        @Index(name = "idx_expired", columnList = "expired_at")
})
public class VideoSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "camera_id", nullable = false, length = 64)
    private String cameraId;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column(name = "bucket_name", nullable = false, length = 128)
    private String bucketName = "streams";

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "resolution", length = 16)
    private String resolution;

    @Column(name = "codec", length = 16)
    private String codec = "h264";

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
