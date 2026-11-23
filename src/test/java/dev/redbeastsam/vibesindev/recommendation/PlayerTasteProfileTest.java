package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.redbeastsam.vibesindev.model.Track;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"deprecation", "null"})
public class PlayerTasteProfileTest {
  private PlayerTasteProfile profile;
  private UUID playerUuid;

  @BeforeEach
  public void setUp() {
    playerUuid = UUID.randomUUID();
    profile = new PlayerTasteProfile(playerUuid);
  }

  @Test
  public void testSpotifyArtistIngestionAndAffinity() {
    List<String> shortTerm = List.of("Daft Punk", "The Weeknd");

    List<String> mediumTerm = List.of("The Weeknd", "Kavinsky");

    profile.ingestSpotifyArtists(shortTerm, mediumTerm);

    // Daft Punk was short term rank 1 -> high affinity

    assertTrue(profile.getArtistAffinity("Daft Punk") > 0.5);

    // The Weeknd was in both -> high affinity

    assertTrue(profile.getArtistAffinity("The Weeknd") > 0.5);

    // Unknown artist -> 0 affinity

    assertEquals(0.0, profile.getArtistAffinity("NonExistentArtist"));
  }

  @Test
  public void testArtistMatchingTier2AndSubstringFalsePositivePrevention() {
    profile.ingestSpotifyArtists(List.of("Daft Punk", "Sia"), null);

    // Exact match
    double daftPunkAffinity = profile.getArtistAffinity("Daft Punk");
    assertTrue(daftPunkAffinity > 0.0);

    // Featuring / collaboration match inherits 0.8x
    double collabAffinity = profile.getArtistAffinity("Daft Punk feat. Pharrell");
    assertEquals(daftPunkAffinity * 0.8, collabAffinity, 0.001);

    // "Asia" contains "sia" as a substring, but must NOT match Sia (fixes F1)
    assertEquals(0.0, profile.getArtistAffinity("Asia"));
  }

  @Test
  public void testFatigueBlacklist() {
    Track t1 = new Track("track_1", "Song 1", "Artist 1", 200, "url1");

    Track t2 = new Track("track_2", "Song 2", "Artist 2", 200, "url2");

    profile.recordPlay(t1);

    // Played just now -> must be fatigued

    assertTrue(profile.isFatigued("track_1", 20, 45 * 60_000L));

    // Not played -> not fatigued

    assertFalse(profile.isFatigued(t2.id(), 20, 45 * 60_000L));
  }

  @Test
  public void testRecentArtistSoftDecay() {
    Track t1 = new Track("t1", "Song 1", "Daft Punk", 180, "url1");

    Track t2 = new Track("t2", "Song 2", "Justice", 180, "url2");

    profile.recordPlay(t1);

    profile.recordPlay(t2);

    // Both Daft Punk and Justice are in the last 2 tracks

    assertTrue(profile.isRecentArtist("Justice"));

    assertTrue(profile.isRecentArtist("Daft Punk"));

    // Play 4 more tracks by other artists to evict Daft Punk from the 4-track window

    profile.recordPlay(new Track("t3", "Song 3", "Deadmau5", 180, "url3"));

    profile.recordPlay(new Track("t4", "Song 4", "Skrillex", 180, "url4"));

    profile.recordPlay(new Track("t5", "Song 5", "Kavinsky", 180, "url5"));

    profile.recordPlay(new Track("t6", "Song 6", "Gesaffelstein", 180, "url6"));

    // Daft Punk is no longer in the last 4

    assertFalse(profile.isRecentArtist("Daft Punk"));

    // Gesaffelstein is now in the last 4

    assertTrue(profile.isRecentArtist("Gesaffelstein"));
  }

  @Test
  public void testFeedbackLikeAndDislike() {
    Track t = new Track("track_fb", "Favorite Song", "Avicii", 200, "url_fb");

    // Initial like boosts affinity

    profile.recordLike(t);

    double likedAffinity = profile.getArtistAffinity("Avicii");

    assertTrue(likedAffinity >= 0.25, "Liked artist should have affinity >= 0.25");

    assertFalse(profile.isDisliked(t.id()), "Track should not be marked disliked");

    // Disliking track downweights artist affinity by 50% and sets 7-day soft ban

    profile.recordDislike(t);

    double dislikedAffinity = profile.getArtistAffinity("Avicii");

    assertTrue(dislikedAffinity < likedAffinity, "Disliked artist affinity must decrease");

    assertTrue(profile.isDisliked(t.id()), "Track should be marked disliked");

    // isFatigued should now return true even if never played

    assertTrue(
        profile.isFatigued(t.id(), 20, 45 * 60_000L), "Disliked track must be considered fatigued");

    // Liking again clears the dislike

    profile.recordLike(t);

    assertFalse(profile.isDisliked(t.id()), "Liking track must clear dislike soft-ban");
  }

  @Test
  public void testSkipDownweight() {
    Track t = new Track("track_skip", "Skipped Song", "Calvin Harris", 210, "url_skip");

    profile.ingestSpotifyArtists(List.of("Calvin Harris"), null);

    double initial = profile.getArtistAffinity("Calvin Harris");

    // 1st early skip: artist affinity is unchanged (only track cooldown)
    profile.recordSkip(t);
    assertEquals(initial, profile.getArtistAffinity("Calvin Harris"), 0.001);

    // 2nd early skip within 24h: artist is downweighted
    Track t2 = new Track("track_skip2", "Skipped Song 2", "Calvin Harris", 210, "url_skip2");
    profile.recordPlay(t2);
    profile.recordSkip(t2);
    double afterSkip = profile.getArtistAffinity("Calvin Harris");
    assertTrue(
        afterSkip < initial, "Second skip of artist within 24h must downweight artist affinity");
  }

  @Test
  public void testSpotifyBlendPartyCentroid() {
    PlayerTasteProfile p1 = new PlayerTasteProfile(UUID.randomUUID());

    p1.ingestSpotifyArtists(List.of("Daft Punk"), null);

    PlayerTasteProfile p2 = new PlayerTasteProfile(UUID.randomUUID());

    p2.ingestSpotifyArtists(List.of("Skrillex"), null);

    Track dislikedTrack = new Track("disliked_by_p2", "Bad Song", "Artist X", 180, "urlX");

    p2.recordDislike(dislikedTrack);

    PlayerTasteProfile blend = PlayerTasteProfile.blend(List.of(p1, p2));

    // Both artists should be present in the blended centroid profile

    assertTrue(blend.getArtistAffinity("Daft Punk") > 0.0);

    assertTrue(blend.getArtistAffinity("Skrillex") > 0.0);

    // Blended party profile inherits dislike from member

    assertTrue(
        blend.isDisliked(dislikedTrack.id()),
        "Party blend should inherit member's disliked tracks");
  }

  @Test
  public void testArtistLineageBandToSolo() {
    // Ingest One Direction into the taste profile
    profile.ingestSpotifyArtists(List.of("One Direction"), Collections.emptyList());

    double oneDirectionAffinity = profile.getArtistAffinity("One Direction");
    assertTrue(oneDirectionAffinity > 0.0, "One Direction should have positive affinity");

    // Harry Styles and Zayn should automatically inherit 0.75x lineage affinity!
    double harryStylesAffinity = profile.getArtistAffinity("Harry Styles");
    assertTrue(harryStylesAffinity > 0.0, "Harry Styles should inherit lineage affinity");
    assertEquals(oneDirectionAffinity * 0.75, harryStylesAffinity, 0.001);

    double zaynAffinity = profile.getArtistAffinity("Zayn");
    assertTrue(zaynAffinity > 0.0, "Zayn should inherit lineage affinity");
    assertEquals(oneDirectionAffinity * 0.75, zaynAffinity, 0.001);
  }

  @Test
  public void testArtistLineageSoloToBand() {
    // Ingest solo member: Paul McCartney
    profile.ingestSpotifyArtists(List.of("Paul McCartney"), Collections.emptyList());

    double paulAffinity = profile.getArtistAffinity("Paul McCartney");
    assertTrue(paulAffinity > 0.0);

    // The Beatles should inherit lineage affinity
    double beatlesAffinity = profile.getArtistAffinity("The Beatles");
    assertTrue(beatlesAffinity > 0.0);
    assertEquals(paulAffinity * 0.75, beatlesAffinity, 0.001);

    // Bandmate John Lennon should also inherit lineage affinity
    double johnAffinity = profile.getArtistAffinity("John Lennon");
    assertTrue(johnAffinity > 0.0);
    assertEquals(paulAffinity * 0.75, johnAffinity, 0.001);
  }

  @Test
  public void testDynamicLineageRegistration() {
    profile.ingestSpotifyArtists(List.of("Radiohead"), Collections.emptyList());
    double radioheadAffinity = profile.getArtistAffinity("Radiohead");

    // Before registering lineage, The Smile has 0 affinity
    assertEquals(0.0, profile.getArtistAffinity("The Smile"));

    // Dynamically register lineage (Thom Yorke / Jonny Greenwood side project)
    profile.registerLineage("Radiohead", "The Smile");

    double smileAffinity = profile.getArtistAffinity("The Smile");
    assertTrue(smileAffinity > 0.0);
    assertEquals(radioheadAffinity * 0.75, smileAffinity, 0.001);
  }

  @Test
  public void testProfileSnapshotExportAndRestore() {
    // Setup initial profile with rich data
    profile.ingestSpotifyArtists(List.of("Daft Punk", "The Weeknd"), List.of("Justice"));
    profile.ingestArtistGenres(List.of("french house", "synthwave", "electronic"));
    profile.registerLineage("Daft Punk", "Thomas Bangalter");

    Track dislikedTrack = new Track("disliked_1", "Bad Track", "Unknown", 180, "url");
    profile.recordDislike(dislikedTrack);

    // Export data to JSON
    String affJson = profile.exportAffinitiesJson();
    String genJson = profile.exportGenresJson();
    String disJson = profile.exportDislikesJson();
    String linJson = profile.exportLineageJson();

    com.google.gson.Gson gson = new com.google.gson.Gson();
    java.lang.reflect.Type mapDouble =
        new com.google.gson.reflect.TypeToken<java.util.Map<String, Double>>() {}.getType();
    java.lang.reflect.Type mapLong =
        new com.google.gson.reflect.TypeToken<java.util.Map<String, Long>>() {}.getType();
    java.lang.reflect.Type mapSet =
        new com.google.gson.reflect.TypeToken<
            java.util.Map<String, java.util.Set<String>>>() {}.getType();

    java.util.Map<String, Double> aff = gson.fromJson(affJson, mapDouble);
    java.util.Map<String, Double> gen = gson.fromJson(genJson, mapDouble);
    java.util.Map<String, Long> dis = gson.fromJson(disJson, mapLong);
    java.util.Map<String, java.util.Set<String>> lin = gson.fromJson(linJson, mapSet);

    // Restore into a completely fresh profile (simulating server restart)
    PlayerTasteProfile restored = new PlayerTasteProfile(playerUuid);
    restored.restoreFromSnapshot(aff, gen, dis, lin, System.currentTimeMillis());

    // Assert that all learned data is accurately restored
    assertTrue(restored.getArtistAffinity("Daft Punk") > 0.4);
    assertTrue(restored.getArtistAffinity("Justice") > 0.0);
    assertTrue(
        restored.getArtistAffinity("Thomas Bangalter") > 0.0,
        "Dynamic lineage must persist across restart");
    assertTrue(restored.getGenreDistribution().containsKey("french house"));
    assertTrue(
        restored.isFatigued("disliked_1", 20, 45 * 60_000L),
        "Dislike 7-day cooldown must persist across restart");
  }

  @Test
  public void testFatigueHistoryRestore() {
    PlayerTasteProfile fresh = new PlayerTasteProfile(playerUuid);
    assertFalse(fresh.isFatigued("track_fatigued", 20, 45 * 60_000L));

    // Restore fatigue from SQLite entries
    PlayerTasteProfile.FatigueEntry entry =
        new PlayerTasteProfile.FatigueEntry(
            "track_fatigued", "Artist", "Fatigued Title", System.currentTimeMillis());
    fresh.restoreFatigue(List.of(entry));

    assertTrue(
        fresh.isFatigued("track_fatigued", 20, 45 * 60_000L),
        "Restored fatigue entry must be recognized");
  }

  @Test
  public void testCanonicalTitleMatchingAndRemixDeduplication() {
    Track original = new Track("id_1", "Neoperreo", "Secret Society, ALIC3E", 210, "url1");
    Track single2025 =
        new Track(
            "id_2",
            "Secret Society - Neoperreo feat. ALIC3E (Single 2025)",
            "Secret Society",
            210,
            "url2");
    Track slowed = new Track("id_3", "Neoperreo (Slowed)", "Secret Society", 240, "url3");
    Track slowedReverb = new Track("id_4", "Neoperreo - Slowed + Reverb", "ALIC3E", 240, "url4");
    Track differentSong = new Track("id_5", "Another Song", "Secret Society", 180, "url5");

    // Assert isSameSongOrRemix detects all variants as duplicate of original
    assertTrue(
        PlayerTasteProfile.isSameSongOrRemix(original, single2025),
        "Single version should match original");
    assertTrue(
        PlayerTasteProfile.isSameSongOrRemix(original, slowed),
        "Slowed version should match original");
    assertTrue(
        PlayerTasteProfile.isSameSongOrRemix(slowed, slowedReverb),
        "Slowed and Slowed+Reverb should match");
    assertFalse(
        PlayerTasteProfile.isSameSongOrRemix(original, differentSong),
        "Different song must not match");

    // When original is played, any variant with a DIFFERENT trackId MUST be fatigued
    profile.recordPlay(original);

    assertTrue(
        profile.isFatigued(slowed, 20, 45 * 60_000L),
        "Slowed variant must be fatigued by title match");
    assertTrue(
        profile.isFatigued(single2025, 20, 45 * 60_000L),
        "Single variant must be fatigued by title match");
    assertFalse(
        profile.isFatigued(differentSong, 20, 45 * 60_000L), "Different song must not be fatigued");
  }

  @Test
  public void testSkippedTrackEntersFatigue() {
    Track trackToSkip = new Track("skip_id", "Annoying Song", "Artist X", 180, "url");
    assertFalse(profile.isFatigued(trackToSkip, 20, 45 * 60_000L));

    // Skip the track
    profile.recordSkip(trackToSkip);

    // Skipped track must now be fatigued so autoplay cannot recommend it
    assertTrue(
        profile.isFatigued(trackToSkip, 20, 45 * 60_000L),
        "Skipped track must immediately enter fatigue");
  }
}
