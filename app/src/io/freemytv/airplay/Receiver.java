package io.freemytv.airplay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The AirPlay receiver: one TCP listener for RTSP and HTTP (both Bonjour services point at it), a thread per
 * connection. Handles discovery (GET /info), pairing, FairPlay, SETUP of the mirror stream and the keep-alives.
 * One session at a time: a new sender's SETUP takes over. Never runs shell commands or touches the TV's settings.
 */
public final class Receiver {
    /** What the service sees of sessions. */
    public interface Sink {
        /** deviceName: what the sender calls itself ("Someone's iPhone"), for the screen only, never the log. */
        void started(String sender, String deviceName);

        /** Where video goes; null drops it after the checks. clock: the session's sync with the sender (may be null). */
        MirrorStream.Listener video(Ntp clock);

        /** Where AAC-ELD audio goes; null drops it. */
        AudioStream.Listener audio(Ntp clock);

        /** The sender's volume slider, in dB (0 full, −30 quietest, −144 mute). */
        void volume(float db);

        /** AirPlay code: show this 4-digit code on the TV (null: hide it, the pairing ended). */
        void showCode(String code);

        /** Devices that paired with the code before (their Ed25519 keys), for "the first time" mode. */
        boolean knownDevice(byte[] edPublic);

        void addDevice(byte[] edPublic);

        void ended(String reason);
    }

    private static final int MAX_CONNECTIONS = 8, FIRST_REQUEST_MS = 10_000, IDLE_MS = 120_000, FEEDBACK_TIMEOUT_MS = 10_000;

    private final Advert.Identity id;
    private final String name, code;
    /** The code on the TV now (/pair-pin-start), and until when it's good. */
    private volatile String pin;
    private volatile long pinUntil;
    private final FairPlay fairPlay;
    private final Sink sink;
    private final AtomicInteger connections = new AtomicInteger();
    private final List<Session> open = new ArrayList<>();
    private ServerSocket server;
    private volatile boolean stopped;
    /** False while the screen is off: no new session may start. */
    private volatile boolean accepting = true;
    private Session active;

    /** code: Advert.CODE_OFF, CODE_FIRST or CODE_ALWAYS. */
    public Receiver(Advert.Identity id, String name, String code, FairPlay fairPlay, Sink sink) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.fairPlay = fairPlay;
        this.sink = sink;
    }

    /** Listens on the first free port of the list (0 = any) and returns it. */
    public int start(int... ports) throws IOException {
        IOException last = null;
        for (int port : ports) {
            ServerSocket s = new ServerSocket();
            try {
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(port));
                server = s;
                break;
            } catch (BindException e) {
                last = e;
                Session.quietly(s);
            }
        }
        if (server == null) throw last != null ? last : new IOException("no port");
        Thread accept = new Thread(this::acceptLoop, "airplay-accept");
        accept.start();
        Thread watch = new Thread(this::watchLoop, "airplay-watch");
        watch.setDaemon(true);
        watch.start();
        return server.getLocalPort();
    }

    public void stop() {
        stopped = true;
        Session.quietly(server);
        List<Session> all;
        synchronized (open) {
            all = new ArrayList<>(open);
        }
        for (Session s : all) s.close("receiver stopped");
    }

    /** Screen off: refuse new sessions (and end the current one with endSession). Screen on: take them again. */
    public void setAccepting(boolean on) {
        accepting = on;
    }

    /** Ends the current session (screen off, the player closed). */
    public void endSession(String reason) {
        Session s;
        synchronized (this) {
            s = active;
        }
        if (s != null) s.close(reason);
    }

    private void acceptLoop() {
        while (!stopped) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (!stopped) Log.w("accept failed: " + e.getMessage());
                return;
            }
            if (stopped) {
                Session.quietly(socket);
                return;
            }
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet();
                Session.quietly(socket);
                Log.w("too many connections: refused " + socket.getInetAddress().getHostAddress());
                continue;
            }
            new Thread(() -> {
                try {
                    serve(socket);
                } catch (Throwable e) {   // never take the process down
                    Log.w("connection failed: " + e);
                } finally {
                    connections.decrementAndGet();
                }
            }, "airplay-rtsp").start();
        }
    }

    /** Ends a mirroring session whose sender went quiet (it sends /feedback every 2 s). */
    private void watchLoop() {
        while (!stopped) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            Session s;
            synchronized (this) {
                s = active;
            }
            if (s != null && s.mirroring && s.feedbackSeen
                    && System.currentTimeMillis() - s.lastFeedback > FEEDBACK_TIMEOUT_MS) {
                s.close("no /feedback for 10 s");
            }
        }
    }

    private void serve(Socket socket) {
        Session s = new Session(socket, id);
        synchronized (open) {
            open.add(s);
        }
        Log.w("connection from " + s.sender);
        String end = "the sender closed the connection";
        try {
            socket.setSoTimeout(FIRST_REQUEST_MS);   // a slot can't be held by a silent connection
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            Rtsp.Request req;
            while (!s.closed && (req = Rtsp.read(in)) != null) {
                socket.setSoTimeout(IDLE_MS);
                Rtsp.Response res;
                try {
                    res = handle(s, req);
                } catch (ProtocolException e) {
                    Log.w(req.method + " " + req.path() + ": " + e.getMessage());
                    res = new Rtsp.Response().status(req.path().startsWith("/pair-") ? 470 : 400);
                } catch (IOException e) {
                    Log.w(req.method + " " + req.path() + " failed: " + e.getMessage());
                    res = new Rtsp.Response().status(500);
                } catch (RuntimeException | Error e) {   // an OutOfMemoryError included: this connection fails, not the process
                    Log.w(req.method + " " + req.path() + " crashed: " + e);
                    res = new Rtsp.Response().status(500);
                }
                if (!"/feedback".equals(req.path())) Log.w(req.method + " " + req.path() + " " + res.status);
                dump("<", req.method + " " + req.uri, req.path(), req.body);
                Rtsp.write(out, req, res);
                dump(">", String.valueOf(res.status), req.path(), res.body);
                if (s.endAfterReply) s.close("the sender ended the session (TEARDOWN)");
            }
        } catch (SocketTimeoutException e) {
            end = "the connection was idle";
        } catch (SocketException e) {
            end = s.closed ? s.endReason() : "connection reset";
        } catch (IOException e) {
            end = "bad request: " + e.getMessage();
        } finally {
            s.close(end);
            synchronized (open) {
                open.remove(s);
            }
            boolean wasActive;
            synchronized (this) {
                wasActive = active == s;
                if (wasActive) active = null;
            }
            if (wasActive) {
                Log.w("session ended: " + s.endReason());
                sink.ended(s.endReason());
            } else {
                Log.d("connection from " + s.sender + " closed");
            }
        }
    }

    private Rtsp.Response handle(Session s, Rtsp.Request req) throws IOException {
        Rtsp.Response res = new Rtsp.Response();
        String path = req.path();
        switch (req.method) {
            case "GET":
                if (path.equals("/info")) return info(req, res);
                return res.status(404);
            case "POST":
                switch (path) {
                    case "/pair-setup":
                        return res.body("application/octet-stream", s.pairing.setup(req.body));
                    case "/pair-pin-start":
                        return pinStart(res);
                    case "/pair-setup-pin":
                        return pinSetup(s, req, res);
                    case "/pair-verify": {
                        if (!mayVerify(s, req.body)) {
                            // UxPlay's answer: an empty 200, and the sender asks for the code.
                            Log.w("pair-verify: this device needs the AirPlay code first");
                            return res;
                        }
                        byte[] out = s.pairing.verify(req.body);
                        if (s.pairing.verified()) Log.w("pair-verify ok (client sig verified)");
                        return out.length == 0 ? res : res.body("application/octet-stream", out);
                    }
                    case "/fp-setup":
                        return fpSetup(s, req, res);
                    case "/feedback":
                        s.lastFeedback = System.currentTimeMillis();
                        s.feedbackSeen = true;
                        return res;
                    case "/audioMode":
                        return res;
                    default:
                        return res.status(404);
                }
            case "OPTIONS":
                return res.header("Public", "SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET");
            case "SETUP":
                return setup(s, req, res);
            case "GET_PARAMETER": {
                String asked = new String(req.body, StandardCharsets.US_ASCII).trim();
                if (asked.equals("volume")) {
                    return res.body("text/parameters", "volume: 0.000000\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                return res;
            }
            case "SET_PARAMETER": {
                String text = new String(req.body, 0, Math.min(req.body.length, 64), StandardCharsets.US_ASCII).trim();
                if (text.startsWith("volume:")) {
                    try {
                        float db = Float.parseFloat(text.substring(7).trim());
                        Log.d("volume " + db + " dB");
                        sink.volume(db);
                    } catch (NumberFormatException e) {
                        throw new ProtocolException("bad volume");
                    }
                }
                return res;
            }
            case "RECORD":
                return res.header("Audio-Latency", "11025").header("Audio-Jack-Status", "connected; type=analog");
            case "FLUSH": {
                // RTP-Info: seq=…;rtptime=… — the next audio packet.
                String info = req.header("RTP-Info");
                java.util.regex.Matcher m = info == null ? null : java.util.regex.Pattern.compile("seq=(\\d+)").matcher(info);
                if (m != null && m.find()) s.flushAudio(Integer.parseInt(m.group(1)) & 0xffff);
                return res;
            }
            case "TEARDOWN":
                return teardown(s, req, res);
            default:
                return res.status(501);
        }
    }

    private Rtsp.Response info(Rtsp.Request req, Rtsp.Response res) throws IOException {
        if (req.body.length > 0 && Plist.is(req.body)) {
            Object qualifier = Plist.dict(req.body).get("qualifier");
            if (qualifier instanceof List && ((List<?>) qualifier).contains("txtAirPlay")) {
                return res.plist(Plist.map("txtAirPlay", Advert.txtBytes(Advert.airplayTxt(id, code))));
            }
        }
        return res.plist(Advert.info(id, name));
    }

    private Rtsp.Response fpSetup(Session s, Rtsp.Request req, Rtsp.Response res) throws IOException {
        byte[] body = req.body;
        if ((body.length != 16 && body.length != 164) || !new String(body, 0, 4, StandardCharsets.US_ASCII).equals("FPLY")) {
            throw new ProtocolException("fp-setup: unexpected " + body.length + "-byte message");
        }
        if (body[4] != 3) throw new ProtocolException("fp-setup: FairPlay version " + body[4] + " not supported");
        if (body.length == 164) s.keyMessage = body.clone();
        byte[] reply = fairPlay.setup(body);
        Log.w("fp-setup " + (body.length == 16 ? "1" : "2") + "/2");
        return res.body("application/octet-stream", reply);
    }

    private Rtsp.Response setup(Session s, Rtsp.Request req, Rtsp.Response res) throws IOException {
        Map<String, Object> p = Plist.dict(req.body);
        byte[] ekey = Plist.data(p, "ekey");
        Object streams = p.get("streams");
        if (ekey == null && !(streams instanceof List)) throw new ProtocolException("SETUP without ekey or streams");
        Map<String, Object> reply = new java.util.LinkedHashMap<>();
        if (ekey != null) {
            if (ekey.length != 72) throw new ProtocolException("SETUP: ekey of " + ekey.length + " bytes");
            if (s.keyMessage == null) throw new ProtocolException("SETUP: ekey before fp-setup");
            if (!accepting) return res.status(453);   // the screen is off
            byte[] key = fairPlay.decryptKey(s.keyMessage, ekey);   // before taking over: junk must not end a session
            s.ekey = ekey;
            s.eiv = Plist.data(p, "eiv");
            s.fairplayKey = key;
            Log.w("SETUP ekey from " + clean(p.get("model")) + " (" + clean(p.get("osName")) + " " + clean(p.get("osVersion"))
                    + "), " + (s.pairing.verified() ? "paired" : "not paired"));
            Log.w("key fp=" + Bytes.fingerprint(key));
            int theirTiming = (int) Plist.number(p, "timingPort", 0);
            int ourTiming = s.startNtp(theirTiming);
            Log.w("timing: theirs " + theirTiming + ", ours " + ourTiming);
            takeOver(s);
            synchronized (this) {
                if (active == s && !s.closed) sink.started(s.sender, display(p.get("name")));
            }
            reply.put("eventPort", 0L);
            reply.put("timingPort", (long) ourTiming);
        }
        if (!(streams instanceof List)) return res.plist(reply);
        if (s.fairplayKey == null) throw new ProtocolException("SETUP streams before the key");
        if (!accepting) return res.status(453);
        List<Object> replies = new ArrayList<>();
        for (Object o : (List<?>) streams) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> stream = (Map<String, Object>) o;
            long type = Plist.number(stream, "type", -1);
            if (type == 110) {
                long streamId = Plist.number(stream, "streamConnectionID", 0);
                byte[][] keys = StreamKeys.video(StreamKeys.aesKey(s.fairplayKey, s.pairing.secret()), streamId);
                MirrorStream mirror = new MirrorStream(keys[0], keys[1], sink.video(s.ntp()));
                final Session session = s;
                int port = s.startMirror(mirror, () -> session.close("the mirror connection closed"));
                Log.w("SETUP streams 110 -> dataPort " + port);
                replies.add(Plist.map("type", 110L, "dataPort", (long) port));
            } else if (type == 96) {
                long ct = Plist.number(stream, "ct", -1), spf = Plist.number(stream, "spf", -1);
                // ct 8 is AAC-ELD (mirroring); ALAC (2) and AAC (4) are for music-only AirPlay, which comes later.
                AudioStream.Listener out = ct == 8 ? sink.audio(s.ntp()) : null;
                // Resend requests go to the sender's control port (from this request).
                AudioStream audio = new AudioStream(StreamKeys.aesKey(s.fairplayKey, s.pairing.secret()), s.eiv, out,
                        s.socket.getInetAddress(), (int) Plist.number(stream, "controlPort", 0));
                s.startAudio(audio);
                Log.w("SETUP streams 96 -> dataPort " + audio.dataPort() + ", controlPort " + audio.controlPort() + " ("
                        + (ct == 8 ? "AAC-ELD, " + spf + " samples per packet" : "ct=" + ct + " not supported yet: dropped") + ")");
                replies.add(Plist.map("type", 96L, "dataPort", (long) audio.dataPort(), "controlPort", (long) audio.controlPort()));
            } else {
                Log.w("SETUP streams: type " + type + " not supported");
            }
        }
        reply.put("streams", replies);
        return res.plist(reply);
    }

    private Rtsp.Response teardown(Session s, Rtsp.Request req, Rtsp.Response res) throws IOException {
        Object streams = req.body.length > 0 && Plist.is(req.body) ? Plist.dict(req.body).get("streams") : null;
        if (streams instanceof List) {
            for (Object o : (List<?>) streams) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked") long type = Plist.number((Map<String, Object>) o, "type", -1);
                if (type == 110) s.stopMirror();
                if (type == 96) s.stopAudio();
                Log.w("TEARDOWN stream " + type);
            }
            return res;
        }
        Log.w("TEARDOWN session");
        s.endAfterReply = true;
        return res;
    }

    /** The sender's name for the screen: no control characters, at most 60 characters. */
    private static String display(Object o) {
        String v = o instanceof String ? ((String) o).replaceAll("\\p{Cntrl}", " ").trim() : "";
        return v.length() > 60 ? v.substring(0, 60) : v;
    }

    // MARK: - AirPlay code

    /** A new 4-digit code on the TV, good for 2 minutes. */
    private Rtsp.Response pinStart(Rtsp.Response res) {
        if (Advert.CODE_OFF.equals(code)) return res.status(404);
        String p = String.format(java.util.Locale.ROOT, "%04d", new java.security.SecureRandom().nextInt(10000));
        pin = p;
        pinUntil = System.currentTimeMillis() + 120_000;
        Log.w("pair-pin-start: showing a code on the TV");
        sink.showCode(p);
        return res;
    }

    private Rtsp.Response pinSetup(Session s, Rtsp.Request req, Rtsp.Response res) throws IOException {
        if (Advert.CODE_OFF.equals(code)) return res.status(404);
        Map<String, Object> p = Plist.dict(req.body);
        if (p.get("method") instanceof String && p.get("user") instanceof String) {
            String current = pin;
            if (current == null || System.currentTimeMillis() > pinUntil) throw new ProtocolException("pair-setup-pin: no code on the TV");
            if (!"pin".equals(p.get("method"))) throw new ProtocolException("pair-setup-pin: method " + clean(p.get("method")));
            s.pin = new PinPairing(current);
            Log.w("pair-setup-pin 1/3");
            return res.plist(s.pin.start((String) p.get("user"), null, null));
        }
        if (s.pin == null) throw new ProtocolException("pair-setup-pin: no step 1");
        if (Plist.data(p, "pk") != null && Plist.data(p, "proof") != null) {
            try {
                Map<String, Object> out = s.pin.proof(Plist.data(p, "pk"), Plist.data(p, "proof"));
                Log.w("pair-setup-pin 2/3: the code is right");
                return res.plist(out);
            } catch (ProtocolException e) {
                s.pin = null;
                throw e;
            }
        }
        if (Plist.data(p, "epk") != null && Plist.data(p, "authTag") != null) {
            Map<String, Object> out = s.pin.confirm(Plist.data(p, "epk"), Plist.data(p, "authTag"), id.edPublic);
            s.pinDevice = s.pin.clientKey();
            pin = null;   // one use
            sink.addDevice(s.pinDevice);
            sink.showCode(null);
            Log.w("pair-setup-pin 3/3: paired with the code");
            return res.plist(out);
        }
        throw new ProtocolException("pair-setup-pin: unexpected step");
    }

    /**
     * With a code on, /pair-verify only for a device that just typed it on this connection, or (the first time mode)
     * one that typed it before.
     */
    private boolean mayVerify(Session s, byte[] body) {
        if (Advert.CODE_OFF.equals(code) || body.length != 68 || body[0] == 0) return true;   // step 2 follows step 1
        byte[] theirs = Bytes.slice(body, 36, 68);
        if (s.pinDevice != null && Bytes.same(s.pinDevice, theirs)) return true;
        return Advert.CODE_FIRST.equals(code) && sink.knownDevice(theirs);
    }

    /** A value from the sender for the log: printable, short. */
    private static String clean(Object o) {
        String v = String.valueOf(o).replaceAll("[^\\x20-\\x7e]", "?");
        return v.length() > 40 ? v.substring(0, 40) : v;
    }

    /** One session at a time: a new sender's SETUP ends the previous one. */
    private void takeOver(Session s) {
        Session old;
        synchronized (this) {
            old = active;
            active = s;
        }
        if (old != null && old != s) {
            Log.w("a new sender (" + s.sender + ") takes over from " + old.sender);
            old.close("replaced by a new sender");
        }
    }

    /**
     * Request and response dumps with debug on. Bodies that carry key material (pairing, FairPlay, SETUP's ekey) show
     * only their size: the log never holds what would let someone decrypt a stream.
     */
    private static void dump(String dir, String first, String path, byte[] body) {
        if (!Log.debug) return;
        boolean secret = path.startsWith("/pair-") || path.startsWith("/fp-setup") || first.startsWith("SETUP");
        String shown = secret || body.length == 0 ? body.length + " bytes"
                : Bytes.hex(body, 0, Math.min(body.length, 512)) + (body.length > 512 ? "…" : "");
        Log.d(dir + " " + first + " " + path + ": " + shown);
    }
}
