package io.freemytv.airplay;

import java.io.IOException;
import java.net.ProtocolException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;

/**
 * AirPlay code pairing (POST /pair-setup-pin, three steps), one per connection, after /pair-pin-start put a 4-digit
 * code on the TV. 1: the sender names itself ("user") and gets the SRP salt and B. 2: it proves it knows the code
 * (SRP M1) and gets M2. 3: it sends its Ed25519 public key AES-GCM sealed with the SRP session key (IV + 1) and gets
 * ours back (IV + 2). Then the usual /pair-verify runs with that key.
 */
public final class PinPairing {
    private final String pin;
    private Srp.Server srp;
    private String user;
    private byte[] clientKey;

    public PinPairing(String pin) {
        this.pin = pin;
    }

    /** Step 1: {method: "pin", user} → {pk: B, salt}. b and salt are random unless a test gives them. */
    public Map<String, Object> start(String user, byte[] salt, byte[] b) throws IOException {
        if (user == null || user.isEmpty() || user.length() > 64) throw new ProtocolException("pair-setup-pin: no user");
        SecureRandom rnd = new SecureRandom();
        // A salt starting with 0 hashes differently in implementations that treat it as a number: avoid it.
        if (salt == null) do rnd.nextBytes(salt = new byte[16]); while (salt[0] == 0);
        if (b == null) rnd.nextBytes(b = new byte[32]);
        this.user = user;
        srp = new Srp.Server(Srp.airplay(), user, pin, salt, b);
        return Plist.map("pk", srp.publicKey(), "salt", srp.salt());
    }

    /** Step 2: {pk: A, proof: M1} → {proof: M2}; a wrong code throws. */
    public Map<String, Object> proof(byte[] a, byte[] m1) throws IOException {
        if (srp == null) throw new ProtocolException("pair-setup-pin: step 2 before step 1");
        byte[] m2 = srp.verify(a, m1);
        if (m2 == null) throw new ProtocolException("pair-setup-pin: wrong code");
        return Plist.map("proof", m2);
    }

    /** Step 3: {epk, authTag} (their Ed25519 key, sealed) → {epk, authTag} (ours). */
    public Map<String, Object> confirm(byte[] epk, byte[] authTag, byte[] ourKey) throws IOException {
        if (srp == null || srp.key() == null) throw new ProtocolException("pair-setup-pin: step 3 before step 2");
        if (epk == null || epk.length != 32 || authTag == null || authTag.length != 16) {
            throw new ProtocolException("pair-setup-pin: bad step 3");
        }
        byte[] key = Bytes.slice(Bytes.sha512(Bytes.ascii("Pair-Setup-AES-Key"), srp.key()), 0, 16);
        byte[] iv = Bytes.slice(Bytes.sha512(Bytes.ascii("Pair-Setup-AES-IV"), srp.key()), 0, 16);
        try {
            iv[15]++;
            byte[] theirs = Gcm.decrypt(key, iv, Bytes.concat(epk, authTag));
            if (theirs == null) throw new ProtocolException("pair-setup-pin: the sender's key doesn't authenticate");
            clientKey = theirs;
            iv[15]++;
            byte[] sealed = Gcm.encrypt(key, iv, ourKey);
            return Plist.map("epk", Bytes.slice(sealed, 0, 32), "authTag", Bytes.slice(sealed, 32, 48));
        } catch (GeneralSecurityException e) {
            throw new IOException("AES unavailable", e);
        }
    }

    public String user() {
        return user;
    }

    /** The sender's Ed25519 public key, after step 3. */
    public byte[] clientKey() {
        return clientKey;
    }
}
