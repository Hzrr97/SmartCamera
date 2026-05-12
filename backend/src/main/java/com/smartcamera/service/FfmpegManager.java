package com.smartcamera.service;

import com.smartcamera.config.CameraProperties;
import com.smartcamera.entity.CameraConfig;
import com.smartcamera.repository.CameraConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class FfmpegManager {

    private final CameraProperties properties;
    private final FfmpegCommandBuilder commandBuilder;
    private final CameraConfigRepository cameraConfigRepository;
    private final RecordingService recordingService;

    public FfmpegManager(CameraProperties properties,
                         FfmpegCommandBuilder commandBuilder,
                         CameraConfigRepository cameraConfigRepository,
                         @Lazy RecordingService recordingService) {
        this.properties = properties;
        this.commandBuilder = commandBuilder;
        this.cameraConfigRepository = cameraConfigRepository;
        this.recordingService = recordingService;
    }

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, FfmpegProcessContext> contexts = new ConcurrentHashMap<>();
    private final Set<String> manuallyStopped = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile boolean shuttingDown = false;

    @PostConstruct
    public void init() {
        // JVM shutdown hook: force-kill all FFmpeg processes even on abrupt JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook: force-killing all FFmpeg processes...");
            processes.values().forEach(p -> {
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            });
            scheduler.shutdownNow();
        }, "ffmpeg-shutdown-hook"));

        // Auto-start all enabled cameras on application startup
        List<CameraConfig> enabledCameras = cameraConfigRepository.findByEnabledTrue();
        if (enabledCameras.isEmpty()) {
            log.info("No enabled cameras found on startup");
        } else {
            log.info("Found {} enabled cameras, auto-starting streams...", enabledCameras.size());
            for (CameraConfig config : enabledCameras) {
                try {
                    start(config);
                } catch (Exception e) {
                    log.error("Failed to auto-start camera {}: {}", config.getCameraId(), e.getMessage());
                }
            }
        }
    }

    public synchronized void start(CameraConfig config) {
        String cameraId = config.getCameraId();
        if (shuttingDown) {
            log.warn("Rejecting start for camera {} — manager is shutting down", cameraId);
            return;
        }
        if (processes.containsKey(cameraId)) {
            log.warn("FFmpeg process already running for camera: {}", cameraId);
            return;
        }

        String devicePath = config.getDevicePath() != null ? config.getDevicePath() : commandBuilder.getDevicePath();
        List<String> command = commandBuilder.buildPushCommand(
                cameraId, devicePath,
                config.getFramerate(),
                config.getResolution(),
                config.getBitrateKbps());

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            FfmpegProcessContext ctx = new FfmpegProcessContext();
            ctx.setCameraId(cameraId);
            ctx.setStartTime(System.currentTimeMillis());
            ctx.setRetryCount(0);

            processes.put(cameraId, process);
            contexts.put(cameraId, ctx);

            // Start log reader thread
            Thread logThread = new Thread(() -> readProcessOutput(cameraId, process), "ffmpeg-log-" + cameraId);
            logThread.setDaemon(true);
            logThread.start();

            // Monitor process exit
            Thread monitorThread = new Thread(() -> {
                try {
                    int exitCode = process.waitFor();
                    log.warn("FFmpeg process exited for camera {} with code {}", cameraId, exitCode);
                    processes.remove(cameraId);
                    contexts.remove(cameraId);

                    boolean wasManuallyStopped = manuallyStopped.remove(cameraId);
                    if (wasManuallyStopped) {
                        log.info("Camera {} was manually stopped, skipping auto-retry", cameraId);
                        updateCameraStatus(cameraId, "OFFLINE");
                        return;
                    }

                    updateCameraStatus(cameraId, "OFFLINE");

                    // Auto retry
                    if (properties.getFfmpeg().isAutoRetry()
                            && ctx.getRetryCount() < properties.getFfmpeg().getMaxRetry()) {
                        log.info("Retrying FFmpeg for camera {} (attempt {}/{})",
                                cameraId, ctx.getRetryCount() + 1, properties.getFfmpeg().getMaxRetry());
                        CameraConfig retryConfig = cameraConfigRepository.findByCameraId(cameraId).orElse(null);
                        if (retryConfig != null) {
                            scheduler.schedule(() -> start(retryConfig),
                                    properties.getFfmpeg().getRetryDelaySeconds(), TimeUnit.SECONDS);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "ffmpeg-monitor-" + cameraId);
            monitorThread.setDaemon(true);
            monitorThread.start();

            updateCameraStatus(cameraId, "ONLINE");

            // Start recording (segment writing to disk)
            try {
                recordingService.startRecording(cameraId, config.getResolution());
            } catch (Exception e) {
                log.error("Failed to start recording for camera {}: {}", cameraId, e.getMessage(), e);
            }

            log.info("FFmpeg process started for camera: {}", cameraId);

        } catch (IOException e) {
            log.error("Failed to start FFmpeg for camera {}: {}", cameraId, e.getMessage(), e);
            updateCameraStatus(cameraId, "ERROR");
            throw new RuntimeException("Failed to start FFmpeg", e);
        }
    }

    public synchronized void stop(String cameraId) {
        // Mark as manually stopped to prevent auto-retry
        manuallyStopped.add(cameraId);

        // Stop recording first
        try {
            recordingService.stopRecording(cameraId);
        } catch (Exception e) {
            log.error("Error stopping recording for camera {}: {}", cameraId, e.getMessage(), e);
        }

        Process process = processes.remove(cameraId);
        FfmpegProcessContext ctx = contexts.remove(cameraId);

        if (process == null) {
            log.warn("No FFmpeg process found for camera: {}", cameraId);
            return;
        }

        try {
            // Graceful shutdown: send 'q' to stdin
            OutputStream stdin = process.getOutputStream();
            stdin.write('q');
            stdin.flush();
            stdin.close();

            boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
            if (!terminated) {
                process.destroyForcibly();
            }

            log.info("FFmpeg process stopped for camera: {}", cameraId);
        } catch (IOException | InterruptedException e) {
            log.error("Error stopping FFmpeg for camera {}: {}", cameraId, e.getMessage(), e);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        updateCameraStatus(cameraId, "OFFLINE");
    }

    public boolean isAlive(String cameraId) {
        Process process = processes.get(cameraId);
        return process != null && process.isAlive();
    }

    public Map<String, FfmpegProcessContext> getContexts() {
        return contexts;
    }

    public FfmpegProcessContext getContext(String cameraId) {
        return contexts.get(cameraId);
    }

    private void readProcessOutput(String cameraId, Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[FFmpeg-{}] {}", cameraId, line);
            }
        } catch (IOException e) {
            log.error("Error reading FFmpeg output for camera {}: {}", cameraId, e.getMessage());
        }
    }

    private void updateCameraStatus(String cameraId, String status) {
        cameraConfigRepository.findByCameraId(cameraId).ifPresent(config -> {
            config.setStatus(status);
            cameraConfigRepository.save(config);
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down all FFmpeg processes...");
        shuttingDown = true;
        // Force-kill immediately to release camera device on Windows
        processes.values().forEach(p -> {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        });
        scheduler.shutdownNow();
    }

    @lombok.Data
    public static class FfmpegProcessContext {
        private String cameraId;
        private long startTime;
        private int retryCount;

        public long getUptimeSeconds() {
            return (System.currentTimeMillis() - startTime) / 1000;
        }

        public void incrementRetry() {
            this.retryCount++;
        }
    }
}
