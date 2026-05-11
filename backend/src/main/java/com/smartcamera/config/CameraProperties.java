package com.smartcamera.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "camera")
public class CameraProperties {

    private Rtsp rtsp = new Rtsp();
    private Ffmpeg ffmpeg = new Ffmpeg();
    private Stream stream = new Stream();
    private Storage storage = new Storage();

    @Data
    public static class Rtsp {
        private int port = 8554;
    }

    @Data
    public static class Ffmpeg {
        private String path = "ffmpeg";
        private int framerate = 30;
        private String resolution = "640x480";
        private int bitrateKbps = 1500;
        private String preset = "ultrafast";
        private Device device = new Device();
        private boolean autoRetry = true;
        private int maxRetry = 3;
        private int retryDelaySeconds = 5;

        @Data
        public static class Device {
            private String linux = "/dev/video0";
            private String windows = "USB Camera";
        }
    }

    @Data
    public static class Stream {
        private int segmentDurationMinutes = 10;
        private int segmentMaxSizeMb = 100;
        private String tempDir = System.getProperty("java.io.tmpdir") + "/smart-camera/segments";
        private int previewBufferSize = 512;
    }

    @Data
    public static class Storage {
        private int retentionDays = 30;
        private String cleanupCron = "0 0 2 * * ?";
    }
}
