package io.freemytv;

import java.util.Arrays;
import java.util.List;

public final class WatchMatchTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void same(String got, String expected) {
        check(got.equals(expected), "expected \"" + expected + "\", got \"" + got + "\"");
    }

    private static WatchMatch.Candidate tv(int id, String title, String original, int year, double popularity, int rank) {
        return new WatchMatch.Candidate("tv", id, title, original, year, popularity, rank);
    }

    private static WatchMatch.Candidate movie(int id, String title, String original, int year, double popularity, int rank) {
        return new WatchMatch.Candidate("movie", id, title, original, year, popularity, rank);
    }

    private static int best(String query, int year, boolean series, WatchMatch.Candidate... found) {
        List<WatchMatch.Candidate> ok = WatchMatch.ranked(query, year, series, Arrays.asList(found));
        return ok.isEmpty() ? -1 : ok.get(0).id;
    }

    public static void main(String[] args) {
        // What to search for.
        same(WatchMatch.searchTitle("The Prince", "Episode 2"), "The Prince");
        same(WatchMatch.searchTitle("The Prince: S2 E2", null), "The Prince");
        same(WatchMatch.searchTitle("The Prince - S02E02 - The Return", null), "The Prince");
        same(WatchMatch.searchTitle("S2:E2 The Prince", null), "The Prince");
        same(WatchMatch.searchTitle("The Prince 2. Sezon 2. Bölüm", null), "The Prince");
        same(WatchMatch.searchTitle("Yalı Çapkını Sezon 3 Bölüm 5", null), "Yalı Çapkını");
        same(WatchMatch.searchTitle("The Prince: The Return", "The Return"), "The Prince");
        same(WatchMatch.searchTitle("Doctor X: Mafia in White", null), "Doctor X: Mafia in White");
        same(WatchMatch.searchTitle("Pilot Season", "Pilot"), "Pilot Season");
        same(WatchMatch.searchTitle("Spartacus", "Spart"), "Spartacus");
        same(WatchMatch.searchTitle("The Return - The Prince", "The Return"), "The Prince");
        same(WatchMatch.searchTitle("The Batman (2022)", null), "The Batman");
        same(WatchMatch.searchTitle("CMXXIV", null), "CMXXIV");
        same(WatchMatch.searchTitle("Se7en", null), "Se7en");
        same(WatchMatch.searchTitle("T2 Trainspotting", null), "T2 Trainspotting");
        same(WatchMatch.searchTitle("Dark – Staffel 2, Folge 3", null), "Dark");
        check(WatchMatch.yearIn("The Batman (2022)") == 2022, "year in a title");
        check(WatchMatch.yearIn("2024-05-01") == 2024 && WatchMatch.yearIn("2019") == 2019, "year in a date");
        check(WatchMatch.yearIn("") == 0 && WatchMatch.yearIn(null) == 0 && WatchMatch.yearIn("abc") == 0, "no year");
        check(WatchMatch.number("2") == 2 && WatchMatch.number(" 12 ") == 12 && WatchMatch.number("x") == -1
                && WatchMatch.number(null) == -1, "numbers");

        // Written alike.
        same(WatchMatch.normalize("Kızıl Goncalar"), "kizil goncalar");
        same(WatchMatch.normalize("KIZIL GONCALAR"), "kizil goncalar");
        same(WatchMatch.normalize("İstanbul'da Bir Gece"), "istanbulda bir gece");
        same(WatchMatch.normalize("Doctor X: Mafia in White"), "doctor x mafia in white");
        same(WatchMatch.normalize("Grey’s Anatomy"), "greys anatomy");
        same(WatchMatch.normalize("Law & Order"), "law and order");
        same(WatchMatch.normalize("Amélie"), "amelie");
        same(WatchMatch.normalize("닥터X: 하얀 마피아의 시대"), "닥터x 하얀 마피아의 시대");
        same(WatchMatch.normalize("CMXXIV"), "cmxxiv");

        // Which result.
        check(best("The Prince", 0, true,
                tv(1, "The Little Prince", "Le Petit Prince", 2010, 9, 0),
                tv(2, "The Prince", "The Prince", 2021, 14, 1),
                tv(3, "Prince of Persia", "Prince of Persia", 2001, 3, 2)) == 2, "The Prince: the exact title");
        check(best("Doctor X: Mafia in White", 2025, true,
                tv(10, "Doctor-X: Surgeon Michiko Daimon", "ドクターX～外科医・大門未知子～", 2012, 30, 0),
                tv(11, "Doctor X: Era of the White Mafia", "닥터X: 하얀 마피아의 시대", 2025, 12, 1)) == 11,
                "Doctor X: the 2025 show, not the 2012 one");
        check(best("The Batman", 2022, false,
                movie(20, "Batman", "Batman", 1989, 40, 1),
                movie(21, "The Batman", "The Batman", 2022, 60, 0),
                movie(22, "Batman Begins", "Batman Begins", 2005, 45, 2)) == 21, "The Batman (2022)");
        check(best("The Batman", 0, false,
                movie(20, "Batman", "Batman", 1989, 90, 0),
                movie(21, "The Batman", "The Batman", 2022, 60, 1)) == 21, "an exact title beats a more popular one");
        check(best("CMXXIV", 0, false, movie(30, "CMXXIV", "CMXXIV", 2024, 0.6, 0)) == 30, "CMXXIV");
        check(best("CMXXIV", 0, false, movie(31, "The Kingdom", "The Kingdom", 2007, 20, 0)) == -1,
                "nothing like it: no match");
        check(best("Dark", 0, true, tv(41, "The Dark Knight Returns", "The Dark Knight Returns", 2012, 20, 0)) == -1,
                "a title that only contains the query's word");
        check(best("Kızıl Goncalar", 0, true, tv(40, "Kizil Goncalar", "Kızıl Goncalar", 2023, 5, 0)) == 40,
                "Turkish letters");
        check(best("Spiderman", 0, false, movie(50, "Spider-Man", "Spider-Man", 2002, 50, 0)) == 50, "spaces");
        check(best("Breaking Bad", 2013, true, tv(60, "Breaking Bad", "Breaking Bad", 2008, 300, 0)) == 60,
                "a show that started before the episode's year");
        check(best("Dune", 2021, false,
                movie(70, "Dune", "Dune", 1984, 30, 0),
                movie(71, "Dune", "Dune", 2021, 80, 1)) == 71, "the year picks between two of the same name");
        check(WatchMatch.ranked("", 0, false, Arrays.asList(movie(80, "", "", 0, 0, 0))).isEmpty(), "empty query");

        System.out.println("WatchMatchTest: all " + assertions + " checks passed");
    }
}
