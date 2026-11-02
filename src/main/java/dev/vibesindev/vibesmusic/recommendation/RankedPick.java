package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.model.Track;

/**
 * An individual ranked recommendation pick surfacing from the scoring and MMR selection pipeline.
 *
 * @param track  the recommended track
 * @param mode   the recommendation mode under which the candidate was evaluated
 * @param source the stream/provenance of the candidate
 * @param reason human-readable justification or breakdown
 * @param score  the candidate's final ranking score
 */
public record RankedPick(
        Track track,
        RecommendationMode mode,
        RecommendationCandidate.Source source,
        String reason,
        double score
) {}
