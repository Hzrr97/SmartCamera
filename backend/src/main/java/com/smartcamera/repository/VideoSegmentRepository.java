package com.smartcamera.repository;

import com.smartcamera.entity.VideoSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface VideoSegmentRepository extends JpaRepository<VideoSegment, Long> {

    List<VideoSegment> findByCameraIdAndStartTimeBetweenOrderByStartTimeAsc(
            String cameraId, LocalDateTime startTime, LocalDateTime endTime);

    List<VideoSegment> findByCameraIdOrderByStartTimeDesc(String cameraId);

    List<VideoSegment> findByExpiredAtBefore(LocalDateTime now);

    long countByCameraId(String cameraId);
}
