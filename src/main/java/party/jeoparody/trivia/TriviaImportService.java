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
            for (int sourceId : categoryIds) {
                importCategory(sourceId, names);
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

    private void importCategory(int sourceId, Map<Integer, String> names) throws InterruptedException {
        try {
            List<ParsedQuestion> questions = fetchQuestions(sourceId);
            String rawName = names.getOrDefault(sourceId,
                    questions.isEmpty() ? "Category " + sourceId : questions.get(0).category());
            long categoryId = repository.upsertCategory(SOURCE, sourceId, OpenTdbParser.displayName(rawName));

            int added = 0;
            for (ParsedQuestion q : questions) {
                if (repository.insertClueIfNew(categoryId, q.difficulty(), q.question(), q.answer())) {
                    added++;
                }
            }
            final int addedForCategory = added;
            status.updateAndGet(s -> s.categoryDone(addedForCategory));
            log.info("Imported {} new clues for category {} ({})", added, sourceId, rawName);
        } catch (IOException | RuntimeException e) {
            log.warn("Import failed for category {}: {}", sourceId, e.getMessage());
            status.updateAndGet(s -> s.withError("Category " + sourceId + ": " + e.getMessage()).categoryDone(0));
        }
    }

    /** Asks for a full batch, halving the amount when the category doesn't have that many questions. */
    private List<ParsedQuestion> fetchQuestions(int sourceId) throws IOException, InterruptedException {
        int amount = Math.max(MIN_BATCH, props.questionsPerCategory());
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            JsonNode root = client.fetchQuestions(sourceId, amount);
            pause();
            int code = OpenTdbParser.responseCode(root);
            switch (code) {
                case OpenTdbParser.SUCCESS:
                    return OpenTdbParser.parseQuestions(root);
                case OpenTdbParser.NO_RESULTS:
                    if (amount <= MIN_BATCH) {
                        return List.of();
                    }
                    amount = Math.max(MIN_BATCH, amount / 2);
                    break;
                case OpenTdbParser.RATE_LIMITED:
                    pause(); // wait an extra interval, then retry
                    break;
                default:
                    throw new IOException("Open Trivia DB response_code " + code);
            }
        }
        throw new IOException("Gave up after " + MAX_ATTEMPTS + " attempts");
    }

    private void pause() throws InterruptedException {
        Thread.sleep(props.requestDelay().toMillis());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
