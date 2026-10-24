package party.jeoparody.review;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO (deploy step): protect /api/admin/** before the app is public.
@RestController
@RequestMapping("/api/admin/review")
public class ReviewController {

    private final ClueReviewService service;

    public ReviewController(ClueReviewService service) {
        this.service = service;
    }

    @GetMapping
    public ClueReviewService.Status status() {
        return service.status();
    }

    @PostMapping
    public ClueReviewService.Status run() {
        service.requestRun();
        return service.status();
    }
}
