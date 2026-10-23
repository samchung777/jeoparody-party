package party.jeoparody.enrich;

import java.time.Instant;

/** Snapshot of the background enrichment job, returned by GET /api/admin/enrich. */
public record EnrichmentStatus(
        boolean enabled,
        boolean running,
        int batches,
        int cluesEnriched,
        int cluesSkipped,
        long remaining,
        String lastError,
        Instant finishedAt) {

    static EnrichmentStatus initial(boolean enabled) {
        return new EnrichmentStatus(enabled, false, 0, 0, 0, 0, null, null);
    }

    EnrichmentStatus started(long remaining) {
        return new EnrichmentStatus(enabled, true, 0, 0, 0, remaining, null, null);
    }

    EnrichmentStatus batchDone(int enriched, int skipped, long remaining) {
        return new EnrichmentStatus(enabled, running, batches + 1, cluesEnriched + enriched, cluesSkipped + skipped,
                remaining, lastError, finishedAt);
    }

    EnrichmentStatus withError(String error) {
        return new EnrichmentStatus(enabled, running, batches, cluesEnriched, cluesSkipped, remaining, error, finishedAt);
    }

    EnrichmentStatus finished(long remaining) {
        return new EnrichmentStatus(enabled, false, batches, cluesEnriched, cluesSkipped, remaining, lastError,
                Instant.now());
    }
}
