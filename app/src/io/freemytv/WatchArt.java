package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Continue Watching's pictures, made like the picks' (Backdrop: the wide card and the background with its glass, by
 * day and at night; a poster for the narrow cards), once, in the background, and kept in memory for the row's items.
 *
 * With a TMDB key, each item is looked up on TMDB (WatchMatch judges the results: a show by its series title, a film
 * by its own) for clean art: a backdrop without text and the poster, in the TV's language. What TMDB said is kept on
 * disk per app and title (30 days; "nothing like it" 3 days) and the images in the cache folder, so a home screen
 * started again asks nothing. Without a key or a match, the app's own picture makes the same set (the poster from its
 * middle); without that either, the app's TV banner.
 *
 * The app's own pictures are read through the apps' providers (content:// and android.resource://) or, where an app
 * left an https address (Netflix, JioHotstar), through NetPicture's checks (https only, never the home network,
 * pictures only, size and time limits). The app never opens files (file://) or plain http.
 */
final class WatchArt {
    /** An item's pictures. The backdrop and the poster hold their own shaders: they leave memory together. */
    static final class Art {
        final Backdrop backdrop;
        final Recs.Poster poster;
        long retryAt;       // the app's picture because TMDB didn't answer: TMDB is asked again from then (0: never)
        boolean keyless;    // made without a TMDB key: made again once there is one

        Art(Backdrop backdrop, Recs.Poster poster) {
            this.backdrop = backdrop;
            this.poster = poster;
        }
    }

    /** A TMDB title's images (paths on image.tmdb.org; no poster: null). */
    private static final class Match {
        final String kind, backdrop, poster;
        final int id;

        Match(String kind, int id, String backdrop, String poster) {
            this.kind = kind;
            this.id = id;
            this.backdrop = backdrop;
            this.poster = poster;
        }
    }

    /** At most this many items' pictures in memory (~1.5 MB each, and the cards on the GPU): the cards on screen (up
     *  to 5) and a couple more. */
    private static final int KEEP = 7;
    private static final long DAY = 86_400_000L, FOUND_MS = 30 * DAY, NONE_MS = 3 * DAY;
    private static final long RETRY_MS = 10 * 60_000L, TMDB_RETRY_MS = 5 * 60_000L;
    /** TMDB images on disk (a backdrop and a poster per title). */
    private static final int FILES = 40;
    private static final int MAX_BYTES = 8 << 20, MAX_IMAGE = 24 << 20;
    /** At most this many pixels decoded from an app's picture (~8 MB): a 60000×300 picture is sampled down first. */
    private static final long MAX_PIXELS = 2_000_000;
    /** How many of the best results are looked at closer (one more request each, only while the first doesn't fit). */
    private static final int DETAILS = 3;

    private final Context app;
    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** One thread for the whole process: a home screen started over (new settings) doesn't leave one behind. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static SharedPreferences store;   // what TMDB said; opened and read only on IO
    private volatile boolean closed;
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> failed = new HashMap<>();
    private Set<String> wanted;   // the row's items (null until the first list: any)
    private final Map<String, Art> cache = new LinkedHashMap<String, Art>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Art> eldest) {
            return size() > KEEP;
        }
    };

    WatchArt(Context c, Runnable onLoaded) {
        app = c.getApplicationContext();
        this.onLoaded = onLoaded;
    }

    /**
     * The item's pictures, or null while they're made (in the background; onLoaded runs on the main thread once
     * they're in). Cheap enough to ask while drawing: nothing is made here.
     */
    Art get(WatchNext.Item it) {
        String key = it.artKey;
        if (key == null || closed) return null;
        synchronized (cache) {
            Art a = cache.get(key);
            if (pending.contains(key)) return a;
            long now = System.currentTimeMillis();
            if (a != null) {
                if (!(a.retryAt != 0 && now >= a.retryAt || a.keyless && !Recs.key().isEmpty())) return a;
                a.retryAt = 0;   // asked once: the job sets them again if TMDB still can't help
                a.keyless = false;
            } else {
                Long at = failed.get(key);
                if (at != null && now - at >= 0 && now - at < RETRY_MS) return null;
            }
            pending.add(key);
            final Art old = a;
            IO.execute(() -> work(it, old));
            return a;
        }
    }

    /** The row's items now: the others' pictures leave memory. */
    void keep(List<WatchNext.Item> list) {
        Set<String> keys = new HashSet<>();
        for (WatchNext.Item it : list) if (it.artKey != null) keys.add(it.artKey);
        synchronized (cache) {
            wanted = keys;
            cache.keySet().retainAll(keys);
            failed.keySet().retainAll(keys);
        }
    }

    /** The home screen went away: nothing more is posted to it, and the pictures go. */
    void close() {
        closed = true;
        synchronized (cache) {
            cache.clear();
            failed.clear();
        }
    }

    // MARK: - Making them (on IO)

    /** A TMDB image that's no longer there (HTTP 404). */
    private static final class Gone extends IOException {
        Gone() { super("TMDB image: HTTP 404"); }
    }

    private void work(WatchNext.Item it, Art old) {
        try {
            make(it, old);
        } finally {
            synchronized (cache) { pending.remove(it.artKey); }   // whatever happened, it can be asked again
        }
    }

    /** True when nothing could be made for it (it shows the empty card until it's tried again). */
    boolean failed(WatchNext.Item it) {
        synchronized (cache) {
            return it.artKey != null && failed.containsKey(it.artKey) && !cache.containsKey(it.artKey);
        }
    }

    private void make(WatchNext.Item it, Art old) {
        Art made = null;
        boolean keyless = Recs.key().isEmpty(), tmdbFailed = false;
        if (!closed && !keyless && looksUp(it)) {
            try {
                Match m = lookup(it);
                try {
                    if (m != null) made = fromTmdb(m);
                } catch (Gone e) {   // TMDB took that image away: ask again next time
                    store().edit().remove(storeId(it)).apply();
                    throw e;
                }
            } catch (IOException | JSONException | RuntimeException | OutOfMemoryError e) {
                tmdbFailed = true;   // the network, most likely (just after the TV starts): the app's picture for now
                AppLog.w("continue watching, TMDB: " + e.getMessage());
            }
        }
        if (!closed && made == null && old == null) {
            try {
                made = fromApp(it);
                made.retryAt = tmdbFailed ? System.currentTimeMillis() + TMDB_RETRY_MS : 0;
                made.keyless = keyless;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("continue watching picture: " + e.getMessage());
            }
        }
        synchronized (cache) {
            pending.remove(it.artKey);
            if (closed) return;
            if (made == null) {
                if (old == null) {
                    failed.put(it.artKey, System.currentTimeMillis());
                } else {   // still the app's picture
                    old.retryAt = tmdbFailed ? System.currentTimeMillis() + TMDB_RETRY_MS : 0;
                    old.keyless = keyless;
                    return;
                }
            } else if (wanted == null || wanted.contains(it.artKey)) {
                cache.put(it.artKey, made);
            }
        }
        main.post(() -> { if (!closed) onLoaded.run(); });   // the pictures, or the empty card for good
    }

    /** Films and shows (or items that don't say): not clips, channels, music or games. */
    private static boolean looksUp(WatchNext.Item it) {
        if (it.pkg.contains("youtube")) return false;   // its own videos: a title could match any film
        int t = it.type;
        return t < 0 || t == TvContract.WatchNextPrograms.TYPE_MOVIE || t == TvContract.WatchNextPrograms.TYPE_TV_SERIES
                || t == TvContract.WatchNextPrograms.TYPE_TV_SEASON || t == TvContract.WatchNextPrograms.TYPE_TV_EPISODE;
    }

    private static boolean series(WatchNext.Item it) {
        return it.season > 0 || it.type == TvContract.WatchNextPrograms.TYPE_TV_SERIES
                || it.type == TvContract.WatchNextPrograms.TYPE_TV_SEASON || it.type == TvContract.WatchNextPrograms.TYPE_TV_EPISODE;
    }

    // MARK: - TMDB

    /** Where what TMDB said about it is kept: its app, the language and the title it's searched by. */
    private static String storeId(WatchNext.Item it) {
        return it.pkg + "|" + Recs.lang() + "|" + WatchMatch.normalize(WatchMatch.searchTitle(it.title, it.episodeTitle));
    }

    /** What TMDB has for it: from disk while fresh, else asked (and kept). Null: nothing like it. */
    private Match lookup(WatchNext.Item it) throws IOException, JSONException {
        String query = WatchMatch.searchTitle(it.title, it.episodeTitle);
        if (WatchMatch.normalize(query).isEmpty()) return null;
        String lang = Recs.lang(), id = storeId(it);
        SharedPreferences s = store();
        long now = System.currentTimeMillis();
        String saved = s.getString(id, null);
        if (saved != null) {
            String[] f = saved.split("\t", -1);
            long at = parseLong(f[0]);
            boolean none = f.length < 5;
            if (now >= at && now - at < (none ? NONE_MS : FOUND_MS)) {
                return none ? null : new Match(f[1], (int) parseLong(f[2]), f[3], f[4].isEmpty() ? null : f[4]);
            }
        }
        Match m = search(it, query, lang);
        s.edit().putString(id, m == null ? String.valueOf(now)
                : now + "\t" + m.kind + "\t" + m.id + "\t" + m.backdrop + "\t" + (m.poster == null ? "" : m.poster)).apply();
        AppLog.w("continue watching “" + query + "”: " + (m == null ? "not on TMDB" : m.kind + " " + m.id));
        return m;
    }

    /**
     * A show as a show and a film as a film (anything else, or nothing found that way: both), in the TV's language,
     * then in English (an English title on a TV in another language).
     */
    private Match search(WatchNext.Item it, String query, String lang) throws IOException, JSONException {
        boolean series = series(it), movie = !series && it.type == TvContract.WatchNextPrograms.TYPE_MOVIE;
        String kind = series ? "tv" : movie ? "movie" : "multi";
        List<String> langs = lang.equals("en-US") ? Collections.singletonList(lang) : Arrays.asList(lang, "en-US");
        for (String l : langs) {
            List<WatchMatch.Candidate> ok = WatchMatch.ranked(query, it.year, series, results(kind, query, l));
            if (ok.isEmpty() && !kind.equals("multi")) ok = WatchMatch.ranked(query, it.year, series, results("multi", query, l));
            Match m = pick(ok, it.season);
            if (m != null) return m;
        }
        return null;
    }

    private static List<WatchMatch.Candidate> results(String kind, String query, String lang) throws IOException, JSONException {
        JSONObject o = Recs.tmdb("/search/" + kind + "?query=" + URLEncoder.encode(query, "UTF-8") + "&language=" + lang
                + "&include_adult=false");
        JSONArray a = o.optJSONArray("results");
        List<WatchMatch.Candidate> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && out.size() < 10; i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) continue;
            String k = kind.equals("multi") ? r.optString("media_type") : kind;
            if (!k.equals("movie") && !k.equals("tv")) continue;   // people
            boolean film = k.equals("movie");
            out.add(new WatchMatch.Candidate(k, r.optInt("id"), r.optString(film ? "title" : "name", ""),
                    r.optString(film ? "original_title" : "original_name", ""),
                    WatchMatch.yearIn(r.optString(film ? "release_date" : "first_air_date", "")),
                    r.optDouble("popularity", 0), out.size()));
        }
        return out;
    }

    /**
     * The best result with a backdrop. A show the app says is in season N skips one with fewer seasons when another
     * as good is there ("The Prince" is more than one show); with none, the best is kept.
     */
    private static Match pick(List<WatchMatch.Candidate> ok, int season) throws IOException, JSONException {
        Match first = null;
        for (int i = 0; i < Math.min(DETAILS, ok.size()); i++) {
            WatchMatch.Candidate c = ok.get(i);
            if (i > 0 && c.score < ok.get(0).score - 12) break;   // a different title, not another of the same name
            JSONObject o = Recs.tmdb("/" + c.kind + "/" + c.id + "?language=" + Recs.lang()
                    + "&append_to_response=images&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
            String backdrop = Recs.bestBackdrop(o.optJSONObject("images"));
            if (backdrop == null && !o.isNull("backdrop_path")) backdrop = o.optString("backdrop_path", "");
            if (!imagePath(backdrop)) continue;
            String poster = o.isNull("poster_path") ? null : o.optString("poster_path", "");
            Match m = new Match(c.kind, c.id, backdrop, imagePath(poster) ? poster : null);
            int seasons = o.optInt("number_of_seasons", 0);
            if (season <= 0 || !c.kind.equals("tv") || seasons <= 0 || seasons >= season) return m;
            if (first == null) first = m;
        }
        return first;
    }

    /** A TMDB image's path ("/abc123.jpg"): it becomes a file name in the cache folder, so nothing else. */
    private static boolean imagePath(String path) {
        return path != null && IMAGE_PATH.matcher(path).matches();
    }

    private static final java.util.regex.Pattern IMAGE_PATH =
            java.util.regex.Pattern.compile("/[A-Za-z0-9_-]{1,100}\\.(jpg|jpeg|png|webp)");

    /** What TMDB said, by app, language and title; entries past their time are dropped when it's opened. */
    private SharedPreferences store() {
        if (store != null) return store;
        SharedPreferences s = app.getSharedPreferences("watch-tmdb", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = null;
        for (Map.Entry<String, ?> entry : s.getAll().entrySet()) {
            String v = String.valueOf(entry.getValue());
            String[] f = v.split("\t", -1);
            long at = parseLong(f[0]);
            if (now >= at && now - at < (f.length < 5 ? NONE_MS : FOUND_MS)) continue;
            if (e == null) e = s.edit();
            e.remove(entry.getKey());
        }
        if (e != null) e.apply();
        return store = s;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The backdrop (TMDB's size for this UI, as the picks) and the poster, from disk or downloaded there. */
    private Art fromTmdb(Match m) throws IOException {
        File dir = new File(app.getCacheDir(), "watch");
        File bf = new File(dir, m.backdrop.replace("/", ""));
        download(Recs.IMAGES + m.backdrop, bf);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(bf.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(bf.getPath(), opts);
        if (b == null) {
            //noinspection ResultOfMethodCallIgnored
            bf.delete();
            throw new IOException("unreadable TMDB image");
        }
        Backdrop backdrop = Backdrop.make(b, true);
        Recs.Poster poster = null;
        if (m.poster != null) {   // without it the narrow card crops the wide one, as the picks do
            File pf = new File(dir, "p" + m.poster.replace("/", ""));
            try {
                download(Recs.POSTERS + m.poster, pf);
                Bitmap full = BitmapFactory.decodeFile(pf.getPath());
                if (full == null) {
                    //noinspection ResultOfMethodCallIgnored
                    pf.delete();
                } else {
                    // A poster is 2:3 and the narrow card a little wider: the strip comes off the bottom, as the picks'.
                    Bitmap card = Backdrop.card(full, Backdrop.TALL_W, Backdrop.TALL_H, 0);
                    if (card != full && !full.isRecycled()) full.recycle();
                    poster = new Recs.Poster(card);
                }
            } catch (IOException e) {
                AppLog.w("continue watching poster: " + e.getMessage());
            }
        }
        prune(dir);
        return new Art(backdrop, poster);
    }

    /** One TMDB image into the cache folder (kept: a file already there is only marked as used). */
    private static void download(String address, File f) throws IOException {
        if (f.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new Gone();
            if (code != 200) throw new IOException("TMDB image: HTTP " + code);
            // Straight to the file (an original backdrop can be several MB: not held in memory).
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_IMAGE) throw new IOException("TMDB image too large");
                    out.write(buffer, 0, n);
                }
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
    }

    /** The cache folder keeps the newest FILES images (and no download cut off midway). */
    private static void prune(File dir) {
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - The app's own picture

    /** From the app's picture (its middle for the poster), else its TV banner: the same set as TMDB's. */
    private Art fromApp(WatchNext.Item it) throws IOException {
        Bitmap src = null;
        if (it.pictureUri != null) {
            try {
                src = decode(read(it.pictureUri));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("continue watching picture: " + e.getMessage());
            }
        }
        Recs.Poster poster;
        if (src != null) {
            poster = new Recs.Poster(Backdrop.card(src, Backdrop.TALL_W, Backdrop.TALL_H, 0.5f));
        } else {
            src = banner(it.pkg, 1280, 720);
            poster = new Recs.Poster(Backdrop.gpu(banner(it.pkg, Backdrop.TALL_W, Backdrop.TALL_H)));
        }
        return new Art(Backdrop.make(src, true), poster);   // make() recycles src
    }

    private static Bitmap decode(byte[] data) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int bw = bounds.outWidth, bh = bounds.outHeight;
        if (bw <= 0 || bh <= 0) throw new IOException("unreadable picture");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        // Halved while both sides stay at least the screen's, or while it's still too many pixels.
        for (int s = 1; bw / (s * 2) >= Backdrop.W && bh / (s * 2) >= Backdrop.H || (long) (bw / s) * (bh / s) > MAX_PIXELS; s *= 2) {
            opts.inSampleSize = s * 2;
        }
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (b == null) throw new IOException("unreadable picture");
        return b;
    }

    /** The app's TV banner (or its icon) on a dark picture, as the shelf shows apps without a logo. */
    private Bitmap banner(String pkg, int w, int h) throws IOException {
        PackageManager pm = app.getPackageManager();
        Drawable d;
        try {
            d = pm.getApplicationBanner(pkg);
            if (d == null) d = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("no app " + pkg);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF15161C);
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw > ih && ih > 0) {   // a TV banner, 16:9: as wide as it fits
            float s = Math.min((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
        } else {                   // an icon, in the middle
            int s = Math.round(Math.min(w, h) * 0.4f);
            d.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
        }
        d.draw(c);
        return out;
    }

    /** content:// and android.resource:// come from the app's provider; https:// through NetPicture's checks. */
    private byte[] read(Uri uri) throws IOException {
        if ("https".equalsIgnoreCase(uri.getScheme())) return NetPicture.get(uri.toString());
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no picture there");
            return NetPicture.readAll(in, MAX_BYTES, System.currentTimeMillis() + 12_000);
        }
    }
}
