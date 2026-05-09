package com.smartcamera.netty.codec;

import com.smartcamera.netty.model.RtpPacket;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses H.264 NAL Units from RTP packets.
 * Handles Single NALU, FU-A fragmentation, and STAP-A aggregation.
 */
@Slf4j
public class H264Parser {

    private static final int NALU_TYPE_SINGLE = 1;
    private static final int NALU_TYPE_FU_A = 28;
    private static final int NALU_TYPE_STAP_A = 24;

    private byte[] fuBuffer = null;
    private int fuBufferSize = 0;

    /**
     * Parse RTP packet payload into H.264 NALU(s).
     * Returns a list of complete NALUs (with start code prefix).
     */
    public List<byte[]> parse(RtpPacket packet) {
        List<byte[]> nalus = new ArrayList<>();
        byte[] payload = packet.getPayload();

        if (payload == null || payload.length == 0) {
            return nalus;
        }

        int naluType = payload[0] & 0x1F;

        switch (naluType) {
            case NALU_TYPE_SINGLE -> {
                // Single NALU
                nalus.add(buildNalu(payload));
            }
            case NALU_TYPE_FU_A -> {
                nalus.addAll(handleFuA(payload));
            }
            case NALU_TYPE_STAP_A -> {
                nalus.addAll(handleStapA(payload));
            }
            default -> {
                // Other NALU types, treat as single
                nalus.add(buildNalu(payload));
            }
        }

        return nalus;
    }

    private List<byte[]> handleFuA(byte[] payload) {
        List<byte[]> nalus = new ArrayList<>();
        if (payload.length < 2) {
            return nalus;
        }

        byte fuHeader = payload[1];
        boolean start = (fuHeader & 0x80) != 0;
        boolean end = (fuHeader & 0x40) != 0;
        int naluType = fuHeader & 0x1F;

        // Reconstruct the original NAL header
        byte nalHeader = (byte) ((payload[0] & 0xE0) | naluType);

        if (start) {
            // Start of FU-A: allocate buffer
            fuBuffer = new byte[payload.length - 1 + 1]; // -1 for FU indicator, +1 for reconstructed NAL header
            fuBuffer[0] = 0x00;
            fuBuffer[1] = 0x00;
            fuBuffer[2] = 0x00;
            fuBuffer[3] = 0x01;
            fuBuffer[4] = nalHeader;
            fuBufferSize = 5;
            System.arraycopy(payload, 2, fuBuffer, fuBufferSize, payload.length - 2);
            fuBufferSize += payload.length - 2;
        } else if (end) {
            // End of FU-A: finalize NALU
            byte[] nalu = new byte[fuBufferSize + payload.length - 2];
            System.arraycopy(fuBuffer, 0, nalu, 0, fuBufferSize);
            System.arraycopy(payload, 2, nalu, fuBufferSize, payload.length - 2);
            nalus.add(nalu);
            fuBuffer = null;
            fuBufferSize = 0;
        } else {
            // Middle of FU-A: append to buffer
            if (fuBuffer != null) {
                int needed = fuBufferSize + payload.length - 2;
                if (needed > fuBuffer.length) {
                    byte[] newBuf = new byte[needed];
                    System.arraycopy(fuBuffer, 0, newBuf, 0, fuBufferSize);
                    fuBuffer = newBuf;
                }
                System.arraycopy(payload, 2, fuBuffer, fuBufferSize, payload.length - 2);
                fuBufferSize += payload.length - 2;
            }
        }

        return nalus;
    }

    private List<byte[]> handleStapA(byte[] payload) {
        List<byte[]> nalus = new ArrayList<>();
        int offset = 1; // Skip STAP-A header byte

        while (offset < payload.length) {
            if (offset + 2 > payload.length) break;
            int naluSize = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
            offset += 2;

            if (offset + naluSize > payload.length) break;

            byte[] nalu = buildNalu(payload, offset, naluSize);
            nalus.add(nalu);
            offset += naluSize;
        }

        return nalus;
    }

    private byte[] buildNalu(byte[] payload) {
        return buildNalu(payload, 0, payload.length);
    }

    private byte[] buildNalu(byte[] payload, int offset, int length) {
        byte[] nalu = new byte[4 + length];
        nalu[0] = 0x00;
        nalu[1] = 0x00;
        nalu[2] = 0x00;
        nalu[3] = 0x01;
        System.arraycopy(payload, offset, nalu, 4, length);
        return nalu;
    }

    /**
     * Get NALU type from the NAL header.
     */
    public static int getNaluType(byte[] nalu) {
        // Skip start code prefix (00 00 00 01)
        int offset = 0;
        if (nalu.length >= 4 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 0 && nalu[3] == 1) {
            offset = 4;
        } else if (nalu.length >= 3 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 1) {
            offset = 3;
        }
        if (offset >= nalu.length) return 0;
        return nalu[offset] & 0x1F;
    }

    public boolean isSps(byte[] nalu) {
        return getNaluType(nalu) == 7;
    }

    public boolean isPps(byte[] nalu) {
        return getNaluType(nalu) == 8;
    }

    public boolean isIDR(byte[] nalu) {
        return getNaluType(nalu) == 5;
    }
}
