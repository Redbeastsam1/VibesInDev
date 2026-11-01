package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RecommendationCandidateTest {

    @Test
    public void testCandidateSorting() {
        Track t1 = new Track("1", "Low Score", "Artist", 180, "url1");
        Track t2 = new Track("2", "High Score", "Artist", 180, "url2");
        Track t3 = new Track("3", "Mid Score", "Artist", 180, "url3");

        RecommendationCandidate c1 = new RecommendationCandidate(t1, 0.35, RecommendationCandidate.Source.YOUTUBE_RADIO, "reason", false);
        RecommendationCandidate c2 = new RecommendationCandidate(t2, 0.95, RecommendationCandidate.Source.SPOTIFY_ARTIST_TOP, "reason", true);
        RecommendationCandidate c3 = new RecommendationCandidate(t3, 0.65, RecommendationCandidate.Source.SERVER_COLLABORATIVE, "reason", false);

        List<RecommendationCandidate> list = new ArrayList<>(List.of(c1, c2, c3));
        Collections.sort(list);

        // Descending order by score
        assertEquals("2", list.get(0).track().id());
        assertEquals("3", list.get(1).track().id());
        assertEquals("1", list.get(2).track().id());
    }

    @Test
    public void testCandidateRecordFields() {
        Track t = new Track("id", "Title", "Artist", 210, "url");
        RecommendationCandidate c = new RecommendationCandidate(t, 0.88, RecommendationCandidate.Source.SPOTIFY_GENRE, "Micro-genre tag", true);

        assertEquals(t, c.track());
        assertEquals(0.88, c.score(), 0.001);
        assertEquals(RecommendationCandidate.Source.SPOTIFY_GENRE, c.source());
        assertEquals("Micro-genre tag", c.reason());
        assertTrue(c.cachedLocally());
    }
}

