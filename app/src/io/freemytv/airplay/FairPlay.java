package io.freemytv.airplay;

import java.io.IOException;

/**
 * FairPlay, Apple's key exchange for the stream key. It isn't in this app: the GPL-3.0 plugin (fairplay/, package
 * io.freemytv.fairplay) does it, reached through FairPlayClient. Stateless: the receiver keeps the 164-byte message.
 */
public interface FairPlay {
    /**
     * The on-device check of the plugin: this first /fp-setup message (mode 0) and the SHA-256 of the plugin's reply.
     * A hash, so none of the plugin's (GPL) bytes live in this app.
     */
    byte[] CHECK_REQUEST = {'F', 'P', 'L', 'Y', 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, (byte) 0xbb};
    String CHECK_REPLY_SHA256 = "8e1a11ea61e4c397f30480910786893aaebb5bff59bf2074db9d7e84fda49dbc";

    /** /fp-setup: a 16-byte request gets a 142-byte reply, the 164-byte one a 32-byte reply. */
    byte[] setup(byte[] request) throws IOException;

    /** The 16-byte AES key inside SETUP's 72-byte ekey, with the 164-byte /fp-setup message. */
    byte[] decryptKey(byte[] keyMessage, byte[] ekey) throws IOException;
}
