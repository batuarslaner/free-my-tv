package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;

import io.freemytv.airplay.Advert;
import io.freemytv.airplay.AudioStream;
import io.freemytv.airplay.Bytes;
import io.freemytv.airplay.Curve25519;
import io.freemytv.airplay.FairPlay;
import io.freemytv.airplay.Log;
import io.freemytv.airplay.MirrorStream;
import io.freemytv.airplay.Ntp;
import io.freemytv.airplay.Receiver;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The AirPlay receiver, a foreground service alone in the :airplay process (a crash here never touches the home
 * screen's watchdog). At start: checks the mode and the plugin, a self-check of the curves and of the plugin, then
 * listens (port 7000, else 7100, else any) and announces itself while the screen is on. Its own crash guard: 3
 * crashes in 5 minutes pause AirPlay until Free My TV mode is turned on again. Status: files/airplay-status.json.
 */
public class AirPlayService extends Service {
    private static final int NOTIFICATION = 7000;
    private static final String CHANNEL = "airplay";
    private static final long CRASH_WINDOW = 5 * 60_000L;
    private static final int CRASH_LIMIT = 3;
    private static boolean guardInstalled;

    /** Everything that changes the receiver runs here, in order. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "airplay-service"));
    private boolean started;
    /** False once shutting down: late session callbacks must not overwrite the final status. */
    private volatile boolean running;
    private Receiver receiver;
    private AirPlayMdns mdns;
    private FairPlayClient fairPlay;
    private WifiManager.MulticastLock multicast;
    private WifiManager.WifiLock wifiLock;
    private String name;
    private int port;

    /** For the player (same process): the stream's decoder and size, and who is mirroring. */
    static volatile AirPlayVideo video;
    private static volatile AirPlayAudio audio;
    /** The sender's last volume (dB): a new audio stream starts at it. */
    private static volatile float volumeDb;
    static volatile int videoWidth, videoHeight;
    static volatile String senderName;
    private static volatile AirPlayService instance;
    static final String STOP_DREAM = "io.freemytv.action.STOP_DREAM";
    /** Start again with the settings as they are now (AirPlay.reload). */
    static final String RELOAD = "io.freemytv.action.AIRPLAY_RELOAD";
    /** Only our own app may send it (and every app of ours holds this permission). */
    static final String OUR_PERMISSION = "io.freemytv.permission.FAIRPLAY";

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            worker.execute(() -> {
                try {
                    screen(on);
                } catch (RuntimeException e) {
                    AppLog.w("airplay: screen change failed: " + e);
                }
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        installCrashGuard(getApplicationContext());
        Log.sink = AppLog::w;
        Log.debug = AppLog.debug();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "AirPlay", NotificationManager.IMPORTANCE_MIN));
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        instance = this;
    }

    /** The player asks: Back twice, or it was closed. Any thread. */
    static void endSession(final String reason) {
        final AirPlayService s = instance;
        if (s == null) return;
        try {
            s.worker.execute(() -> {
                if (s.receiver != null) s.receiver.endSession(reason);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {   // stopping anyway
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Every start: a startForegroundService() must be answered, or Android ends the app 10 s later.
        try {
            startForeground(NOTIFICATION, new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.icon).setContentTitle("AirPlay").setContentText("Free My TV").build());
        } catch (RuntimeException e) {   // not allowed from the background (a sticky restart): the home screen restarts us
            AppLog.w("airplay: could not go to the foreground: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            worker.execute(this::start);
        } else if (intent != null && RELOAD.equals(intent.getAction())) {
            worker.execute(() -> {
                AppLog.w("airplay: settings changed, starting again");
                shutdown();
                android.os.SystemClock.sleep(1000);   // the old Bonjour names go first, or the new ones become "(2)"
                start();
            });
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        unregisterReceiver(screen);
        worker.execute(() -> {
            if (receiver == null) return;   // never started, or stop() already said why
            shutdown();
            status("off", "stopped");
            AppLog.w("airplay: stopped");
        });
        worker.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    // MARK: - On the worker thread

    private void start() {
        String why = AirPlay.blocker(this);
        if (why != null) {
            stop("off", why);
            return;
        }
        if (!Curve25519.selfCheck()) {
            stop("error", "self-check FAILED (X25519/Ed25519 vectors)");
            return;
        }
        AppLog.w("airplay: self-check ok");
        fairPlay = new FairPlayClient(this);
        try {
            int api = fairPlay.version();
            String hash = Bytes.hex(Bytes.sha256(fairPlay.setup(FairPlay.CHECK_REQUEST)));
            if (api != 1 || !hash.equals(FairPlay.CHECK_REPLY_SHA256)) {
                stop("error", "plugin check FAILED (api " + api + ", reply " + hash.substring(0, 8) + ")");
                return;
            }
            AppLog.w("airplay: plugin " + api + " ok");
            Advert.Identity id = AirPlay.identity(this);
            name = AirPlay.name(this);
            final String code = AirPlay.code(this);
            final Context app = getApplicationContext();
            receiver = new Receiver(id, name, code, fairPlay, new Receiver.Sink() {
                @Override public void showCode(String digits) {
                    if (digits == null) AirPlayActivity.codeDone();
                    else AirPlayActivity.showCode(app, digits);
                }

                @Override public boolean knownDevice(byte[] edPublic) {
                    return AirPlay.knownDevice(app, edPublic);
                }

                @Override public void addDevice(byte[] edPublic) {
                    AirPlay.addDevice(app, edPublic);
                }

                @Override public void started(String sender, String deviceName) {
                    senderName = deviceName;
                    wifi(true);
                    if (running) status("session", "mirroring from " + sender);
                }

                @Override public MirrorStream.Listener video(Ntp clock) {
                    AirPlayVideo old = video;
                    if (old != null) old.release();
                    AirPlayVideo v = new AirPlayVideo((width, height, first) -> streamSize(width, height, first), clock);
                    video = v;
                    return v;
                }

                @Override public AudioStream.Listener audio(Ntp clock) {
                    AirPlayAudio old = audio;
                    if (old != null) old.release();
                    AirPlayAudio a = new AirPlayAudio(clock, volumeDb);
                    audio = a;
                    return a;
                }

                @Override public void volume(float db) {
                    volumeDb = db;
                    AirPlayAudio a = audio;
                    if (a != null) a.volume(db);
                }

                @Override public void ended(String reason) {
                    AirPlayVideo v = video;
                    video = null;
                    if (v != null) v.release();
                    releaseAudio();
                    AirPlayActivity.close();
                    wifi(false);
                    if (running) status("advertising", "last session ended: " + reason);
                }
            });
            running = true;
            port = receiver.start(7000, 7100, 0);
            mdns = new AirPlayMdns(this, id, name, code, port);
            boolean on = getSystemService(android.os.PowerManager.class).isInteractive();
            receiver.setAccepting(on);
            if (on) advertise();
            else status("advertising", "waiting for the screen");
        } catch (IOException | RuntimeException e) {
            stop("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** The mirror stream's first config opens the player; later ones (a rotation) resize it. Mirror thread. */
    private void streamSize(int width, int height, boolean first) {
        videoWidth = width;
        videoHeight = height;
        if (!first) {
            AirPlayActivity.streamChanged();
            return;
        }
        sendBroadcast(new Intent(STOP_DREAM).setPackage(getPackageName()));   // the screensaver steps aside
        final long asked = android.os.SystemClock.elapsedRealtime();
        try {
            startActivity(new Intent(this, AirPlayActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AppLog.w("video: opening the player");
        } catch (RuntimeException e) {
            AppLog.w("video: could not open the player: " + e);
        }
        AirPlayActivity.streamChanged();   // already open (a new stream in the same session)
        // Android doesn't say when it blocks a start from the background: check that the player came.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (AirPlayActivity.openedAt < asked) {
                AppLog.w("video: the player didn't open within 3 s (\"Background activity launch blocked\"? "
                        + "see ActivityTaskManager in logcat)");
            }
        }, 3000);
    }

    private static void releaseAudio() {
        AirPlayAudio a = audio;
        audio = null;
        if (a != null) a.release();
    }

    /** Wi-Fi at full speed while mirroring (low latency on Android 10+). */
    private synchronized void wifi(boolean on) {
        if (on && wifiLock == null) {
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            if (wifi == null) return;
            // WIFI_MODE_FULL_LOW_LATENCY (4) arrived in Android 10.
            wifiLock = wifi.createWifiLock(Build.VERSION.SDK_INT >= 29 ? 4 : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FreeMyTV:airplay");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } else if (!on && wifiLock != null) {
            if (wifiLock.isHeld()) wifiLock.release();
            wifiLock = null;
        }
    }

    private void advertise() {
        if (mdns == null) return;
        if (multicast == null) {
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("FreeMyTV:airplay");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
        }
        mdns.start();
        AppLog.w("airplay: advertising \"" + name + "\" on " + port + (Advert.CODE_OFF.equals(AirPlay.code(this)) ? ""
                : ", AirPlay code " + AirPlay.code(this)));
        status("advertising", null);
    }

    /** Screen off: no session and no announcements (nothing keeps the TV awake). Screen on: back. */
    private void screen(boolean on) {
        if (receiver == null) return;
        receiver.setAccepting(on);
        if (on) {
            advertise();
        } else {
            receiver.endSession("the screen turned off");
            if (mdns != null) mdns.stop();
            releaseMulticast();
            AppLog.w("airplay: screen off, not advertising");
            status("advertising", "screen off");
        }
    }

    private void stop(String state, String why) {
        AppLog.w("airplay: " + ("off".equals(state) ? "off: " : "not started: ") + why);
        status(state, why);
        shutdown();
        stopSelf();
    }

    private void shutdown() {
        running = false;
        AirPlayVideo v = video;
        video = null;
        if (v != null) v.release();
        releaseAudio();
        AirPlayActivity.close();
        wifi(false);
        if (mdns != null) mdns.stop();
        mdns = null;
        if (receiver != null) receiver.stop();
        receiver = null;
        if (fairPlay != null) fairPlay.close();
        fairPlay = null;
        releaseMulticast();
    }

    private void releaseMulticast() {
        if (multicast != null && multicast.isHeld()) multicast.release();
        multicast = null;
    }

    /** files/airplay-status.json, written only by this process (the settings screen reads it in P4). */
    private synchronized void status(String state, String detail) {
        try {
            JSONObject s = new JSONObject().put("state", state).put("at", System.currentTimeMillis());
            if (name != null) s.put("name", name);
            if (port > 0) s.put("port", port);
            if (detail != null) s.put("detail", detail);
            File tmp = new File(getFilesDir(), "airplay-status.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(getFilesDir(), "airplay-status.json"))) AppLog.w("airplay: could not save the status");
        } catch (IOException | JSONException e) {
            AppLog.w("airplay: could not save the status: " + e.getMessage());
        }
    }

    // MARK: - Crash guard (files/airplay-crashes.txt)

    private static synchronized void installCrashGuard(final Context app) {
        if (guardInstalled) return;
        guardInstalled = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                AppLog.w("airplay: crashed: " + error);
                if (recordCrash(app) >= CRASH_LIMIT) {
                    AirPlay.putSetting(app, "paused", "true");
                    ModeJob.notice(app, Ui.tr("O AirPlay caiu 3 vezes seguidas e foi pausado; ligue o Free My TV de novo para tentar outra vez.",
                            "AirPlay crashed 3 times in a row and is paused; turn Free My TV on again to retry.",
                            "AirPlay falló 3 veces seguidas y quedó en pausa; vuelve a activar Free My TV para intentarlo de nuevo."));
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static int recordCrash(Context c) throws IOException {
        File f = new File(c.getFilesDir(), "airplay-crashes.txt");
        long now = System.currentTimeMillis();
        List<Long> recent = new ArrayList<>();
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) Math.min(f.length(), 4096)];
                int n = in.read(data);
                if (n > 0) {
                    for (String line : new String(data, 0, n, StandardCharsets.UTF_8).split("\n")) {
                        try {
                            long at = Long.parseLong(line.trim());
                            if (now - at < CRASH_WINDOW && at <= now) recent.add(at);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        recent.add(now);
        StringBuilder sb = new StringBuilder();
        for (long at : recent) sb.append(at).append('\n');
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return recent.size();
    }
}
