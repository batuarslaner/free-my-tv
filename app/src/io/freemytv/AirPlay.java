package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

import io.freemytv.airplay.Advert;
import io.freemytv.airplay.Bytes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AirPlay screen mirroring for iPhones and iPads (the receiver is io.freemytv.airplay). On in Free My TV mode when
 * the FairPlay plugin (FreeMyTV-FairPlay.apk, GPL-3.0, source in fairplay/) is installed with our key. The receiver
 * runs in its own process (:airplay, AirPlayService): it listens on the network, but never runs shell commands or
 * switches anything. Shared between processes: files/airplay.txt (enabled=, name=, code=, paused=), written
 * atomically by whoever changes it (the settings screen in :home, the crash guard in :airplay); :airplay rereads it
 * when told (reload). Devices that paired with the AirPlay code: files/airplay-clients.txt (only :airplay writes it).
 */
final class AirPlay {
    private AirPlay() {}

    static final String PLUGIN = "io.freemytv.fairplay";
    private static long lastEnsure = Long.MIN_VALUE / 2;

    /** Starts the receiver when it should run (boot, app update, a switch). Any process; never throws. */
    static void ensure(Context c) {
        lastEnsure = SystemClock.elapsedRealtime();
        try {
            String why = blocker(c);
            if (why != null) {
                AppLog.d("airplay: not started: " + why);
                return;
            }
            c.startForegroundService(new Intent(c, AirPlayService.class));
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException is an IllegalStateException
            AppLog.w("airplay: could not start: " + e);
        }
    }

    /**
     * From the home screen's onResume: the same, at most every 30 s (a crashed or killed receiver comes back), off the
     * UI thread (it reads files and asks the package manager). The activity is visible, so the start is allowed.
     */
    static void ensureOften(Context c) {
        if (SystemClock.elapsedRealtime() - lastEnsure < 30_000) return;
        lastEnsure = SystemClock.elapsedRealtime();
        final Context app = c.getApplicationContext();
        new Thread(() -> ensure(app), "airplay-ensure").start();
    }

    /** Free My TV mode was turned on again: a crash pause ends, and the receiver starts. */
    static void resume(Context c) {
        if ("true".equals(settings(c).get("paused"))) putSetting(c, "paused", "false");
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), "airplay-crashes.txt").delete();   // the crash count starts over
        ensure(c);
    }

    /** The settings changed: the receiver starts again with them (or stops, when it must not run). */
    static void reload(Context c) {
        try {
            if (blocker(c) != null) {
                stop(c);
                return;
            }
            c.startForegroundService(new Intent(c, AirPlayService.class).setAction(AirPlayService.RELOAD));
        } catch (RuntimeException e) {
            AppLog.w("airplay: could not reload: " + e);
        }
    }

    /** On or off in the settings (on unless turned off). */
    static boolean enabled(Context c) {
        return !"false".equals(settings(c).get("enabled"));
    }

    static boolean paused(Context c) {
        return "true".equals(settings(c).get("paused"));
    }

    /** Advert.CODE_OFF (the default), CODE_FIRST or CODE_ALWAYS. */
    static String code(Context c) {
        String v = settings(c).get("code");
        return Advert.CODE_FIRST.equals(v) || Advert.CODE_ALWAYS.equals(v) ? v : Advert.CODE_OFF;
    }

    /** The name picked in the settings ("" for the default). */
    static String customName(Context c) {
        String n = settings(c).get("name");
        return n == null ? "" : n;
    }

    // MARK: - Devices that paired with the code (:airplay only)

    static synchronized boolean knownDevice(Context c, byte[] edPublic) {
        return read(clientsFile(c)).containsKey(Bytes.hex(edPublic));
    }

    /** Remembers a device's key; the 50 most recent stay. */
    static synchronized void addDevice(Context c, byte[] edPublic) {
        Map<String, String> m = read(clientsFile(c));
        m.remove(Bytes.hex(edPublic));
        m.put(Bytes.hex(edPublic), String.valueOf(System.currentTimeMillis()));
        while (m.size() > 50) m.remove(m.keySet().iterator().next());
        if (!write(clientsFile(c), m)) AppLog.w("airplay: could not save the paired devices");
    }

    private static File clientsFile(Context c) {
        return new File(c.getFilesDir(), "airplay-clients.txt");
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, AirPlayService.class));
        } catch (RuntimeException e) {
            AppLog.w("airplay: could not stop: " + e);
        }
    }

    /** Why the receiver must not run now, or null. */
    static String blocker(Context c) {
        if (!HomeMode.FREE.equals(HomeMode.state(c).optString("mode"))) return "not in Free My TV mode";
        Map<String, String> s = settings(c);
        if ("false".equals(s.get("enabled"))) return "turned off";
        if ("true".equals(s.get("paused"))) return "paused after crashing 3 times";
        if (!pluginInstalled(c)) return "the FairPlay plugin is not installed";
        return null;
    }

    /** Installed and signed with our key (anything else could be someone else's code). */
    static boolean pluginInstalled(Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            pm.getPackageInfo(PLUGIN, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return pm.checkSignatures(c.getPackageName(), PLUGIN) == PackageManager.SIGNATURE_MATCH;
    }

    /**
     * The name iPhones show: the one picked in Settings, else the TV's own (Settings > Device name) + " AirPlay", else
     * ours. Android 14's advertiser refuses a second service under a name the TV already announces (its remote service,
     * _androidtvremote2._tcp, uses the TV's name) and never calls back, so the TV's name gets the suffix.
     */
    static String name(Context c) {
        String n = settings(c).get("name");
        if (n == null || n.trim().isEmpty()) {
            n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
            if (n != null && !n.trim().isEmpty()) n = n.trim() + " AirPlay";
        }
        return Advert.safeName(n);
    }

    // MARK: - Identity (files/airplay-id.txt, created once; never logged)

    /** A random MAC-like id (locally administered), our own UUID and an Ed25519 seed: what iPhones remember us by. */
    static Advert.Identity identity(Context c) throws IOException {
        File f = new File(c.getFilesDir(), "airplay-id.txt");
        Map<String, String> m = read(f);
        String deviceId = m.get("deviceid"), pi = m.get("pi"), seed = m.get("seed");
        if (deviceId == null || pi == null || seed == null || !seed.matches("[0-9a-f]{64}")) {
            SecureRandom rnd = new SecureRandom();
            byte[] mac = new byte[6];
            rnd.nextBytes(mac);
            mac[0] = (byte) ((mac[0] & 0xfe) | 0x02);
            StringBuilder sb = new StringBuilder();
            for (byte b : mac) sb.append(sb.length() == 0 ? "" : ":").append(String.format("%02X", b & 0xff));
            byte[] s = new byte[32];
            rnd.nextBytes(s);
            m.clear();
            m.put("deviceid", deviceId = sb.toString());
            m.put("pi", pi = UUID.randomUUID().toString());
            m.put("seed", seed = Bytes.hex(s));
            if (!write(f, m)) throw new IOException("could not save the AirPlay identity");
        }
        return new Advert.Identity(deviceId, pi, Bytes.unhex(seed));
    }

    // MARK: - Settings file

    static Map<String, String> settings(Context c) {
        return read(new File(c.getFilesDir(), "airplay.txt"));
    }

    static void putSetting(Context c, String key, String value) {
        File f = new File(c.getFilesDir(), "airplay.txt");
        synchronized (AirPlay.class) {
            Map<String, String> m = read(f);
            m.put(key, value);
            if (!write(f, m)) AppLog.w("airplay: could not save " + key);
        }
    }

    private static Map<String, String> read(File f) {
        Map<String, String> m = new LinkedHashMap<>();
        if (!f.exists()) return m;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 8192)];
            int n = 0;
            while (n < data.length) {
                int r = in.read(data, n, data.length - n);
                if (r < 0) break;
                n += r;
            }
            m.putAll(io.freemytv.airplay.KeyValues.parse(new String(data, 0, n, StandardCharsets.UTF_8)));
        } catch (IOException ignored) {
        }
        return m;
    }

    /** A temporary file per process, synced before it replaces the old one (as Prefs does). */
    private static boolean write(File f, Map<String, String> m) {
        String text = io.freemytv.airplay.KeyValues.format(m);
        File tmp = new File(f.getParentFile(), f.getName() + "." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f);
    }
}
