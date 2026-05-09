package com.smartcamera.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PlaybackResultVO {
    private String cameraId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String playbackUrl;
    private Long durationMs;
    private List<SegmentInfoVO> segments;

    @Data
    public static class SegmentInfoVO {
        private Long id;
        private String filePath;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer durationMs;
        private Long fileSize;
        private String downloadUrl;
    }
}
