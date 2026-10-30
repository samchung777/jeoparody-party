package party.jeoparody.research;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import party.jeoparody.genre.GenreService;

/** Admin API for the library page: research tasks, suggestions, genres, and "research now". */
// Protected: AdminKeyFilter requires the X-Admin-Key header on /api/admin/**.
@RestController
@RequestMapping("/api/admin/research")
public class ResearchController {

    private final ResearchRepository repository;
    private final ResearchPlanner planner;
    private final ResearchService research;
    private final ResearchLauncher launcher;
    private final GenreService genres;

    public ResearchController(ResearchRepository repository, ResearchPlanner planner, ResearchService research,
            ResearchLauncher launcher, GenreService genres) {
        this.repository = repository;
        this.planner = planner;
        this.research = research;
        this.launcher = launcher;
        this.genres = genres;
    }

    /** GET /api/admin/research: everything the library page shows about research. */
    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runsIn", launcher.describe());
        out.put("tasks", repository.recentTasks(100));
        out.put("suggestions", repository.recentSuggestions(100));
        out.put("genres", genres.counts());
        out.put("fanWikis", research.fanWikis().entries().keySet());
        out.put("fanWikiProblems", research.fanWikis().rejected());
        return out;
    }

    /** POST /api/admin/research: plan now (stale categories + suggestions) and start the worker. */
    @PostMapping
    public ResearchPlanner.Plan planNow() {
        return planner.plan(null);
    }

    /** POST /api/admin/research/topics, body {"topics": "Gravity Falls, Avatar"}: research these topics. */
    @PostMapping("/topics")
    public ResponseEntity<?> topics(@RequestBody Map<String, String> body) {
        List<String> topics = List.of(body.getOrDefault("topics", "").split("[,\\n;]")).stream()
                .map(String::strip).filter(t -> !t.isEmpty()).toList();
        try {
            return ResponseEntity.ok(planner.queueManual(topics));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
