package com.smartcamera.netty;

import com.smartcamera.service.FrameDistributor;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles incoming RTP packets over UDP.
 * Parses RTP payload and distributes H.264 NALUs.
 */
@Slf4j
public class RtpChannelHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private final String cameraId;
    private final FrameDistributor frameDistributor;

    private static final int RTP_HEADER_SIZE = 12;

    // FU-A reassembly buffer
    private byte[] fuBuffer;
    private int fuWritePos = 0;

    public RtpChannelHandler(String cameraId, FrameDistributor frameDistributor) {
        this.cameraId = cameraId;
        this.frameDistributor = frameDistributor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf buf = packet.content();
        log.debug("RTP packet received for camera {}: {} bytes", cameraId, buf.readableBytes());

        if (buf.readableBytes() < RTP_HEADER_SIZE) {
            log.warn("RTP packet too small: {} bytes", buf.readableBytes());
            return;
        }

        // Parse RTP header
        int firstByte = buf.readUnsignedByte();
        int secondByte = buf.readUnsignedByte();

        int version = (firstByte >> 6) & 0x03;
        if (version != 2) {
            return; // Not RTP version 2
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

        // Parse H.264 NALUs from RTP payload
        parseAndDistribute(payload, marker);
    }

    private void parseAndDistribute(byte[] payload, boolean marker) {
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

            // 实际 NALU 数据长度 = 总长度 - 2（FU indicator + FU header）
            int dataLen = payload.length - 2;

            if (start) {
                // 起始分片：重建 NAL header + 保存第一段数据
                fuBuffer = new byte[1 + dataLen];
                fuBuffer[0] = (byte) (nri | nalTypeFromFu);
                System.arraycopy(payload, 2, fuBuffer, 1, dataLen);
                fuWritePos = fuBuffer.length;
            } else if (end) {
                // 末尾分片：追加数据并输出完整 NALU
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
                // 中间分片：扩容并追加数据
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
        // First byte is STAP-A NAL header, sub-NALUs start at offset 1
        int offset = 1;
        int count = 0;
        while (offset + 2 < payload.length) {
            int naluSize = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
            offset += 2;
            if (offset + naluSize > payload.length) {
                log.warn("STAP-A NALU size {} exceeds remaining payload, skipping", naluSize);
                break;
            }
            // Extract sub-NALU and add 4-byte start code
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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in RTP handler for camera {}", cameraId, cause);
    }
}
