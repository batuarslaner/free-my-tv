package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0), rendered by tools/make_symbols.py as white PNGs in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 * Each size is scaled down once (in steps of at most 2×) and drawn pixel for pixel at a whole-pixel position: a GPU
 * shrinking a ~150 px PNG to 40 px in one bilinear step skips pixels, and the edges come out jagged.
 */
final class Symbols {
    private Symbols() {}

    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    /** Scaled copies, by name and then by pixel size (width << 16 | height); only touched holding PAINT. */
    private static final Map<String, SparseArray<Bitmap>> SIZED = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    static Bitmap get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) {
                    b = b.extractAlpha();
                    b.setHasMipMap(true);
                }
            } catch (IOException ignored) {
            }
            CACHE.put(name, b);
            return b;
        }
    }

    static float width(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * pointSize / PX_PER_POINT;
    }

    static float height(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getHeight() * pointSize / PX_PER_POINT;
    }

    /** Draws centered at (cx, cy) in the given color (lighter, translucent parts of the symbol stay lighter). */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        Bitmap b = get(c, name);
        if (b == null) return;
        synchronized (PAINT) {   // card loading (another thread) also draws symbols
            drawLocked(canvas, name, b, cx, cy, pointSize, color);
        }
    }

    /** Scales a size ahead of time (the layout step), so the first frame that draws it doesn't. */
    static void warm(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        if (b == null) return;
        synchronized (PAINT) {
            sized(name, b, pointSize);
        }
    }

    private static void drawLocked(Canvas canvas, String name, Bitmap b, float cx, float cy, float pointSize, int color) {
        PAINT.setColor(color);
        Bitmap s = sized(name, b, pointSize);
        if (s != null) {
            canvas.drawBitmap(s, Math.round(cx - s.getWidth() / 2f), Math.round(cy - s.getHeight() / 2f), PAINT);
            return;
        }
        float w = b.getWidth() * pointSize / PX_PER_POINT, h = b.getHeight() * pointSize / PX_PER_POINT;
        RECT.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        canvas.drawBitmap(b, null, RECT, PAINT);
    }

    /** The symbol scaled down to `pointSize` (made once per size), or null when it is drawn at full size or larger. */
    private static Bitmap sized(String name, Bitmap b, float pointSize) {
        int w = Math.max(1, Math.round(b.getWidth() * pointSize / PX_PER_POINT));
        int h = Math.max(1, Math.round(b.getHeight() * pointSize / PX_PER_POINT));
        if (w >= b.getWidth() || h >= b.getHeight()) return null;
        SparseArray<Bitmap> sizes = SIZED.get(name);
        if (sizes == null) SIZED.put(name, sizes = new SparseArray<>());
        int key = w << 16 | h;
        Bitmap s = sizes.get(key);
        if (s != null) return s;
        s = b;
        while (s.getWidth() / 2 >= w && s.getHeight() / 2 >= h) s = Bitmap.createScaledBitmap(s, s.getWidth() / 2, s.getHeight() / 2, true);
        s = Bitmap.createScaledBitmap(s, w, h, true);
        sizes.put(key, s);
        return s;
    }

    // Weather colors, like Apple's Weather: {on light glass, on dark glass}.
    private static final int[] SUN = {0xFFF5A300, 0xFFFFCC33}, MOON = {0xFF6E73C8, 0xFFDCDDF5}, CLOUD = {0xFF8C97A8, 0xFFDDE3EC},
            RAIN = {0xFF2F8CFF, 0xFF5AB0FF}, SNOW = {0xFF4FA8E8, 0xFFA9DBFF}, BOLT = {0xFFF5B800, 0xFFFFD43B};

    /**
     * A weather symbol in color: the sun amber, the moon pale, clouds gray, rain and snow blue, lightning yellow. Two-part
     * symbols are drawn from their NAME_cloud and NAME_accent PNGs (tools/make_symbols.py). `dark`: 0 = on light glass,
     * 1 = on dark glass (in between during the night transition).
     */
    static void drawWeather(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, float dark) {
        switch (name) {
            case "sunny": draw(c, canvas, name, cx, cy, pointSize, tone(SUN, dark)); break;
            case "clear_night": draw(c, canvas, name, cx, cy, pointSize, tone(MOON, dark)); break;
            case "snowflake": draw(c, canvas, name, cx, cy, pointSize, tone(SNOW, dark)); break;
            case "partly_cloudy_day": twoTone(c, canvas, "partly_cloudy_day_cloud", "partly_cloudy_day_accent", cx, cy, pointSize, tone(SUN, dark), dark); break;
            case "partly_cloudy_night": twoTone(c, canvas, "partly_cloudy_night_cloud", "partly_cloudy_night_accent", cx, cy, pointSize, tone(MOON, dark), dark); break;
            case "rainy": twoTone(c, canvas, "rainy_cloud", "rainy_accent", cx, cy, pointSize, tone(RAIN, dark), dark); break;
            case "thunderstorm": twoTone(c, canvas, "thunderstorm_cloud", "thunderstorm_accent", cx, cy, pointSize, tone(BOLT, dark), dark); break;
            default: draw(c, canvas, name, cx, cy, pointSize, tone(CLOUD, dark)); break;   // cloud, foggy
        }
    }

    private static void twoTone(Context c, Canvas canvas, String cloud, String accent, float cx, float cy, float pointSize, int color, float dark) {
        draw(c, canvas, cloud, cx, cy, pointSize, tone(CLOUD, dark));
        draw(c, canvas, accent, cx, cy, pointSize, color);
    }

    /** Scales ahead of time every part drawWeather will draw for `name`. */
    static void warmWeather(Context c, String name, float pointSize) {
        warm(c, name, pointSize);
        warm(c, name + "_cloud", pointSize);   // missing for one-part symbols: nothing to scale
        warm(c, name + "_accent", pointSize);
    }

    private static int tone(int[] pair, float dark) {
        int a = pair[0], b = pair[1];
        float f = Math.max(0, Math.min(1, dark));
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "rainy";
        if (code == 65 || code == 67 || code == 82) return "rainy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
