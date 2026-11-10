package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class PicksTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_800_000_000_000L;

    private static Picks.Title title(String id, String country, double rating, List<String> genres,
                                     List<String> makers, List<String> cast) {
        return new Picks.Title(id, "series", "Name " + id, null, null, genres, makers, cast, country, rating, 2020);
    }

    private static Picks.Title title(String id, String country, String... genres) {
        return title(id, country, 7.0, Arrays.asList(genres), Collections.emptyList(), Collections.emptyList());
    }

    @Test public void animeOnlyMatchesAnime() {
        Picks.Title chainsaw = title("tt1", "Japan", "Animation", "Action", "Fantasy");
        Picks.Title jujutsu = title("tt2", "Japan, United States", "Animation", "Action", "Fantasy");
        Picks.Title simpsons = title("tt3", "United States", "Animation", "Comedy");
        assertTrue(Picks.score(chainsaw, jujutsu) > Picks.MIN_SCORE);
        assertTrue(Picks.score(chainsaw, simpsons) < 0);
        assertTrue(Picks.score(simpsons, chainsaw) < 0);
    }

    @Test public void noSharedGenreIsNoMatch() {
        assertTrue(Picks.score(title("tt1", "United States", "Drama"), title("tt2", "United States", "Comedy")) < 0);
    }

    @Test public void sharedWriterAndCastLiftAMatch() {
        Picks.Title seed = title("tt1", "United States", 8.8, Arrays.asList("Comedy", "Drama"),
                Arrays.asList("Jesse Armstrong"), Arrays.asList("Brian Cox"));
        Picks.Title plain = title("tt2", "United States", 7.0, Arrays.asList("Comedy", "Drama"),
                Collections.emptyList(), Collections.emptyList());
        Picks.Title sameWriter = title("tt3", "United States", 7.0, Arrays.asList("Comedy", "Drama"),
                Arrays.asList("jesse armstrong"), Arrays.asList("Brian Cox"));
        assertTrue(Picks.score(seed, sameWriter) > Picks.score(seed, plain) + 2);
    }

    @Test public void rankLeavesOutWatchedTitlesTheSeedAndDuplicates() {
        Picks.Title seed = title("tt1", "United States", "Crime", "Drama");
        List<Picks.Title> candidates = Arrays.asList(seed, title("tt2", "United States", "Crime", "Drama"),
                title("tt3", "United States", "Crime", "Drama"), title("tt2", "United States", "Crime", "Drama"));
        List<Picks.Pick> ranked = Picks.rank(seed, "Seed", candidates, new HashSet<>(Collections.singletonList("tt3")));
        assertEquals(1, ranked.size());
        assertEquals("tt2", ranked.get(0).title.id);
        assertEquals("Seed", ranked.get(0).because);
    }

    @Test public void mergeTakesTurnsAndNeverRepeats() {
        Picks.Title a = title("ttA", "US", "Drama"), b = title("ttB", "US", "Drama"), c = title("ttC", "US", "Drama");
        List<Picks.Pick> first = Arrays.asList(new Picks.Pick(a, "One", 5), new Picks.Pick(b, "One", 4));
        List<Picks.Pick> second = Arrays.asList(new Picks.Pick(a, "Two", 6), new Picks.Pick(c, "Two", 3));
        List<Picks.Pick> row = Picks.merge(Arrays.asList(first, second), 10);
        List<String> ids = new ArrayList<>();
        for (Picks.Pick p : row) ids.add(p.title.id);
        assertEquals(Arrays.asList("ttA", "ttC", "ttB"), ids);
        assertEquals("One", row.get(0).because);
    }

    @Test public void seedsPreferRecentWatchedTitlesAndSkipBrowsing() {
        List<LayoverFeed.Watched> history = Arrays.asList(
                new LayoverFeed.Watched("tt1", "series", "Recent", NOW - DAY, 0.6),
                new LayoverFeed.Watched("tt2", "movie", "Old", NOW - 90 * DAY, 1.0),
                new LayoverFeed.Watched("tt3", "series", "Opened only", NOW, 0.05),
                new LayoverFeed.Watched("tt4", "movie", "Too old", NOW - 300 * DAY, 1.0),
                new LayoverFeed.Watched("kitsu:9", "series", "Not IMDb", NOW, 1.0));
        List<Picks.Seed> seeds = Picks.seeds(history, NOW);
        assertEquals(2, seeds.size());
        assertEquals("tt1", seeds.get(0).id);
        assertEquals("tt2", seeds.get(1).id);
    }

    @Test public void atMostFourSeeds() {
        List<LayoverFeed.Watched> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) history.add(new LayoverFeed.Watched("tt" + i, "movie", "T" + i, NOW - i * DAY, 1.0));
        assertEquals(Picks.MAX_SEEDS, Picks.seeds(history, NOW).size());
    }
}
