package dev.vibesindev.vibesmusic.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.vibesindev.vibesmusic.model.Track;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class ColdStartTest {
  @Test
  @DisplayName(
      "Task 4.4: PlayerTasteProfile.isColdStart is true only for <5 plays and no Spotify link")
  void testIsColdStart() {
    UUID uuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(uuid);

    // 1. Fresh profile has 0 plays, no Spotify link -> isColdStart = true
    assertTrue(profile.isColdStart());
    assertEquals(0, profile.getTotalPlays());

    // 2. Play 4 tracks -> still cold start
    for (int i = 1; i <= 4; i++) {
      profile.recordStart(new Track("t" + i, "Title " + i, "Artist", 200, "url"));
      assertTrue(profile.isColdStart(), "Should be cold start with " + i + " plays");
    }
    assertEquals(4, profile.getTotalPlays());

    // 3. Play 5th track -> no longer cold start!
    profile.recordStart(new Track("t5", "Title 5", "Artist", 200, "url"));
    assertEquals(5, profile.getTotalPlays());
    assertFalse(profile.isColdStart(), "5 plays is no longer cold start");

    // 4. Reset profile, but ingest real Spotify micro-genres -> no longer cold start even with 0
    // plays
    PlayerTasteProfile spotifyProfile = new PlayerTasteProfile(UUID.randomUUID());
    assertTrue(spotifyProfile.isColdStart());
    spotifyProfile.ingestSpotifyArtistGenres(List.of("pop", "dance pop"), 1.0);
    assertFalse(spotifyProfile.isColdStart(), "Profile with Spotify genres is not cold start");
  }

  @Test
  @DisplayName("Task 4.4: Cold start hint is triggered at most once per session")
  void testColdStartHintOncePerSession() {
    RecommendationManager manager = new RecommendationManager(null);
    UUID playerUuid = UUID.randomUUID();
    PlayerTasteProfile profile = new PlayerTasteProfile(playerUuid);

    // Before any hint
    assertFalse(manager.hasSentColdStartHint(playerUuid));

    // First check
    manager.checkAndSendColdStartHint(playerUuid, profile);
    assertTrue(manager.hasSentColdStartHint(playerUuid));

    // Subsequent check in same session does not re-add or re-hint (idempotent)
    manager.checkAndSendColdStartHint(playerUuid, profile);
    assertTrue(manager.hasSentColdStartHint(playerUuid));

    // On quit, session hint is cleared
    manager.onPlayerQuit(playerUuid);
    assertFalse(manager.hasSentColdStartHint(playerUuid));
  }
}
