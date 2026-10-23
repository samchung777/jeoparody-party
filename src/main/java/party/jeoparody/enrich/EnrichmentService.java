package party.jeoparody.enrich;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Background job that sends un-enriched clues to Gemini in batches until none are left.
 * Runs on startup, after every trivia import, and on demand via POST /api/admin/enrich.
 */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    private static final int KEYWORD_HINTS = 150;
    private static final int MAX_CONSECUTIVE_FAILURES = 8;
    private static final long PAUSE_BETWEEN_BATCHES_MS = 2_000;
    private static final long FIRST_BACKOFF_MS = 15_000;
    private static final long MAX_BACKOFF_MS = 300_000;

    private final ClueEnricher enricher;
    private final EnrichmentRepository repository;
    private final EnrichmentProperties props;
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private final AtomicReference<EnrichmentStatus> status;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clue-enrichment");
        t.setDaemon(true);
        return t;
    });

    public EnrichmentService(ClueEnricher enricher, EnrichmentRepository repository, EnrichmentProperties props) {
        this.enricher = enricher;
        this.repository = repository;
        this.props = props;
        this.status = new AtomicReference<>(EnrichmentStatus.initial(props.enabled()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        requestRun();
    }

    /**
     * Queues a run. Runs execute one at a time on a single thread, so a request made while a run is
     * in progress simply queues one more pass (which picks up any clues imported in the meantime).
     */
    public boolean requestRun() {
        if (!props.enabled()) {
            return false;
        }
        if (queued.compareAndSet(false, true)) {
            executor.submit(this::run);
        }
        return true;
    }

    public EnrichmentStatus status() {
        EnrichmentStatus s = status.get();
        return s.running() ? s : new EnrichmentStatus(s.enabled(), false, s.batches(), s.cluesEnriched(),
                s.cluesSkipped(), repository.countPending(props.maxAttempts()), s.lastError(), s.finishedAt());
    }

    private void run() {
        queued.set(false);
        long remaining = repository.countPending(props.maxAttempts());
        if (remaining == 0) {
            return;
        }
        status.updateAndGet(s -> s.started(remaining));
        log.info("Enriching {} clues with {} ({})", remaining, props.model(), props.location());

        int consecutiveFailures = 0;
        try {
            while (consecutiveFailures < MAX_CONSECUTIVE_FAILURES && !Thread.currentThread().isInterrupted()) {
                List<ClueToEnrich> batch = repository.findPending(props.batchSize(), props.maxAttempts());
                if (batch.isEmpty()) {
                    break;
                }
                try {
                    List<EnrichedClue> results = enricher.enrich(batch, repository.topKeywords(KEYWORD_HINTS));
                    Set<Long> saved = new HashSet<>();
                    for (EnrichedClue e : results) {
                        repository.save(e);
                        saved.add(e.id());
                    }
                    // Only clues Gemini answered for but couldn't convert count as a failed attempt.
                    // Outages, auth and quota errors (the catch below) never burn a clue's attempts.
                    repository.recordAttempt(batch.stream().filter(c -> !saved.contains(c.id())).toList());
                    int enriched = saved.size();
                    int skipped = batch.size() - enriched;
                    long left = repository.countPending(props.maxAttempts());
                    status.updateAndGet(s -> s.batchDone(enriched, skipped, left));
                    log.info("Enriched {}/{} clues in batch ({} remaining)", enriched, batch.size(), left);
                    consecutiveFailures = 0;
                    Thread.sleep(PAUSE_BETWEEN_BATCHES_MS); // stay under Vertex AI's per-minute quota
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    consecutiveFailures++;
                    status.updateAndGet(s -> s.withError(e.getMessage()));
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        log.warn("Enrichment stopped after {} failures in a row: {}", consecutiveFailures, e.getMessage());
                        break;
                    }
                    long backoff = backoffMillis(consecutiveFailures);
                    log.warn("Enrichment batch failed ({}/{}), retrying in {}s: {}", consecutiveFailures,
                            MAX_CONSECUTIVE_FAILURES, backoff / 1000, e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            long left = repository.countPending(props.maxAttempts());
            log.info("Enrichment finished: {}", status.updateAndGet(s -> s.finished(left)));
        }
    }

    /** Exponential back-off: 15s, 30s, 60s, 120s, then 5 minutes. */
    static long backoffMillis(int failures) {
        return Math.min(MAX_BACKOFF_MS, FIRST_BACKOFF_MS << Math.min(failures - 1, 10));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
