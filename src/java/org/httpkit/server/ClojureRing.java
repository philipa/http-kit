package org.httpkit.server;

import static clojure.lang.Keyword.intern;
import static org.httpkit.HttpUtils.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

import org.httpkit.DynamicBytes;
import org.httpkit.HttpStatus;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

//  SimpleDateFormat is not threadsafe
class DateFormater extends ThreadLocal<SimpleDateFormat> {
    protected SimpleDateFormat initialValue() {
        // Formats into HTTP date format (RFC 822/1123).
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f;
    }

    private static final DateFormater FORMATER = new DateFormater();

    public static String getDate() {
        return FORMATER.get().format(new Date());
    }
}

public class ClojureRing {

    public static final Keyword SERVER_PORT = intern("server-port");
    public static final Keyword SERVER_NAME = intern("server-name");
    public static final Keyword REMOTE_ADDR = intern("remote-addr");
    public static final Keyword URI = intern("uri");
    public static final Keyword QUERY_STRING = intern("query-string");
    public static final Keyword SCHEME = intern("scheme");
    public static final Keyword REQUEST_METHOD = intern("request-method");
    public static final Keyword HEADERS = intern("headers");
    public static final Keyword CONTENT_TYPE = intern("content-type");
    public static final Keyword CONTENT_LENGTH = intern("content-length");
    public static final Keyword CHARACTER_ENCODING = intern("character-encoding");
    public static final Keyword BODY = intern("body");
    public static final Keyword KEEP_ALIVE = intern("keep_alive");
    public static final Keyword WEBSOCKET = intern("websocket");

    public static final Keyword M_GET = intern("get");
    public static final Keyword M_HEAD = intern("head");
    public static final Keyword M_POST = intern("post");
    public static final Keyword M_PUT = intern("put");
    public static final Keyword M_DELETE = intern("delete");
    public static final Keyword M_TRACE = intern("trace");
    public static final Keyword M_OPTIONS = intern("options");
    public static final Keyword M_CONNECT = intern("connect");
    public static final Keyword M_PATCH = intern("patch");

    public static final Keyword HTTP = intern("http");

    public static final Keyword STATUS = intern("status");

    public static final int getStatus(Map<Keyword, Object> resp) {
        int status = 200;
        Object s = resp.get(STATUS);
        if (s instanceof Long) {
            status = ((Long) s).intValue();
        } else if (s instanceof Integer) {
            status = (Integer) s;
        }
        return status;
    }

    public static final String CL = "Content-Length";

    public static ByteBuffer[] encode(int status, Map<String, Object> headers, Object body) {
        headers = camelCase(headers);
        headers.put("Server", "http-kit");
        headers.put("Date", DateFormater.getDate());
        ByteBuffer bodyBuffer;

        try {
            bodyBuffer = bodyBuffer(body);
            if (bodyBuffer != null) {
                headers.put(CL, Integer.toString(bodyBuffer.remaining()));
            } else {
                headers.put(CL, "0");
            }
        } catch (IOException e) {
            byte[] b = e.getMessage().getBytes(ASCII);
            status = 500;
            headers.clear();
            headers.put(CL, Integer.toString(b.length));
            bodyBuffer = ByteBuffer.wrap(b);
        }

        DynamicBytes bytes = new DynamicBytes(196);
        byte[] bs = HttpStatus.valueOf(status).getResponseIntialLineBytes();
        bytes.append(bs, 0, bs.length);
        encodeHeaders(bytes, headers);
        ByteBuffer headBuffer = ByteBuffer.wrap(bytes.get(), 0, bytes.length());

        return new ByteBuffer[] { headBuffer, bodyBuffer };
    }

    public static IPersistentMap buildRequestMap(HttpRequest req) {

        Map<Object, Object> m = new TreeMap<Object, Object>();
        m.put(SERVER_PORT, req.getServerPort());
        m.put(SERVER_NAME, req.getServerName());
        m.put(REMOTE_ADDR, req.getRemoteAddr());
        m.put(URI, req.uri);
        m.put(QUERY_STRING, req.queryString);
        m.put(SCHEME, HTTP); // only http is supported
        if (req.isWs()) {
            m.put(WEBSOCKET, req.getWebSocketCon());
        }

        m.put(REQUEST_METHOD, req.method.KEY);

        // key is already downcased, required by ring spec
        m.put(HEADERS, PersistentArrayMap.create(req.getHeaders()));
        m.put(CONTENT_TYPE, req.getContentType());
        m.put(CONTENT_LENGTH, req.getContentLength());
        m.put(CHARACTER_ENCODING, req.getCharactorEncoding());
        m.put(BODY, req.getBody());
        return PersistentArrayMap.create(m);
    }
}
