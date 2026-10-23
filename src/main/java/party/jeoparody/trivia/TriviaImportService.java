package party.jeoparody.trivia;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import party.jeoparody.enrich.EnrichmentService;

import jakarta.annotation.PreDestroy;

/**
 * Pulls questions from Open Trivia DB into MySQL on a background thread.
 * Runs automatically on startup when the clue table is empty (if enabled),
 * or on demand via POST /api/admin/import.
 */
@Service
public class TriviaImportService {

    private static final Logger log = LoggerFactory.getLogger(TriviaImportService.class);
    private static final String SOURCE = "opentdb";
    private static final int MIN_BATCH = 10;
    private static final int MAX_ATTEMPTS = 6;

    private final OpenTdbClient client;
    private final TriviaRepository repository;
    private final TriviaProperties props;
    private final EnrichmentService enrichment;
    private final AtomicReference<ImportStatus> status = new AtomicReference<>(ImportStatus.idle());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trivia-import");
        t.setDaemon(true);
        return t;
    });

    public TriviaImportService(OpenTdbClient client, TriviaRepository repository, TriviaProperties props,
            EnrichmentService enrichment) {
        this.client = client;
        this.repository = repository;
        this.props = props;
        this.enrichment = enrichment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importIfEmpty() {
        if (props.importOnStartup() && repository.countClues() == 0) {
            log.info("No clues in the database yet; importing from Open Trivia DB");
            startImport(props.categories());
        }
    }

    /** Starts an import in the background. Returns false if one is already running. */
    public boolean startImport(List<Integer> categoryIds) {
        ImportStatus current = status.get();
        if (current.running() || !status.compareAndSet(current, ImportStatus.started(categoryIds.size()))) {
            return false;
        }
        List<Integer> ids = List.copyOf(categoryIds);
        executor.submit(() -> runImport(ids));
        return true;
    }

    public ImportStatus status() {
        return status.get();
    }

    private void runImport(List<Integer> categoryIds) {
        try {
            Map<Integer, String> names = fetchCategoryNames();
            String token = requestToken();
            for (int sourceId : categoryIds) {
                importCategory(sourceId, names, token);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status.updateAndGet(s -> s.withError("Import interrupted"));
        } finally {
            log.info("Trivia import finished: {}", status.updateAndGet(ImportStatus::finished));
            enrichment.requestRun(); // convert the new clues to Jeopardy format and tag keywords
        }
    }

    private Map<Integer, String> fetchCategoryNames() throws InterruptedException {
        try {
            Map<Integer, String> names = client.fetchCategories();
            pause();
            return names;
        } catch (IOException e) {
            log.warn("Could not load Open Trivia DB category names: {}", e.getMessage());
            return Map.of();
        }
    }

    private String requestToken() throws InterruptedException {
        try {
            String token = client.requestToken();
            pause();
            if (token == null) {
                log.warn("No Open Trivia DB session token; importing one random page per category");
            }
            return token;
        } catch (IOException e) {
            log.warn("Could not get an Open Trivia DB session token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Pages through a category (50 questions per request) until it is exhausted or
     * trivia.max-questions-per-category is reached, saving each page as it arrives.
     */
    private void importCategory(int sourceId, Map<Integer, String> names, String token) throws InterruptedException {
        int amount = Math.max(MIN_BATCH, props.questionsPerCategory());
        int fetched = 0;
        int added = 0;
        int rateLimited = 0;
        Long categoryId = null;
        String rawName = names.get(sourceId);
        try {
            while (fetched < props.maxQuestionsPerCategory()) {
                JsonNode root = client.fetchQuestions(sourceId, amount, token);
                pause();
                int code = OpenTdbParser.responseCode(root);
                if (code == OpenTdbParser.SUCCESS) {
                    JsonNode results = root.path("results");
                    if (results.isEmpty()) {
                        break;
                    }
                    fetched += results.size();
                    if (categoryId == null) {
                        if (rawName == null) {
                            rawName = OpenTdbParser.decode(results.path(0).path("category").asText("Category " + sourceId));
                        }
                        categoryId = repository.upsertCategory(SOURCE, sourceId, OpenTdbParser.displayName(rawName));
                    }
                    for (ParsedQuestion q : OpenTdbParser.parseQuestions(root)) {
                        if (repository.insertClueIfNew(categoryId, q.difficulty(), q.question(), q.answer())) {
                            added++;
                        }
                    }
                    if (token == null) {
                        break; // without a token every page is random, so one page is enough
                    }
                } else if (code == OpenTdbParser.NO_RESULTS) {
                    // Fewer questions left than requested: ask for a smaller page, then stop.
                    if (amount <= MIN_BATCH) {
                        break;
                    }
                    amount = Math.max(MIN_BATCH, amount / 2);
                } else if (code == OpenTdbParser.TOKEN_EMPTY) {
                    break; // the token has seen every question in this category
                } else if (code == OpenTdbParser.RATE_LIMITED && ++rateLimited <= MAX_ATTEMPTS) {
                    pause();
                } else {
                    throw new IOException("Open Trivia DB response_code " + code);
                }
            }
            final int addedForCategory = added;
            status.updateAndGet(s -> s.categoryDone(addedForCategory));
            log.info("Imported {} new clues for category {} ({}) from {} fetched", added, sourceId, rawName, fetched);
        } catch (IOException | RuntimeException e) {
            log.warn("Import failed for category {}: {}", sourceId, e.getMessage());
            final int addedForCategory = added;
            status.updateAndGet(s -> s.withError("Category " + sourceId + ": " + e.getMessage())
                    .categoryDone(addedForCategory));
        }
    }

    private void pause() throws InterruptedException {
        Thread.sleep(props.requestDelay().toMillis());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
