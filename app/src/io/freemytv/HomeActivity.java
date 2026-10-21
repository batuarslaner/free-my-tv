package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("O Início do Free My TV teve um problema.\nVoltando para o Início original da TV…",
                    "Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        watchScreen(getApplicationContext());
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        AirPlay.ensureOften(this);   // while we're visible, Android lets us start its foreground service
        if (home != null) powerOnCheck(this);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        resumed = false;
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // MARK: - Power on (Home Settings): live TV when the TV wakes from standby or boots

    /**
     * After the screen turns on, the home screen's next resume opens live TV; if it doesn't resume by FALLBACK_MS (the
     * TV woke into another app), live TV opens anyway, and a later resume inside WAKE_MS opens it again (TVs that bring
     * the home screen up late). Only once per wake: pressing Home afterwards shows the home screen.
     */
    private static final long FALLBACK_MS = 2_500, WAKE_MS = 8_000, BOOT_MS = 5 * 60_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static boolean resumed, bootChecked;
    private static long wokeAt;   // elapsedRealtime of the last screen on still waiting; 0 = none

    private static final Runnable fallback = () -> {
        if (wokeAt != 0) powerOn(app, false);
    };

    /**
     * Screen on reaches only receivers registered at run time: this process holds the home screen, so it stays alive
     * while the TV sleeps. Registered once per process.
     */
    private static void watchScreen(Context c) {
        if (app != null) return;
        app = c;
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!"live".equals(Prefs.powerOn())) return;
                MAIN.removeCallbacks(fallback);
                if (resumed) {
                    wokeAt = 0;
                    powerOn(app, false);
                } else {
                    wokeAt = SystemClock.elapsedRealtime();
                    MAIN.postDelayed(fallback, FALLBACK_MS);
                }
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** On resume: a wake still waiting, or the first home screen after the TV booted. */
    private static void powerOnCheck(Context c) {
        if (wokeAt != 0) {
            boolean fresh = SystemClock.elapsedRealtime() - wokeAt < WAKE_MS;
            wokeAt = 0;
            MAIN.removeCallbacks(fallback);
            if (fresh) powerOn(c, false);
        } else if (!bootChecked) {
            bootChecked = true;
            if (SystemClock.elapsedRealtime() < BOOT_MS) powerOn(c, true);
        }
    }

    /** Opens live TV when Power on says so and Free My TV is the TV's home screen. The checks read files: off the UI thread. */
    private static void powerOn(Context c, boolean boot) {
        if (!"live".equals(Prefs.powerOn())) return;
        Context a = c.getApplicationContext();
        new Thread(() -> {
            if (boot && !firstOfBoot(a)) return;
            JSONObject s = HomeMode.state(a);
            if (!HomeMode.FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted")) {
                AppLog.w("power on: Free My TV isn't the home screen, live TV stays closed");
                return;
            }
            Intent live = HomeApps.liveTv(a);
            if (live == null) {
                AppLog.w("power on: this TV has no live TV");
                return;
            }
            MAIN.post(() -> {
                AppLog.w("power on: opening live TV (" + (boot ? "boot" : "wake") + ")");
                try {
                    a.startActivity(live.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException e) {
                    AppLog.w("power on: couldn't open live TV: " + e.getMessage());
                }
            });
        }).start();
    }

    /** True once per boot (Android counts them): the home screen may start again later in the same boot. */
    private static boolean firstOfBoot(Context a) {
        int boot = Settings.Global.getInt(a.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        if (boot < 0 || ui.getInt("powerOnBoot", -1) == boot) return false;
        ui.edit().putInt("powerOnBoot", boot).apply();
        return true;
    }
}
