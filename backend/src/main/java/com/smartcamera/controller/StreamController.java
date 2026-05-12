package com.smartcamera.controller;

import com.smartcamera.entity.CameraConfig;
import com.smartcamera.netty.codec.FlvMuxer;
import com.smartcamera.netty.codec.H264Parser;
import com.smartcamera.repository.CameraConfigRepository;
import com.smartcamera.service.FrameDistributor;
import com.smartcamera.service.FfmpegManager;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/streams")
public class StreamController {

    private final FfmpegManager ffmpegManager;
    private final FrameDistributor frameDistributor;
    private final CameraConfigRepository cameraConfigRepository;

    public StreamController(FfmpegManager ffmpegManager, FrameDistributor frameDistributor,
                            CameraConfigRepository cameraConfigRepository) {
        this.ffmpegManager = ffmpegManager;
        this.frameDistributor = frameDistributor;
        this.cameraConfigRepository = cameraConfigRepository;
    }

    @GetMapping(value = "/{cameraId}/live.flv", produces = "video/x-flv")
    public StreamingResponseBody streamFlv(@PathVariable String cameraId, HttpServletResponse response) {
        log.info("FLV stream request for camera {}", cameraId);

        response.setContentType("video/x-flv");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        return outputStream -> {
            log.info("Stream thread started for camera {}", cameraId);

            FlvMuxer flvMuxer = new FlvMuxer();
            final byte[][] spsHolder = new byte[1][];
            final byte[][] ppsHolder = new byte[1][];

            // 1. 写 FLV header（无音频，纯视频）
            outputStream.write(flvMuxer.getFlvHeader());
            outputStream.write(new byte[]{0, 0, 0, 0}); // PreviousTagSize0
            log.info("FLV header written for camera {}", cameraId);

            // 写 onMetaData（仅视频）
            byte[] metaTag = flvMuxer.getMetaDataTag(640, 480, 30.0);
            outputStream.write(metaTag);
            outputStream.flush();
            log.info("FLV onMetaData written for camera {}", cameraId);

            // 2. 建立视频订阅
            String videoSubId = frameDistributor.subscribe(cameraId, nalu -> {
                try {
                    int naluType = H264Parser.getNaluType(nalu);
                    if (naluType == 7) {
                        spsHolder[0] = stripStartCode(nalu);
                        log.info("SPS captured from stream: {} bytes", spsHolder[0].length);
                        return;
                    } else if (naluType == 8) {
                        ppsHolder[0] = stripStartCode(nalu);
                        log.info("PPS captured from stream: {} bytes", ppsHolder[0].length);
                        return;
                    } else if (naluType == 6) {
                        log.debug("Skipping SEI NALU");
                        return;
                    }

                    if (naluType != 1 && naluType != 5) {
                        log.debug("Skipping unsupported NALU type: {}", naluType);
                        return;
                    }

                    if (naluType == 5) {
                        if (spsHolder[0] == null || ppsHolder[0] == null) {
                            byte[][] cachedNow = com.smartcamera.netty.RtspServerHandler.getSpsPps(cameraId);
                            if (cachedNow != null) {
                                spsHolder[0] = cachedNow[0];
                                ppsHolder[0] = cachedNow[1];
                                log.info("Lazy-loaded SPS ({} bytes) and PPS ({} bytes) from ANNOUNCE cache for camera {}",
                                        spsHolder[0].length, ppsHolder[0].length, cameraId);
                            }
                        }
                    }

                    if (spsHolder[0] != null && ppsHolder[0] != null) {
                        // 首次写入 AVC 序列头
                        if (spsHolder[0] != null && ppsHolder[0] != null) {
                            byte[] seqHeader = flvMuxer.processAvcSequenceHeader(spsHolder[0], ppsHolder[0]);
                            outputStream.write(seqHeader);
                            outputStream.flush();
                            log.info("AVC sequence header written for camera {} (SPS={} PPS={})",
                                    cameraId, spsHolder[0].length, ppsHolder[0].length);
                            // 标记已写入，避免重复
                            spsHolder[0] = null;
                            ppsHolder[0] = null;
                        }
                        // 写入当前帧
                        byte[] flvData = flvMuxer.processNalu(nalu);
                        if (flvData.length > 0) {
                            outputStream.write(flvData);
                            outputStream.flush();
                        }
                    }
                } catch (IOException e) {
                    log.error("Failed to write FLV data for camera {}", cameraId, e);
                }
            });

            log.info("Subscribed to frameDistributor for camera {}, video: {}", cameraId, videoSubId);

            // 3. 启动 FFmpeg
            if (!ffmpegManager.isAlive(cameraId)) {
                log.info("Starting FFmpeg for camera: {}", cameraId);
                try {
                    CameraConfig config = cameraConfigRepository.findByCameraId(cameraId)
                            .orElseThrow(() -> new RuntimeException("Camera not found: " + cameraId));
                    ffmpegManager.start(config);
                } catch (Exception e) {
                    log.error("Failed to start FFmpeg for camera {}: {}", cameraId, e.getMessage());
                }
            } else {
                log.info("FFmpeg already running for camera {}", cameraId);
            }

            log.info("Waiting for frames from camera {}...", cameraId);

            // Keep connection alive
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                frameDistributor.unsubscribe(videoSubId);
                log.info("Unsubscribed from frameDistributor for camera {}", cameraId);
            }
        };
    }

    @GetMapping("/{cameraId}/status")
    public Map<String, Object> getStatus(@PathVariable String cameraId) {
        Map<String, Object> result = new HashMap<>();
        result.put("cameraId", cameraId);
        result.put("isAlive", ffmpegManager.isAlive(cameraId));

        FfmpegManager.FfmpegProcessContext ctx = ffmpegManager.getContext(cameraId);
        if (ctx != null) {
            result.put("uptimeSeconds", ctx.getUptimeSeconds());
        }

        return result;
    }

    private static byte[] stripStartCode(byte[] nalu) {
        int offset = 0;
        if (nalu.length >= 4 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 0 && nalu[3] == 1) {
            offset = 4;
        } else if (nalu.length >= 3 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 1) {
            offset = 3;
        }
        byte[] result = new byte[nalu.length - offset];
        System.arraycopy(nalu, offset, result, 0, result.length);
        return result;
    }
}
