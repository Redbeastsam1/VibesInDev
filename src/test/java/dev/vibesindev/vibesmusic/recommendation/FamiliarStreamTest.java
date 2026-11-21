package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.cache.TrackCache;
import dev.vibesindev.vibesmusic.model.Track;
import dev.vibesindev.vibesmusic.playlist.MusicPlaylistManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class FamiliarStreamTest {
  private VibesInDevPlugin mockPlugin;
  private TrackCache mockCache;
  private MusicPlaylistManager mockPlaylists;
  private FileConfiguration mockConfig;
  private RecommendationEngine engine;
  private UUID playerUuid;
  private PlayerTasteProfile profile;

  @BeforeEach
  public void setUp() {
    mockPlugin = Mockito.mock(VibesInDevPlugin.class);
    mockCache = Mockito.mock(TrackCache.class);
    mockPlaylists = Mockito.mock(MusicPlaylistManager.class);
    mockConfig = Mockito.mock(FileConfiguration.class);

    when(mockPlugin.cache()).thenReturn(mockCache);
    when(mockPlugin.playlists()).thenReturn(mockPlaylists);
    when(mockPlugin.getConfig()).thenReturn(mockConfig);
    when(mockConfig.getInt("recommendations.cooldown-tracks", 20)).thenReturn(20);
    when(mockConfig.getLong("recommendations.cooldown-minutes", 45)).thenReturn(45L);

    engine = new RecommendationEngine(mockPlugin);
    playerUuid = UUID.randomUUID();
    profile = new PlayerTasteProfile(playerUuid);
  }

  @Test
  public void testFamiliarPullsFromPlaylistsLikesHistoryAndSpotify() {
    Track playlistTrack = new Track("pl_1", "Playlist Song", "Artist 1", 200, "url1");
    Track likedTrack = new Track("liked_1", "Liked Song", "Artist 2", 210, "url2");
    Track topPlayedTrack = new Track("top_1", "Top Played Song", "Artist 3", 220, "url3");
    Track spotifyTrack =
        new Track(
            "sp_1", "Spotify Fav", "Artist 4", 230, "spotify-lazy:Artist 4 - Spotify Fav::230");

    MusicPlaylistManager.CustomPlaylist customPl =
        new MusicPlaylistManager.CustomPlaylist(
            "My Favorites", System.currentTimeMillis(), List.of(playlistTrack));
    when(mockPlaylists.getPlaylists(playerUuid)).thenReturn(Map.of("fav", customPl));
    when(mockCache.getLikedTracksForPlayer(eq(playerUuid), anyInt()))
        .thenReturn(List.of(likedTrack));
    when(mockCache.getTopPlayedTracksForPlayer(eq(playerUuid), anyInt(), anyLong()))
        .thenReturn(List.of(topPlayedTrack));
    when(mockCache.getPlayerSpotifyTracks(eq(playerUuid), anyInt()))
        .thenReturn(List.of(spotifyTrack));

    Track seed = new Track("seed_1", "Current Playing", "Different Artist", 200, "url0");

    List<RecommendationCandidate> candidates = engine.fetchFamiliarCandidates(seed, profile).join();

    assertEquals(4, candidates.size());
    for (RecommendationCandidate c : candidates) {
      assertEquals(RecommendationCandidate.Source.FAMILIAR, c.source());
      assertEquals("Familiar library track", c.reason());
    }
  }

  @Test
  public void testFamiliarFatigueAndSeedRejection() {
    Track seed = new Track("seed_1", "Playing Now", "Artist 1", 200, "url0");
    Track seedRemix = new Track("remix", "Playing Now (Slowed)", "Artist 1", 240, "url1");
    Track fatiguedTrack = new Track("fatigued", "Heard 5m Ago", "Artist 2", 180, "url2");
    Track dislikedTrack = new Track("disliked", "Bad Song", "Artist 3", 190, "url3");
    Track validTrack = new Track("valid", "Good Library Song", "Artist 4", 210, "url4");

    profile.recordStart(fatiguedTrack);
    profile.recordDislike(dislikedTrack);

    when(mockCache.getLikedTracksForPlayer(eq(playerUuid), anyInt()))
        .thenReturn(List.of(seedRemix, fatiguedTrack, dislikedTrack, validTrack));

    List<RecommendationCandidate> candidates = engine.fetchFamiliarCandidates(seed, profile).join();

    assertEquals(
        1,
        candidates.size(),
        "Only valid non-seed, non-fatigued, non-disliked tracks should be returned");
    assertEquals("valid", candidates.get(0).track().id());
  }

  @Test
  public void testFamiliarDeduplicationAcrossSources() {
    Track fromPlaylist = new Track("t1", "Same Song", "Same Artist", 200, "url1");
    Track fromLiked = new Track("t2", "Same Song", "Same Artist", 200, "url2");
    Track fromSpotify =
        new Track(
            "sp_same",
            "Same Song",
            "Same Artist",
            200,
            "spotify-lazy:Same Artist - Same Song::200");

    MusicPlaylistManager.CustomPlaylist customPl =
        new MusicPlaylistManager.CustomPlaylist(
            "Pl", System.currentTimeMillis(), List.of(fromPlaylist));
    when(mockPlaylists.getPlaylists(playerUuid)).thenReturn(Map.of("pl", customPl));
    when(mockCache.getLikedTracksForPlayer(eq(playerUuid), anyInt()))
        .thenReturn(List.of(fromLiked));
    when(mockCache.getPlayerSpotifyTracks(eq(playerUuid), anyInt()))
        .thenReturn(List.of(fromSpotify));

    Track seed = new Track("seed_1", "Seed", "Other Artist", 200, "url0");

    List<RecommendationCandidate> candidates = engine.fetchFamiliarCandidates(seed, profile).join();

    assertEquals(
        1,
        candidates.size(),
        "Identical song across playlists, likes, and Spotify must be deduplicated to 1 candidate");
  }

  @Test
  public void testFamiliarPartyPooling() {
    UUID member1 = UUID.randomUUID();
    UUID member2 = UUID.randomUUID();
    PlayerTasteProfile p1 = new PlayerTasteProfile(member1);
    PlayerTasteProfile p2 = new PlayerTasteProfile(member2);
    PlayerTasteProfile partyBlend = PlayerTasteProfile.blend(List.of(p1, p2));

    Track track1 = new Track("m1_t", "Song One", "Artist 1", 200, "url1");
    Track track2 = new Track("m2_t", "Song Two", "Artist 2", 210, "url2");

    when(mockCache.getLikedTracksForPlayer(eq(member1), anyInt())).thenReturn(List.of(track1));
    when(mockCache.getLikedTracksForPlayer(eq(member2), anyInt())).thenReturn(List.of(track2));

    Track seed = new Track("seed_1", "Seed", "Other Artist", 200, "url0");

    List<RecommendationCandidate> candidates =
        engine.fetchFamiliarCandidates(seed, partyBlend).join();

    assertEquals(2, candidates.size());
    assertTrue(candidates.stream().anyMatch(c -> c.track().id().equals("m1_t")));
    assertTrue(candidates.stream().anyMatch(c -> c.track().id().equals("m2_t")));
  }

  @Test
  public void testFamiliarCapAt15() {
    List<Track> thirtyTracks = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      thirtyTracks.add(
          new Track("t_" + i, "Unique Title " + i, "Unique Artist " + i, 200, "url" + i));
    }

    when(mockCache.getLikedTracksForPlayer(eq(playerUuid), anyInt())).thenReturn(thirtyTracks);

    Track seed = new Track("seed_1", "Seed", "Other Artist", 200, "url0");

    List<RecommendationCandidate> candidates = engine.fetchFamiliarCandidates(seed, profile).join();

    assertEquals(15, candidates.size(), "Stream E candidates must be capped at 15");
  }
}
