package party.jeoparody.review;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Protected: AdminKeyFilter requires the X-Admin-Key header on /api/admin/** (key from Secret Manager on Cloud Run).
/** Admin API for the review pass: GET shows status, POST starts a run. Used by library.html. */
@RestController
@RequestMapping("/api/admin/review")
public class ReviewController {

    private final ClueReviewService service;

    /** Spring injects the service. */
    public ReviewController(ClueReviewService service) {
        this.service = service;
    }

    /** GET /api/admin/review */
    @GetMapping
    public ClueReviewService.Status status() {
        return service.status();
    }

    /** POST /api/admin/review: queue a run and return the status. */
    @PostMapping
    public ClueReviewService.Status run() {
        service.requestRun();
        return service.status();
    }
}
