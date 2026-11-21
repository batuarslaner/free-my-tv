package io.freemytv.airplay;

import java.io.IOException;
import java.net.ProtocolException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Legacy AirPlay pairing (feature bit 27), one per connection. /pair-setup swaps Ed25519 public keys; /pair-verify
 * runs an X25519 exchange where each side signs both ECDH keys and sends the signature AES-128-CTR encrypted.
 * The resulting secret feeds the stream keys (StreamKeys).
 */
public final class Pairing {
    private final byte[] edSeed, edPublic;
    private byte[] theirEd, theirEcdh, ourEcdh, secret;
    private boolean verified;

    public Pairing(Advert.Identity id) {
        edSeed = id.edSeed;
        edPublic = id.edPublic;
    }

    /** /pair-setup: their 32-byte Ed25519 key in, ours out. */
    public byte[] setup(byte[] body) throws IOException {
        if (body.length != 32) throw new ProtocolException("pair-setup: " + body.length + " bytes");
        return edPublic.clone();
    }

    /**
     * /pair-verify. Step 1 (flag 1): their ECDH key and Ed25519 key in; our ECDH key and encrypted signature out.
     * Step 2 (flag 0): their encrypted signature in, nothing out; throws when it doesn't verify.
     */
    public byte[] verify(byte[] body) throws IOException {
        if (body.length != 68) throw new ProtocolException("pair-verify: " + body.length + " bytes");
        if (body[0] != 0) {
            theirEcdh = Bytes.slice(body, 4, 36);
            theirEd = Bytes.slice(body, 36, 68);
            byte[] ours = new byte[32];
            new SecureRandom().nextBytes(ours);
            ourEcdh = Curve25519.x25519Public(ours);
            secret = Curve25519.x25519(ours, theirEcdh);
            verified = false;
            byte[] signature = Curve25519.sign(edSeed, edPublic, Bytes.concat(ourEcdh, theirEcdh));
            return Bytes.concat(ourEcdh, cipher().update(signature));
        }
        if (secret == null) throw new ProtocolException("pair-verify step 2 before step 1");
        Cipher c = cipher();
        c.update(new byte[64]);   // the keystream our own signature used (UxPlay's "fake round")
        byte[] signature = c.update(Bytes.slice(body, 4, 68));
        if (!Curve25519.verify(theirEd, Bytes.concat(theirEcdh, ourEcdh), signature)) {
            throw new ProtocolException("pair-verify: the sender's signature doesn't verify");
        }
        verified = true;
        return new byte[0];
    }

    public boolean verified() {
        return verified;
    }

    /** The X25519 shared secret once verified, else null. */
    public byte[] secret() {
        return verified ? secret : null;
    }

    private Cipher cipher() throws IOException {
        try {
            byte[] key = Bytes.slice(Bytes.sha512(Bytes.ascii("Pair-Verify-AES-Key"), secret), 0, 16);
            byte[] iv = Bytes.slice(Bytes.sha512(Bytes.ascii("Pair-Verify-AES-IV"), secret), 0, 16);
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IOException("AES-CTR unavailable", e);
        }
    }
}
