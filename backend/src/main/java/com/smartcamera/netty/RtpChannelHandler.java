package com.smartcamera.netty;

import com.smartcamera.service.FrameDistributor;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles incoming RTP packets over UDP.
 * Parses H.264 video NALUs and AAC audio frames, distributes via FrameDistributor.
 */
@Slf4j
public class RtpChannelHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final int RTP_HEADER_SIZE = 12;

    // 视频 payload type (H.264)
    private static final int PT_VIDEO = 96;

    private final String cameraId;
    private final FrameDistributor frameDistributor;
    private int audioPayloadType = 97; // default, updated from SDP

    // H.264 FU-A 重组缓冲区
    private byte[] fuBuffer;
    private int fuWritePos = 0;

    // 音频 RTP 包重组缓冲区
    private byte[] auBuffer;
    private int auBufferSize = 0;
    private boolean auBufferStart = false;

    public RtpChannelHandler(String cameraId, FrameDistributor frameDistributor) {
        this.cameraId = cameraId;
        this.frameDistributor = frameDistributor;
        // 从 SDP 缓存中获取音频 payload type
        this.audioPayloadType = com.smartcamera.netty.RtspServerHandler.getAudioPayloadType(cameraId);
        log.debug("RTP handler for camera {} initialized: video PT={}, audio PT={}", cameraId, PT_VIDEO, this.audioPayloadType);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf buf = packet.content();

        if (buf.readableBytes() < RTP_HEADER_SIZE) {
            return;
        }

        // Parse RTP header
        int firstByte = buf.readUnsignedByte();
        int secondByte = buf.readUnsignedByte();

        int version = (firstByte >> 6) & 0x03;
        if (version != 2) {
            return;
        }

        boolean marker = (secondByte & 0x80) != 0;
        int payloadType = secondByte & 0x7F;

        // Read sequence number
        buf.readUnsignedShort();

        // Read timestamp
        buf.readUnsignedInt();

        // Read SSRC
        buf.readUnsignedInt();

        // Skip CSRC if present
        int csrcCount = firstByte & 0x0F;
        buf.skipBytes(csrcCount * 4);

        // Read payload
        int payloadLength = buf.readableBytes();
        if (payloadLength <= 0) {
            return;
        }

        byte[] payload = new byte[payloadLength];
        buf.readBytes(payload);

        // 根据 payload type 分发
        if (payloadType == PT_VIDEO) {
            parseAndDistributeVideo(payload, marker);
        } else if (payloadType == audioPayloadType) {
            parseAndDistributeAudio(payload, marker);
        }
    }

    // ==================== H.264 视频解析 ====================

    private void parseAndDistributeVideo(byte[] payload, boolean marker) {
        if (payload.length < 1) {
            return;
        }

        int firstByte = payload[0] & 0xFF;
        int nalType = firstByte & 0x1F;
        int nri = firstByte & 0x60;

        if (nalType == 28) {
            // FU-A fragmentation unit
            if (payload.length < 2) return;

            int fuHeader = payload[1] & 0xFF;
            boolean start = (fuHeader & 0x80) != 0;
            boolean end = (fuHeader & 0x40) != 0;
            int nalTypeFromFu = fuHeader & 0x1F;

            int dataLen = payload.length - 2;

            if (start) {
                fuBuffer = new byte[1 + dataLen];
                fuBuffer[0] = (byte) (nri | nalTypeFromFu);
                System.arraycopy(payload, 2, fuBuffer, 1, dataLen);
                fuWritePos = fuBuffer.length;
            } else if (end) {
                if (fuBuffer == null) return;
                int totalLen = fuWritePos + dataLen;
                byte[] nalu = new byte[totalLen + 4];
                nalu[0] = 0; nalu[1] = 0; nalu[2] = 0; nalu[3] = 1;
                System.arraycopy(fuBuffer, 0, nalu, 4, fuWritePos);
                System.arraycopy(payload, 2, nalu, 4 + fuWritePos, dataLen);
                frameDistributor.distribute(cameraId, nalu);
                fuBuffer = null;
                fuWritePos = 0;
            } else {
                if (fuBuffer == null) return;
                int needed = fuWritePos + dataLen;
                if (needed > fuBuffer.length) {
                    byte[] newBuf = new byte[needed];
                    System.arraycopy(fuBuffer, 0, newBuf, 0, fuWritePos);
                    fuBuffer = newBuf;
                }
                System.arraycopy(payload, 2, fuBuffer, fuWritePos, dataLen);
                fuWritePos = needed;
            }
        } else if (nalType < 24) {
            // Single NAL unit packet - add start code
            byte[] nalu = new byte[payload.length + 4];
            nalu[0] = 0; nalu[1] = 0; nalu[2] = 0; nalu[3] = 1;
            System.arraycopy(payload, 0, nalu, 4, payload.length);
            frameDistributor.distribute(cameraId, nalu);
        } else if (nalType == 24) {
            // STAP-A: multiple NALUs aggregated in one RTP packet
            parseStapA(payload);
        }
    }

    private void parseStapA(byte[] payload) {
        int offset = 1;
        int count = 0;
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
            count++;
        }
    }

    // ==================== AAC 音频解析 ====================

    private void parseAndDistributeAudio(byte[] payload, boolean marker) {
        if (payload.length < 2) {
            return;
        }

        // FFmpeg RTSP 输出使用 AAC-hbr (RFC 3640)
        // 前两个字节是 AU-headers-length + AU-header
        // AU-header: 16 bits = AU-size (in bits)
        int auHeaderLength = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        int auHeadersCount = auHeaderLength / 16;

        int offset = 2 + (auHeadersCount * 2); // skip AU headers

        if (offset >= payload.length) {
            return;
        }

        // Extract AAC frame and add ADTS header for standalone playback
        int aacFrameSize = payload.length - offset;
        byte[] aacFrame = new byte[aacFrameSize];
        System.arraycopy(payload, offset, aacFrame, 0, aacFrameSize);

        // Add ADTS header (7 bytes) to make it a standalone AAC frame
        byte[] aacWithAdts = addAdtsHeader(aacFrame);
        frameDistributor.distributeAudio(cameraId, aacWithAdts);
    }

    /**
     * Add ADTS header to raw AAC frame for standalone playback.
     * AAC-LC, 44100Hz, mono.
     */
    private byte[] addAdtsHeader(byte[] aacFrame) {
        int frameLength = aacFrame.length + 7; // 7-byte ADTS header
        byte[] adts = new byte[frameLength];

        // ADTS header (7 bytes)
        // Syncword: 0xFFF
        adts[0] = (byte) 0xFF;
        adts[1] = (byte) 0xF1; // MPEG-4, Layer 0, no CRC
        // Profile: AAC-LC = 1, Sample rate: 44100Hz = 4, Channels: 1
        adts[2] = (byte) 0x4C; // (profile-1)<<6 | (sample_rate_idx)<<2 | (channels>>2)
        adts[3] = (byte) 0x80; // (channels&3)<<6 | (frame_length>>11)
        adts[4] = (byte) ((frameLength >> 3) & 0xFF);
        adts[5] = (byte) (((frameLength & 0x7) << 5) | 0x1F);
        adts[6] = (byte) 0xFC; // 0x1F | (num_aac_frames-1)<<2

        System.arraycopy(aacFrame, 0, adts, 7, aacFrame.length);
        return adts;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in RTP handler for camera {}", cameraId, cause);
    }
}
