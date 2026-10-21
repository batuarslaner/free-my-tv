package io.freemytv.airplay;

/** The mirror stream's AES-CTR key and IV, from the FairPlay key, the pairing secret and the stream's id. */
public final class StreamKeys {
    private StreamKeys() {}

    /** SHA-512(fairplayKey ‖ ecdhSecret)[0..16]; without pairing the FairPlay key alone. */
    public static byte[] aesKey(byte[] fairplayKey, byte[] ecdhSecret) {
        if (ecdhSecret == null || ecdhSecret.length == 0) return fairplayKey.clone();
        return Bytes.slice(Bytes.sha512(fairplayKey, ecdhSecret), 0, 16);
    }

    /** {key, iv} for a video stream: SHA-512("AirPlayStreamKey" + unsigned id ‖ aesKey)[0..16], "AirPlayStreamIV" alike. */
    public static byte[][] video(byte[] aesKey, long streamConnectionId) {
        String id = Long.toUnsignedString(streamConnectionId);
        byte[] key = Bytes.slice(Bytes.sha512(Bytes.ascii("AirPlayStreamKey" + id), aesKey), 0, 16);
        byte[] iv = Bytes.slice(Bytes.sha512(Bytes.ascii("AirPlayStreamIV" + id), aesKey), 0, 16);
        return new byte[][] {key, iv};
    }
}
