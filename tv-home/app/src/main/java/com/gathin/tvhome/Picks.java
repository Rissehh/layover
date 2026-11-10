package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Picked for you": titles like the ones watched lately, from Cinemeta (Stremio's metadata, no key needed).
 * Pure logic, no Android: which watched titles to start from (seeds), how alike two titles are, and how the
 * per-seed lists are merged into one row. Cinemeta does the fetching.
 */
final class Picks {

    private Picks() {
    }

    /** What the scoring needs to know about a title. */
    static final class Title {
        final String id;
        final String type;
        final String name;
        final String poster;
        final String background;
        final Set<String> genres;
        /** Cinemeta's genre names in its order, for catalog URLs. */
        final List<String> genreNames;
        final Set<String> makers;    // directors and writers
        final Set<String> cast;
        final String country;
        final double rating;         // IMDb, 0 when unknown
        final int year;              // first year, 0 when unknown

        Title(String id, String type, String name, String poster, String background, Collection<String> genres,
              Collection<String> makers, Collection<String> cast, String country, double rating, int year) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.poster = poster;
            this.background = background;
            this.genres = lower(genres);
            this.genreNames = genres == null ? new ArrayList<>() : new ArrayList<>(genres);
            this.makers = lower(makers);
            this.cast = lower(cast);
            this.country = country == null ? "" : country;
            this.rating = rating;
            this.year = year;
        }

        /** Japanese animation: anime is only ever matched with anime. */
        boolean anime() {
            return genres.contains("animation") && country.contains("Japan");
        }

        /** The genres to pull candidates from: Animation first for anime, else the first two listed. */
        List<String> searchGenres() {
            List<String> out = new ArrayList<>();
            if (anime()) out.add("Animation");
            for (String g : genreNames) {
                if (out.size() >= 2) break;
                if (!out.contains(g)) out.add(g);
            }
            return out;
        }
    }

    /** A watched title to recommend from. */
    static final class Seed {
        final String id;
        final String type;
        final String name;
        final double weight;

        Seed(String id, String type, String name, double weight) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.weight = weight;
        }
    }

    /** One card in the row: the title, and the watched title it came from. */
    static final class Pick {
        final Title title;
        final String because;
        final double score;

        Pick(Title title, String because, double score) {
            this.title = title;
            this.because = because;
            this.score = score;
        }
    }

    static final int MAX_SEEDS = 4;
    static final int MAX_PICKS = 18;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** A title watched this many days ago counts half as much as one watched today. */
    private static final double HALF_LIFE_DAYS = 30;
    /** Below this a candidate isn't shown at all, however few picks there are. */
    static final double MIN_SCORE = 1.2;

    /**
     * The titles to recommend from: watched within the last six months and actually watched (a quarter of
     * the way in, or finished), weighted by how recently and how much. Newest-weighted first.
     */
    static List<Seed> seeds(List<LayoverFeed.Watched> history, long now) {
        List<Seed> out = new ArrayList<>();
        for (LayoverFeed.Watched w : history) {
            if (w.id == null || !w.id.startsWith("tt")) continue;
            if (!"movie".equals(w.type) && !"series".equals(w.type)) continue;
            double ageDays = Math.max(0, (now - w.lastWatched) / (double) DAY_MS);
            if (ageDays > 180 || w.engagement < 0.25) continue;
            double weight = Math.pow(0.5, ageDays / HALF_LIFE_DAYS) * (0.4 + Math.min(1.0, w.engagement));
            out.add(new Seed(w.id, w.type, w.name, weight));
        }
        Collections.sort(out, (a, b) -> Double.compare(b.weight, a.weight));
        return out.size() > MAX_SEEDS ? new ArrayList<>(out.subList(0, MAX_SEEDS)) : out;
    }

    /**
     * How alike a candidate is to a seed: shared genres matter most, then a shared director or writer, then
     * shared cast; a good rating helps a little. Anime and non-anime never match. Below 0: not alike at all.
     */
    static double score(Title seed, Title c) {
        if (seed.anime() != c.anime()) return -1;
        double genre = jaccard(seed.genres, c.genres);
        if (genre == 0) return -1;
        double s = 3.0 * genre;
        s += Math.min(3.0, 1.5 * shared(seed.makers, c.makers));
        s += Math.min(1.8, 0.6 * shared(seed.cast, c.cast));
        if (c.rating > 0) s += (c.rating - 7.0) * 0.35;
        if (seed.anime()) s += 1.0;
        return s;
    }

    /**
     * Scores each seed's candidates against it, best first, leaving out anything watched (exclude) and the
     * seeds themselves.
     */
    static List<Pick> rank(Title seed, String seedName, List<Title> candidates, Set<String> exclude) {
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Title c : candidates) {
            if (c.id.equals(seed.id) || exclude.contains(c.id) || !seen.add(c.id)) continue;
            double s = score(seed, c);
            if (s >= MIN_SCORE) out.add(new Pick(c, seedName, s));
        }
        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    /**
     * One row from the per-seed lists: in turns, heaviest seed first, each seed adds its best title not
     * already in the row, so every recent watch gets a say and no title shows twice.
     */
    static List<Pick> merge(List<List<Pick>> perSeed, int max) {
        Map<String, Pick> row = new LinkedHashMap<>();
        int[] next = new int[perSeed.size()];
        boolean added = true;
        while (row.size() < max && added) {
            added = false;
            for (int s = 0; s < perSeed.size() && row.size() < max; s++) {
                List<Pick> list = perSeed.get(s);
                while (next[s] < list.size()) {
                    Pick p = list.get(next[s]++);
                    if (row.containsKey(p.title.id)) continue;
                    row.put(p.title.id, p);
                    added = true;
                    break;
                }
            }
        }
        return new ArrayList<>(row.values());
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int both = shared(a, b);
        return both / (double) (a.size() + b.size() - both);
    }

    private static int shared(Set<String> a, Set<String> b) {
        int n = 0;
        for (String x : a) if (b.contains(x)) n++;
        return n;
    }

    private static Set<String> lower(Collection<String> in) {
        Set<String> out = new HashSet<>();
        if (in == null) return out;
        for (String s : in) if (s != null && !s.trim().isEmpty()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out;
    }
}
