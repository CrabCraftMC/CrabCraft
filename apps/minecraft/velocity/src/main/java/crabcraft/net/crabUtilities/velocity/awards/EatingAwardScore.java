package crabcraft.net.crabUtilities.velocity.awards;

import crabcraft.net.crabUtilities.awards.EatingAwardSnapshot;

/** Applies cumulative meal snapshots without replaying meals or discarding provisional scores. */
final class EatingAwardScore {
    private EatingAwardScore() {}

    /** Null means an older or inconsistent snapshot must leave both score and checkpoint unchanged. */
    static Double advance(Double score, EatingAwardSnapshot previous, EatingAwardSnapshot incoming) {
        if (previous != null) {
            if (incoming.trackingStartedAt() < previous.trackingStartedAt()
                    || incoming.capturedAt() <= previous.capturedAt()) return null;
            if (incoming.trackingStartedAt() == previous.trackingStartedAt()) {
                if (incoming.meals() < previous.meals()) return null;
                if (previous.historicalScore() != null && incoming.historicalScore() == null) return null;
                if (incoming.historicalScore() == null) {
                    return score + (incoming.meals() - previous.meals());
                }
            }
        }
        if (incoming.historicalScore() != null) {
            return (double) Math.addExact(incoming.historicalScore(), incoming.meals());
        }
        // The old score may already include some pre-migration meals. Anchor once instead of guessing.
        return score == null ? (double) incoming.meals() : score;
    }
}
