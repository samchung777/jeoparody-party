package party.jeoparody.genre;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import party.jeoparody.enrich.EnrichmentProperties;
import party.jeoparody.enrich.EnrichmentService;

/**
 * Gives every keyword one or more general categories (Movies, TV, ...), which host themes and genre browsing
 * depend on.
 *
 * Where it fits: rules run on start-up (fast SQL, no AI); Gemini handles whatever the rules can't decide, after
 * each enrichment pass and in the research worker. Why it matters: a theme only offers classified keywords, so
 * this keeps themes full as new categories arrive.
 */
@Service
public class GenreService {

    private static final Logger log = LoggerFactory.getLogger(GenreService.class);
    private static final int BATCH = 40;
    private static final int MAX_BATCHES = 25;

    private final GenreRepository repository;
    private final GenreClassifier classifier;
    private final EnrichmentProperties gemini;

    public GenreService(GenreRepository repository, GenreClassifier classifier, EnrichmentProperties gemini) {
        this.repository = repository;
        this.classifier = classifier;
        this.gemini = gemini;
    }

    /** Runs the rules once the app is ready (skipped in the research worker, which runs everything in order). */
    @EventListener(value = ApplicationReadyEvent.class,
            condition = "@environment.getProperty('app.startup-jobs', 'true') == 'true'")
    public void onStartup() {
        try {
            assignByRules();
        } catch (RuntimeException e) {
            log.warn("Genre rules failed: {}", e.getMessage());
        }
    }

    /** Classifies every unclassified keyword the rules can decide. Returns how many got genres. */
    public int assignByRules() {
        int assigned = 0;
        for (GenreRepository.Unclassified k : repository.unclassified()) {
            Set<String> genres = GenreRules.pick(k.name(), k.clueCountsByCategory());
            if (!genres.isEmpty()) {
                repository.save(k.id(), genres, "rule");
                assigned++;
            }
        }
        if (assigned > 0) {
            log.info("Assigned genres to {} keywords by rule", assigned);
        }
        return assigned;
    }

    /**
     * Rules first, then Gemini for playable keywords the rules couldn't place. Keywords Gemini answers for are
     * marked checked even with no genre, so they aren't asked about again.
     */
    public int classifyPending() {
        int total = assignByRules();
        if (!gemini.enabled()) {
            return total;
        }
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<KeywordToClassify> keywords = repository.forGemini(EnrichmentService.MIN_CLUES_FOR_GENRE, BATCH);
            if (keywords.isEmpty()) {
                break;
            }
            try {
                Map<Long, Set<String>> genres = classifier.classify(keywords);
                for (KeywordToClassify k : keywords) {
                    repository.save(k.id(), genres.getOrDefault(k.id(), Set.of()), "gemini");
                }
                total += genres.size();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Gemini genre classification failed: {}", e.getMessage());
                break; // try again on the next pass
            }
        }
        return total;
    }

    public Map<String, Integer> counts() {
        return repository.counts();
    }
}
