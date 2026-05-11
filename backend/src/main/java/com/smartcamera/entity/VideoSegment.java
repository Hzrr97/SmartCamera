package com.smartcamera.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_segment")
public class VideoSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("camera_id")
    private String cameraId;

    @TableField("file_path")
    private String filePath;

    @TableField("bucket_name")
    private String bucketName = "streams";

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("duration_ms")
    private Integer durationMs;

    @TableField("file_size")
    private Long fileSize;

    @TableField("resolution")
    private String resolution;

    @TableField("codec")
    private String codec = "h264";

    @TableField("expired_at")
    private LocalDateTime expiredAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
