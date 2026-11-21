package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

public class CandidateFilterTest {
  @Test
  public void testBlacklistedTitlesRejected() {
    List<String> junkTitles =
        List.of(
            "10 Hours Rain and Thunder Loop",
            "1 hour study music",
            "Pink Floyd - The Wall (Full Album)",
            "Best 80s Rock Compilation",
            "Summer Vibes 2025 Playlist",
            "Rap Mix 2024",
            "Vocal Coach Reaction to Dimash",
            "How to play Wonderwall on Guitar (Tutorial)",
            "Easy Drum Lesson for Beginners",
            "How to make beats in FL Studio",
            "Taylor Swift - Shake It Off (Karaoke Version)",
            "Lofi Hip Hop Radio 24/7 Loop",
            "Nonstop 90s Dance Hits",
            "Golden Oldies Jukebox",
            "Top 50 Billboard Songs of All Time",
            "Top 10 Guitar Solos");

    for (String junkTitle : junkTitles) {
      assertTrue(
          CandidateFilter.matchesBlacklist(junkTitle),
          "Must blacklist junk/compilation: " + junkTitle);
      assertTrue(
          CandidateFilter.isHardRejected(
              junkTitle, 200, RecommendationCandidate.Source.YOUTUBE_RADIO, 60, 600));
    }
  }

  @Test
  public void testCleanMusicTitlesAccepted() {
    List<String> cleanTitles =
        List.of(
            "Blinding Lights",
            "Get Lucky",
            "Levitating",
            "Bohemian Rhapsody",
            "Lose Yourself",
            "Hotel California",
            "Bad Guy",
            "Shape of You",
            "Starboy",
            "Anti-Hero",
            "Flowers",
            "Stay",
            "Neoperreo",
            "Secret Society",
            "Midnight City");

    for (String cleanTitle : cleanTitles) {
      assertFalse(
          CandidateFilter.matchesBlacklist(cleanTitle),
          "Clean title must not be blacklisted: " + cleanTitle);
      assertFalse(
          CandidateFilter.isHardRejected(
              cleanTitle, 200, RecommendationCandidate.Source.YOUTUBE_RADIO, 60, 600));
    }
  }

  @Test
  public void testDurationGuardrails() {
    // Unknown (0) duration rejected for YouTube Radio
    assertTrue(
        CandidateFilter.isHardRejected(
            "Good Song", 0, RecommendationCandidate.Source.YOUTUBE_RADIO, 60, 600));

    // Unknown (0) duration allowed for Stream E (Familiar) and Stream A (Spotify artist catalog)
    assertFalse(
        CandidateFilter.isHardRejected(
            "Good Song", 0, RecommendationCandidate.Source.FAMILIAR, 60, 600));
    assertFalse(
        CandidateFilter.isHardRejected(
            "Good Song", 0, RecommendationCandidate.Source.SPOTIFY_ARTIST_TOP, 60, 600));

    // Outlier durations rejected across all sources
    assertTrue(
        CandidateFilter.isHardRejected(
            "Meme Clip", 45, RecommendationCandidate.Source.YOUTUBE_RADIO, 60, 600));
    assertTrue(
        CandidateFilter.isHardRejected(
            "20min Symphony", 1200, RecommendationCandidate.Source.YOUTUBE_RADIO, 60, 600));
  }

  @Test
  public void testLiveAndVersionSoftPenalty() {
    String seedTitle = "Neoperreo";
    String slowedCandidate = "Neoperreo (Slowed)";
    String liveCandidate = "Hotel California - Live 1976";
    String regularCandidate = "Levitating";

    // Slowed / Live candidate penalized when seed is original version
    double slowedQuality =
        CandidateFilter.evaluateQuality(slowedCandidate, seedTitle, "Artist", null);
    assertEquals(
        0.50,
        slowedQuality,
        1e-6,
        "Slowed version must receive 0.5x quality penalty when seed is regular");

    double liveQuality = CandidateFilter.evaluateQuality(liveCandidate, seedTitle, "Artist", null);
    assertEquals(
        0.50,
        liveQuality,
        1e-6,
        "Live version must receive 0.5x quality penalty when seed is regular");

    double regularQuality =
        CandidateFilter.evaluateQuality(regularCandidate, seedTitle, "Artist", null);
    assertEquals(1.00, regularQuality, 1e-6, "Regular studio track keeps full quality");

    // When seed is already live / slowed, soft penalty does not fire
    double sameLiveQuality =
        CandidateFilter.evaluateQuality(
            liveCandidate, "Another Song - Live Concert", "Artist", null);
    assertEquals(
        1.00, sameLiveQuality, 1e-6, "When seed is live, candidate live version is not penalized");
  }

  @Test
  public void testChannelTopicBoostAndReuploadPenalty() {
    double topicQuality = CandidateFilter.evaluateQuality("Track", "Seed", "Artist - Topic", null);
    assertEquals(1.0, topicQuality, 1e-6, "Topic channel should receive boost (clamped at 1.0)");

    List<String> reuploads = List.of("slowed", "lyrics", "audio edits");
    double reuploadQuality =
        CandidateFilter.evaluateQuality("Track", "Seed", "Chill Lyrics Hub", reuploads);
    assertEquals(0.70, reuploadQuality, 1e-6, "Reupload channel must receive -0.3 penalty");
  }
}
