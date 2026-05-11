package com.smartcamera.netty.codec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 FlvMuxer 输出的字节格式是否符合 FLV 规范。
 */
class FlvMuxerTest {

    /**
     * 模拟后端输出到 outputstream 的完整字节流，
     * 然后验证前若干字节是否符合 FLV 规范。
     */
    @Test
    void testFlvOutputFormat() throws IOException {
        FlvMuxer flvMuxer = new FlvMuxer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 1. SPS/PPS (from ANNOUNCE SDP)
        byte[] sps = java.util.Base64.getDecoder().decode("Z0LAHtoCgPaagIECoACiwqAmJaAR4sXU");
        byte[] pps = java.util.Base64.getDecoder().decode("aM48gA==");

        // 2. Write FLV header + PreviousTagSize0
        out.write(flvMuxer.getFlvHeader());
        out.write(new byte[]{0, 0, 0, 0});

        // 3. Write AVC sequence header
        byte[] seqHeader = flvMuxer.processAvcSequenceHeader(sps, pps);
        out.write(seqHeader);
        System.out.println("AVC sequence header bytes: " + bytesToHex(seqHeader));

        // 4. Simulate an IDR NALU (fake data with start code, NAL type 5)
        byte[] fakeIdr = new byte[]{0x00, 0x00, 0x00, 0x01, 0x65, 0x01, 0x02, 0x03, 0x04};
        byte[] flvNalu = flvMuxer.processNalu(fakeIdr);
        out.write(flvNalu);
        System.out.println("FLV NALU tag bytes: " + bytesToHex(flvNalu));

        // 5. Verify FLV header
        byte[] all = out.toByteArray();
        System.out.println("Full output bytes (" + all.length + "): " + bytesToHex(all));

        assertEquals((byte)'F', all[0], "FLV header byte 0");
        assertEquals((byte)'L', all[1], "FLV header byte 1");
        assertEquals((byte)'V', all[2], "FLV header byte 2");
        assertEquals(0x01, all[3], "FLV version");
        assertEquals(0x01, all[4], "FLV flags (video only)");

        // 6. Verify PreviousTagSize0 = 0
        assertEquals(0x00, all[9], "PrevTagSize0 byte 0");
        assertEquals(0x00, all[10], "PrevTagSize0 byte 1");
        assertEquals(0x00, all[11], "PrevTagSize0 byte 2");
        assertEquals(0x00, all[12], "PrevTagSize0 byte 3");

        // 7. Verify AVC sequence header tag
        int seqTagOffset = 13; // 9 (FLV header) + 4 (PrevTagSize0)
        assertEquals(0x09, all[seqTagOffset], "AVC seq header tag type (0x09=video)");

        int seqTagBodySize = ((all[seqTagOffset + 1] & 0xFF) << 16) |
                             ((all[seqTagOffset + 2] & 0xFF) << 8) |
                             (all[seqTagOffset + 3] & 0xFF);
        System.out.println("AVC sequence header tag body size: " + seqTagBodySize);

        int seqHeaderDataOffset = seqTagOffset + 11; // tag header is 11 bytes
        assertEquals(0x17, all[seqHeaderDataOffset], "Seq header first byte: FrameType=1, CodecId=7");
        assertEquals(0x00, all[seqHeaderDataOffset + 1], "Seq header AVCPacketType = 0 (sequence header)");
        assertEquals(0x00, all[seqHeaderDataOffset + 2], "Seq header CompositionTime 0");
        assertEquals(0x00, all[seqHeaderDataOffset + 3], "Seq header CompositionTime 0");
        assertEquals(0x00, all[seqHeaderDataOffset + 4], "Seq header CompositionTime 0");

        // 8. Verify AVCDecoderConfigurationRecord
        int avcConfigOffset = seqHeaderDataOffset + 5;
        assertEquals(0x01, all[avcConfigOffset], "configurationVersion = 1");
        assertEquals(sps[1], all[avcConfigOffset + 1], "AVCProfileIndication");
        assertEquals(sps[2], all[avcConfigOffset + 2], "profile_compatibility");
        assertEquals(sps[3], all[avcConfigOffset + 3], "AVCLevelIndication");
        assertEquals((byte)0xFF, all[avcConfigOffset + 4], "lengthSizeMinusOne = 4");
        assertEquals((byte)0xE1, all[avcConfigOffset + 5], "numOfSPS = 1");

        // 9. Verify NALU tag
        int prevTagSizeAfterSeq = seqTagOffset + 11 + seqTagBodySize;
        int naluTagOffset = prevTagSizeAfterSeq + 4; // PreviousTagSize field
        assertEquals(0x09, all[naluTagOffset], "NALU tag type (0x09=video)");

        int naluDataOffset = naluTagOffset + 11;
        assertEquals(0x17, all[naluDataOffset], "NALU first byte: FrameType=1, CodecId=7");
        assertEquals(0x01, all[naluDataOffset + 1], "NALU AVCPacketType = 1 (NALU)");
        assertEquals(0x00, all[naluDataOffset + 2], "NALU CompositionTime 0");
        assertEquals(0x00, all[naluDataOffset + 3], "NALU CompositionTime 0");
        assertEquals(0x00, all[naluDataOffset + 4], "NALU CompositionTime 0");
    }

    /**
     * 输出可直接用 ffplay 播放的 FLV 文件到磁盘，
     * 然后用 ffprobe 验证。
     */
    @Test
    void testFlvOutputPlayable() throws IOException {
        FlvMuxer flvMuxer = new FlvMuxer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] sps = java.util.Base64.getDecoder().decode("Z0LAHtoCgPaagIECoACiwqAmJaAR4sXU");
        byte[] pps = java.util.Base64.getDecoder().decode("aM48gA==");

        // Write FLV header
        out.write(flvMuxer.getFlvHeader());
        out.write(new byte[]{0, 0, 0, 0});

        // Write AVC sequence header
        out.write(flvMuxer.processAvcSequenceHeader(sps, pps));

        // Write some fake NALUs (I-frame, P-frame, SPS, PPS)
        // SPS NALU
        byte[] spsNalu = new byte[sps.length + 5];
        spsNalu[0] = 0; spsNalu[1] = 0; spsNalu[2] = 0; spsNalu[3] = 1;
        spsNalu[4] = 0x67; // SPS type
        System.arraycopy(sps, 0, spsNalu, 5, sps.length);
        out.write(flvMuxer.processNalu(spsNalu));

        // PPS NALU
        byte[] ppsNalu = new byte[pps.length + 5];
        ppsNalu[0] = 0; ppsNalu[1] = 0; ppsNalu[2] = 0; ppsNalu[3] = 1;
        ppsNalu[4] = 0x68; // PPS type
        System.arraycopy(pps, 0, ppsNalu, 5, pps.length);
        out.write(flvMuxer.processNalu(ppsNalu));

        // IDR NALU (fake)
        byte[] idrNalu = new byte[100];
        idrNalu[0] = 0; idrNalu[1] = 0; idrNalu[2] = 0; idrNalu[3] = 1;
        idrNalu[4] = 0x65; // IDR type
        for (int i = 5; i < idrNalu.length; i++) {
            idrNalu[i] = (byte)(i % 256);
        }
        out.write(flvMuxer.processNalu(idrNalu));

        // P-frame NALU (fake)
        byte[] pNalu = new byte[80];
        pNalu[0] = 0; pNalu[1] = 0; pNalu[2] = 0; pNalu[3] = 1;
        pNalu[4] = 0x61; // P-frame type
        for (int i = 5; i < pNalu.length; i++) {
            pNalu[i] = (byte)(i % 256);
        }
        out.write(flvMuxer.processNalu(pNalu));

        // Save to file for manual testing
        String tempPath = System.getProperty("java.io.tmpdir") + "/test_output.flv";
        java.nio.file.Files.write(java.nio.file.Paths.get(tempPath), out.toByteArray());
        System.out.println("FLV file written to: " + tempPath);
        System.out.println("Total size: " + out.size() + " bytes");
        System.out.println("Hex dump: " + bytesToHex(out.toByteArray()));

        assertTrue(out.size() > 50, "FLV output should be non-trivial");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString();
    }
}
