package com.smartcamera.controller;

import com.smartcamera.entity.VideoSegment;
import com.smartcamera.model.dto.PlaybackQueryDTO;
import com.smartcamera.model.vo.PlaybackResultVO;
import com.smartcamera.service.PlaybackService;
import com.smartcamera.service.StorageService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/playback")
@RequiredArgsConstructor
public class PlaybackController {

    private final PlaybackService playbackService;
    private final StorageService storageService;

    /**
     * Query video segments by time range.
     * GET /api/v1/playback/segments?cameraId=xxx&startTime=xxx&endTime=xxx
     */
    @GetMapping("/segments")
    public ResponseEntity<List<PlaybackResultVO.SegmentInfoVO>> querySegments(
            @RequestParam String cameraId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        List<PlaybackResultVO.SegmentInfoVO> segments = playbackService.querySegments(cameraId, startTime, endTime);
        return ResponseEntity.ok(segments);
    }

    /**
     * Get merged MP4 playback URL.
     * GET /api/v1/playback/merge?cameraId=xxx&startTime=xxx&endTime=xxx
     */
    @GetMapping("/merge")
    public ResponseEntity<PlaybackResultVO> getMergeUrl(
            @RequestParam String cameraId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        PlaybackResultVO result = playbackService.getMergedPlaybackUrl(cameraId, startTime, endTime);
        return ResponseEntity.ok(result);
    }

    /**
     * Get M3U8 playlist.
     * GET /api/v1/playback/playlist?cameraId=xxx&startTime=xxx&endTime=xxx
     */
    @GetMapping("/playlist")
    public ResponseEntity<String> getPlaylist(
            @RequestParam String cameraId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        String m3u8 = playbackService.getM3u8Playlist(cameraId, startTime, endTime);
        return ResponseEntity.ok()
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .body(m3u8);
    }

    /**
     * Download a single segment.
     * GET /api/v1/playback/download/{segmentId}
     */
    @GetMapping("/download/{segmentId}")
    public ResponseEntity<String> downloadSegment(@PathVariable Long segmentId) {
        String url = storageService.getSegmentUrl(
                playbackService.getSegment(segmentId).getFilePath(), 60);
        if (url != null) {
            return ResponseEntity.ok(url);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Stream a single H.264 segment as MP4 directly to the browser.
     * GET /api/v1/playback/stream/{segmentId}
     * Converts H.264 to MP4 on-the-fly and streams via HTTP response.
     */
    @GetMapping(value = "/stream/{segmentId}")
    public void streamSegmentAsMp4(@PathVariable Long segmentId, HttpServletResponse response) {
        playbackService.streamSegmentAsMp4(segmentId, response);
    }
}
