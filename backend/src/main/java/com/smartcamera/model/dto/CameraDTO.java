package com.smartcamera.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CameraDTO {

    @NotBlank(message = "摄像头ID不能为空")
    private String cameraId;

    @NotBlank(message = "摄像头名称不能为空")
    private String cameraName;

    private String devicePath;

    private String resolution = "1920x1080";

    private Integer framerate = 25;

    private Integer bitrateKbps = 2000;

    private Integer rtspPort = 8554;

    private Boolean enabled = true;
}
