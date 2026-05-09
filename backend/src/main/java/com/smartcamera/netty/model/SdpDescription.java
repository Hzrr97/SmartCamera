package com.smartcamera.netty.model;

import lombok.Data;

@Data
public class SdpDescription {
    private String version = "0";
    private String origin;
    private String sessionName = "Live Camera Stream";
    private String connection;
    private String time;
    private String media;
    private String rtpmap;
    private String control;
    private String fmtp;

    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("v=").append(version).append("\r\n");
        sb.append("o=").append(origin != null ? origin : "- 0 0 IN IP4 127.0.0.1").append("\r\n");
        sb.append("s=").append(sessionName).append("\r\n");
        sb.append("c=").append(connection != null ? connection : "IN IP4 0.0.0.0").append("\r\n");
        sb.append("t=").append(time != null ? time : "0 0").append("\r\n");
        sb.append("a=recvonly\r\n");
        sb.append("m=").append(media != null ? media : "video 0 RTP/AVP 96").append("\r\n");
        sb.append("a=rtpmap:").append(rtpmap != null ? rtpmap : "96 H264/90000").append("\r\n");
        sb.append("a=fmtp:96 ").append(fmtp != null ? fmtp : "packetization-mode=1").append("\r\n");
        sb.append("a=control:").append(control != null ? control : "trackID=1").append("\r\n");
        return sb.toString();
    }
}
