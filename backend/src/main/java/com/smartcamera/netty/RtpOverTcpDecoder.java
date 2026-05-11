package com.smartcamera.netty;

import com.smartcamera.service.FrameDistributor;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decodes RTP-over-TCP interleaved binary data.
 * Format: $ <channel:1> <length:2> <RTP data>
 */
@Slf4j
public class RtpOverTcpDecoder extends ByteToMessageDecoder {

    private static final int MAGIC = 0x24; // '$'
    private static final int HEADER_SIZE = 4; // $ + channel + length

    private final Map<Integer, FrameDistributor> cameraDistributors = new ConcurrentHashMap<>();
    private String cameraId;
    private FrameDistributor frameDistributor;

    private byte[] fuBuffer;
    private int fuWritePos = 0;

    public void setCameraContext(String cameraId, FrameDistributor distributor) {
        this.cameraId = cameraId;
        this.frameDistributor = distributor;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Check if we have enough data for header
        if (in.readableBytes() < HEADER_SIZE) {
            return;
        }

        // Mark current position
        in.markReaderIndex();

        // Check magic byte
        int magic = in.readUnsignedByte();
        if (magic != MAGIC) {
            // Not interleaved data, might be RTSP text
            in.resetReaderIndex();
            out.add(in.readBytes(in.readableBytes()));
            return;
        }

        // Read channel and length
        int channel = in.readUnsignedByte();
        int length = in.readUnsignedShort();

        // Check if we have full RTP packet
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        // Read RTP data
        byte[] rtpData = new byte[length];
        in.readBytes(rtpData);

        // Parse and distribute NALUs (same logic as RtpChannelHandler)
        if (channel == 0 && cameraId != null && frameDistributor != null) {
            parseAndDistribute(rtpData);
        }
    }

    private void parseAndDistribute(byte[] rtpData) {
        if (rtpData.length < 12) return;

        // Skip RTP header (12 bytes minimum)
        int headerLen = 12;
        int firstByte = rtpData[0] & 0xFF;
        int csrcCount = firstByte & 0x0F;
        headerLen += csrcCount * 4;

        // Check for extension
        if ((firstByte & 0x10) != 0 && rtpData.length >= headerLen + 4) {
            int extLen = ((rtpData[headerLen + 2] & 0xFF) << 8) | (rtpData[headerLen + 3] & 0xFF);
            headerLen += 4 + extLen * 4;
        }

        if (rtpData.length <= headerLen) return;

        // Extract payload
        int payloadLen = rtpData.length - headerLen;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(rtpData, headerLen, payload, 0, payloadLen);

        // Parse NALU
        parseNalu(payload);
    }

    private void parseNalu(byte[] payload) {
        if (payload.length < 1) return;

        int firstByte = payload[0] & 0xFF;
        int nalType = firstByte & 0x1F;
        int nri = firstByte & 0x60;

        if (nalType == 28) {
            // FU-A fragmentation
            if (payload.length < 2) return;
            int fuHeader = payload[1] & 0xFF;
            boolean start = (fuHeader & 0x80) != 0;
            boolean end = (fuHeader & 0x40) != 0;
            int nalTypeFromFu = fuHeader & 0x1F;

            if (start) {
                fuBuffer = new byte[65536];
                fuBuffer[0] = (byte) (nri | nalTypeFromFu);
                fuWritePos = 1 + payload.length - 2;
                System.arraycopy(payload, 2, fuBuffer, 1, payload.length - 2);
            } else if (end) {
                if (fuBuffer != null && fuWritePos + payload.length - 2 <= fuBuffer.length) {
                    System.arraycopy(payload, 2, fuBuffer, fuWritePos, payload.length - 2);
                    int naluLen = fuWritePos + payload.length - 2;
                    byte[] withStartCode = new byte[naluLen + 4];
                    withStartCode[0] = 0;
                    withStartCode[1] = 0;
                    withStartCode[2] = 0;
                    withStartCode[3] = 1;
                    System.arraycopy(fuBuffer, 0, withStartCode, 4, naluLen);
                    frameDistributor.distribute(cameraId, withStartCode);
                    fuBuffer = null;
                    fuWritePos = 0;
                }
            } else {
                // Middle fragment
                if (fuBuffer != null && fuWritePos + payload.length - 2 <= fuBuffer.length) {
                    System.arraycopy(payload, 2, fuBuffer, fuWritePos, payload.length - 2);
                    fuWritePos += payload.length - 2;
                }
            }
        } else if (nalType < 24) {
            // Single NAL unit - add start code
            byte[] withStartCode = new byte[payload.length + 4];
            withStartCode[0] = 0;
            withStartCode[1] = 0;
            withStartCode[2] = 0;
            withStartCode[3] = 1;
            System.arraycopy(payload, 0, withStartCode, 4, payload.length);
            frameDistributor.distribute(cameraId, withStartCode);
        } else if (nalType == 24) {
            // STAP-A: multiple NALUs aggregated
            parseStapA(payload);
        }
    }

    private void parseStapA(byte[] payload) {
        int offset = 1;
        while (offset + 2 < payload.length) {
            int naluSize = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
            offset += 2;
            if (offset + naluSize > payload.length) {
                log.warn("STAP-A NALU size {} exceeds remaining payload, skipping", naluSize);
                break;
            }
            byte[] withStartCode = new byte[naluSize + 4];
            withStartCode[0] = 0;
            withStartCode[1] = 0;
            withStartCode[2] = 0;
            withStartCode[3] = 1;
            System.arraycopy(payload, offset, withStartCode, 4, naluSize);
            frameDistributor.distribute(cameraId, withStartCode);
            offset += naluSize;
        }
    }
}
