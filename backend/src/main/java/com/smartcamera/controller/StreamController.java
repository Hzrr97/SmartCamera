package com.smartcamera.controller;

import com.smartcamera.netty.codec.FlvMuxer;
import com.smartcamera.netty.codec.H264Parser;
import com.smartcamera.service.FrameDistributor;
import com.smartcamera.service.FfmpegManager;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/streams")
@RequiredArgsConstructor
public class StreamController {

    private final FfmpegManager ffmpegManager;
    private final FrameDistributor frameDistributor;
    private final H264Parser h264Parser = new H264Parser();

    /**
     * HTTP-FLV live stream endpoint.
     * GET /api/v1/streams/{cameraId}/live.flv
     */
    @GetMapping("/{cameraId}/live.flv")
    public StreamingResponseBody streamFlv(@PathVariable String cameraId) {
        log.info("FLV stream request for camera {}", cameraId);

        return outputStream -> {
            FlvMuxer flvMuxer = new FlvMuxer();
            byte[] sps = null;
            byte[] pps = null;

            // Write FLV header
            outputStream.write(flvMuxer.getFlvHeader());

            String subscriptionId = frameDistributor.subscribe(cameraId, nalu -> {
                try {
                    int naluType = H264Parser.getNaluType(nalu);
                    if (naluType == 7) {
                        sps = stripStartCode(nalu);
                    } else if (naluType == 8) {
                        pps = stripStartCode(nalu);
                    }

                    // Send SPS/PPS as AVC sequence header before first IDR
                    if (naluType == 5 && sps != null && pps != null) {
                        byte[] seqHeader = flvMuxer.processAvcSequenceHeader(sps, pps);
                        outputStream.write(seqHeader);
                        outputStream.flush();
                    }

                    byte[] flvData = flvMuxer.processNalu(nalu);
                    if (flvData.length > 0) {
                        outputStream.write(flvData);
                        outputStream.flush();
                    }
                } catch (IOException e) {
                    log.error("Failed to write FLV data for camera {}", cameraId, e);
                }
            });

            // Keep connection alive
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1000);
                }
            } finally {
                frameDistributor.unsubscribe(subscriptionId);
            }
        };
    }

    /**
     * Get stream status.
     * GET /api/v1/streams/{cameraId}/status
     */
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

    private byte[] stripStartCode(byte[] nalu) {
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
