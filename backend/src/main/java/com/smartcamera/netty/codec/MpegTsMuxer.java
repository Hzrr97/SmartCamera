package com.smartcamera.netty.codec;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Collects H.264 NALUs into Annex B byte stream format.
 * Each NALU is written with its 4-byte start code (00 00 00 01).
 * Output can be saved as .h264 file which FFmpeg reads natively.
 */
@Slf4j
public class MpegTsMuxer {

    private static final byte[] START_CODE = {0x00, 0x00, 0x00, 0x01};

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(1024 * 1024);
    private byte[] spsNalu, ppsNalu;
    private boolean spsPpsSent = false;

    public MpegTsMuxer() {
    }

    /**
     * Pre-seed SPS/PPS from a previous segment so they can be injected
     * at the start of this segment file.
     */
    public void init(byte[] spsNalu, byte[] ppsNalu) throws IOException {
        if (spsNalu != null && ppsNalu != null) {
            this.spsNalu = spsNalu;
            this.ppsNalu = ppsNalu;
            writeNalu(spsNalu);
            writeNalu(ppsNalu);
            spsPpsSent = true; // Prevent duplicate injection on first frame
        }
    }

    public byte[] getSpsNalu() {
        return spsNalu;
    }

    public byte[] getPpsNalu() {
        return ppsNalu;
    }

    /**
     * Process a H.264 NALU and return Annex B data.
     * SPS/PPS are written directly to the output file.
     * They are also injected before each IDR frame for segment self-containment.
     */
    public byte[] processNalu(byte[] nalu) throws IOException {
        byte[] payload = stripStartCode(nalu);
        int type = H264Parser.getNaluType(nalu);

        // Capture and write SPS directly
        if (type == 7) {
            spsNalu = payload;
            writeNalu(payload);
            return drain();
        }
        // Capture and write PPS directly
        if (type == 8) {
            ppsNalu = payload;
            writeNalu(payload);
            return drain();
        }

        // Skip SEI and AUD
        if (type == 6 || type == 9) {
            return drain();
        }

        // Inject SPS/PPS before IDR/P frames for segment self-containment
        if ((type == 5 || type == 1) && spsNalu != null && ppsNalu != null && !spsPpsSent) {
            writeNalu(spsNalu);
            writeNalu(ppsNalu);
            spsPpsSent = true;
        }
        // Re-inject SPS/PPS before each IDR frame so each segment is independently playable
        if (type == 5 && spsNalu != null && ppsNalu != null && spsPpsSent) {
            writeNalu(spsNalu);
            writeNalu(ppsNalu);
        }

        writeNalu(payload);
        return drain();
    }

    /**
     * Flush any buffered data.
     */
    public byte[] flush() {
        return drain();
    }

    private void writeNalu(byte[] nalu) throws IOException {
        out.write(START_CODE);
        out.write(nalu);
    }

    private byte[] drain() {
        if (out.size() == 0) return null;
        byte[] result = out.toByteArray();
        out.reset();
        return result;
    }

    public void reset() {
        spsNalu = null;
        ppsNalu = null;
        spsPpsSent = false;
        out.reset();
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
