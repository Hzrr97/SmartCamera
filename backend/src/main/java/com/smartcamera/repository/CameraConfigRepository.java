package com.smartcamera.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartcamera.entity.CameraConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface CameraConfigRepository extends BaseMapper<CameraConfig> {

    default Optional<CameraConfig> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default List<CameraConfig> findAll() {
        return selectList(null);
    }

    default CameraConfig save(CameraConfig entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default void deleteById(Long id) {
        deleteById(id);
    }

    default long count() {
        return selectCount(null);
    }

    @Select("SELECT * FROM camera_config WHERE camera_id = #{cameraId}")
    Optional<CameraConfig> findByCameraId(@Param("cameraId") String cameraId);

    @Select("SELECT * FROM camera_config WHERE status = #{status}")
    List<CameraConfig> findByStatus(@Param("status") String status);

    @Select("SELECT * FROM camera_config WHERE enabled = true")
    List<CameraConfig> findByEnabledTrue();
}
