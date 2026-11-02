package dev.vibesindev.vibesmusic.recommendation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure candidate filtering and quality evaluation rules for recommendation pipelines.
 */
public final class CandidateFilter {

    private CandidateFilter() {}

    private static final Pattern TITLE_BLACKLIST = Pattern.compile(
            "(?i)(?U)\\b(\\d+\\s*(hours?|hrs?)|1\\s*hour|full album|compilation|playlist|mix\\b|reaction|tutorial|lesson|how to|karaoke|loop|nonstop|jukebox|top \\d+)\\b"
    );

    private static final Pattern VERSION_OR_LIVE = Pattern.compile(
            "(?i)(?U)\\b(live|concert|session|acoustic|cover|sped up|slowed|nightcore|8d)\\b"
    );

    public static boolean matchesBlacklist(String title) {
        if (title == null || title.isBlank()) return true;
        return TITLE_BLACKLIST.matcher(title).find();
    }

    public static boolean isVersionOrLive(String title) {
        if (title == null || title.isBlank()) return false;
        return VERSION_OR_LIVE.matcher(title).find();
    }

    public static boolean isHardRejected(
            String title,
            long durationSeconds,
            RecommendationCandidate.Source source,
            long minDurationSeconds,
            long maxDurationSeconds
    ) {
        if (title == null || title.isBlank()) {
            return true;
        }

        if (matchesBlacklist(title)) {
            return true;
        }

        // Duration guardrails: allow 0 / unknown ONLY for Stream E (FAMILIAR) and Stream A (SPOTIFY_ARTIST_TOP)
        if (durationSeconds <= 0) {
            if (source != RecommendationCandidate.Source.FAMILIAR && source != RecommendationCandidate.Source.SPOTIFY_ARTIST_TOP) {
                return true;
            }
        } else {
            if (durationSeconds < minDurationSeconds || durationSeconds > maxDurationSeconds) {
                return true;
            }
        }

        return false;
    }

    public static double evaluateQuality(
            String candidateTitle,
            String seedTitle,
            String uploaderOrChannel,
            List<String> reuploadChannels
    ) {
        double quality = 1.0;

        if (uploaderOrChannel != null && !uploaderOrChannel.isBlank()) {
            String lowerChannel = uploaderOrChannel.toLowerCase(Locale.ROOT).trim();
            if (lowerChannel.endsWith("- topic") || lowerChannel.endsWith("vevo")) {
                quality += 0.1;
            } else if (reuploadChannels != null) {
                for (String pattern : reuploadChannels) {
                    if (pattern != null && !pattern.isBlank() && lowerChannel.contains(pattern.toLowerCase(Locale.ROOT))) {
                        quality -= 0.3;
                        break;
                    }
                }
            }
        }

        // Live / version soft penalty (halves quality if seed is not also that version)
        if (isVersionOrLive(candidateTitle)) {
            if (seedTitle == null || !isVersionOrLive(seedTitle)) {
                quality *= 0.5;
            }
        }

        return Math.max(0.05, Math.min(1.0, quality));
    }
}
