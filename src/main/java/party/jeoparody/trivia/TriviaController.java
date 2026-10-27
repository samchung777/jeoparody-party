package party.jeoparody.trivia;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import party.jeoparody.enrich.EnrichmentService;
import party.jeoparody.enrich.EnrichmentStatus;

/**
 * REST endpoints for the trivia library (library.html) and imports. Public: /api/categories, /api/keywords (the
 * smoke test calls /api/keywords). Admin: /api/admin/import and /api/admin/enrich, which AdminKeyFilter protects.
 */
@RestController
@RequestMapping("/api")
public class TriviaController {

    private final TriviaRepository repository;
    private final TriviaImportService importService;
    private final TriviaProperties props;
    private final EnrichmentService enrichmentService;

    /**
     * Spring injects the repository, the import and enrichment services and the trivia.* settings.
     */
    public TriviaController(TriviaRepository repository, TriviaImportService importService, TriviaProperties props,
            EnrichmentService enrichmentService) {
        this.repository = repository;
        this.importService = importService;
        this.props = props;
        this.enrichmentService = enrichmentService;
    }

    /** GET /api/categories: Open Trivia DB categories with clue counts by difficulty. */
    @GetMapping("/categories")
    public List<Category> categories() {
        return repository.listCategories();
    }

    /** GET /api/categories/{id}/clues: browse clues in the library; limit is capped server-side. */
    @GetMapping("/categories/{id}/clues")
    public List<Clue> clues(@PathVariable long id, @RequestParam(defaultValue = "25") int limit) {
        return repository.findClues(id, Math.clamp(limit, 1, 200));
    }

    /** Starts a background import. Optional ?categories=9,22 overrides the configured list. */
    // Protected: AdminKeyFilter requires the X-Admin-Key header on /api/admin/**.
    @PostMapping("/admin/import")
    public ResponseEntity<ImportStatus> startImport(@RequestParam(required = false) List<Integer> categories) {
        List<Integer> ids = (categories == null || categories.isEmpty()) ? props.categories() : categories;
        boolean started = importService.startImport(ids);
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(importService.status());
    }

    /** GET /api/admin/import: progress of the background import. */
    @GetMapping("/admin/import")
    public ImportStatus importStatus() {
        return importService.status();
    }

    /** Keywords that have enough clues to fill a board column (5). */
    @GetMapping("/keywords")
    public List<KeywordStats> keywords(@RequestParam(defaultValue = "5") int minClues,
            @RequestParam(defaultValue = "100") int limit) {
        return repository.keywordStats(Math.max(1, minClues), Math.clamp(limit, 1, 500));
    }

    /** Re-runs Gemini enrichment for clues that don't have Jeopardy wording yet. */
    @PostMapping("/admin/enrich")
    public ResponseEntity<EnrichmentStatus> startEnrichment() {
        boolean accepted = enrichmentService.requestRun();
        return ResponseEntity.status(accepted ? HttpStatus.ACCEPTED : HttpStatus.SERVICE_UNAVAILABLE)
                .body(enrichmentService.status());
    }

    /** GET /api/admin/enrich: progress of the Gemini enrichment job. */
    @GetMapping("/admin/enrich")
    public EnrichmentStatus enrichmentStatus() {
        return enrichmentService.status();
    }
}
