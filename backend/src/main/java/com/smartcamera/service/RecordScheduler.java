package com.smartcamera.service;

import com.smartcamera.config.CameraProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordScheduler {

    private final StorageService storageService;
    private final CameraProperties cameraProperties;

    /**
     * Scheduled task to clean up expired video segments.
     * Runs at the configured cron time (default: 2:00 AM daily).
     */
    @Scheduled(cron = "${camera.storage.cleanup-cron}")
    public void cleanupExpiredSegments() {
        log.info("Running scheduled cleanup of expired segments...");
        storageService.deleteExpiredSegments();
    }
}
