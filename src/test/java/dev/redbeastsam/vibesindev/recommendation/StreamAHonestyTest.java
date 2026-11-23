package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.redbeastsam.vibesindev.VibesInDevPlugin;
import dev.redbeastsam.vibesindev.cache.TrackCache;
import dev.redbeastsam.vibesindev.model.Track;
import dev.redbeastsam.vibesindev.spotify.SpotifyClient;
import dev.redbeastsam.vibesindev.spotify.SpotifyHttp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
public class StreamAHonestyTest {
  private VibesInDevPlugin mockPlugin;
  private TrackCache mockCache;
  private SpotifyClient mockSpotify;
  private FileConfiguration mockConfig;
  private RecommendationEngine engine;
  private PlayerTasteProfile profile;

  @BeforeEach
  public void setUp() {
    SpotifyClient.clearKnownArtistIds();
    mockPlugin = mock(VibesInDevPlugin.class);
    mockCache = mock(TrackCache.class);
    mockSpotify = mock(SpotifyClient.class);
    mockConfig = mock(FileConfiguration.class);

    when(mockPlugin.cache()).thenReturn(mockCache);
    when(mockPlugin.spotify()).thenReturn(mockSpotify);
    when(mockPlugin.getConfig()).thenReturn(mockConfig);
    when(mockSpotify.isConfigured()).thenReturn(true);
    when(mockSpotify.isFeatureAvailable(any())).thenReturn(true);

    engine = new RecommendationEngine(mockPlugin);
    profile = new PlayerTasteProfile(UUID.randomUUID());
  }

  @AfterEach
  public void tearDown() {
    SpotifyClient.clearKnownArtistIds();
  }

  @Test
  public void testStreamACandidateReasonAndLocalCatalogOnlyWhenNoSpotifyId() {
    Track seed = new Track("seed_1", "Get Lucky", "Daft Punk", 240, "url0");
    Track local1 = new Track("local_1", "One More Time", "Daft Punk", 320, "url1");

    when(mockCache.getTracksByArtist("Daft Punk", 3)).thenReturn(List.of(local1));
    when(mockSpotify.getKnownArtistId("Daft Punk")).thenReturn(null);

    List<RecommendationCandidate> candidates = engine.fetchSpotifyCandidates(seed, profile).join();

    assertEquals(1, candidates.size());
    RecommendationCandidate cand = candidates.get(0);
    assertEquals(RecommendationCandidate.Source.SPOTIFY_ARTIST_TOP, cand.source());
    assertEquals("local catalog + Spotify (if available): Daft Punk", cand.reason());
    assertEquals("local_1", cand.track().id());
  }

  @Test
  public void testSpotifyArtistTopTracksQueriedWhenArtistIdKnown() {
    Track seed = new Track("seed_1", "Get Lucky", "Daft Punk", 240, "url0");
    Track local1 = new Track("local_1", "One More Time", "Daft Punk", 320, "url1");
    Track spotify1 = new Track("sp_1", "Around the World", "Daft Punk", 230, "url2");

    when(mockSpotify.getKnownArtistId("Daft Punk")).thenReturn("daft_punk_spotify_id");
    when(mockCache.getTracksByArtist("Daft Punk", 3)).thenReturn(List.of(local1));
    when(mockSpotify.getArtistTopTracks("daft_punk_spotify_id"))
        .thenReturn(CompletableFuture.completedFuture(List.of(spotify1)));

    List<RecommendationCandidate> candidates = engine.fetchSpotifyCandidates(seed, profile).join();

    assertEquals(2, candidates.size());
    assertTrue(candidates.stream().anyMatch(c -> c.track().id().equals("local_1")));
    assertTrue(candidates.stream().anyMatch(c -> c.track().id().equals("sp_1")));
    for (RecommendationCandidate c : candidates) {
      assertEquals("local catalog + Spotify (if available): Daft Punk", c.reason());
    }
  }

  @Test
  public void testDeduplicationBetweenLocalAndSpotify() {
    Track seed = new Track("seed_1", "Get Lucky", "Daft Punk", 240, "url0");
    Track local1 = new Track("local_1", "One More Time", "Daft Punk", 320, "url1");
    // Same recording returned by Spotify
    Track spotifyDuplicate =
        new Track(
            "sp_1",
            "One More Time",
            "Daft Punk",
            320,
            "spotify-lazy:Daft Punk - One More Time::320");

    when(mockSpotify.getKnownArtistId("Daft Punk")).thenReturn("daft_punk_spotify_id");
    when(mockCache.getTracksByArtist("Daft Punk", 3)).thenReturn(List.of(local1));
    when(mockSpotify.getArtistTopTracks("daft_punk_spotify_id"))
        .thenReturn(CompletableFuture.completedFuture(List.of(spotifyDuplicate)));

    List<RecommendationCandidate> candidates = engine.fetchSpotifyCandidates(seed, profile).join();

    assertEquals(
        1,
        candidates.size(),
        "Duplicate recording across local catalog and Spotify top tracks must be deduplicated");
    assertEquals("local_1", candidates.get(0).track().id());
  }

  @Test
  public void testSpotifyArtistTopTracksSkippedWhenFeatureDisabled() {
    Track seed = new Track("seed_1", "Get Lucky", "Daft Punk", 240, "url0");
    Track local1 = new Track("local_1", "One More Time", "Daft Punk", 320, "url1");

    when(mockSpotify.getKnownArtistId("Daft Punk")).thenReturn("daft_punk_spotify_id");
    when(mockSpotify.isFeatureAvailable(SpotifyHttp.Feature.ARTIST_TOP_TRACKS)).thenReturn(false);
    when(mockCache.getTracksByArtist("Daft Punk", 3)).thenReturn(List.of(local1));

    List<RecommendationCandidate> candidates = engine.fetchSpotifyCandidates(seed, profile).join();

    assertEquals(1, candidates.size());
    assertEquals("local_1", candidates.get(0).track().id());
  }
}
