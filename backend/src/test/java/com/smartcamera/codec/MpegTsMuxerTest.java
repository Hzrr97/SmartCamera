package com.smartcamera.codec;

import com.smartcamera.netty.codec.H264Parser;
import com.smartcamera.netty.codec.MpegTsMuxer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MpegTsMuxer SPS/PPS writing behavior.
 * Verifies that every produced segment contains SPS/PPS at the beginning.
 */
class MpegTsMuxerTest {

    // Simulated H.264 NALUs with start code (00 00 00 01)
    private static final byte[] SPS = {0x00, 0x00, 0x00, 0x01, 0x67, 0x42, (byte) 0xc0, 0x1f, 0x01, 0x20, 0x20, 0x01};
    private static final byte[] PPS = {0x00, 0x00, 0x00, 0x01, 0x68, (byte) 0xce, 0x3c, (byte) 0x80};
    private static final byte[] IDR = {0x00, 0x00, 0x00, 0x01, 0x65, (byte) 0x88, (byte) 0x84, (byte) 0xa0};
    private static final byte[] PFRAME = {0x00, 0x00, 0x00, 0x01, 0x41, (byte) 0x9c, 0x10, 0x00};
    private static final byte[] SEI = {0x00, 0x00, 0x00, 0x01, 0x06, 0x01, 0x02};
    private static final byte[] AUD = {0x00, 0x00, 0x00, 0x01, 0x09, (byte) 0xf0};

    @Test
    @DisplayName("SPS and PPS should be written directly when received")
    void testSpsPpsWrittenDirectly() throws IOException {
        MpegTsMuxer muxer = new MpegTsMuxer();

        // Process SPS
        byte[] spsOutput = muxer.processNalu(SPS);
        assertNotNull(spsOutput, "SPS should be written to output");
        assertStartsWithStartCode(spsOutput);
        assertEquals(7, H264Parser.getNaluType(spsOutput), "Output should contain SPS");

        // Process PPS
        byte[] ppsOutput = muxer.processNalu(PPS);
        assertNotNull(ppsOutput, "PPS should be written to output");
        assertStartsWithStartCode(ppsOutput);
        assertEquals(8, H264Parser.getNaluType(ppsOutput), "Output should contain PPS");

        // Process IDR
        byte[] idrOutput = muxer.processNalu(IDR);
        assertNotNull(idrOutput, "IDR should be written to output");
        // After IDR, output should contain: SPS + PPS + IDR (since spsPpsSent not set yet)
        assertTrue(idrOutput.length > IDR.length, "IDR output should include SPS+PPS prefix");
    }

    @Test
    @DisplayName("SPS/PPS should be injected before first IDR frame")
    void testSpsPpsInjectedBeforeIdr() throws IOException {
        MpegTsMuxer muxer = new MpegTsMuxer();

        // Process SPS and PPS first (they get saved but written too)
        muxer.processNalu(SPS);
        muxer.processNalu(PPS);

        // Process a P frame first (no SPS/PPS injection since not IDR)
        byte[] pOut = muxer.processNalu(PFRAME);
        assertNotNull(pOut, "P frame should be written");

        // Process IDR frame - SPS/PPS should be injected
        byte[] idrOut = muxer.processNalu(IDR);
        assertNotNull(idrOut, "IDR frame output should not be null");

        // Verify SPS+PPS+IDR structure
        List<NaluInfo> nalus = parseAnnexB(idrOut);
        assertTrue(nalus.size() >= 2, "IDR output should contain at least SPS+PPS or SPS+PPS+IDR, got: " + nalus.size());
        assertEquals(7, nalus.get(0).type, "First NALU should be SPS");
        assertEquals(8, nalus.get(1).type, "Second NALU should be PPS");
    }

    @Test
    @DisplayName("Every segment starts with SPS/PPS after init() pre-seeding")
    void testInitPreSeedsSpsPps() throws IOException {
        MpegTsMuxer muxer = new MpegTsMuxer();

        // Simulate segment rotation: pre-seed SPS/PPS from previous segment
        muxer.init(extractPayload(SPS), extractPayload(PPS));

        // Drain the init output (SPS+PPS already in buffer)
        byte[] initOut = muxer.processNalu(PFRAME);
        assertNotNull(initOut, "After init, first frame output should not be null");

        List<NaluInfo> nalus = parseAnnexB(initOut);
        // Should contain SPS, PPS, and P frame
        assertTrue(nalus.size() >= 3, "Output should contain SPS+PPS+P frame, got " + nalus.size() + " NALUs");
        assertEquals(7, nalus.get(0).type, "First NALU should be SPS");
        assertEquals(8, nalus.get(1).type, "Second NALU should be PPS");
    }

    @Test
    @DisplayName("Segment is independently playable: IDR frames always have SPS/PPS before them")
    void testSegmentSelfContained() throws IOException {
        // Build a complete segment by processing NALUs in order
        MpegTsMuxer muxer = new MpegTsMuxer();
        ByteArrayOutputStream segment = new ByteArrayOutputStream();

        byte[][] nalus = {SPS, PPS, SEI, AUD, IDR, PFRAME, PFRAME, AUD, IDR, PFRAME};
        for (byte[] nalu : nalus) {
            byte[] out = muxer.processNalu(nalu);
            if (out != null) {
                segment.write(out);
            }
        }
        byte[] data = segment.toByteArray();

        // Verify the segment is valid Annex B
        List<NaluInfo> parsed = parseAnnexB(data);
        assertFalse(parsed.isEmpty(), "Segment should contain NALUs");

        // Check SPS/PPS appear before first IDR
        boolean foundSpsBeforeIdr = false;
        boolean foundPpsBeforeIdr = false;
        for (NaluInfo info : parsed) {
            if (info.type == 5) { // IDR
                break;
            }
            if (info.type == 7) foundSpsBeforeIdr = true;
            if (info.type == 8) foundPpsBeforeIdr = true;
        }
        assertTrue(foundSpsBeforeIdr, "SPS must appear before first IDR");
        assertTrue(foundPpsBeforeIdr, "PPS must appear before first IDR");

        // Verify no SEI/AUD leaked into output
        for (NaluInfo info : parsed) {
            assertNotEquals(6, info.type, "SEI should not be in output");
            assertNotEquals(9, info.type, "AUD should not be in output");
        }
    }

    // --- Helper methods ---

    private void assertStartsWithStartCode(byte[] data) {
        assertNotNull(data);
        assertTrue(data.length >= 4, "Data should have at least 4 bytes for start code");
        assertEquals(0, data[0]);
        assertEquals(0, data[1]);
        assertEquals(0, data[2]);
        assertEquals(1, data[3]);
    }

    private byte[] extractPayload(byte[] naluWithStartCode) {
        int offset = 4;
        byte[] payload = new byte[naluWithStartCode.length - offset];
        System.arraycopy(naluWithStartCode, offset, payload, 0, payload.length);
        return payload;
    }

    static class NaluInfo {
        int type;
        int length;
    }

    /**
     * Parse Annex B byte stream and extract NALU types and lengths.
     */
    private List<NaluInfo> parseAnnexB(byte[] data) {
        List<NaluInfo> result = new ArrayList<>();
        int i = 0;
        while (i < data.length - 3) {
            // Find start code (00 00 00 01 or 00 00 01)
            if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
                i += 4;
                if (i < data.length) {
                    NaluInfo info = new NaluInfo();
                    info.type = data[i] & 0x1F;
                    // Find next start code
                    int start = i;
                    while (i < data.length - 3) {
                        if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 1) break;
                        if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) break;
                        i++;
                    }
                    info.length = i - start;
                    result.add(info);
                }
            } else if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 1) {
                i += 3;
                if (i < data.length) {
                    NaluInfo info = new NaluInfo();
                    info.type = data[i] & 0x1F;
                    int start = i;
                    while (i < data.length - 3) {
                        if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 1) break;
                        if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) break;
                        i++;
                    }
                    info.length = i - start;
                    result.add(info);
                }
            } else {
                i++;
            }
        }
        return result;
    }
}
