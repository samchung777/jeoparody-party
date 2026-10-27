package party.jeoparody.enrich;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import party.jeoparody.review.ClueReviewService;

/**
 * Background job that sends un-enriched clues to Gemini in batches until none are left.
 * Runs on startup, after every trivia import, and on demand via POST /api/admin/enrich.
 *
 * Where it fits: TriviaImportService and host feedback ("reword") call requestRun(); it then calls the review
 * pass (ClueReviewService). Why it matters: it is the resilience example in the app: one background thread,
 * batches with pauses to stay under quota, and exponential back-off on rate limits (HTTP 429).
 */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    /**
     * Tuning: how many existing keywords to suggest to Gemini (so it reuses them), the minimum clues before a
     * keyword gets a title, batch sizes, when to give up, and the back-off range.
     */
    private static final int KEYWORD_HINTS = 150;
    private static final int MIN_CLUES_FOR_TITLE = 5;
    private static final int TITLE_BATCH = 25;
    private static final int MAX_CONSECUTIVE_FAILURES = 8;
    private static final long PAUSE_BETWEEN_BATCHES_MS = 2_000;
    private static final long FIRST_BACKOFF_MS = 15_000;
    private static final long MAX_BACKOFF_MS = 300_000;

    private final ClueEnricher enricher;
    private final CategoryTitler titler;
    private final ClueReviewService review;
    private final EnrichmentRepository repository;
    private final EnrichmentProperties props;
    /**
     * Thread-safe flags: at most one extra run is queued, however many times requestRun() is called.
     */
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private final AtomicReference<EnrichmentStatus> status;
    /**
     * A single daemon thread named clue-enrichment: work runs one batch at a time, off the web request threads.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clue-enrichment");
        t.setDaemon(true);
        return t;
    });

    /**
     * Depends on interfaces (ClueEnricher, CategoryTitler), so tests can pass fakes instead of calling Gemini.
     */
    public EnrichmentService(ClueEnricher enricher, CategoryTitler titler, EnrichmentRepository repository,
            EnrichmentProperties props, ClueReviewService review) {
        this.review = review;
        this.enricher = enricher;
        this.titler = titler;
        this.repository = repository;
        this.props = props;
        this.status = new AtomicReference<>(EnrichmentStatus.initial(props.enabled()));
    }

    /**
     * Starts a pass once the app is ready (ApplicationReadyEvent); does nothing when trivia.enrichment.enabled is false.
     */
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

    /** Progress for the admin library page; recounts pending clues when idle. */
    public EnrichmentStatus status() {
        EnrichmentStatus s = status.get();
        return s.running() ? s : new EnrichmentStatus(s.enabled(), false, s.batches(), s.cluesEnriched(),
                s.cluesSkipped(), repository.countPending(props.maxAttempts()), s.lastError(), s.finishedAt());
    }

    /**
     * One full pass: enrich clues, delete keywords nothing uses, title new keywords, then trigger the review pass.
     */
    private void run() {
        queued.set(false);
        enrichClues();
        int removed = repository.deleteUnusedKeywords();
        if (removed > 0) {
            log.info("Removed {} keywords no longer used by any clue", removed);
        }
        titleKeywords();
        review.requestRun(); // newly converted clues get the behind-the-scenes check
    }

    /** Phase 1: Jeopardy wording + keywords for clues not yet at the current prompt version. */
    private void enrichClues() {
        long remaining = repository.countPending(props.maxAttempts());
        if (remaining == 0) {
            return;
        }
        status.updateAndGet(s -> s.started(remaining));
        log.info("Enriching {} clues with {} ({}), prompt v{}", remaining, props.model(), props.location(),
                EnrichmentPrompt.VERSION);
        try {
            processUntilDone(() -> {
                List<ClueToEnrich> batch = repository.findPending(props.batchSize(), props.maxAttempts());
                if (batch.isEmpty()) {
                    return false;
                }
                List<EnrichedClue> results = enricher.enrich(batch, repository.topKeywords(KEYWORD_HINTS));
                Set<Long> saved = new HashSet<>();
                for (EnrichedClue e : results) {
                    repository.save(e);
                    saved.add(e.id());
                }
                // Only clues Gemini answered for but couldn't convert count as a failed attempt.
                // Outages, auth and quota errors never burn a clue's attempts.
                repository.recordAttempt(batch.stream().filter(c -> !saved.contains(c.id())).toList());
                int enriched = saved.size();
                int skipped = batch.size() - enriched;
                long left = repository.countPending(props.maxAttempts());
                status.updateAndGet(s -> s.batchDone(enriched, skipped, left));
                log.info("Enriched {}/{} clues in batch ({} remaining)", enriched, batch.size(), left);
                return true;
            });
        } finally {
            long left = repository.countPending(props.maxAttempts());
            log.info("Enrichment finished: {}", status.updateAndGet(s -> s.finished(left)));
        }
    }

    /** Phase 2: a themed, punny board title for every keyword with enough clues. */
    private void titleKeywords() {
        processUntilDone(() -> {
            List<KeywordToTitle> batch = repository.findUntitledKeywords(MIN_CLUES_FOR_TITLE, TITLE_BATCH);
            if (batch.isEmpty()) {
                return false;
            }
            Map<Long, String> titles = titler.title(batch);
            for (KeywordToTitle k : batch) {
                // Fall back to the keyword itself so one odd reply can't stall the queue.
                repository.saveTitle(k.id(), titles.getOrDefault(k.id(), k.keyword()));
            }
            log.info("Titled {} categories (e.g. \"{}\" -> \"{}\")", batch.size(), batch.get(0).keyword(),
                    titles.getOrDefault(batch.get(0).id(), batch.get(0).keyword()));
            return true;
        });
    }

    /** One unit of Gemini work; returns false when there is nothing left to do. */
    @FunctionalInterface
    private interface Step {
        boolean runOnce() throws Exception;
    }

    /** Repeats a step until it reports no more work, backing off on failures (e.g. HTTP 429). */
    private void processUntilDone(Step step) {
        int consecutiveFailures = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!step.runOnce()) {
                    return;
                }
                consecutiveFailures = 0;
                Thread.sleep(PAUSE_BETWEEN_BATCHES_MS); // stay under Vertex AI's per-minute quota
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                consecutiveFailures++;
                status.updateAndGet(s -> s.withError(e.getMessage()));
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("Gemini work stopped after {} failures in a row: {}", consecutiveFailures, e.getMessage());
                    return;
                }
                long backoff = backoffMillis(consecutiveFailures);
                log.warn("Gemini call failed ({}/{}), retrying in {}s: {}", consecutiveFailures,
                        MAX_CONSECUTIVE_FAILURES, backoff / 1000, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Exponential back-off: 15s, 30s, 60s, 120s, then 5 minutes. */
    public static long backoffMillis(int failures) {
        return Math.min(MAX_BACKOFF_MS, FIRST_BACKOFF_MS << Math.min(failures - 1, 10));
    }

    /**
     * Stops the background thread when the app shuts down (@PreDestroy), e.g. when Cloud Run replaces an instance.
     */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
