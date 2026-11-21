package io.freemytv;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which TMDB title a Continue Watching item is (WatchArt asks TMDB, this only judges): the show's or film's own title
 * out of what the app wrote ("The Prince: S2 E2" is "The Prince"), both written alike (case, accents, Turkish ı,
 * punctuation) and TMDB's results scored by how well their title or original title matches, the year, TMDB's own
 * order and popularity. Plain Java with no android.*: tests/WatchMatchTest.java runs it on a JDK.
 */
final class WatchMatch {
    private WatchMatch() {}

    /** Below this a result is someone else's title. */
    static final double ACCEPT = 0.6;

    /** One of TMDB's search results. */
    static final class Candidate {
        final String kind;      // "movie" or "tv"
        final int id, year, rank;
        final String title, original;
        final double popularity;
        double score;

        Candidate(String kind, int id, String title, String original, int year, double popularity, int rank) {
            this.kind = kind;
            this.id = id;
            this.title = title == null ? "" : title;
            this.original = original == null ? "" : original;
            this.year = year;
            this.popularity = popularity;
            this.rank = rank;
        }
    }

    // Season and episode as apps write them into a title: S2 E5, S02E05, S2:E5, T2 E5, S2 B2 (Turkish), St. 2 F. 5,
    // 2x05, Season 2, 2. Sezon, Episode 5, 5. Bölüm…
    private static final Pattern MARKER = Pattern.compile("(?iU)"
            + "\\b[st]\\s?\\d{1,2}\\s?[:.,-]?\\s?[ebf]\\s?\\d{1,4}\\b"
            + "|\\bst\\.?\\s?\\d{1,2}\\s?f\\.?\\s?\\d{1,4}\\b"
            + "|\\b\\d{1,2}x\\d{1,3}\\b"
            + "|\\b(season|series|sezon|temporada|staffel|saison|stagione|seizoen|sæson|säsong|sezona|сезон)\\s*\\d+"
            + "|\\b\\d+\\.?\\s*(sezon|bölüm|staffel|folge|temporada|сезон|серия)\\b"
            + "|\\b(episode|ep|bölüm|episodio|episódio|capítulo|capitulo|folge|épisode|aflevering|odcinek|серия)\\.?\\s*\\d+");
    private static final Pattern YEAR = Pattern.compile("\\(\\s*((?:19|20)\\d{2})\\s*\\)");
    private static final Pattern YEAR_ANY = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final String SEPARATORS = " \t-–—:|·,/";
    private static final Set<String> ARTICLES = new HashSet<>(Arrays.asList(
            "the", "a", "an", "el", "la", "los", "las", "le", "les", "l", "der", "die", "das", "o", "os", "as", "il", "lo"));

    /** The first whole number in s, or -1 (TvContract keeps season numbers and types as text). */
    static int number(String s) {
        if (s == null) return -1;
        Matcher m = DIGITS.matcher(s);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A year in a date ("2024-05-01", "2024") or in a title's "(2022)", or 0. */
    static int yearIn(String s) {
        if (s == null) return 0;
        Matcher m = YEAR.matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = YEAR_ANY.matcher(s.trim());
        return m.lookingAt() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * What to search TMDB for: the title without its "(2022)", without the episode's own title when the app put it
     * in, and without season and episode numbers (the part before them, else the part after).
     */
    static String searchTitle(String title, String episodeTitle) {
        if (title == null) return "";
        String t = YEAR.matcher(title).replaceAll(" ").trim();
        if (episodeTitle != null) {
            String e = episodeTitle.trim();
            int at = e.length() < 2 ? -1 : t.lastIndexOf(e);
            // Only where it's set off from the rest by a separator ("The Prince: The Return"), never part of the
            // title itself ("Pilot Season" with the episode "Pilot", "Spartacus" with "Spart").
            int end = at + e.length();
            String gap = at < 0 ? "" : at > 0 ? t.substring(Math.max(0, at - 3), at) : t.substring(end, Math.min(t.length(), end + 3));
            boolean apart = false;
            for (int i = 0; i < gap.length(); i++) apart |= gap.charAt(i) != ' ' && SEPARATORS.indexOf(gap.charAt(i)) >= 0;
            if (apart && t.length() > e.length()) {
                String rest = trim(t.substring(0, at)) + " " + trim(t.substring(at + e.length()));
                if (!trim(rest).isEmpty()) t = trim(rest);
            }
        }
        Matcher m = MARKER.matcher(t);
        if (m.find()) {
            String before = trim(t.substring(0, m.start())), after = trim(t.substring(m.end()));
            t = !before.isEmpty() ? before : after;
        }
        return trim(t);
    }

    private static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && SEPARATORS.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && SEPARATORS.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    /** Lower case, no accents (Turkish ı and İ too), "&" as "and", apostrophes dropped, other punctuation as spaces. */
    static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replace('ı', 'i').replace("ß", "ss").replace("æ", "ae")
                .replace("œ", "oe").replace('ø', 'o').replace('đ', 'd').replace('ł', 'l').replace("&", " and ");
        // Accents off, then composed again (Hangul syllables come apart in NFD).
        t = Normalizer.normalize(MARKS.matcher(Normalizer.normalize(t, Normalizer.Form.NFD)).replaceAll(""), Normalizer.Form.NFC);
        t = t.replace("'", "").replace("’", "").replace("`", "").replace("´", "");
        return NOT_WORD.matcher(t).replaceAll(" ").trim();
    }

    /**
     * How alike two normalized titles are: 1 the same, 0.97 the same without spaces ("spider man", "spiderman"), 0.9
     * the same but for a leading article, else up to 0.85 by the words they share (mostly the query's).
     */
    static double similarity(String query, String title) {
        if (query.isEmpty() || title.isEmpty()) return 0;
        if (query.equals(title)) return 1;
        if (query.replace(" ", "").equals(title.replace(" ", ""))) return 0.97;
        if (withoutArticle(query).equals(withoutArticle(title))) return 0.9;
        Set<String> q = new HashSet<>(Arrays.asList(query.split(" "))), c = new HashSet<>(Arrays.asList(title.split(" ")));
        int common = 0;
        for (String word : q) if (c.contains(word)) common++;
        double inQuery = (double) common / q.size(), inTitle = (double) common / c.size();
        if (inTitle < 0.5) return 0;   // "Dark" isn't "The Dark Knight"
        return 0.85 * (0.62 * inQuery + 0.38 * inTitle);
    }

    private static String withoutArticle(String s) {
        int space = s.indexOf(' ');
        return space > 0 && ARTICLES.contains(s.substring(0, space)) ? s.substring(space + 1) : s;
    }

    /**
     * The results that are this title, best first: the closer title (or original title), then the year (a film's
     * own; a show must have started by then), TMDB's order and popularity.
     */
    static List<Candidate> ranked(String query, int year, boolean series, List<Candidate> found) {
        String q = normalize(query);
        List<Candidate> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (Candidate c : found) {
            double sim = Math.max(similarity(q, normalize(c.title)), similarity(q, normalize(c.original)));
            if (sim < ACCEPT) continue;
            double score = sim * 100;
            if (year > 0 && c.year > 0) {
                int d = Math.abs(year - c.year);
                if ("tv".equals(c.kind) || series) score += c.year <= year ? 4 : -15;
                else score += d == 0 ? 12 : d == 1 ? 6 : -12;
            }
            score += Math.max(0, 6 - 2 * c.rank);
            score += Math.min(6, Math.log10(1 + Math.max(0, c.popularity)) * 2.5);
            c.score = score;
            out.add(c);
        }
        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));
        return out;
    }
}
