package party.jeoparody.review;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import party.jeoparody.enrich.EnrichmentProperties;
import party.jeoparody.enrich.EnrichmentService;

/**
 * Background pass that has Gemini check live clues and retires the behind-the-scenes ones
 * (directors, release dates, box office...). Runs on startup and after new clues are converted.
 *
 * Where it fits: EnrichmentService calls requestRun() after converting clues. Why it matters: play-testing showed
 * production-trivia clues (directors, box office) were no fun; this is the automated quality gate for content,
 * using the same single-thread + back-off pattern as enrichment.
 */
@Service
public class ClueReviewService {

    private static final Logger log = LoggerFactory.getLogger(ClueReviewService.class);
    private static final int BATCH = 40;
    private static final int MAX_CONSECUTIVE_FAILURES = 8;
    private static final long PAUSE_MS = 2_000;

    private final ClueReviewer reviewer;
    private final ClueReviewRepository repository;
    private final EnrichmentProperties gemini;
    private final boolean enabled;
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clue-review");
        t.setDaemon(true);
        return t;
    });

    /**
     * @Value reads trivia.review.enabled (false in application-test.yml, so tests never call Gemini).
     */
    public ClueReviewService(ClueReviewer reviewer, ClueReviewRepository repository, EnrichmentProperties gemini,
            @Value("${trivia.review.enabled:true}") boolean enabled) {
        this.reviewer = reviewer;
        this.repository = repository;
        this.gemini = gemini;
        this.enabled = enabled;
    }

    /** Runs only if both review and Gemini enrichment are enabled. */
    public boolean enabled() {
        return enabled && gemini.enabled();
    }

    /** Reviews anything pending once the app is ready. */
    // Skipped in the research worker (app.startup-jobs=false), which runs each job in order and then exits.
    @EventListener(value = ApplicationReadyEvent.class,
            condition = "@environment.getProperty('app.startup-jobs', 'true') == 'true'")
    public void onStartup() {
        requestRun();
    }

    /** Queues one background pass (at most one waiting at a time). */
    public boolean requestRun() {
        if (!enabled()) {
            return false;
        }
        if (queued.compareAndSet(false, true)) {
            executor.submit(() -> {
                queued.set(false);
                processQueue();
            });
        }
        return true;
    }

    /** Shown on the library page: clues still to review and how many were retired. */
    public record Status(boolean enabled, long pending, long retired) {
    }

    /** Current counts from the database. */
    public Status status() {
        return new Status(enabled(), repository.countPending(), repository.countRetired());
    }

    /** Reviews every pending clue on the calling thread. Public so tests can run it synchronously. */
    public void processQueue() {
        long pending = repository.countPending();
        if (pending == 0) {
            return;
        }
        log.info("Reviewing {} clues for behind-the-scenes trivia", pending);
        int failures = 0;
        int retired = 0;
        while (!Thread.currentThread().isInterrupted()) {
            List<ClueToReview> batch = repository.findPending(BATCH);
            if (batch.isEmpty()) {
                break;
            }
            try {
                Map<Long, String> offTopic = reviewer.offTopic(batch);
                for (Map.Entry<Long, String> e : offTopic.entrySet()) {
                    if (repository.retire(e.getKey(), e.getValue())) {
                        retired++;
                    }
                }
                repository.markReviewed(batch);
                failures = 0;
                log.info("Reviewed {} clues, retired {} ({} remaining)", batch.size(), offTopic.size(),
                        repository.countPending());
                Thread.sleep(PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (++failures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("Clue review stopped after {} failures: {}", failures, e.getMessage());
                    break;
                }
                long wait = EnrichmentService.backoffMillis(failures);
                log.warn("Clue review batch failed, retrying in {}s: {}", wait / 1000, e.getMessage());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.info("Clue review finished: {} behind-the-scenes clues retired", retired);
    }

    /** Stops the background thread on shutdown. */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
