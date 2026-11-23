package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ArtistNamesTest {
  @Test
  public void testDiacriticsAndNormalization() {
    assertEquals("beyonce", ArtistNames.normalize("Beyoncé"));
    assertEquals("motley crue", ArtistNames.normalize("Mötley Crüe"));
    assertEquals("bjork", ArtistNames.normalize("Björk"));
  }

  @Test
  public void testTrailingChannelSuffixes() {
    assertEquals("taylor swift", ArtistNames.normalize("Taylor Swift - Topic"));
    assertEquals("taylor swift", ArtistNames.normalize("Taylor Swift - topic"));
    assertEquals("taylor swift", ArtistNames.normalize("Taylor Swift VEVO"));
    assertEquals("artist", ArtistNames.normalize("ArtistVEVO"));
    assertEquals("coldplay", ArtistNames.normalize("Coldplay Official"));
    assertEquals("marshmello", ArtistNames.normalize("Marshmello Music"));
  }

  @Test
  public void testShortNameSubstringFalsePositivesPrevented() {
    // Sia vs Asia
    assertFalse(ArtistNames.sameArtist("Sia", "Asia"), "Sia must not match Asia");
    assertEquals(0.0, ArtistNames.exactOrCollab("Sia", "Asia"));

    // Eve vs Steve
    assertFalse(ArtistNames.sameArtist("Eve", "Steve"), "Eve must not match Steve");
    assertEquals(0.0, ArtistNames.exactOrCollab("Eve", "Steve"));

    // Ye vs Kanye West
    assertFalse(ArtistNames.sameArtist("Ye", "Kanye West"), "Ye must not match Kanye West");
    assertEquals(0.0, ArtistNames.exactOrCollab("Ye", "Kanye West"));
  }

  @Test
  public void testCollaborationsAndFeatures() {
    // Daft Punk with Pharrell
    assertTrue(ArtistNames.sameArtist("Daft Punk", "Daft Punk, Pharrell Williams"));
    assertEquals(
        0.8, ArtistNames.exactOrCollab("Daft Punk", "Daft Punk feat. Pharrell Williams"), 0.001);
    assertEquals(
        0.8, ArtistNames.exactOrCollab("Daft Punk", "Daft Punk & Pharrell Williams"), 0.001);
    assertEquals(
        0.8, ArtistNames.exactOrCollab("Daft Punk", "Daft Punk x Pharrell Williams"), 0.001);
    assertEquals(1.0, ArtistNames.exactOrCollab("Daft Punk", "daft punk"), 0.001);
  }

  @Test
  public void testProtectedNamesContainingSeparators() {
    // "Tyler, The Creator" contains a comma and must NOT be split into "Tyler"
    assertFalse(
        ArtistNames.sameArtist("Tyler, The Creator", "Tyler"),
        "Tyler, The Creator must not match standalone Tyler");
    assertTrue(ArtistNames.sameArtist("Tyler, The Creator", "tyler, the creator"));
    assertEquals(1.0, ArtistNames.exactOrCollab("Tyler, The Creator", "tyler, the creator"));

    // Simon & Garfunkel contains &
    assertTrue(ArtistNames.sameArtist("Simon & Garfunkel", "simon & garfunkel"));
    assertFalse(ArtistNames.sameArtist("Simon & Garfunkel", "Simon"));

    // Guns N' Roses
    assertTrue(ArtistNames.sameArtist("Guns N' Roses", "guns n' roses"));

    // AC/DC contains /
    assertTrue(ArtistNames.sameArtist("AC/DC", "ac/dc"));
  }

  @Test
  public void testProtectedNamesInCollaborations() {
    // Collaboration with protected artist: "Tyler, The Creator feat. A$AP Rocky"
    Set<String> tokens = ArtistNames.tokens("Tyler, The Creator feat. A$AP Rocky");
    assertTrue(
        tokens.contains("tyler, the creator"),
        "Protected artist name must be preserved intact in tokens");
    assertTrue(tokens.contains("a$ap rocky"));
    assertFalse(tokens.contains("the creator"), "Protected artist name must not be split");
  }

  @Test
  public void testConfigExtensibleProtectedNames() {
    ArtistNames.addProtectedNames(List.of("Custom, Band With Comma"));
    assertFalse(ArtistNames.sameArtist("Custom, Band With Comma", "Custom"));
    assertTrue(ArtistNames.sameArtist("Custom, Band With Comma", "custom, band with comma"));
  }
}
