package dev.redbeastsam.vibesindev.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import dev.redbeastsam.vibesindev.model.Track;
import org.junit.jupiter.api.Test;

public class RecordingIdentityTest {
  @Test
  public void testLoveVsLoveStoryDifferent() {
    Track love = new Track("t1", "Love", "Taylor Swift", 210, "url1");
    Track loveStory = new Track("t2", "Love Story", "Taylor Swift", 235, "url2");

    // F3 fix: "Love" must NOT match "Love Story" even with same artist
    assertFalse(
        RecordingIdentity.sameRecording(love, loveStory),
        "Love and Love Story are not the same recording");
    assertFalse(
        RecordingIdentity.relatedRecording(love, loveStory),
        "Love and Love Story are not related compositions");
  }

  @Test
  public void testOriginalVsSlowedReverbRelatedButNotSameRecording() {
    Track original = new Track("t1", "Blinding Lights", "The Weeknd", 200, "url1");
    Track slowed = new Track("t2", "Blinding Lights (Slowed + Reverb)", "The Weeknd", 230, "url2");

    // Same recording must be false (different versions/descriptors)
    assertFalse(
        RecordingIdentity.sameRecording(original, slowed),
        "Slowed+Reverb is not the same audio recording as studio original");

    // Related recording must be true (seed protection / fatigue should recognize composition match)
    assertTrue(
        RecordingIdentity.relatedRecording(original, slowed),
        "Slowed+Reverb is a related version of Blinding Lights");
  }

  @Test
  public void testPartsRemainDistinct() {
    Track part1 = new Track("t1", "Epic Song (Part 1)", "Band", 300, "url1");
    Track part2 = new Track("t2", "Epic Song (Part 2)", "Band", 320, "url2");

    assertFalse(
        RecordingIdentity.sameRecording(part1, part2),
        "Part 1 and Part 2 are not the same recording");
    assertFalse(
        RecordingIdentity.relatedRecording(part1, part2),
        "Part 1 and Part 2 are distinct compositions");
  }

  @Test
  public void testUnicodeDevanagariAndKoreanPreserved() {
    // Devanagari (Hindi)
    String hindi1 = "नमस्ते भारत (Official Audio)";
    String hindi2 = "नमस्ते भारत";
    assertEquals(
        RecordingIdentity.recordingTitleKey(hindi1, "Artist"),
        RecordingIdentity.recordingTitleKey(hindi2, "Artist"));
    assertEquals("नमस्ते भारत", RecordingIdentity.recordingTitleKey(hindi2, "Artist"));

    // Korean (Hangul)
    String korean1 = "강남스타일 [Official Video]";
    String korean2 = "강남스타일";
    assertEquals(
        RecordingIdentity.recordingTitleKey(korean1, "PSY"),
        RecordingIdentity.recordingTitleKey(korean2, "PSY"));
    assertEquals("강남스타일", RecordingIdentity.recordingTitleKey(korean2, "PSY"));
  }

  @Test
  public void testPromotionalWrappersDoNotChangeRecordingKey() {
    String base = "Midnight City";
    assertEquals(
        RecordingIdentity.recordingTitleKey(base, "M83"),
        RecordingIdentity.recordingTitleKey("Midnight City (Official Video)", "M83"));
    assertEquals(
        RecordingIdentity.recordingTitleKey(base, "M83"),
        RecordingIdentity.recordingTitleKey("Midnight City [Lyric Video]", "M83"));
    assertEquals(
        RecordingIdentity.recordingTitleKey(base, "M83"),
        RecordingIdentity.recordingTitleKey("Midnight City (4K Audio)", "M83"));
  }
}
