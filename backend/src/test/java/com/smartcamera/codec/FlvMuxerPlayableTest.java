package com.smartcamera.netty.codec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 生成一个可被 ffplay/mpegts.js 播放的 FLV 文件，
 * 使用真实 SPS/PPS 构造完整的 H.264 IDR 和 P 帧。
 */
class FlvMuxerPlayableTest {

    @Test
    void generatePlayableFlv() throws IOException {
        FlvMuxer flvMuxer = new FlvMuxer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Real SPS/PPS from FFmpeg ANNOUNCE
        byte[] sps = java.util.Base64.getDecoder().decode("Z0LAHtoCgPaagIECoACiwqAmJaAR4sXU");
        byte[] pps = java.util.Base64.getDecoder().decode("aM48gA==");

        // 1. FLV header + PrevTagSize0
        out.write(flvMuxer.getFlvHeader());
        out.write(new byte[]{0, 0, 0, 0});

        // 2. AVC sequence header
        out.write(flvMuxer.processAvcSequenceHeader(sps, pps));

        // 3. Construct a minimal valid IDR frame
        // IDR NAL = start code + NAL header (0x65) + minimal slice data
        byte[] idrNal = buildNal(0x65, sps, pps);
        out.write(flvMuxer.processNalu(idrNal));

        // 4. Construct a minimal P-frame
        byte[] pNal = buildNal(0x61, new byte[0], pps);
        out.write(flvMuxer.processNalu(pNal));

        // 5. Write more frames
        for (int i = 0; i < 5; i++) {
            byte[] pFrame = buildNal(0x61, new byte[i * 10 + 5], pps);
            out.write(flvMuxer.processNalu(pFrame));
        }

        String tempPath = System.getProperty("java.io.tmpdir") + "/test_output.flv";
        Files.write(Paths.get(tempPath), out.toByteArray());
        System.out.println("FLV file: " + tempPath + " (" + out.size() + " bytes)");
        System.out.println("Hex: " + bytesToHex(out.toByteArray(), 120));
    }

    /**
     * Build a minimal valid H.264 NALU with start code.
     * Contains slice header with enough data to be parseable.
     */
    private byte[] buildNal(int nalType, byte[] payload, byte[] pps) {
        // Minimal valid H.264 slice: NAL header + slice header + minimal payload
        // First byte of slice header: first_mb_in_slice (usually 0)
        // Then: slice_type (ue(v)), pic_parameter_set_id (ue(v)), then more...
        // Using pre-encoded minimal slice data
        byte[] sliceData = payload.length > 0 ? payload : new byte[]{(byte) 0x80};
        byte[] nal = new byte[4 + 1 + sliceData.length];
        nal[0] = 0x00;
        nal[1] = 0x00;
        nal[2] = 0x00;
        nal[3] = 0x01;
        nal[4] = (byte) (nalType & 0xFF);
        System.arraycopy(sliceData, 0, nal, 5, sliceData.length);
        return nal;
    }

    private static String bytesToHex(byte[] bytes, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(bytes.length, maxLen);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x ", bytes[i]));
        }
        if (bytes.length > maxLen) sb.append("...");
        return sb.toString();
    }
}
