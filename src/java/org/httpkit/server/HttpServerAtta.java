package org.httpkit.server;

public class HttpServerAtta extends ServerAtta {
    public HttpServerAtta(int maxBody, int maxLine) {
        decoder = new RequestDecoder(maxBody, maxLine);
    }

    public final RequestDecoder decoder;

    public boolean isKeepAlive() {
        return decoder.request != null && decoder.request.isKeepAlive();
    }
}
