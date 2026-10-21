package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The AirPlay player (in :airplay, with AirPlayService): black, the mirror stream fitted in the middle (a portrait
 * iPhone gets bars at the sides). Shows who is mirroring for 3 s; Back twice ends the session (the iPhone stops).
 * Leaving it (Home, another app) ends the session too. Before a session, it can also show the AirPlay code to type
 * on the iPhone (code mode: Back or leaving just closes it).
 */
public class AirPlayActivity extends Activity implements SurfaceHolder.Callback {
    private static volatile AirPlayActivity current;
    /** Set when a player opens, to tell a blocked background start from a slow one. */
    static volatile long openedAt;

    private FrameLayout root;
    private SurfaceView view;
    private TextView overlay, code, codeHint;
    private boolean surfaceReady, codeMode;
    private long backAt = Long.MIN_VALUE / 2;
    private final Runnable hide = () -> overlay.animate().alpha(0f).setDuration(300).start();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        openedAt = SystemClock.elapsedRealtime();
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Ui.init(this);
        float textScale = "large".equals(Prefs.textSize()) ? 1.2f : 1f;
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        root.addView(view, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22 * textScale);
        if (Ui.medium != null) overlay.setTypeface(Ui.medium);
        overlay.setGravity(Gravity.CENTER);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        overlay.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB0000000);
        bg.setCornerRadius(pad * 2);
        overlay.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = pad * 3;
        root.addView(overlay, lp);
        // The AirPlay code: big digits in the middle, what to do with them below.
        code = new TextView(this);
        code.setTextColor(Color.WHITE);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 96 * textScale);
        code.setLetterSpacing(0.3f);
        if (Ui.semibold != null) code.setTypeface(Ui.semibold);
        codeHint = new TextView(this);
        codeHint.setTextColor(0xB3FFFFFF);
        codeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24 * textScale);
        if (Ui.regular != null) codeHint.setTypeface(Ui.regular);
        codeHint.setText(Ui.tr("Digite este código no iPhone ou iPad", "Type this code on your iPhone or iPad",
                "Escribe este código en el iPhone o iPad"));
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.addView(code);
        box.addView(codeHint);
        root.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit());
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        codeMode(getIntent());
        if (!codeMode) {
            AppLog.w("video: player opened");
            showWho();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        codeMode(intent);
        if (!codeMode) {
            showWho();
            fit();
        }
    }

    /** With a code in the intent: show it (and nothing else); without: the player. */
    private void codeMode(Intent intent) {
        String digits = intent.getStringExtra("code");
        codeMode = digits != null;
        code.setText(codeMode ? digits : "");
        code.setVisibility(codeMode ? View.VISIBLE : View.GONE);
        codeHint.setVisibility(codeMode ? View.VISIBLE : View.GONE);
        if (codeMode) {
            overlay.setAlpha(0f);
            overlay.removeCallbacks(closeCode);
            overlay.postDelayed(closeCode, 120_000);   // the code's lifetime
        }
    }

    private final Runnable closeCode = () -> {
        if (codeMode) finish();
    };

    @Override protected void onStop() {
        super.onStop();
        // Home, another app, the screen going off: the session ends with the player (a code screen just closes).
        if (codeMode) {
            finish();
            return;
        }
        if (!isFinishing()) {
            AirPlayService.endSession("the player was closed");
            finish();
        }
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        overlay.removeCallbacks(hide);
        overlay.removeCallbacks(closeCode);
        if (!codeMode) AppLog.w("video: player closed");
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) && codeMode) {
            finish();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            long now = SystemClock.elapsedRealtime();
            if (now - backAt < 3000) {
                AppLog.w("video: Back pressed twice: ending the session");
                AirPlayService.endSession("Back pressed twice on the TV");
                finish();
            } else {
                backAt = now;
                show(Ui.tr("Aperte Voltar de novo para parar o AirPlay", "Press Back again to stop AirPlay",
                        "Pulsa Atrás otra vez para detener AirPlay"));
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // MARK: - Surface

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        attach();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        AppLog.d("video: player surface " + width + "x" + height);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        AirPlayVideo v = AirPlayService.video;
        if (v != null) v.setSurface(null);   // off the surface before it goes
    }

    private void attach() {
        AirPlayVideo v = AirPlayService.video;
        if (v != null && surfaceReady) {
            v.setSurface(view.getHolder().getSurface());
            AppLog.w("video: player surface ready");
        }
    }

    /** The stream's size inside the screen, keeping its shape. */
    private void fit() {
        int w = AirPlayService.videoWidth, h = AirPlayService.videoHeight;
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return;
        int fw = FrameLayout.LayoutParams.MATCH_PARENT, fh = FrameLayout.LayoutParams.MATCH_PARENT;   // size unknown: fill
        if (w > 0 && h > 0) {
            float scale = Math.min(sw / (float) w, sh / (float) h);
            fw = Math.round(w * scale);
            fh = Math.round(h * scale);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.width == fw && lp.height == fh) return;
        lp.width = fw;
        lp.height = fh;
        view.setLayoutParams(lp);
    }

    // MARK: - Overlay

    private void showWho() {
        String who = AirPlayService.senderName;
        show(who == null || who.isEmpty() ? "AirPlay" : "AirPlay · " + who);
    }

    private void show(String text) {
        overlay.setText(text);
        overlay.animate().cancel();
        overlay.setAlpha(1f);
        overlay.removeCallbacks(hide);
        overlay.postDelayed(hide, 3000);
    }

    // MARK: - From the service (any thread)

    /** A new stream (or a new size): fit it, and hand it the surface if the player is already open. */
    static void streamChanged() {
        final AirPlayActivity a = current;
        if (a != null) a.runOnUiThread(() -> {
            a.fit();
            a.attach();
        });
    }

    static void close() {
        final AirPlayActivity a = current;
        if (a != null) a.runOnUiThread(a::finish);
    }

    /** Shows the AirPlay code (from the service, when a sender asks for it). */
    static void showCode(Context c, String digits) {
        try {
            c.startActivity(new Intent(c, AirPlayActivity.class).putExtra("code", digits)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("airplay: could not show the code: " + e);
        }
    }

    /** The code was used: its screen closes (the player comes when the stream starts). */
    static void codeDone() {
        final AirPlayActivity a = current;
        if (a != null) a.runOnUiThread(() -> {
            if (a.codeMode) a.finish();
        });
    }
}
