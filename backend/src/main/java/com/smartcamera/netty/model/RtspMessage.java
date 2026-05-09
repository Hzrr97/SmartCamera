package com.smartcamera.netty.model;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.rtsp.RtspMethods;
import io.netty.handler.codec.rtsp.RtspVersions;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class RtspMessage {
    private RtspMethods method;
    private RtspVersions version;
    private String uri;
    private Map<String, String> headers = new HashMap<>();
    private String body;

    public String getHeader(String name) {
        return headers.get(name);
    }

    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    public int getCSeq() {
        String cseq = getHeader("CSeq");
        return cseq != null ? Integer.parseInt(cseq) : 0;
    }
}
