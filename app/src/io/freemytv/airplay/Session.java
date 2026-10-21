package io.freemytv.airplay;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * One sender's RTSP connection and what it set up: pairing, the FairPlay message, the keys, the clock sync and the
 * stream sockets. Closing it closes all of them, the connection included.
 */
final class Session {
    final Socket socket;
    final String sender;
    final Pairing pairing;
    /** AirPlay code pairing on this connection, and the device key it proved. */
    PinPairing pin;
    byte[] pinDevice;
    byte[] keyMessage, ekey, eiv, fairplayKey;
    private Ntp ntp;
    /** A full TEARDOWN: the session ends once its reply is out. */
    boolean endAfterReply;
    private ServerSocket mirrorServer;
    private Socket mirror;
    private AudioStream audio;
    /** Mirroring: the sender's /feedback every 2 s keeps the session alive once it started sending them. */
    volatile long lastFeedback;
    volatile boolean feedbackSeen, mirroring;
    volatile boolean closed;
    private volatile String endReason;

    Session(Socket socket, Advert.Identity id) {
        this.socket = socket;
        this.sender = socket.getInetAddress().getHostAddress();
        this.pairing = new Pairing(id);
    }

    /** Opens the mirror listener and runs the stream on its own thread once the sender connects. Returns dataPort. */
    synchronized int startMirror(final MirrorStream stream, final Runnable onEnd) throws IOException {
        if (closed) throw new IOException("session closed");
        stopMirror();
        final ServerSocket server = new ServerSocket(0);
        server.setSoTimeout(30_000);
        mirrorServer = server;
        mirroring = true;
        lastFeedback = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            Socket s = null;
            try {
                s = server.accept();
                synchronized (Session.this) {
                    if (mirrorServer != server) return;
                    mirror = s;
                }
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(1 << 20);
                // An iPhone may use another of its addresses (IPv4/IPv6) here than for RTSP: noted, not refused.
                boolean same = s.getInetAddress().equals(socket.getInetAddress());
                Log.w("mirror: connected from " + s.getInetAddress().getHostAddress() + (same ? "" : " (not the RTSP address)"));
                stream.run(s.getInputStream());
            } catch (SocketTimeoutException e) {
                Log.w("mirror: the sender never connected");
            } catch (IOException e) {
                if (mirrorServer == server && !closed) Log.w("mirror: " + e.getMessage());
            } catch (Throwable e) {   // never take the process down (a decoder error in P2, say)
                Log.w("mirror: failed: " + e);
            } finally {
                quietly(s);
                quietly(server);
                boolean ours;
                synchronized (Session.this) {
                    ours = mirrorServer == server;
                    if (ours) {
                        mirrorServer = null;
                        mirror = null;
                        mirroring = false;
                    }
                }
                if (ours && !closed) onEnd.run();
            }
        }, "airplay-mirror");
        t.start();
        return server.getLocalPort();
    }

    synchronized void stopMirror() {
        ServerSocket server = mirrorServer;
        mirrorServer = null;
        mirroring = false;
        quietly(server);
        quietly(mirror);
        mirror = null;
    }

    /** The audio stream (its sockets are open already); replaces an earlier one. */
    synchronized void startAudio(AudioStream stream) throws IOException {
        if (closed) {
            stream.close();
            throw new IOException("session closed");
        }
        stopAudio();
        audio = stream;
    }

    synchronized void stopAudio() {
        quietly(audio);
        audio = null;
    }

    synchronized void flushAudio(int nextSeq) {
        if (audio != null) audio.flush(nextSeq);
    }

    /** The clock sync, or null before SETUP's ekey. */
    synchronized Ntp ntp() {
        return ntp;
    }

    /** The clock sync for this sender, replacing an earlier one. Returns our timing port. */
    synchronized int startNtp(int senderPort) throws IOException {
        if (closed) throw new IOException("session closed");
        if (ntp != null) ntp.close();
        ntp = new Ntp(socket.getInetAddress(), senderPort);
        return ntp.port();
    }

    /** Ends everything once; the first reason wins. */
    void close(String reason) {
        Ntp clock;
        synchronized (this) {
            if (closed) return;
            closed = true;
            endReason = reason;
            clock = ntp;
            ntp = null;
        }
        stopMirror();
        stopAudio();
        if (clock != null) clock.close();
        quietly(socket);
    }

    String endReason() {
        return endReason;
    }

    static void quietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
