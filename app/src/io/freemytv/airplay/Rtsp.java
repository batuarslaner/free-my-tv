package io.freemytv.airplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RTSP/1.0 and HTTP/1.1 requests as AirPlay senders write them (a request line, headers, a body of Content-Length
 * bytes; lines end in CRLF or a bare LF), and the responses. Bounded: 16 KB of headers, 1 MB of body.
 */
public final class Rtsp {
    private Rtsp() {}

    static final int MAX_HEADERS = 16 * 1024, MAX_BODY = 1 << 20;
    public static final String SERVER = "AirTunes/220.68";

    public static final class Request {
        public final String method, uri, protocol;
        /** Header names in lower case. */
        public final Map<String, String> headers;
        public final byte[] body;

        Request(String method, String uri, String protocol, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.uri = uri;
            this.protocol = protocol;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** The path without the rtsp://host part or the query: "/info", "/fp-setup", or the session URI for SETUP. */
        public String path() {
            String u = uri;
            int scheme = u.indexOf("://");
            if (scheme >= 0) {
                int slash = u.indexOf('/', scheme + 3);
                u = slash < 0 ? "/" : u.substring(slash);
            }
            int q = u.indexOf('?');
            return q < 0 ? u : u.substring(0, q);
        }
    }

    /** The next request, or null when the sender closed the connection between requests. */
    public static Request read(InputStream in) throws IOException {
        int[] budget = {MAX_HEADERS};
        String line = line(in, budget, true);
        if (line == null) return null;
        while (line.isEmpty()) {   // stray line ends between requests
            line = line(in, budget, false);
        }
        String[] parts = line.split(" ");
        if (parts.length != 3 || !(parts[2].startsWith("RTSP/") || parts[2].startsWith("HTTP/")) || parts[0].isEmpty()
                || parts[1].isEmpty()) {
            throw new ProtocolException("bad request line");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        while (!(line = line(in, budget, false)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) throw new ProtocolException("bad header");
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        int length = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                length = Integer.parseInt(cl);
            } catch (NumberFormatException e) {
                throw new ProtocolException("bad Content-Length");
            }
            if (length < 0 || length > MAX_BODY) throw new ProtocolException("body too large: " + length);
        }
        byte[] body = new byte[length];
        Bytes.readFully(in, body, 0, length);
        return new Request(parts[0], parts[1], parts[2], headers, body);
    }

    /** One line without its CR/LF; null only at a clean end of stream before the first byte (when allowed). */
    private static String line(InputStream in, int[] budget, boolean eofOk) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (eofOk && b.size() == 0) return null;
                throw new ProtocolException("connection closed mid-request");
            }
            if (--budget[0] < 0) throw new ProtocolException("headers too large");
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    public static final class Response {
        public int status = 200;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        public Response status(int s) {
            status = s;
            return this;
        }

        public Response header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Response body(String contentType, byte[] b) {
            headers.put("Content-Type", contentType);
            body = b;
            return this;
        }

        public Response plist(Object root) {
            return body("application/x-apple-binary-plist", Plist.write(root));
        }
    }

    public static void write(OutputStream out, Request req, Response res) throws IOException {
        StringBuilder sb = new StringBuilder();
        String protocol = req != null && req.protocol.startsWith("HTTP/") ? "HTTP/1.1" : "RTSP/1.0";
        sb.append(protocol).append(' ').append(res.status).append(' ').append(reason(res.status)).append("\r\n");
        sb.append("Server: ").append(SERVER).append("\r\n");
        String cseq = req == null ? null : req.header("CSeq");
        if (cseq != null) sb.append("CSeq: ").append(cseq).append("\r\n");
        for (Map.Entry<String, String> h : res.headers.entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("Content-Length: ").append(res.body.length).append("\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[head.length + res.body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(res.body, 0, all, head.length, res.body.length);
        out.write(all);
        out.flush();
    }

    static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 453: return "Not Enough Bandwidth";
            case 470: return "Connection Authorization Required";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            default: return "Error";
        }
    }
}
