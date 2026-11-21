package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Unicode-safe title canonicalization and recording identity resolution (fixes F2 and F3).
 *
 * <p>Distinguishes {@code sameRecording()} (for cross-provider deduplication) from {@code
 * relatedRecording()} (for seed protection and fatigue penalties).
 */
public final class RecordingIdentity {
  // Retain all Unicode letters (\p{L}), combining marks / Brahmic vowel signs (\p{M}), numbers
  // (\p{N}), and whitespace (\s)
  private static final Pattern UNICODE_STRIP = Pattern.compile("[^\\p{L}\\p{M}\\p{N}\\s]+");
  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

  // Promotional & non-musical brackets to strip from both keys (e.g. [Official Video], (4K Audio),
  // [Lyric Video])
  private static final Pattern PROMOTIONAL_BRACKETS =
      Pattern.compile(
          "\\s*[\\[\\(][^\\]\\)]*?\\b(?:"
              + "official|"
              + "video|"
              + "audio|"
              + "lyrics?|"
              + "lyric\\s*video|"
              + "visualizer|"
              + "hd|"
              + "hq|"
              + "4k|"
              + "8k|"
              + "music\\s*video|"
              + "mv|"
              + "videoclip|"
              + "single\\s*\\d{4}|"
              + "feat\\.?|"
              + "ft\\.?|"
              + "featuring"
              + ")\\b[^\\]\\)]*?[\\]\\)]",
          Pattern.CASE_INSENSITIVE);

  // Promotional keywords outside brackets
  private static final Pattern PROMOTIONAL_STANDALONE =
      Pattern.compile(
          "\\b(?:"
              + "official\\s*(?:music\\s*)?video|"
              + "official\\s*(?:music\\s*)?audio|"
              + "lyric\\s*video|"
              + "visualizer|"
              + "music\\s*video|"
              + "single\\s*\\d{4}"
              + ")\\b",
          Pattern.CASE_INSENSITIVE);

  // Musical version descriptors stripped ONLY in compositionTitleKey (preserved in
  // recordingTitleKey)
  private static final Pattern VERSION_BRACKETS =
      Pattern.compile(
          "\\s*[\\[\\(][^\\]\\)]*?\\b(?:"
              + "slowed|"
              + "reverb|"
              + "sped\\s*up|"
              + "speed\\s*up|"
              + "nightcore|"
              + "remix|"
              + "live|"
              + "concert|"
              + "session|"
              + "acoustic|"
              + "cover|"
              + "instrumental|"
              + "extended|"
              + "remastered|"
              + "remaster|"
              + "radio\\s*edit|"
              + "8d|"
              + "bass\\s*boosted"
              + ")\\b[^\\]\\)]*?[\\]\\)]",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern VERSION_STANDALONE =
      Pattern.compile(
          "\\b(?:"
              + "slowed\\s*\\+?\\s*reverb|"
              + "slowed|"
              + "reverb|"
              + "sped\\s*up|"
              + "speed\\s*up|"
              + "nightcore|"
              + "remix|"
              + "live|"
              + "concert|"
              + "acoustic|"
              + "cover|"
              + "instrumental|"
              + "extended|"
              + "remastered|"
              + "remaster|"
              + "radio\\s*edit|"
              + "8d|"
              + "bass\\s*boosted"
              + ")\\b",
          Pattern.CASE_INSENSITIVE);

  // Feat / ft trailer in titles (e.g. "Song feat. Artist")
  private static final Pattern FEAT_TRAILER =
      Pattern.compile("(?i)\\s*\\b(?:feat\\.?|ft\\.?|featuring)\\b.*$");

  private RecordingIdentity() {}

  private static String stripArtistPrefix(String title, String artist) {
    if (title == null) return "";
    if (artist == null || artist.isBlank()) return title;

    String t = title.trim();

    // Strip leading "Artist - Title" or "Artist – Title" or "Artist: Title"
    for (String sep : new String[] {" - ", " – ", " — ", ": "}) {
      int idx = t.indexOf(sep);
      if (idx > 0) {
        String prefix = t.substring(0, idx);
        if (ArtistNames.sameArtist(prefix, artist)) {
          return t.substring(idx + sep.length()).trim();
        }
      }
    }
    return t;
  }

  /**
   * Key for recording identity (cross-provider deduplication). Strips promotional wrappers
   * (Official Video, Lyrics, HD, 4K) but PRESERVES version descriptors (Live, Acoustic, Remix,
   * Slowed, Reverb, Sped Up, Cover).
   */
  public static String recordingTitleKey(String title, String artist) {
    if (title == null || title.isBlank()) return "";

    // 1. Strip artist prefix
    String s = stripArtistPrefix(title, artist);

    // 2. Unicode NFKC normalization and lowercase
    s = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);

    // 3. Remove promotional wrappers
    s = PROMOTIONAL_BRACKETS.matcher(s).replaceAll(" ");
    s = PROMOTIONAL_STANDALONE.matcher(s).replaceAll(" ");

    // 4. Retained characters: Unicode letters (\p{L}), combining marks (\p{M}), numbers (\p{N}),
    // spaces (\s)
    s = UNICODE_STRIP.matcher(s).replaceAll(" ");
    s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();

    // 5. Fallback to raw title if stripped empty
    if (s.isEmpty()) {
      s =
          UNICODE_STRIP
              .matcher(Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
              .replaceAll(" ")
              .trim();
    }
    return s;
  }

  /**
   * Key for composition identity (seed protection and fatigue penalties). Removes both promotional
   * wrappers and version descriptors (Slowed, Remix, Acoustic, Live). Part numbers (e.g. Part 1,
   * Part 2) are intentionally NOT removed.
   */
  public static String compositionTitleKey(String title, String artist) {
    if (title == null || title.isBlank()) return "";

    // 1. Strip artist prefix
    String s = stripArtistPrefix(title, artist);

    // 2. Unicode NFKC normalization and lowercase
    s = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);

    // 3. Remove promotional wrappers
    s = PROMOTIONAL_BRACKETS.matcher(s).replaceAll(" ");
    s = PROMOTIONAL_STANDALONE.matcher(s).replaceAll(" ");

    // 4. Remove version descriptors (slowed, remix, live, acoustic, cover, etc.)
    s = VERSION_BRACKETS.matcher(s).replaceAll(" ");
    s = VERSION_STANDALONE.matcher(s).replaceAll(" ");

    // 5. Remove featuring artist suffix from composition title
    s = FEAT_TRAILER.matcher(s).replaceAll(" ");

    // 6. Retained characters: Unicode letters, combining marks, numbers, spaces
    s = UNICODE_STRIP.matcher(s).replaceAll(" ");
    s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();

    if (s.isEmpty()) {
      return recordingTitleKey(title, artist);
    }
    return s;
  }

  /** Returns true if {@code a} and {@code b} represent the exact same audio recording. */
  public static boolean sameRecording(Track a, Track b) {
    if (a == null || b == null) return false;
    if (a.id() != null && b.id() != null && a.id().equalsIgnoreCase(b.id())) {
      return true;
    }

    if (!ArtistNames.sameArtist(a.artist(), b.artist())) {
      return false;
    }

    String recA = recordingTitleKey(a.title(), a.artist());
    String recB = recordingTitleKey(b.title(), b.artist());
    if (recA.isEmpty() || recB.isEmpty()) return false;

    if (!recA.equals(recB)) {
      return false;
    }

    // Supporting duration check if both have known duration
    if (a.durationSeconds() > 0 && b.durationSeconds() > 0) {
      long diff = Math.abs(a.durationSeconds() - b.durationSeconds());
      if (diff > 15) {
        return false; // Different length recording
      }
    }

    return true;
  }

  /**
   * Returns true if {@code a} and {@code b} represent the same underlying composition or an
   * alternate version/remix/live/cover of it.
   */
  public static boolean relatedRecording(Track a, Track b) {
    if (a == null || b == null) return false;
    if (sameRecording(a, b)) return true;

    if (!ArtistNames.sameArtist(a.artist(), b.artist())) {
      return false;
    }

    String compA = compositionTitleKey(a.title(), a.artist());
    String compB = compositionTitleKey(b.title(), b.artist());
    if (compA.isEmpty() || compB.isEmpty()) return false;

    return compA.equals(compB);
  }
}
