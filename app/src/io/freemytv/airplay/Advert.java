package io.freemytv.airplay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the TV announces on the network (Bonjour TXT records of _airplay._tcp and _raop._tcp) and in GET /info. The
 * values are UxPlay's: an Apple TV 3 with legacy pairing (feature bit 27), H.264 mirroring, no HEVC (bit 42 off).
 */
public final class Advert {
    private Advert() {}

    /** Bits 1, 2, 5, 6, 7, 9-22, 25, 27, 28, 30. */
    public static final long FEATURES = 0x5A7FFEE6L;
    public static final String FEATURES_TEXT = "0x5A7FFEE6,0x0";
    public static final String MODEL = "AppleTV3,2", VERSION = "220.68";
    /** The AirPlay code setting: none, the first time a device connects, every time. */
    public static final String CODE_OFF = "off", CODE_FIRST = "first", CODE_ALWAYS = "always";

    /** Our identity: a random MAC-like id ("AA:BB:CC:DD:EE:FF"), a UUID (pi) and the Ed25519 public key. */
    public static final class Identity {
        public final String deviceId, pi;
        public final byte[] edSeed, edPublic;

        public Identity(String deviceId, String pi, byte[] edSeed) {
            this.deviceId = deviceId;
            this.pi = pi;
            this.edSeed = edSeed;
            this.edPublic = Curve25519.edPublic(edSeed);
        }
    }

    public static Map<String, String> airplayTxt(Identity id, String code) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("deviceid", id.deviceId);
        m.put("features", FEATURES_TEXT);
        m.put("flags", "0x4");
        m.put("model", MODEL);
        m.put("pk", Bytes.hex(id.edPublic));
        m.put("pi", id.pi);
        m.put("srcvers", VERSION);
        m.put("vv", "2");
        m.put("pw", CODE_OFF.equals(code) ? "false" : "true");
        return m;
    }

    /**
     * raop's status flags: 0x4 plain; with a code also 0x8 (a PIN is needed) and, for "the first time", 0x80 (pairing
     * once is enough). UxPlay announces 0x8c for its on-screen PIN.
     */
    static String statusFlags(String code) {
        return CODE_FIRST.equals(code) ? "0x8c" : CODE_ALWAYS.equals(code) ? "0xc" : "0x4";
    }

    public static Map<String, String> raopTxt(Identity id, String code) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ch", "2");
        m.put("cn", "0,1,2,3");
        m.put("da", "true");
        m.put("et", "0,3,5");
        m.put("vv", "2");
        m.put("ft", FEATURES_TEXT);
        m.put("am", MODEL);
        m.put("md", "0,1,2");
        m.put("rhd", "5.6.0.0");
        m.put("pw", CODE_OFF.equals(code) ? "false" : "true");
        m.put("sf", statusFlags(code));
        m.put("sr", "44100");
        m.put("ss", "16");
        m.put("sv", "false");
        m.put("tp", "UDP");
        m.put("txtvers", "1");
        m.put("vs", VERSION);
        m.put("vn", "65537");
        m.put("pk", Bytes.hex(id.edPublic));
        return m;
    }

    /** "AABBCCDDEEFF@Name", the _raop._tcp instance name. */
    public static String raopName(Identity id, String name) {
        return id.deviceId.replace(":", "") + "@" + name;
    }

    /** The name as a DNS label allows (63 bytes, with room for the raop prefix and a " (2)" on a clash). */
    public static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}.]", " ").trim();
        if (n.isEmpty()) n = "Free My TV";
        while (n.getBytes(StandardCharsets.UTF_8).length > 44) n = n.substring(0, n.offsetByCodePoints(n.length(), -1));
        return n.trim();
    }

    /** Raw DNS TXT rdata ("len key=value" strings), as GET /info returns it for the txtAirPlay qualifier. */
    public static byte[] txtBytes(Map<String, String> txt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : txt.entrySet()) {
            byte[] kv = (e.getKey() + "=" + e.getValue()).getBytes(StandardCharsets.UTF_8);
            if (kv.length > 255) continue;
            out.write(kv.length);
            out.write(kv, 0, kv.length);
        }
        return out.toByteArray();
    }

    /** The full GET /info answer. */
    public static Map<String, Object> info(Identity id, String name) {
        List<Object> latencies = new ArrayList<>();
        List<Object> formats = new ArrayList<>();
        for (long type : new long[] {100, 101}) {
            latencies.add(Plist.map("audioType", "default", "inputLatencyMicros", 0L, "outputLatencyMicros", 0L,
                    "type", type));
            formats.add(Plist.map("audioInputFormats", 0x3fffffcL, "audioOutputFormats", 0x3fffffcL, "type", type));
        }
        List<Object> displays = new ArrayList<>();
        displays.add(Plist.map("uuid", "e0ff8a27-6738-3d56-8a16-cc53aacee925", "width", 1920L, "height", 1080L,
                "widthPixels", 1920L, "heightPixels", 1080L, "widthPhysical", 0L, "heightPhysical", 0L,
                "refreshRate", 1.0 / 60, "maxFPS", 30L, "rotation", false, "overscanned", false, "features", 14L));
        return Plist.map(
                "deviceID", id.deviceId,
                "macAddress", id.deviceId,
                "pk", id.edPublic,
                "pi", id.pi,
                "features", FEATURES,
                "name", name,
                "model", MODEL,
                "sourceVersion", VERSION,
                "vv", 2L,
                "statusFlags", 68L,
                "keepAliveLowPower", true,
                "keepAliveSendStatsAsBody", true,
                "initialVolume", -20.0,
                "audioLatencies", latencies,
                "audioFormats", formats,
                "displays", displays);
    }
}
