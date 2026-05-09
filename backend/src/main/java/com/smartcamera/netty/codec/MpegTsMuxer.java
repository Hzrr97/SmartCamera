package com.smartcamera.netty.codec;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Multiplexes H.264 NALUs into MPEG-TS format.
 * Outputs 188-byte TS packets.
 */
@Slf4j
public class MpegTsMuxer {

    private static final int TS_PACKET_SIZE = 188;
    private static final int VIDEO_PID = 0x100;
    private static final int PAT_PID = 0x000;
    private static final int PMT_PID = 0x1000;
    private static final int PCR_PID = VIDEO_PID;

    private int continuityCounter = 0;
    private boolean sppsSent = false;
    private byte[] spsNalu = null;
    private byte[] ppsNalu = null;

    // Buffer for TS output
    private final ByteArrayOutputStream tsOutput = new ByteArrayOutputStream();

    public MpegTsMuxer() {
        // Write PAT and PMT at initialization
        writePat();
        writePmt();
    }

    /**
     * Process a H.264 NALU and return TS packets.
     */
    public byte[] processNalu(byte[] nalu) throws IOException {
        // Skip start code prefix
        byte[] payload = stripStartCode(nalu);

        // Capture SPS/PPS for later use
        int naluType = H264Parser.getNaluType(nalu);
        if (naluType == 7) {
            spsNalu = payload;
        } else if (naluType == 8) {
            ppsNalu = payload;
        }

        // Send SPS/PPS before IDR if not yet sent
        if (naluType == 5 && !sppsSent && spsNalu != null && ppsNalu != null) {
            writeVideoPes(spsNalu, true);
            writeVideoPes(ppsNalu, true);
            sppsSent = true;
        }

        writeVideoPes(payload, false);

        // Flush complete TS packets
        byte[] result = tsOutput.toByteArray();
        tsOutput.reset();
        return result;
    }

    /**
     * Flush any remaining data as TS packets.
     */
    public byte[] flush() {
        // Write null packets to fill the buffer
        while (tsOutput.size() >= TS_PACKET_SIZE) {
            // Already handled by writeTsPacket
        }
        byte[] result = tsOutput.toByteArray();
        tsOutput.reset();
        return result;
    }

    private void writePat() {
        byte[] patPayload = new byte[185]; // 188 - 4 (TS header) - 1 (pointer field) + 1 (already accounted)

        // PAT section
        int idx = 0;
        patPayload[idx++] = 0x00; // table_id
        patPayload[idx++] = (byte) 0xB0; // section_syntax_indicator + length high
        patPayload[idx++] = 0x0D; // section length
        patPayload[idx++] = 0x00; // transport_stream_id high
        patPayload[idx++] = 0x01; // transport_stream_id low
        patPayload[idx++] = (byte) 0xC1; // version_number + current_next_indicator
        patPayload[idx++] = 0x00; // section_number
        patPayload[idx++] = 0x00; // last_section_number
        patPayload[idx++] = 0x00; // program_number high
        patPayload[idx++] = 0x01; // program_number low
        patPayload[idx++] = (byte) 0xE0; // reserved + PMT PID high
        patPayload[idx++] = 0x10; // PMT PID low

        // CRC32 placeholder (4 bytes of zeros)
        patPayload[idx++] = 0x00;
        patPayload[idx++] = 0x00;
        patPayload[idx++] = 0x00;
        patPayload[idx++] = 0x00;

        // Write TS packet
        writeTsPacket(PAT_PID, patPayload, 0, idx, true, false);
    }

    private void writePmt() {
        byte[] pmtPayload = new byte[185];
        int idx = 0;
        pmtPayload[idx++] = 0x02; // table_id
        pmtPayload[idx++] = (byte) 0xB0; // section_syntax_indicator + length high
        pmtPayload[idx++] = 0x17; // section length
        pmtPayload[idx++] = 0x00; // program_number high
        pmtPayload[idx++] = 0x01; // program_number low
        pmtPayload[idx++] = (byte) 0xC1; // version_number + current_next_indicator
        pmtPayload[idx++] = 0x00; // section_number
        pmtPayload[idx++] = 0x00; // last_section_number
        pmtPayload[idx++] = (byte) 0xE0; // reserved + PCR_PID high
        pmtPayload[idx++] = 0x10; // PCR_PID low
        pmtPayload[idx++] = (byte) 0xF0; // reserved + program_info_length high
        pmtPayload[idx++] = 0x00; // program_info_length low

        // H.264 stream descriptor
        pmtPayload[idx++] = 0x1B; // stream_type (H.264)
        pmtPayload[idx++] = (byte) 0xE0; // reserved + elementary_PID high
        pmtPayload[idx++] = 0x00; // elementary_PID low
        pmtPayload[idx++] = (byte) 0xF0; // reserved + ES_info_length high
        pmtPayload[idx++] = 0x00; // ES_info_length low

        // Write TS packet
        writeTsPacket(PMT_PID, pmtPayload, 0, idx, true, false);
    }

    private void writeVideoPes(byte[] payload, boolean isAu) throws IOException {
        // PES header
        ByteArrayOutputStream pesStream = new ByteArrayOutputStream();
        pesStream.write(0x00); // packet_start_code_prefix
        pesStream.write(0x00);
        pesStream.write(0x01);
        pesStream.write(0xE0); // stream_id (video 0)

        int pesPacketLength = 0; // 0 = unbounded for streaming
        pesStream.write(pesPacketLength >> 8);
        pesStream.write(pesPacketLength & 0xFF);

        // PES optional header
        pesStream.write(0x80); // 10 + PES_scrambling_control(00) + PES_priority(0) + data_alignment_indicator(0)
        pesStream.write(0x80); // PTS_DTS_flags(10) + ESCR_flag(0) + ...
        pesStream.write(0x05); // PES_header_data_length

        // PTS (33 bits)
        long pts = System.currentTimeMillis() * 9; // convert ms to 90kHz clock
        writePts(pesStream, pts);

        // Write payload
        pesStream.write(payload);

        byte[] pesData = pesStream.toByteArray();

        // Split PES data into TS packets
        int offset = 0;
        while (offset < pesData.length) {
            int payloadSize = Math.min(TS_PACKET_SIZE - 4 - 1, pesData.length - offset); // -4 TS header, -1 adaptation flag
            boolean payloadStart = (offset == 0);

            byte[] tsPayload = new byte[payloadSize];
            System.arraycopy(pesData, offset, tsPayload, 0, payloadSize);

            writeTsPacket(VIDEO_PID, tsPayload, 0, payloadSize, payloadStart, isAu);
            offset += payloadSize;
        }
    }

    private void writeTsPacket(int pid, byte[] payload, int offset, int length, boolean payloadStart, boolean isAu) {
        byte[] tsPacket = new byte[TS_PACKET_SIZE];

        // TS header (4 bytes)
        tsPacket[0] = 0x47; // sync byte
        tsPacket[1] = (byte) ((pid >> 8) & 0x1F); // transport_error(0) + payload_unit_start_indicator + PID high
        if (payloadStart) {
            tsPacket[1] |= 0x40; // payload_unit_start_indicator
        }
        tsPacket[2] = (byte) (pid & 0xFF); // PID low
        tsPacket[3] = (byte) (0x30 | (continuityCounter & 0x0F)); // continuity counter

        continuityCounter = (continuityCounter + 1) & 0x0F;

        // Copy payload
        int payloadOffset = 4;
        int available = TS_PACKET_SIZE - payloadOffset;

        if (length < available) {
            // Need adaptation field for padding
            tsPacket[3] |= 0x20; // adaptation_field_control
            int stuffingBytes = available - length - 1; // -1 for adaptation_field_length
            tsPacket[4] = (byte) (stuffingBytes); // adaptation_field_length
            for (int i = 0; i < stuffingBytes; i++) {
                tsPacket[5 + i] = (byte) 0xFF; // stuffing bytes
            }
            System.arraycopy(payload, offset, tsPacket, 5 + stuffingBytes, length);
        } else {
            tsPacket[3] |= 0x10; // has payload only
            System.arraycopy(payload, offset, tsPacket, payloadOffset, Math.min(length, available));
        }

        tsOutput.write(tsPacket, 0, TS_PACKET_SIZE);
    }

    private void writePts(ByteArrayOutputStream out, long pts) {
        byte[] b = new byte[5];
        b[0] = (byte) (0x20 | ((pts >> 30) & 0x0E) | 0x01);
        b[1] = (byte) (((pts >> 22) & 0xFF) | 0x01);
        b[2] = (byte) (((pts >> 15) & 0xFF) | 0x01);
        b[3] = (byte) (((pts >> 7) & 0xFF) | 0x01);
        b[4] = (byte) (((pts << 1) & 0xFE) | 0x01);
        out.writeBytes(b);
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

    public void reset() {
        continuityCounter = 0;
        sppsSent = false;
        tsOutput.reset();
    }
}
