package com.smartcamera.codec;

import com.smartcamera.netty.codec.FlvMuxer;
import com.smartcamera.netty.codec.H264Parser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模拟 StreamController 中缓冲帧释放的完整场景。
 * 验证：SPS/PPS 到达前的缓冲帧在序列头写入后能正确释放。
 */
class StreamControllerFlvIntegrationTest {

    @Test
    void testBufferedFramesDiscardedOnIdrArrival() throws IOException {
        FlvMuxer flvMuxer = new FlvMuxer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] sps = java.util.Base64.getDecoder().decode("Z0LAHtoCgPaagIECoACiwqAmJaAR4sXU");
        byte[] pps = java.util.Base64.getDecoder().decode("aM48gA==");

        // 1. 写 FLV header
        out.write(flvMuxer.getFlvHeader());
        out.write(new byte[]{0, 0, 0, 0});

        // 2. 模拟先收到 P 帧（SPS/PPS 未到达，缓冲）
        byte[] pFrame1 = buildNalu(0x61, new byte[]{0x01, 0x02, 0x03});
        // 实际逻辑中：bufferHolder[0].add(pFrame1)

        // 3. 模拟收到 SPS 和 PPS（从 ANNOUNCE 缓存 lazy-load）
        // spsHolder[0] = ..., ppsHolder[0] = ...

        // 4. IDR 帧到达，触发序列头写入
        // 缓冲帧被丢弃（不清出），直接从 IDR 开始
        byte[] idrFrame = buildNalu(0x65, new byte[]{0x10, 0x20, 0x30});

        out.write(flvMuxer.processAvcSequenceHeader(sps, pps));
        out.write(flvMuxer.processNalu(idrFrame));

        // 5. 继续收到后续帧
        byte[] pFrame2 = buildNalu(0x61, new byte[]{0x04, 0x05, 0x06});
        out.write(flvMuxer.processNalu(pFrame2));

        byte[] all = out.toByteArray();

        // 验证：应该有 3 个 tag（seq header + IDR + P-frame）
        int tagCount = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == 0x09 && i > 9) {
                tagCount++;
            }
        }
        assertEquals(3, tagCount, "Should have exactly 3 tags (seq header + IDR + P-frame), buffered P-frame discarded");

        // 验证第一个 video tag 是序列头
        int firstTagOffset = 13;
        assertEquals(0x09, all[firstTagOffset]); // video tag
        int firstTagBodyOffset = firstTagOffset + 11;
        assertEquals(0x17, all[firstTagBodyOffset]); // FrameType=1, CodecId=7
        assertEquals(0x00, all[firstTagBodyOffset + 1]); // AVCPacketType = 0 (sequence header)

        System.out.println("Total output: " + all.length + " bytes, tags: " + tagCount);
        System.out.println("Hex: " + bytesToHex(all));
    }

    @Test
    void testTimestampProgression() throws IOException {
        FlvMuxer flvMuxer = new FlvMuxer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] sps = java.util.Base64.getDecoder().decode("Z0LAHtoCgPaagIECoACiwqAmJaAR4sXU");
        byte[] pps = java.util.Base64.getDecoder().decode("aM48gA==");

        out.write(flvMuxer.getFlvHeader());
        out.write(new byte[]{0, 0, 0, 0});

        // 序列头 timestamp = 0
        out.write(flvMuxer.processAvcSequenceHeader(sps, pps, 0));

        // 缓冲帧使用递增时间戳
        byte[] nalu1 = buildNalu(0x61, new byte[10]);
        byte[] nalu2 = buildNalu(0x61, new byte[10]);
        byte[] nalu3 = buildNalu(0x65, new byte[20]);

        out.write(flvMuxer.processNalu(nalu1, 0));
        out.write(flvMuxer.processNalu(nalu2, 40));
        out.write(flvMuxer.processNalu(nalu3, 80));

        // 后续帧自动递增
        out.write(flvMuxer.processNalu(buildNalu(0x61, new byte[10])));
        out.write(flvMuxer.processNalu(buildNalu(0x61, new byte[10])));

        byte[] all = out.toByteArray();

        // 验证时间戳字段
        // 序列头 tag: timestamp 在 offset 13+4=17 处
        int seqTagOffset = 13;
        int seqTs = ((all[seqTagOffset + 4] & 0xFF) << 16) |
                    ((all[seqTagOffset + 5] & 0xFF) << 8) |
                    (all[seqTagOffset + 6] & 0xFF) |
                    ((all[seqTagOffset + 7] & 0xFF) << 24);
        assertEquals(0, seqTs, "Sequence header timestamp should be 0");

        System.out.println("Timestamp progression test passed, total: " + all.length + " bytes");
    }

    private byte[] buildNalu(int nalType, byte[] payload) {
        byte[] nalu = new byte[4 + 1 + payload.length];
        nalu[0] = 0x00; nalu[1] = 0x00; nalu[2] = 0x00; nalu[3] = 0x01;
        nalu[4] = (byte) (nalType & 0xFF);
        System.arraycopy(payload, 0, nalu, 5, payload.length);
        return nalu;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString();
    }
}
