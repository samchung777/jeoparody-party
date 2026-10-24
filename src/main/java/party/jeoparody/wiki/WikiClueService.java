package party.jeoparody.wiki;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import party.jeoparody.enrich.EnrichedClue;
import party.jeoparody.enrich.EnrichmentProperties;
import party.jeoparody.enrich.EnrichmentRepository;
import party.jeoparody.enrich.EnrichmentService;
import party.jeoparody.enrich.TransientEnrichmentException;
import party.jeoparody.review.ReviewPrompt;
import party.jeoparody.trivia.TriviaRepository;

/**
 * Turns queued topics into clues: fetch the Wikipedia article, have Gemini write clues from it,
 * keep the verifiable ones, store them under the topic's keyword. One topic at a time, in the background.
 */
@Service
public class WikiClueService {

    private static final Logger log = LoggerFactory.getLogger(WikiClueService.class);
    static final String SOURCE = "wikipedia";
    private static final int MAX_ATTEMPTS = 4;
    private static final long PAUSE_BETWEEN_TOPICS_MS = 2_000;

    private final WikiSource source;
    private final WikiClueGenerator generator;
    private final WikiRepository repository;
    private final TriviaRepository trivia;
    private final EnrichmentRepository enrichmentRepository;
    private final EnrichmentService enrichment;
    private final WikiProperties props;
    private final EnrichmentProperties geminiProps;
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wiki-clues");
        t.setDaemon(true);
        return t;
    });

    public WikiClueService(WikiSource source, WikiClueGenerator generator, WikiRepository repository,
            TriviaRepository trivia, EnrichmentRepository enrichmentRepository, EnrichmentService enrichment,
            WikiProperties props, EnrichmentProperties geminiProps) {
        this.source = source;
        this.generator = generator;
        this.repository = repository;
        this.trivia = trivia;
        this.enrichmentRepository = enrichmentRepository;
        this.enrichment = enrichment;
        this.props = props;
        this.geminiProps = geminiProps;
    }

    /** Needs both the wiki feature and Gemini. */
    public boolean enabled() {
        return props.enabled() && geminiProps.enabled();
    }

    /**
     * On startup: sync the starter topic list (new topics are queued, changed ones updated), re-queue topics
     * written with an older prompt, and resume anything still queued.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled()) {
            return;
        }
        if (props.seedOnStartup()) {
            syncStarterTopics();
        }
        int requeued = repository.requeueOutdated(WikiCluePrompt.VERSION);
        if (requeued > 0) {
            log.info("Re-queued {} Wikipedia topics written with an older prompt", requeued);
        }
        requestRun();
    }

    /** Matches starter topics to existing rows by page, so edits (e.g. added groups) update in place. */
    void syncStarterTopics() {
        Map<String, Long> byPage = new HashMap<>();
        for (WikiRepository.TopicRow row : repository.allTopics()) {
            byPage.put(pageKey(row.topic()), row.id());
        }
        int added = 0;
        for (String starter : props.starterTopics()) {
            Long existing = byPage.get(pageKey(starter));
            if (existing == null) {
                repository.queue(starter);
                added++;
            } else {
                repository.rename(existing, starter);
            }
        }
        if (added > 0) {
            log.info("Queued {} new starter Wikipedia topics", added);
        }
    }

    private static String pageKey(String topic) {
        return WikiText.parseTopic(topic).pageQuery().toLowerCase(Locale.ROOT);
    }

    /** Accepts "a, b" or one topic per line. Returns the cleaned topics that were queued. */
    public List<String> queue(String rawTopics) {
        Set<String> topics = new LinkedHashSet<>();
        for (String part : rawTopics.split("[,\\n;]")) {
            String t = part.strip().replaceAll("\\s+", " ");
            if (!t.isEmpty()) {
                topics.add(t);
            }
        }
        topics.forEach(repository::queue);
        requestRun();
        return new ArrayList<>(topics);
    }

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

    /** Processes every queued topic on the calling thread. Public so tests can run it synchronously. */
    public void processQueue() {
        int processed = 0;
        Optional<WikiRepository.QueuedTopic> next;
        while (!Thread.currentThread().isInterrupted() && (next = repository.nextQueued()).isPresent()) {
            process(next.get());
            processed++;
            sleep(PAUSE_BETWEEN_TOPICS_MS);
        }
        if (processed > 0) {
            enrichment.requestRun(); // gives the new keywords their punny category titles
        }
    }

    void process(WikiRepository.QueuedTopic topic) {
        WikiText.TopicSpec spec = WikiText.parseTopic(topic.topic());
        try {
            Optional<WikiPage> page = source.fetch(spec.pageQuery());
            if (page.isEmpty()) {
                log.info("Wiki topic '{}': no article found", topic.topic());
                repository.markNotFound(topic.id());
                return;
            }
            Set<String> existing = repository.existingAnswers(page.get().url());
            int count = spec.count() != null ? spec.count() : props.cluesPerTopic();
            List<GeneratedClue> clues = generateWithRetry(spec, page.get(), count, existing);
            long categoryId = trivia.upsertCategory(SOURCE, (int) page.get().pageId(), page.get().title());
            int added = 0;
            for (GeneratedClue c : clues) {
                OptionalLong id = repository.insertClue(categoryId, c, page.get().url(), ReviewPrompt.VERSION);
                if (id.isPresent()) {
                    enrichmentRepository.save(new EnrichedClue(id.getAsLong(), c.clue(), c.response(), c.keywords()));
                    added++;
                }
            }
            repository.markDone(topic.id(), page.get(), clues.size(), added, WikiCluePrompt.VERSION);
            log.info("Wiki topic '{}' ({}): {} verified clues, {} new", topic.topic(), page.get().title(),
                    clues.size(), added);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Wiki topic '{}' failed: {}", topic.topic(), e.getMessage());
            repository.markFailed(topic.id(), e.getMessage());
        }
    }

    private List<GeneratedClue> generateWithRetry(WikiText.TopicSpec spec, WikiPage page, int count,
            Set<String> existing) throws IOException, InterruptedException {
        String keyword = spec.keyword();
        for (int attempt = 1; ; attempt++) {
            try {
                return generator.generate(spec, page, count, existing);
            } catch (TransientEnrichmentException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                long wait = EnrichmentService.backoffMillis(attempt);
                log.warn("Gemini busy for '{}', retrying in {}s", keyword, wait / 1000);
                Thread.sleep(wait);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
