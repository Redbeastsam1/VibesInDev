package dev.vibesindev.vibesmusic.recommendation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Offline-learned logistic regression ranker that predicts P(completion >= 0.8).
 *
 * <p>Supports:
 * <ul>
 *   <li>Shadow mode (default true): computes learned scores and logs telemetry while serving heuristic rankings.</li>
 *   <li>Active mode (shadow = false): blends heuristic and learned scores via {@code blendAlpha * learned + (1 - alpha) * heuristic}.</li>
 *   <li>Safe failure: a missing or corrupted weights file logs a single warning and seamlessly falls back to heuristic ranking.</li>
 * </ul>
 */
public final class LearnedRanker implements CandidateRanker {

    private static final Logger LOGGER = Logger.getLogger(LearnedRanker.class.getName());

    private final Map<String, Double> weights = new HashMap<>();
    private double intercept = 0.0;
    private String version = "unknown";
    private boolean loaded = false;
    private boolean shadowMode = true;
    private double blendAlpha = 0.50;
    private boolean warnedFailure = false;

    public LearnedRanker() {
    }

    public LearnedRanker(boolean shadowMode, double blendAlpha) {
        this.shadowMode = shadowMode;
        this.blendAlpha = Math.max(0.0, Math.min(1.0, blendAlpha));
    }

    public synchronized boolean loadFromFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            if (!warnedFailure) {
                LOGGER.warning("[VibesInDev] Learned ranker weights file not found (" + (file != null ? file.getPath() : "null") + "). Serving heuristic baseline.");
                warnedFailure = true;
            }
            this.loaded = false;
            return false;
        }

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return parseJsonObject(json);
        } catch (Exception e) {
            if (!warnedFailure) {
                LOGGER.warning("[VibesInDev] Failed to parse learned ranker weights file: " + e.getMessage() + ". Falling back to heuristic.");
                warnedFailure = true;
            }
            this.loaded = false;
            return false;
        }
    }

    public synchronized boolean loadFromJson(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            this.loaded = false;
            return false;
        }

        try {
            JsonObject json = JsonParser.parseString(jsonContent).getAsJsonObject();
            return parseJsonObject(json);
        } catch (Exception e) {
            if (!warnedFailure) {
                LOGGER.warning("[VibesInDev] Failed to parse weights JSON: " + e.getMessage());
                warnedFailure = true;
            }
            this.loaded = false;
            return false;
        }
    }

    private boolean parseJsonObject(JsonObject json) {
        if (json == null) return false;
        this.weights.clear();
        if (json.has("version")) {
            this.version = json.get("version").getAsString();
        }
        if (json.has("intercept")) {
            this.intercept = json.get("intercept").getAsDouble();
        }
        if (json.has("weights") && json.get("weights").isJsonObject()) {
            JsonObject wObj = json.getAsJsonObject("weights");
            for (String key : wObj.keySet()) {
                this.weights.put(key, wObj.get(key).getAsDouble());
            }
        }
        this.loaded = !this.weights.isEmpty();
        return this.loaded;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isShadowMode() {
        return shadowMode;
    }

    public void setShadowMode(boolean shadowMode) {
        this.shadowMode = shadowMode;
    }

    public double getBlendAlpha() {
        return blendAlpha;
    }

    public void setBlendAlpha(double blendAlpha) {
        this.blendAlpha = Math.max(0.0, Math.min(1.0, blendAlpha));
    }

    public String getVersion() {
        return version;
    }

    public double getIntercept() {
        return intercept;
    }

    public Map<String, Double> getWeights() {
        return Collections.unmodifiableMap(weights);
    }

    /**
     * Computes the logistic probability score P(completion >= 0.8) for the given features.
     *
     * @param features normalized feature vector [0, 1]
     * @return predicted completion probability in [0.0, 1.0]
     */
    public double computeLearnedScore(Map<String, Double> features) {
        if (!loaded || features == null) {
            return 0.50; // Neutral prior
        }

        double z = this.intercept;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            Double val = features.get(entry.getKey());
            double featVal = (val != null) ? val : 0.50; // Neutral midpoint for missing features
            z += entry.getValue() * featVal;
        }

        // Logistic sigmoid function
        if (z < -40.0) return 0.0;
        if (z > 40.0) return 1.0;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /**
     * Blends the candidate's heuristic score and learned score based on shadow mode and alpha.
     *
     * @param heuristicScore baseline heuristic score [0, 1]
     * @param learnedScore model predicted score [0, 1]
     * @return final decision score
     */
    public double blend(double heuristicScore, double learnedScore) {
        if (!loaded || shadowMode) {
            return heuristicScore;
        }
        return (blendAlpha * learnedScore) + ((1.0 - blendAlpha) * heuristicScore);
    }

    @Override
    public double score(RecommendationCandidate candidate, Map<String, Double> features, RecommendationMode mode) {
        double heuristic = (candidate != null) ? candidate.score() : 0.50;
        if (!loaded) {
            return heuristic;
        }
        double learned = computeLearnedScore(features);
        return blend(heuristic, learned);
    }
}
