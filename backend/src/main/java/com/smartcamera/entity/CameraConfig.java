package com.smartcamera.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "camera_config")
public class CameraConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "camera_id", unique = true, nullable = false, length = 64)
    private String cameraId;

    @Column(name = "camera_name", nullable = false, length = 128)
    private String cameraName;

    @Column(name = "device_path", length = 256)
    private String devicePath;

    @Column(name = "resolution", length = 16)
    private String resolution = "1920x1080";

    @Column(name = "framerate")
    private Integer framerate = 25;

    @Column(name = "bitrate_kbps")
    private Integer bitrateKbps = 2000;

    @Column(name = "rtsp_port")
    private Integer rtspPort = 8554;

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "status", length = 16)
    private String status = "OFFLINE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
