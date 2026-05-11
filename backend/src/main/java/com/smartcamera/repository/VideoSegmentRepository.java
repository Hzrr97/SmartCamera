package com.smartcamera.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartcamera.entity.VideoSegment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface VideoSegmentRepository extends BaseMapper<VideoSegment> {

    default Optional<VideoSegment> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default List<VideoSegment> findAll() {
        return selectList(null);
    }

    default VideoSegment save(VideoSegment entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default void delete(VideoSegment entity) {
        deleteById(entity.getId());
    }

    default void deleteById(Long id) {
        deleteById(id);
    }

    default long count() {
        return selectCount(null);
    }

    @Select("SELECT * FROM video_segment WHERE camera_id = #{cameraId} " +
            "AND start_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY start_time ASC")
    List<VideoSegment> findByCameraIdAndStartTimeBetweenOrderByStartTimeAsc(
            @Param("cameraId") String cameraId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Select("SELECT * FROM video_segment WHERE camera_id = #{cameraId} ORDER BY start_time DESC")
    List<VideoSegment> findByCameraIdOrderByStartTimeDesc(@Param("cameraId") String cameraId);

    @Select("SELECT * FROM video_segment WHERE expired_at < #{now}")
    List<VideoSegment> findByExpiredAtBefore(@Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM video_segment WHERE camera_id = #{cameraId}")
    long countByCameraId(@Param("cameraId") String cameraId);
}
