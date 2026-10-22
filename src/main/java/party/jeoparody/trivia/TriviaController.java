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

@RestController
@RequestMapping("/api")
public class TriviaController {

    private final TriviaRepository repository;
    private final TriviaImportService importService;
    private final TriviaProperties props;

    public TriviaController(TriviaRepository repository, TriviaImportService importService, TriviaProperties props) {
        this.repository = repository;
        this.importService = importService;
        this.props = props;
    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return repository.listCategories();
    }

    @GetMapping("/categories/{id}/clues")
    public List<Clue> clues(@PathVariable long id, @RequestParam(defaultValue = "25") int limit) {
        return repository.findClues(id, Math.clamp(limit, 1, 200));
    }

    /** Starts a background import. Optional ?categories=9,22 overrides the configured list. */
    // TODO (Step 3): restrict admin endpoints to the host.
    @PostMapping("/admin/import")
    public ResponseEntity<ImportStatus> startImport(@RequestParam(required = false) List<Integer> categories) {
        List<Integer> ids = (categories == null || categories.isEmpty()) ? props.categories() : categories;
        boolean started = importService.startImport(ids);
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(importService.status());
    }

    @GetMapping("/admin/import")
    public ImportStatus importStatus() {
        return importService.status();
    }
}
