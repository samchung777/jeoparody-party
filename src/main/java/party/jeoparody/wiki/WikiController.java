package party.jeoparody.wiki;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Queue Wikipedia topics and watch their progress from the trivia library page. */
// TODO (deploy step): protect /api/admin/** before the app is public.
@RestController
@RequestMapping("/api/admin/wiki")
public class WikiController {

    private final WikiClueService service;
    private final WikiRepository repository;

    public WikiController(WikiClueService service, WikiRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /** Body: {"topics": "Fire Emblem, Hamilton\nHarvest Moon = Harvest Moon (video game series)"} */
    @PostMapping("/topics")
    public ResponseEntity<Map<String, Object>> queue(@RequestBody Map<String, String> body) {
        if (!service.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Wikipedia clues need trivia.wiki.enabled and Gemini enrichment turned on"));
        }
        List<String> topics = service.queue(body.getOrDefault("topics", ""));
        return ResponseEntity.accepted().body(Map.of("queued", topics));
    }

    @GetMapping("/topics")
    public Map<String, Object> list(@RequestParam(defaultValue = "200") int limit) {
        return Map.of("enabled", service.enabled(), "topics", repository.list(Math.clamp(limit, 1, 1000)));
    }
}
