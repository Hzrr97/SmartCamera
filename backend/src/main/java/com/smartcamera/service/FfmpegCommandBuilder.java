package com.smartcamera.service;

import com.smartcamera.config.CameraProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegCommandBuilder {

    private final CameraProperties properties;

    public List<String> buildPushCommand(String cameraId, String devicePath, Integer framerate, String resolution, Integer bitrateKbps) {
        List<String> command = new ArrayList<>();

        command.add(properties.getFfmpeg().getPath());

        // Input
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) {
            command.addAll(List.of("-f", "v4l2"));
        } else {
            command.addAll(List.of("-f", "dshow"));
        }

        // Use database config if provided, otherwise fall back to properties
        int fr = framerate != null ? framerate : properties.getFfmpeg().getFramerate();
        String res = resolution != null ? resolution : properties.getFfmpeg().getResolution();
        int bitrate = bitrateKbps != null ? bitrateKbps : properties.getFfmpeg().getBitrateKbps();

        command.addAll(List.of("-framerate", String.valueOf(fr)));
        command.addAll(List.of("-video_size", res));
        // Windows dshow requires video= prefix, wrap in quotes if contains spaces
        if (os.contains("win")) {
            if (!devicePath.startsWith("video=")) {
                devicePath = "video=" + devicePath;
            }
            // Wrap entire argument in quotes for FFmpeg parser
            if (devicePath.contains(" ")) {
                devicePath = "\"" + devicePath + "\"";
            }
        }
        command.add("-i");
        command.add(devicePath);

        // Output - H.264 encoding
        command.addAll(List.of("-pix_fmt", "yuv420p"));
        command.addAll(List.of("-c:v", "libx264"));
        command.addAll(List.of("-preset", properties.getFfmpeg().getPreset()));
        command.addAll(List.of("-tune", "zerolatency"));
        // 禁用 sliced_threads 确保每帧只产生一个 NALU，避免 MSE 解码器花屏
        command.addAll(List.of("-x264-params", "sliced_threads=0"));

        // 关键帧间隔：每 0.5 秒一个 I 帧，直播流畅
        command.addAll(List.of("-g", String.valueOf(Math.max(1, fr / 2))));
        command.addAll(List.of("-keyint_min", String.valueOf(Math.max(1, fr / 2))));

        command.addAll(List.of("-b:v", bitrate + "k"));
        command.addAll(List.of("-maxrate", bitrate + "k"));
        command.addAll(List.of("-bufsize", (bitrate * 2) + "k"));

        // RTSP output with UDP transport (more compatible)
        command.addAll(List.of("-rtsp_transport", "udp"));
        String rtspUrl = buildRtspUrl(cameraId);
        command.addAll(List.of("-f", "rtsp", rtspUrl));

        log.info("FFmpeg command: {}", String.join(" ", command));
        return command;
    }

    public String buildRtspUrl(String cameraId) {
        return String.format("rtsp://localhost:%d/live/%s",
                properties.getRtsp().getPort(), cameraId);
    }

    public String getDevicePath() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) {
            return properties.getFfmpeg().getDevice().getLinux();
        } else {
            return properties.getFfmpeg().getDevice().getWindows();
        }
    }

    /**
     * Normalize device path for FFmpeg dshow input.
     * If device name contains spaces, use alternative name format.
     */
    public String normalizeDevicePath(String devicePath) {
        if (devicePath == null || devicePath.isEmpty()) {
            return getDevicePath();
        }

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win") && devicePath.contains(" ") && !devicePath.startsWith("@device_")) {
            // Device name has spaces, try to find alternative name
            // For now, log warning and return as-is with video= prefix
            log.warn("Device name '{}' contains spaces. Consider using alternative name from: " +
                    "ffmpeg -list_devices true -f dshow -i dummy", devicePath);
        }

        // Ensure video= prefix for Windows
        if (os.contains("win") && !devicePath.startsWith("video=") && !devicePath.startsWith("@device_")) {
            devicePath = "video=" + devicePath;
        }

        return devicePath;
    }
}
