package com.smartcamera.repository;

import com.smartcamera.entity.CameraConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CameraConfigRepository extends JpaRepository<CameraConfig, Long> {

    Optional<CameraConfig> findByCameraId(String cameraId);

    List<CameraConfig> findByStatus(String status);
}
