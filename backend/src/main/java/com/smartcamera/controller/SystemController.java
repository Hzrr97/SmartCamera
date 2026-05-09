package com.smartcamera.controller;

import com.smartcamera.config.CameraProperties;
import com.smartcamera.repository.CameraConfigRepository;
import com.smartcamera.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private final CameraConfigRepository cameraConfigRepository;
    private final StorageService storageService;
    private final CameraProperties cameraProperties;

    /**
     * Get system status.
     * GET /api/v1/system/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();

        long totalCameras = cameraConfigRepository.count();
        long onlineCameras = cameraConfigRepository.findByStatus("ONLINE").size();

        status.put("totalCameras", totalCameras);
        status.put("onlineCameras", onlineCameras);
        status.put("recordingCount", onlineCameras); // Simplified
        status.put("storageUsedBytes", storageService.getTotalStorageBytes());
        status.put("totalSegments", storageService.getTotalSegmentCount());
        status.put("retentionDays", cameraProperties.getStorage().getRetentionDays());

        return ResponseEntity.ok(status);
    }
}
