package com.smartcamera.netty.model;

import lombok.Data;

@Data
public class RtpPacket {
    private int version;
    private boolean padding;
    private boolean extension;
    private int csrcCount;
    private boolean marker;
    private int payloadType;
    private int sequenceNumber;
    private long timestamp;
    private long ssrc;
    private byte[] payload;

    public static RtpPacket parse(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }

        RtpPacket packet = new RtpPacket();
        byte firstByte = data[0];
        packet.version = (firstByte >> 6) & 0x03;
        packet.padding = ((firstByte >> 5) & 0x01) == 1;
        packet.extension = ((firstByte >> 4) & 0x01) == 1;
        packet.csrcCount = firstByte & 0x0F;

        byte secondByte = data[1];
        packet.marker = ((secondByte >> 7) & 0x01) == 1;
        packet.payloadType = secondByte & 0x7F;

        packet.sequenceNumber = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        packet.timestamp = ((long)(data[4] & 0xFF) << 24) | ((long)(data[5] & 0xFF) << 16)
                | ((long)(data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        packet.ssrc = ((long)(data[8] & 0xFF) << 24) | ((long)(data[9] & 0xFF) << 16)
                | ((long)(data[10] & 0xFF) << 8) | (data[11] & 0xFF);

        int headerSize = 12 + (packet.csrcCount * 4);
        if (packet.extension) {
            if (data.length >= headerSize + 4) {
                int extLen = ((data[headerSize + 2] & 0xFF) << 8) | (data[headerSize + 3] & 0xFF);
                headerSize += 4 + (extLen * 4);
            }
        }

        int payloadSize = data.length - headerSize;
        if (packet.padding) {
            int paddingSize = data[data.length - 1] & 0xFF;
            payloadSize -= paddingSize;
        }

        packet.payload = new byte[payloadSize];
        System.arraycopy(data, headerSize, packet.payload, 0, payloadSize);

        return packet;
    }
}
