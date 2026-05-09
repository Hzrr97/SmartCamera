package com.smartcamera.controller;

import com.smartcamera.entity.CameraConfig;
import com.smartcamera.model.dto.CameraDTO;
import com.smartcamera.repository.CameraConfigRepository;
import com.smartcamera.service.FfmpegManager;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final CameraConfigRepository cameraConfigRepository;
    private final FfmpegManager ffmpegManager;

    @GetMapping
    public ResponseEntity<List<CameraConfig>> listCameras() {
        return ResponseEntity.ok(cameraConfigRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<CameraConfig> createCamera(@Valid @RequestBody CameraDTO dto) {
        CameraConfig config = new CameraConfig();
        config.setCameraId(dto.getCameraId());
        config.setCameraName(dto.getCameraName());
        config.setDevicePath(dto.getDevicePath());
        config.setResolution(dto.getResolution());
        config.setFramerate(dto.getFramerate());
        config.setBitrateKbps(dto.getBitrateKbps());
        config.setRtspPort(dto.getRtspPort());
        config.setEnabled(dto.getEnabled());
        config.setStatus("OFFLINE");

        CameraConfig saved = cameraConfigRepository.save(config);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CameraConfig> updateCamera(@PathVariable Long id, @Valid @RequestBody CameraDTO dto) {
        CameraConfig config = cameraConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Camera not found: " + id));

        config.setCameraName(dto.getCameraName());
        config.setDevicePath(dto.getDevicePath());
        config.setResolution(dto.getResolution());
        config.setFramerate(dto.getFramerate());
        config.setBitrateKbps(dto.getBitrateKbps());
        config.setRtspPort(dto.getRtspPort());
        config.setEnabled(dto.getEnabled());

        return ResponseEntity.ok(cameraConfigRepository.save(config));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCamera(@PathVariable Long id) {
        CameraConfig config = cameraConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Camera not found: " + id));

        // Stop stream if running
        ffmpegManager.stop(config.getCameraId());

        cameraConfigRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Map<String, String>> startStream(@PathVariable Long id) {
        CameraConfig config = cameraConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Camera not found: " + id));

        ffmpegManager.start(config.getCameraId());
        return ResponseEntity.ok(Map.of("status", "starting", "cameraId", config.getCameraId()));
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, String>> stopStream(@PathVariable Long id) {
        CameraConfig config = cameraConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Camera not found: " + id));

        ffmpegManager.stop(config.getCameraId());
        return ResponseEntity.ok(Map.of("status", "stopped", "cameraId", config.getCameraId()));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getCameraStatus(@PathVariable Long id) {
        CameraConfig config = cameraConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Camera not found: " + id));

        Map<String, Object> status = new HashMap<>();
        status.put("cameraId", config.getCameraId());
        status.put("cameraName", config.getCameraName());
        status.put("status", config.getStatus());
        status.put("isStreaming", ffmpegManager.isAlive(config.getCameraId()));

        FfmpegManager.FfmpegProcessContext ctx = ffmpegManager.getContext(config.getCameraId());
        if (ctx != null) {
            status.put("uptimeSeconds", ctx.getUptimeSeconds());
        }

        return ResponseEntity.ok(status);
    }
}
