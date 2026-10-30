package party.jeoparody.research;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import party.jeoparody.enrich.EnrichmentService;
import party.jeoparody.review.ClueReviewService;

/**
 * Works through the research queue on one background thread inside this app. Used locally, and in production as
 * the fallback when the Cloud Run Job can't be started. At most one extra run waits while one is in progress.
 */
public class LocalResearchLauncher implements ResearchLauncher {

    private static final Logger log = LoggerFactory.getLogger(LocalResearchLauncher.class);

    private final ResearchService research;
    private final EnrichmentService enrichment;
    private final ClueReviewService review;
    private final boolean enabled;
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clue-research");
        t.setDaemon(true);
        return t;
    });

    public LocalResearchLauncher(ResearchService research, EnrichmentService enrichment, ClueReviewService review,
            boolean enabled) {
        this.research = research;
        this.enrichment = enrichment;
        this.review = review;
        this.enabled = enabled;
    }

    @Override
    public void launch() {
        if (!enabled || !queued.compareAndSet(false, true)) {
            return;
        }
        executor.submit(() -> {
            queued.set(false);
            try {
                if (research.processQueue() > 0) {
                    enrichment.requestRun(); // titles and genres for new keywords
                    review.requestRun();
                }
            } catch (RuntimeException e) {
                log.warn("Background research failed: {}", e.getMessage());
            }
        });
    }

    @Override
    public String describe() {
        return enabled ? "background thread in the game server" : "disabled";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
