package com.smartcamera.netty.codec;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Multiplexes H.264 NALUs into FLV format for real-time streaming.
 */
@Slf4j
public class FlvMuxer {

    private static final int FLV_TAG_TYPE_VIDEO = 0x09;
    private static final int FLV_TAG_TYPE_SCRIPT = 0x12;
    private static final int FLV_TAG_TYPE_AUDIO = 0x08;

    private int previousTagSize = 0;
    private int timestamp = 0;
    private boolean headerWritten = false;

    // FLV header: "FLV" + version(1) + flags(1) + offset(4) = 9 bytes
    private static final byte[] FLV_HEADER = {
            0x46, 0x4C, 0x56, // "FLV"
            0x01,              // version
            0x01,              // video only
            0x00, 0x00, 0x00, 0x09 // data offset
    };

    /**
     * Get FLV header bytes (call once at stream start).
     */
    public byte[] getFlvHeader() {
        headerWritten = true;
        return FLV_HEADER;
    }

    /**
     * Generate onMetaData script tag. Write after FLV header, before video tags.
     */
    public byte[] getMetaDataTag(int width, int height, double frameRate) throws IOException {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();

        // AMF0 onMetaData object
        meta.write(0x02); // String type marker
        writeAmfStringRaw(meta, "onMetaData");
        meta.write(0x08); // ECMA array type marker
        meta.write(0x00); meta.write(0x00); meta.write(0x00); meta.write(0x00); // ECMA array length

        writeMetaPair(meta, "width", (double) width);
        writeMetaPair(meta, "height", (double) height);
        writeMetaPair(meta, "framerate", frameRate);
        writeMetaPair(meta, "videocodecid", 7.0); // AVC
        writeMetaPair(meta, "encoder", "SmartCamera FLV");

        meta.write(0x09); // Object end marker

        ByteArrayOutputStream tagOut = new ByteArrayOutputStream();
        writeFlvTag(tagOut, FLV_TAG_TYPE_SCRIPT, meta.toByteArray(), 0, true);
        return tagOut.toByteArray();
    }

    private void writeMetaPair(ByteArrayOutputStream out, String key, double value) throws IOException {
        writeAmfStringRaw(out, key);
        out.write(0x00); // Number type
        long bits = Double.doubleToLongBits(value);
        for (int i = 56; i >= 0; i -= 8) {
            out.write((int) ((bits >> i) & 0xFF));
        }
    }

    private void writeMetaPair(ByteArrayOutputStream out, String key, String value) throws IOException {
        writeAmfStringRaw(out, key);
        out.write(0x02); // String type
        writeAmfStringRaw(out, value);
    }

    private void writeAmfStringRaw(ByteArrayOutputStream out, String str) throws IOException {
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write((bytes.length >> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.write(bytes);
    }

    /**
     * Process an AVC sequence header (SPS/PPS) for FLV.
     */
    public byte[] processAvcSequenceHeader(byte[] sps, byte[] pps) throws IOException {
        return processAvcSequenceHeader(sps, pps, 0);
    }

    /**
     * Process an AVC sequence header (SPS/PPS) for FLV with specific timestamp.
     */
    public byte[] processAvcSequenceHeader(byte[] sps, byte[] pps, int ts) throws IOException {
        // Build AVC decoder configuration record
        ByteArrayOutputStream avcConfig = new ByteArrayOutputStream();
        avcConfig.write(0x01); // configurationVersion
        avcConfig.write(sps[1]); // AVCProfileIndication
        avcConfig.write(sps[2]); // profile_compatibility
        avcConfig.write(sps[3]); // AVCLevelIndication
        avcConfig.write(0xFF); // lengthSizeMinusOne (4 bytes)

        // SPS
        avcConfig.write(0xE1); // numOfSequenceParameterSets
        avcConfig.write(sps.length >> 8);
        avcConfig.write(sps.length & 0xFF);
        avcConfig.write(sps);

        // PPS
        avcConfig.write(0x01); // numOfPictureParameterSets
        avcConfig.write(pps.length >> 8);
        avcConfig.write(pps.length & 0xFF);
        avcConfig.write(pps);

        byte[] avcConfigData = avcConfig.toByteArray();

        // Wrap with FLV video tag header
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x17); // FrameType=1(keyframe), CodecId=7(AVC)
        out.write(0x00); // AVCPacketType = 0 (sequence header)
        out.write(0x00); // CompositionTime offset = 0
        out.write(0x00);
        out.write(0x00);
        out.write(avcConfigData);

        byte[] tagBody = out.toByteArray();
        ByteArrayOutputStream tagOut = new ByteArrayOutputStream();
        writeFlvTag(tagOut, FLV_TAG_TYPE_VIDEO, tagBody, ts, true);

        return tagOut.toByteArray();
    }

    /**
     * Process a H.264 NALU into FLV video tag.
     */
    public byte[] processNalu(byte[] nalu) throws IOException {
        return processNalu(nalu, -1);
    }

    /**
     * Process a H.264 NALU into FLV video tag with optional explicit timestamp.
     */
    public byte[] processNalu(byte[] nalu, int explicitTs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Strip start code
        byte[] payload = stripStartCode(nalu);
        if (payload == null) return new byte[0];

        int naluType = H264Parser.getNaluType(nalu);

        // Build FLV video tag body
        ByteArrayOutputStream videoData = new ByteArrayOutputStream();
        videoData.write(0x17); // FrameType=1(keyframe) or 2, CodecId=7(AVC)
        videoData.write(0x01); // AVCPacketType = 1 (NALU)
        videoData.write(0x00); // CompositionTime offset = 0 (no B-frames)
        videoData.write(0x00);
        videoData.write(0x00);

        // NALU length (4 bytes)
        videoData.write((payload.length >> 24) & 0xFF);
        videoData.write((payload.length >> 16) & 0xFF);
        videoData.write((payload.length >> 8) & 0xFF);
        videoData.write(payload.length & 0xFF);

        // NALU data
        videoData.write(payload);

        byte[] videoTagData = videoData.toByteArray();

        // Update frame type based on NALU type
        int frameType = (naluType == 5) ? 0x10 : 0x20; // keyframe or inter frame
        videoTagData[0] = (byte) (frameType | 0x07);

        int ts = explicitTs >= 0 ? explicitTs : timestamp;
        writeFlvTag(out, FLV_TAG_TYPE_VIDEO, videoTagData, ts, false);

        // Increment timestamp (approx 40ms per frame at 25fps)
        timestamp += 40;

        return out.toByteArray();
    }

    private void writeFlvTag(ByteArrayOutputStream out, int tagType, byte[] data, int timestamp, boolean isHeader) {
        // Tag header (11 bytes)
        out.write(tagType);
        out.write((data.length >> 16) & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write(data.length & 0xFF);

        out.write((timestamp >> 16) & 0xFF);
        out.write((timestamp >> 8) & 0xFF);
        out.write(timestamp & 0xFF);
        out.write((timestamp >> 24) & 0xFF); // timestamp extended

        out.write(0x00); // stream_id (always 0)
        out.write(0x00);
        out.write(0x00);

        // Tag data
        try {
            out.write(data);
        } catch (IOException e) {
            log.error("Failed to write FLV tag data", e);
        }

        // PreviousTagSize (4 bytes)
        int totalTagSize = 11 + data.length;
        out.write((totalTagSize >> 24) & 0xFF);
        out.write((totalTagSize >> 16) & 0xFF);
        out.write((totalTagSize >> 8) & 0xFF);
        out.write(totalTagSize & 0xFF);

        previousTagSize = totalTagSize;
    }

    private byte[] stripStartCode(byte[] nalu) {
        int offset = 0;
        if (nalu.length >= 4 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 0 && nalu[3] == 1) {
            offset = 4;
        } else if (nalu.length >= 3 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 1) {
            offset = 3;
        }
        if (offset >= nalu.length) return null;
        byte[] result = new byte[nalu.length - offset];
        System.arraycopy(nalu, offset, result, 0, result.length);
        return result;
    }

    public void reset() {
        timestamp = 0;
        previousTagSize = 0;
        headerWritten = false;
    }
}
