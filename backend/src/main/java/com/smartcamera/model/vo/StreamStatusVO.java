package com.smartcamera.model.vo;

import lombok.Data;

@Data
public class StreamStatusVO {
    private String cameraId;
    private String status;
    private String resolution;
    private Integer framerate;
    private Long bitrateKbps;
    private String rtspUrl;
    private Long uptimeSeconds;
}
