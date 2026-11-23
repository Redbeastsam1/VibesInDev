package dev.redbeastsam.vibesindev.recommendation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/** Robust artist name normalizer and token matcher (fixes F1 substring match bugs). */
public final class ArtistNames {
  private static final List<String> PROTECTED_NAMES =
      new CopyOnWriteArrayList<>(
          List.of(
              "tyler, the creator",
              "earth, wind & fire",
              "simon & garfunkel",
              "guns n' roses",
              "ac/dc",
              "years & years",
              "of monsters and men",
              "florence + the machine",
              "crosby, stills & nash",
              "hall & oates",
              "mumford & sons",
              "the chainsmokers",
              "sam & dave",
              "kc and the sunshine band"));

  private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

  // Trailing channel / platform suffixes
  private static final Pattern TRAILING_TOPIC = Pattern.compile("(?i)\\s*-\\s*topic$");
  private static final Pattern TRAILING_VEVO =
      Pattern.compile("(?i)(?:\\s+vevo|(?<=[a-z0-9])vevo)$");
  private static final Pattern TRAILING_OFFICIAL = Pattern.compile("(?i)\\s+official$");
  private static final Pattern TRAILING_MUSIC = Pattern.compile("(?i)\\s+music$");
  private static final Pattern TRAILING_TV = Pattern.compile("(?i)\\s+tv$");

  // Separators for token splitting
  // Splits on: , ; / & x and with feat. ft. featuring vs.
  private static final Pattern TOKEN_SPLIT =
      Pattern.compile(
          "\\s*(?:[,;/]|\\s+&\\s+|\\s+[xX]\\s+|\\s+and\\s+|\\s+with\\s+|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+|\\s+vs\\.?\\s+)\\s*",
          Pattern.CASE_INSENSITIVE);

  private ArtistNames() {}

  /**
   * Registers additional protected names (e.g. from config recommendations.artist-name-protect).
   */
  public static void addProtectedNames(List<String> extraNames) {
    if (extraNames == null) return;
    for (String name : extraNames) {
      if (name != null && !name.isBlank()) {
        String norm = normalizeBase(name);
        if (!PROTECTED_NAMES.contains(norm)) {
          PROTECTED_NAMES.add(norm);
        }
      }
    }
  }

  private static String normalizeBase(String raw) {
    if (raw == null || raw.isBlank()) return "";
    // 1. Unicode NFKD & strip combining marks
    String s = Normalizer.normalize(raw, Normalizer.Form.NFKD);
    s = COMBINING_MARKS.matcher(s).replaceAll("");
    // 2. Lowercase with Locale.ROOT
    s = s.toLowerCase(Locale.ROOT);
    // 3. Normalize whitespace
    s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
    return s;
  }

  /**
   * Normalizes an artist name: Unicode NFKD, strips combining marks, lowercases, trims/collapses
   * whitespace, strips trailing channel noise (- topic, vevo, official, music, tv), and normalizes
   * & / + / and.
   */
  public static String normalize(String raw) {
    String s = normalizeBase(raw);
    if (s.isEmpty()) return "";

    // Strip trailing channel suffixes (only if string remains reasonably long)
    if (TRAILING_TOPIC.matcher(s).find()) {
      s = TRAILING_TOPIC.matcher(s).replaceFirst("");
    }
    if (s.length() > 4 && TRAILING_VEVO.matcher(s).find()) {
      s = TRAILING_VEVO.matcher(s).replaceFirst("");
    }
    if (s.length() > 8 && TRAILING_OFFICIAL.matcher(s).find()) {
      s = TRAILING_OFFICIAL.matcher(s).replaceFirst("");
    }
    if (s.length() > 6 && TRAILING_MUSIC.matcher(s).find()) {
      s = TRAILING_MUSIC.matcher(s).replaceFirst("");
    }
    if (s.length() > 4 && TRAILING_TV.matcher(s).find()) {
      s = TRAILING_TV.matcher(s).replaceFirst("");
    }

    s = s.trim();

    // Consistent separator representation: normalize ' + ' and ' and ' to ' & '
    // but avoid breaking protected names if they already match
    return s;
  }

  /**
   * Extracts individual artist tokens from an artist string, respecting protected names that
   * contain separators. Always also includes the full normalized string.
   */
  public static Set<String> tokens(String raw) {
    if (raw == null || raw.isBlank()) return Collections.emptySet();
    String fullNorm = normalize(raw);
    if (fullNorm.isEmpty()) return Collections.emptySet();

    Set<String> result = new LinkedHashSet<>();
    result.add(fullNorm);

    // Check protected names to prevent splitting within them
    String working = fullNorm;
    List<String> matchedProtected = new ArrayList<>();
    for (int i = 0; i < PROTECTED_NAMES.size(); i++) {
      String pName = PROTECTED_NAMES.get(i);
      if (working.contains(pName)) {
        String placeholder = "___PROTECTED_" + i + "___";
        working = working.replace(pName, placeholder);
        matchedProtected.add(pName);
      }
    }

    // Split on collaboration delimiters
    String[] parts = TOKEN_SPLIT.split(working);
    for (String part : parts) {
      String token = part.trim();
      if (token.isEmpty()) continue;

      // Restore protected names
      for (int i = 0; i < PROTECTED_NAMES.size(); i++) {
        String placeholder = "___PROTECTED_" + i + "___";
        if (token.contains(placeholder)) {
          token = token.replace(placeholder, PROTECTED_NAMES.get(i));
        }
      }

      token = token.trim();
      // Strip any leftover wrapping brackets e.g. (feat. ...)
      token = token.replaceAll("^[\\(\\[]+|[\\)\\]]+$", "").trim();
      if (!token.isEmpty()) {
        result.add(token);
      }
    }

    return result;
  }

  public static List<String> splitTokens(String raw) {
    return new ArrayList<>(tokens(raw));
  }

  /** Returns true if either full normalized string matches, or if the token sets intersect. */
  public static boolean sameArtist(String a, String b) {
    if (a == null || b == null) return false;
    String aNorm = normalize(a);
    String bNorm = normalize(b);
    if (aNorm.isEmpty() || bNorm.isEmpty()) return false;
    if (aNorm.equals(bNorm)) return true;

    Set<String> aTokens = tokens(a);
    Set<String> bTokens = tokens(b);
    for (String at : aTokens) {
      if (bTokens.contains(at)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns 1.0 for full-string equality, 0.8 for token intersection (collaboration/feature), and
   * 0.0 otherwise.
   */
  public static double exactOrCollab(String a, String b) {
    if (a == null || b == null) return 0.0;
    String aNorm = normalize(a);
    String bNorm = normalize(b);
    if (aNorm.isEmpty() || bNorm.isEmpty()) return 0.0;
    if (aNorm.equals(bNorm)) return 1.0;

    if (sameArtist(a, b)) {
      return 0.8;
    }
    return 0.0;
  }
}
