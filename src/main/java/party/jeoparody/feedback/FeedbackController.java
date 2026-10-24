package party.jeoparody.feedback;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Review and undo clue feedback from the trivia library page. */
// TODO (deploy step): protect /api/admin/** before the app is public.
@RestController
@RequestMapping("/api/admin")
public class FeedbackController {

    private final ClueFeedbackService feedback;

    public FeedbackController(ClueFeedbackService feedback) {
        this.feedback = feedback;
    }

    @GetMapping("/feedback")
    public List<ClueFeedbackService.FlaggedClue> recent(@RequestParam(defaultValue = "100") int limit) {
        return feedback.recent(Math.clamp(limit, 1, 500));
    }

    /** Body (optional): {"note": "too broad"} */
    @PostMapping("/keywords/{id}/remove")
    public ResponseEntity<Void> removeKeyword(@PathVariable long id, @RequestBody(required = false) Map<String, String> body) {
        feedback.removeKeyword(id, body == null ? null : body.get("note"), null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/keywords/{id}/restore")
    public ResponseEntity<Void> restoreKeyword(@PathVariable long id) {
        feedback.restoreKeyword(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/keywords/removed")
    public List<ClueFeedbackService.RemovedCategory> removedKeywords() {
        return feedback.removedKeywords();
    }

    @PostMapping("/clues/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable long id) {
        feedback.restore(id);
        return ResponseEntity.noContent().build();
    }
}
