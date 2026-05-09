package com.smartcamera.controller;

import com.smartcamera.config.CameraProperties;
import com.smartcamera.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;
    private final CameraProperties cameraProperties;

    /**
     * Get storage statistics.
     * GET /api/v1/storage/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "totalUsedBytes", storageService.getTotalStorageBytes(),
                "totalSegments", storageService.getTotalSegmentCount(),
                "retentionDays", cameraProperties.getStorage().getRetentionDays()
        ));
    }

    /**
     * Delete expired segments.
     * DELETE /api/v1/storage/expired
     */
    @DeleteMapping("/expired")
    public ResponseEntity<Map<String, String>> deleteExpired() {
        storageService.deleteExpiredSegments();
        return ResponseEntity.ok(Map.of("status", "expired segments cleanup completed"));
    }

    /**
     * Update retention days.
     * PUT /api/v1/storage/retention-days
     */
    @PutMapping("/retention-days")
    public ResponseEntity<Map<String, Object>> updateRetentionDays(@RequestBody Map<String, Integer> body) {
        int days = body.get("days");
        cameraProperties.getStorage().setRetentionDays(days);
        return ResponseEntity.ok(Map.of("retentionDays", days));
    }
}
