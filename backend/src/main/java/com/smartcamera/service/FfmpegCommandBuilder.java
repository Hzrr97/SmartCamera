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

    public List<String> buildPushCommand(String cameraId, String devicePath) {
        List<String> command = new ArrayList<>();

        command.add(properties.getFfmpeg().getPath());

        // Input
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) {
            command.addAll(List.of("-f", "v4l2"));
        } else {
            command.addAll(List.of("-f", "dshow"));
        }

        command.addAll(List.of("-framerate", String.valueOf(properties.getFfmpeg().getFramerate())));
        command.addAll(List.of("-video_size", properties.getFfmpeg().getResolution()));
        command.addAll(List.of("-i", devicePath));

        // Output - H.264 encoding
        command.addAll(List.of("-c:v", "libx264"));
        command.addAll(List.of("-preset", properties.getFfmpeg().getPreset()));
        command.addAll(List.of("-tune", "zerolatency"));

        int bitrate = properties.getFfmpeg().getBitrateKbps();
        command.addAll(List.of("-b:v", bitrate + "k"));
        command.addAll(List.of("-maxrate", bitrate + "k"));
        command.addAll(List.of("-bufsize", (bitrate * 2) + "k"));

        // RTSP output
        String rtspUrl = buildRtspUrl(cameraId);
        command.addAll(List.of("-f", "rtsp", rtspUrl));

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
}
