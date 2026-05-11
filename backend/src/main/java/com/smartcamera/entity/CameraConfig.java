package com.smartcamera.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("camera_config")
public class CameraConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("camera_id")
    private String cameraId;

    @TableField("camera_name")
    private String cameraName;

    @TableField("device_path")
    private String devicePath;

    @TableField("resolution")
    private String resolution = "1920x1080";

    @TableField("framerate")
    private Integer framerate = 25;

    @TableField("bitrate_kbps")
    private Integer bitrateKbps = 2000;

    @TableField("rtsp_port")
    private Integer rtspPort = 8554;

    @TableField("enabled")
    private Boolean enabled = true;

    @TableField("status")
    private String status = "OFFLINE";

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
