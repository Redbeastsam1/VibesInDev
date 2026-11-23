package dev.redbeastsam.vibesindev.spotify;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class SpotifyClientTest {
  @Test
  public void testUserSessionRecord() {
    SpotifyClient.UserSession session =
        new SpotifyClient.UserSession("acc_token", "ref_token", 1234567890L);
    assertEquals("acc_token", session.accessToken());
    assertEquals("ref_token", session.refreshToken());
    assertEquals(1234567890L, session.expiresAt());
  }

  @Test
  public void testPendingStateRecord() {
    UUID player = UUID.randomUUID();
    long exp = System.currentTimeMillis() + 600_000L;
    SpotifyClient.PendingState state = new SpotifyClient.PendingState(player, exp);

    assertEquals(player, state.playerUuid());
    assertEquals(exp, state.expiresAt());
  }

  @Test
  public void testAuthCodeExtractionFromUrl() {
    String testUrl = "http://127.0.0.1:8888/callback?code=AQD123xyz_code&state=random_state_abc";
    Pattern pattern = Pattern.compile("[?&]code=([^&#\\s]+)");
    Matcher matcher = pattern.matcher(testUrl);

    assertTrue(matcher.find());
    assertEquals("AQD123xyz_code", matcher.group(1));

    // When code is passed directly without URL prefix
    String directCode = "AQD123xyz_code";
    assertFalse(pattern.matcher(directCode).find());
  }

  @Test
  public void testCsrfStateBindingWithPlayerUuid() {
    UUID playerUuid = UUID.randomUUID();
    String randomPart = "abc123randomToken";
    String state = randomPart + "_" + playerUuid;

    assertTrue(state.contains("_"));
    String[] parts = state.split("_", 2);
    assertEquals(randomPart, parts[0]);
    assertEquals(playerUuid.toString(), parts[1]);
    assertEquals(playerUuid, UUID.fromString(parts[1]));
  }
}
